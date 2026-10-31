package tailscale

// Android app UID 没有 NETLINK_ROUTE / /proc/net 权限（tailscale#2293,
// golang/go#40569），Go 标准库 net.Interfaces() 恒失败；tsnet 由此在
// Android 上启动即报 netlinkrib: permission denied（tailscale#17311）。
// bionic 允许 UDP socket 上的 ioctl(SIOCGIFCONF/SIOCGIFINDEX)——与 Java
// NetworkInterface.getNetworkInterfaces 同一路径。这里直接实现该路径并
// 适配成 tsnet netmon 的接口 getter（不依赖第三方库的 linkname hack，
// 那些库在 Go 1.26 下链接失败）。

import (
	"fmt"
	"net"
	"runtime"
	"unsafe"

	"golang.org/x/sys/unix"

	"tailscale.com/net/netmon"
)

// struct ifconf { int len; union { char* buf } } —— arm64 8 字节对齐
type ifconf struct {
	_   [4]byte
	buf uintptr
}

func siocgifconfInterfaces() ([]netmon.Interface, error) {
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM, 0)
	if err != nil {
		return nil, fmt.Errorf("anet socket: %w", err)
	}
	defer unix.Close(fd)

	// linux ifreq: name[16] + union(24) = 40 bytes (arm64/android align)
	const ifreqSize = 40
	var buf [64 * ifreqSize]byte
	ifc := ifconf{buf: uintptr(unsafe.Pointer(&buf[0]))}
	ifc.len(len(buf))
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd),
		unix.SIOCGIFCONF, uintptr(unsafe.Pointer(&ifc))); errno != 0 {
		return nil, fmt.Errorf("SIOCGIFCONF: %w", errno)
	}
	n := ifc.length() / ifreqSize
	out := make([]netmon.Interface, 0, n)
	for i := 0; i < n; i++ {
		raw := buf[i*ifreqSize : (i+1)*ifreqSize]
		name := cstring(raw[:16])
		if name == "" || name == "lo" {
			continue
		}
		iface := net.Interface{
			Name:  name,
			Flags: net.FlagUp | net.FlagBroadcast | net.FlagMulticast,
		}
		// SIOCGIFINDEX 拿真实 index（netmon 用 index 做关联 key）
		if ifr, err := unix.NewIfreq(name); err == nil {
			if err := unix.IoctlIfreq(fd, unix.SIOCGIFINDEX, ifr); err == nil {
				iface.Index = int(ifr.Uint32())
			} else {
				iface.Index = i + 1
			}
		} else {
			iface.Index = i + 1
		}
		nm := netmon.Interface{Interface: &iface}
		// sockaddr_in 在 union 前 8 字节内：family(2) + port(2) + addr(4)
		if len(raw) >= 24 {
			family := uint16(raw[16]) | uint16(raw[17])<<8
			if family == unix.AF_INET {
				ip := net.IPv4(raw[20], raw[21], raw[22], raw[23])
				if !ip.Equal(net.IPv4zero) {
					nm.AltAddrs = []net.Addr{&net.IPNet{IP: ip, Mask: net.CIDRMask(24, 32)}}
				}
			}
		}
		out = append(out, nm)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no interfaces via SIOCGIFCONF")
	}
	return out, nil
}

func (c *ifconf) len(n int)    { *(*int32)(unsafe.Pointer(c)) = int32(n) }
func (c *ifconf) length() int  { return int(*(*int32)(unsafe.Pointer(c))) }

func cstring(b []byte) string {
	for i, c := range b {
		if c == 0 {
			return string(b[:i])
		}
	}
	return ""
}

func init() {
	if runtime.GOOS == "android" {
		netmon.RegisterInterfaceGetter(androidInterfaces)
	}
}
