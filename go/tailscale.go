package tailscale

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

// P0 原型：把 tsnet 节点嵌进 Android App 进程（用户态，不建 VPN 接口，
// 其他 App 不会被路由进隧道）。暴露给 Java 的接口保持最小。
//
// 安全边界（用户拍板的"应用许可机制"）：
//   1) 不做通用 SOCKS5/开放代理；DialBridge 是私有协议。
//   2) 每个桥接端口带一次性随机 token：Java 侧连上后必须先写 token，
//      校验失败立即断开——同机其他 App 即使扫到端口也无法使用。
//   3) 端口仅在该连接存活期间监听，splice 结束即关闭。

var (
	mu    sync.Mutex
	srv   *tsnet.Server
	runID int
)

func init() {
	// Android app UID 没有 NETLINK_ROUTE/proc/net 权限，Go 标准库
	// net.Interfaces() 在 Android 11+ 上报 netlinkrib: permission denied
	// (tailscale#17311, golang/go#40569)。
	// bionic 允许 UDP socket 上的 ioctl(SIOCGIFCONF)——与 Java
	// NetworkInterface.getNetworkInterfaces 同一路径。用轻量实现注册
	// netmon 接口 getter，让 tsnet 在 untrusted_app 下也能枚举本机地址。
	if runtime.GOOS == "android" {
		netmon.RegisterInterfaceGetter(androidInterfaces)
	}
}

// Start 启动内置 tsnet 节点。authKey 可为空（交互登录走返回的状态里的
// URL；P0 由调用方把 URL 展示出来让用户在浏览器完成登录）。
// controlURL 为空时使用官方控制服务器；自建（如 headscale）传完整地址。
// 更换服务器需要先 Stop 再以新地址 Start（状态文件按服务器区分）。
func Start(stateDir, hostname, authKey, controlURL string) error {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return fmt.Errorf("already running")
	}
	if !filepath.IsAbs(stateDir) {
		return fmt.Errorf("state directory must be absolute")
	}
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		return fmt.Errorf("create state directory: %w", err)
	}
	if err := os.Setenv("TS_LOGS_DIR", stateDir); err != nil {
		return fmt.Errorf("configure log directory: %w", err)
	}
	s := &tsnet.Server{
		Dir:       stateDir,
		Hostname:  hostname,
		AuthKey:   authKey,
		Ephemeral: false,
	}
	if controlURL != "" {
		s.ControlURL = controlURL
	}
	if err := s.Start(); err != nil {
		_ = s.Close()
		return fmt.Errorf("tsnet start: %w", err)
	}
	srv = s
	runID++
	return nil
}

// Stop 关闭节点。
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if srv == nil {
		return nil
	}
	err := srv.Close()
	srv = nil
	return err
}

// StatusJSON 返回节点状态（loginURL/needsAuth/tailscaleIPs/peers 数）。
func StatusJSON() (string, error) {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return `{"running":false}`, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	out := map[string]any{"running": true}
	if lc, err := s.LocalClient(); err == nil {
		st, err := lc.Status(ctx)
		if err != nil {
			return "", err
		}
		out["backend"] = st.BackendState
		out["authURL"] = st.AuthURL
		out["needsLogin"] = st.BackendState == "NeedsLogin"
		ips := []string{}
		for _, a := range st.TailscaleIPs {
			ips = append(ips, a.String())
		}
		out["ips"] = ips
		out["peers"] = len(st.Peer)
		// 连接路径可见化：活跃 peer 的直连地址或中继地区（速度诊断用）。
		paths := []string{}
		for _, p := range st.Peer {
			if p == nil || !p.Active {
				continue
			}
			if p.CurAddr != "" {
				paths = append(paths, "直连 "+p.HostName+" ("+p.CurAddr+")")
			} else if p.Relay != "" && p.Relay != "auto" {
				paths = append(paths, "中继 "+p.HostName+" ("+p.Relay+")")
			}
		}
		if len(paths) == 0 {
			paths = append(paths, "等待对端连接…")
		}
		out["paths"] = paths
		// 出口节点生效状态（沙箱公网转发的关键诊断位）。
		if st.ExitNodeStatus != nil {
			alive := "未生效"
			if st.ExitNodeStatus.Online {
				alive = "生效中"
			}
			out["exit"] = string(st.ExitNodeStatus.ID) + " (" + alive + ")"
		} else {
			out["exit"] = ""
		}
	}
	b, err := json.Marshal(out)
	return string(b), err
}

// Connection is an in-process stream; no local listening port is opened.
//
// 读路径带预读泵：独立 goroutine 持续把 conn 数据抽进 1MB 缓冲，
// 使 tsnet 用户态 TCP 的接收窗口始终张开——否则 Java 每次按需 read，
// 窗口随消费速度开合，长 RTT 链路退化为每 RTT 一个小块的乒乓
// （实测 ~350KB/s @ 23ms RTT，修复后窗口全开恢复线速）。
type Connection struct {
	conn      net.Conn
	timeoutMS atomic.Int64

	mu       sync.Mutex
	buf      []byte // 已预读待消费
	rerr     error  // pump 侧读错误（io.EOF=对端关闭）
	dataCh   chan struct{}
	spaceCh  chan struct{}
	pumpDone chan struct{}
}

// activeDials 跟踪进行中的拨号，CancelAllDials 可立即中断它们。
// CancelFunc 是函数类型不能作 map key，用自增序号索引。
var (
	dialMu      sync.Mutex
	activeDials = map[int]context.CancelFunc{}
	dialSeq     int
)

func Dial(target string) (*Connection, error) {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return nil, fmt.Errorf("tsnet not running")
	}
	host, _, err := net.SplitHostPort(target)
	if err != nil || host == "" {
		return nil, fmt.Errorf("target must be host:port")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	dialMu.Lock()
	dialSeq++
	id := dialSeq
	activeDials[id] = cancel
	dialMu.Unlock()
	defer func() {
		cancel()
		dialMu.Lock()
		delete(activeDials, id)
		dialMu.Unlock()
	}()
	c, err := s.Dial(ctx, "tcp", target)
	if err != nil {
		return nil, err
	}
	return newConnection(c), nil
}

func newConnection(c net.Conn) *Connection {
	cc := &Connection{
		conn:     c,
		dataCh:   make(chan struct{}, 1),
		spaceCh:  make(chan struct{}, 1),
		pumpDone: make(chan struct{}),
	}
	go cc.pump()
	return cc
}

func (c *Connection) notify(ch chan struct{}) {
	select {
	case ch <- struct{}{}:
	default:
	}
}

// pump 持续预读；缓冲超过 high 水位后背压等待消费，防止内存无界。
func (c *Connection) pump() {
	defer close(c.pumpDone)
	const high = 1024 * 1024
	const low = 256 * 1024
	chunk := make([]byte, 64*1024)
	for {
		n, err := c.conn.Read(chunk)
		c.mu.Lock()
		if n > 0 {
			c.buf = append(c.buf, chunk[:n]...)
		}
		if err != nil {
			c.rerr = err
		}
		fulled := len(c.buf) > high
		c.mu.Unlock()
		c.notify(c.dataCh)
		if err != nil {
			return
		}
		for fulled {
			<-c.spaceCh
			c.mu.Lock()
			fulled = len(c.buf) > low
			c.mu.Unlock()
		}
	}
}

// CancelAllDials 中断所有进行中的 Dial（用户取消连接时 Java 侧调用）。
func CancelAllDials() {
	dialMu.Lock()
	defer dialMu.Unlock()
	for _, cancel := range activeDials {
		cancel()
	}
}
func (c *Connection) Read(maxBytes int) ([]byte, error) {
	if maxBytes < 1 || maxBytes > 65536 {
		return nil, fmt.Errorf("invalid read size")
	}
	ms := c.timeoutMS.Load()
	var deadline <-chan time.Time
	if ms > 0 {
		timer := time.NewTimer(time.Duration(ms) * time.Millisecond)
		defer timer.Stop()
		deadline = timer.C
	}
	for {
		c.mu.Lock()
		if len(c.buf) > 0 {
			n := len(c.buf)
			if n > maxBytes {
				n = maxBytes
			}
			out := make([]byte, n)
			copy(out, c.buf[:n])
			c.buf = c.buf[n:]
			c.notify(c.spaceCh) // pump 可能因背压在等
			if len(c.buf) > 0 {
				c.notify(c.dataCh) // 唤醒其他等待者继续消费
			}
			c.mu.Unlock()
			return out, nil
		}
		err := c.rerr
		c.mu.Unlock()
		if err != nil {
			if errors.Is(err, io.EOF) {
				return []byte{}, nil
			}
			return nil, err
		}
		// 缓冲空且 pump 还在读：等待数据或超时。
		if ms > 0 {
			select {
			case <-c.dataCh:
			case <-deadline:
				return nil, errors.New("TSNET_READ_TIMEOUT")
			}
		} else {
			<-c.dataCh
		}
	}
}
func (c *Connection) SetReadTimeout(ms int) error {
	if ms < 0 {
		return fmt.Errorf("invalid timeout")
	}
	c.timeoutMS.Store(int64(ms))
	return nil
}
func (c *Connection) Write(b []byte) (int, error) { return c.conn.Write(b) }
func (c *Connection) Close() error {
	err := c.conn.Close()
	c.notify(c.dataCh) // 唤醒可能阻塞在缓冲等待的 Read
	return err
}
