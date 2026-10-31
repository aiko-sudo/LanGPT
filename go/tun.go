package tailscale

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/miekg/dns"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
	"tailscale.com/wgengine/netstack"
)

// 应用沙箱（白名单 VPN）：Android VpnService 的 TUN fd 上的 IP 包经
// gVisor netstack 拆成 TCP/UDP 会话，再通过内置 tsnet 节点转发。
// 仅白名单 App 的流量会进入该 TUN；其他应用不受影响。
//
// UDP 使用 tsnet 的 netstack 直连，按原目的地址转发。

var (
	tunMu     sync.Mutex
	tunCancel context.CancelFunc
	tunFile   io.ReadWriteCloser
)

// StartTunVpn 接管 Android VpnService 建立的 TUN 描述符。
// fd 必须是 detachFd() 之后由本侧独占的描述符；StopTunVpn 会关闭它。
func StartTunVpn(fd int, mtu int) error {
	if mtu < 576 || mtu > 65536 {
		return fmt.Errorf("invalid mtu")
	}
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return fmt.Errorf("tsnet not running")
	}

	tunMu.Lock()
	defer tunMu.Unlock()
	if tunCancel != nil {
		return fmt.Errorf("tun vpn already running")
	}
	f := osNewFile(fd)
	if f == nil {
		return fmt.Errorf("invalid tun fd")
	}

	ctx, cancel := context.WithCancel(context.WithValue(context.Background(), v6StateKey{}, new(atomic.Bool)))

	snk := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	const nicID = 1
	linkEP := channel.New(512, uint32(mtu), "")
	if err := snk.CreateNIC(nicID, linkEP); err != nil {
		cancel()
		f.Close()
		return fmt.Errorf("create nic: %v", err)
	}
	snk.SetPromiscuousMode(nicID, true)
	snk.SetSpoofing(nicID, true)
	snk.AddProtocolAddress(nicID, tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4([4]byte{198, 18, 0, 1}).WithPrefix(),
	}, stack.AddressProperties{})
	snk.AddProtocolAddress(nicID, tcpip.ProtocolAddress{
		Protocol:          ipv6.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom16([16]byte{0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}).WithPrefix(),
	}, stack.AddressProperties{})
	snk.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})

	dialTailnet := func(network, addr string) (net.Conn, error) {
		dctx, dcancel := context.WithTimeout(ctx, 8*time.Second)
		c, err := s.Dial(dctx, network, addr)
		dcancel()
		return c, err
	}

	dialTCP := func(ctx context.Context, addr string) (net.Conn, error) {
		return s.Dial(ctx, "tcp", addr)
	}

	tcpFwd := tcp.NewForwarder(snk, 0, 128, func(r *tcp.ForwarderRequest) {
		id := r.ID()
		dst := net.JoinHostPort(net.IP(id.LocalAddress.AsSlice()).String(), fmt.Sprint(id.LocalPort))
		remote, err := dialTailnet("tcp", dst)
		if err != nil {
			r.Complete(true)
			return
		}
		var wq waiter.Queue
		ep, terr := r.CreateEndpoint(&wq)
		if terr != nil {
			r.Complete(true)
			remote.Close()
			return
		}
		r.Complete(false)
		local := gonet.NewTCPConn(&wq, ep)
		go relay(local, remote)
	})
	snk.SetTransportProtocolHandler(header.TCPProtocolNumber, tcpFwd.HandlePacket)

	udpDial := func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		sys := s.Sys()
		if sys == nil {
			return nil, fmt.Errorf("tsnet system unavailable")
		}
		impl, ok := sys.Netstack.GetOK()
		if !ok {
			return nil, fmt.Errorf("tsnet netstack unavailable")
		}
		ns, ok := impl.(*netstack.Impl)
		if !ok || ns == nil {
			return nil, fmt.Errorf("unsupported tsnet netstack")
		}
		v4, v6 := s.TailscaleIPs()
		src := v4
		if dst.Addr().Is6() {
			src = v6
		}
		if !src.IsValid() {
			return nil, fmt.Errorf("no tailnet address for destination")
		}
		c, err := ns.DialContextUDPWithBind(ctx, src, dst)
		if err != nil {
			return nil, err
		}
		if err := ctx.Err(); err != nil {
			c.Close()
			return nil, err
		}
		return c, nil
	}
	installUDPForwarder(snk, ctx, udpDial, dialTCP)
	go monitorDualStack(ctx, dialTCP)

	// TUN → netstack：fd 读出 IP 包按版本注入。
	go func() {
		buf := make([]byte, mtu+header.IPv6MinimumMTU)
		for {
			n, err := f.Read(buf)
			if err != nil || n == 0 {
				cancel()
				return
			}
			if n < 1 {
				continue
			}
			var proto tcpip.NetworkProtocolNumber
			switch buf[0] >> 4 {
			case 4:
				proto = header.IPv4ProtocolNumber
			case 6:
				proto = header.IPv6ProtocolNumber
			default:
				continue
			}
			pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
				Payload: buffer.MakeWithData(buf[:n:n]),
			})
			linkEP.InjectInbound(proto, pkt)
			pkt.DecRef()
		}
	}()

	// netstack → TUN：linkEP 读出发包写回 fd。
	go func() {
		for {
			pkt := linkEP.ReadContext(ctx)
			if pkt == nil {
				return
			}
			b := pkt.ToBuffer()
			flat := b.Flatten()
			pkt.DecRef()
			if len(flat) == 0 {
				continue
			}
			if _, err := f.Write(flat); err != nil {
				cancel()
				return
			}
		}
	}()

	tunCancel = cancel
	tunFile = f
	return nil
}

// Each gVisor UDP endpoint represents one source/destination flow.
// DNS (dst port 53) is answered over DNS-over-TCP instead of relaying UDP:
// in tsnet userspace the netstack swallows outbound UDP:53 flows (its DNS
// interception has no resolver behind it), so plain UDP DNS never returns.
// TCP egress through the exit node is verified working; DNS-over-TCP (RFC
// 7766) rides that path.
func installUDPForwarder(snk *stack.Stack, ctx context.Context, dial func(context.Context, netip.AddrPort) (net.Conn, error), dialTCP func(context.Context, string) (net.Conn, error)) {
	udpFwd := udp.NewForwarder(snk, func(r *udp.ForwarderRequest) bool {
		id := r.ID()
		addr, ok := netip.AddrFromSlice(id.LocalAddress.AsSlice())
		if !ok {
			return false
		}
		var wq waiter.Queue
		// V1.6.1: reject QUIC (UDP/443) outright. Many transparent-proxy
		// environments only proxy TCP; UDP/443 silently blackholes and QUIC
		// apps wait for slow timeouts instead of falling back to TCP.
		// Returning false lets the stack answer ICMP port-unreachable so apps
		// fall back to TCP (which is proxied and fast) within milliseconds.
		if id.LocalPort == 443 {
			return false
		}
		ep, err := r.CreateEndpoint(&wq)
		if err != nil {
			return false
		}
		local := gonet.NewUDPConn(&wq, ep)
		if id.LocalPort == 53 {
			go relayDNSOverTCP(ctx, local, dialTCP)
			return true
		}
		go func() {
			defer local.Close()
			remote, err := dial(ctx, netip.AddrPortFrom(addr.Unmap(), id.LocalPort))
			if err != nil {
				return
			}
			relayUDP(ctx, local, remote, 2*time.Minute)
		}()
		return true
	})
	snk.SetTransportProtocolHandler(header.UDPProtocolNumber, udpFwd.HandlePacket)
}

// relayDNSOverTCP answers one UDP DNS query by asking a public resolver over
// TCP (with the RFC 7766 two-byte length framing) and writing the answer back
// as a UDP datagram.
func relayDNSOverTCP(ctx context.Context, client *gonet.UDPConn, dialTCP func(context.Context, string) (net.Conn, error)) {
	defer client.Close()
	buf := make([]byte, 1500)
	if dl, ok := ctx.Deadline(); ok {
		_ = client.SetReadDeadline(time.Now().Add(time.Until(dl)))
	}
	n, err := client.Read(buf)
	if err != nil || n < 12 {
		return
	}
	query := append([]byte(nil), buf[:n]...)
	// V1.6.1: DNS over HTTPS first. Plain TCP:53 upstreams are transparently
	// hijacked by many LAN gateways (e.g. mihomo/clash fake-ip REDIRECT chains)
	// which answer with fake-ip addresses — when the fake-ip range collides
	// with this sandbox's own TUN subnet (198.18.0.0/15), every
	// system-resolver app inside the sandbox blackholes. DoH (443) rides the
	// normal TCP egress path: no port-53 hijack, real IPs, and GFW-resistant
	// encryption. TCP:53 stays as last-resort fallback.
	type dohUpstream struct {
		ip, sni string
	}
	for _, u := range []dohUpstream{{"8.8.8.8:443", "dns.google"}, {"1.1.1.1:443", "cloudflare-dns.com"}} {
		if answer, ok := dnsOverHTTPS(ctx, dialTCP, u.ip, u.sni, query); ok {
			_, _ = client.Write(answer)
			return
		}
	}
	for _, upstream := range []string{"1.1.1.1:53", "8.8.8.8:53"} {
		if answer, ok := dnsOverTCP(ctx, dialTCP, upstream, query); ok {
			_, _ = client.Write(answer)
			return
		}
	}
}

// DNS messages are bounded by the RFC 1035 TCP framing limit.
const maxDNSMessage = 65535
const maxHTTPHeaders = 16384

type v6StateKey struct{}

func v6State(ctx context.Context) *atomic.Bool {
	if state, ok := ctx.Value(v6StateKey{}).(*atomic.Bool); ok {
		return state
	}
	return nil
}

// validateDNS rejects malformed, unrelated, truncated and server-failure answers
// so the caller can try the next upstream. NXDOMAIN and NODATA are valid.
func validateDNS(query, answer []byte) (*dns.Msg, bool) {
	var q, r dns.Msg
	if len(query) > maxDNSMessage || len(answer) > maxDNSMessage || q.Unpack(query) != nil || r.Unpack(answer) != nil {
		return nil, false
	}
	if len(answer) < 12 || int(binary.BigEndian.Uint16(answer[4:6])) != len(r.Question) || int(binary.BigEndian.Uint16(answer[6:8])) != len(r.Answer) || int(binary.BigEndian.Uint16(answer[8:10])) != len(r.Ns) || int(binary.BigEndian.Uint16(answer[10:12])) != len(r.Extra) {
		return nil, false
	}
	if q.Response || !r.Response || q.Id != r.Id || q.Opcode != r.Opcode || r.Truncated || len(q.Question) != 1 || len(r.Question) != 1 {
		return nil, false
	}
	a, b := q.Question[0], r.Question[0]
	if !strings.EqualFold(a.Name, b.Name) || a.Qtype != b.Qtype || a.Qclass != b.Qclass {
		return nil, false
	}
	if r.Rcode != dns.RcodeSuccess && r.Rcode != dns.RcodeNameError {
		return nil, false
	}
	return &r, true
}
func processDNSAnswer(ctx context.Context, query, answer []byte) ([]byte, bool) {
	msg, ok := validateDNS(query, answer)
	if !ok {
		return nil, false
	}
	if state := v6State(ctx); state != nil && state.Load() {
		return answer, true
	}
	filter := func(rr []dns.RR) []dns.RR {
		out := make([]dns.RR, 0, len(rr))
		for _, r := range rr {
			if r.Header().Rrtype != dns.TypeAAAA {
				out = append(out, r)
			}
		}
		return out
	}
	msg.Answer = filter(msg.Answer)
	msg.Ns = filter(msg.Ns)
	msg.Extra = filter(msg.Extra)
	// Altering signed records invalidates DNSSEC authentication.
	msg.AuthenticatedData = false
	msg.Compress = true
	out, err := msg.Pack()
	if err != nil || len(out) > maxDNSMessage {
		return nil, false
	}
	return out, true
}

// dnsOverHTTPS uses the standard HTTP parser for framing including chunked.
func dnsOverHTTPS(ctx context.Context, dialTCP func(context.Context, string) (net.Conn, error), addr, sni string, query []byte) ([]byte, bool) {
	return dnsOverHTTPSConfig(ctx, dialTCP, addr, sni, query, &tls.Config{ServerName: sni, MinVersion: tls.VersionTLS12})
}

func dnsOverHTTPSConfig(ctx context.Context, dialTCP func(context.Context, string) (net.Conn, error), addr, sni string, query []byte, config *tls.Config) ([]byte, bool) {
	if len(query) > maxDNSMessage {
		return nil, false
	}
	cctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	conn, err := dialTCP(cctx, addr)
	if err != nil {
		return nil, false
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	tlsConn := tls.Client(conn, config)
	if tlsConn.HandshakeContext(cctx) != nil {
		return nil, false
	}
	req, err := http.NewRequestWithContext(cctx, http.MethodPost, "https://"+sni+"/dns-query", bytes.NewReader(query))
	if err != nil {
		return nil, false
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	req.Close = true
	if req.Write(tlsConn) != nil {
		return nil, false
	}
	// Limit HTTP headers without imposing a limit on the separately bounded body.
	limited := &io.LimitedReader{R: tlsConn, N: maxHTTPHeaders}
	reader := bufio.NewReader(limited)
	resp, err := http.ReadResponse(reader, req)
	if err != nil {
		return nil, false
	}
	// ReadResponse may have buffered body bytes; lift the header limit now.
	limited.N = maxDNSMessage + maxHTTPHeaders + 1
	defer resp.Body.Close()
	media, _, err := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	if resp.StatusCode != http.StatusOK || err != nil || !strings.EqualFold(media, "application/dns-message") || resp.ContentLength > maxDNSMessage {
		return nil, false
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxDNSMessage+1))
	if err != nil || len(body) > maxDNSMessage {
		return nil, false
	}
	return processDNSAnswer(ctx, query, body)
}

func dnsOverTCP(ctx context.Context, dialTCP func(context.Context, string) (net.Conn, error), upstream string, query []byte) ([]byte, bool) {
	if len(query) > maxDNSMessage {
		return nil, false
	}
	cctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	conn, err := dialTCP(cctx, upstream)
	if err != nil {
		return nil, false
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
	frame := make([]byte, 2+len(query))
	frame[0] = byte(len(query) >> 8)
	frame[1] = byte(len(query))
	copy(frame[2:], query)
	if n, err := conn.Write(frame); err != nil || n != len(frame) {
		return nil, false
	}
	head := make([]byte, 2)
	if _, err := io.ReadFull(conn, head); err != nil {
		return nil, false
	}
	size := int(head[0])<<8 | int(head[1])
	if size < 12 {
		return nil, false
	}
	answer := make([]byte, size)
	if _, err := io.ReadFull(conn, answer); err != nil {
		return nil, false
	}
	return processDNSAnswer(ctx, query, answer)
}

// Each VPN session owns its state. Unknown or failed measurement is v4-only.
// Re-measure periodically so a route change can enable or disable IPv6.
func monitorDualStack(ctx context.Context, dialTCP func(context.Context, string) (net.Conn, error)) {
	probeDualStack(ctx, dialTCP)
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			probeDualStack(ctx, dialTCP)
		}
	}
}
func probeDualStack(ctx context.Context, dialTCP func(context.Context, string) (net.Conn, error)) {
	targets := []struct{ v4, v6 string }{
		{"1.1.1.1:443", "[2606:4700:4700::1111]:443"},
		{"8.8.8.8:443", "[2001:4860:4860::8888]:443"},
		{"9.9.9.9:443", "[2620:fe::fe]:443"},
	}
	var sum4, sum6 float64
	good := true
	for _, t := range targets {
		d4 := handshakeMs(ctx, dialTCP, t.v4)
		d6 := handshakeMs(ctx, dialTCP, t.v6)
		if d4 <= 0 || d6 <= 0 {
			good = false
			break
		}
		sum4 += d4
		sum6 += d6
	}
	if state := v6State(ctx); state != nil && ctx.Err() == nil {
		state.Store(good && sum6 < sum4*0.85)
	}
}
func handshakeMs(ctx context.Context, dialTCP func(context.Context, string) (net.Conn, error), addr string) float64 {
	start := time.Now()
	cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	conn, err := dialTCP(cctx, addr)
	if err != nil {
		return -1
	}
	conn.Close()
	return float64(time.Since(start).Nanoseconds()) / 1e6
}

// probeDNSQuery builds a minimal wire-format DNS A query for "example.com".
func probeDNSQuery() ([]byte, error) {
	msg := []byte{
		0x00, 0x01, // ID
		0x01, 0x00, // flags: recursion desired
		0x00, 0x01, // QDCOUNT
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	}
	for _, label := range []string{"example", "com"} {
		msg = append(msg, byte(len(label)))
		msg = append(msg, label...)
	}
	msg = append(msg, 0x00)       // root label
	msg = append(msg, 0x00, 0x01) // QTYPE=A
	msg = append(msg, 0x00, 0x01) // QCLASS=IN
	return msg, nil
}

// stripAAAA is retained for callers that need a best-effort transformation.
// Network paths use processDNSAnswer and reject malformed messages instead.
func stripAAAA(m []byte) []byte {
	var msg dns.Msg
	if msg.Unpack(m) != nil {
		return m
	}
	filter := func(in []dns.RR) []dns.RR {
		out := make([]dns.RR, 0, len(in))
		for _, r := range in {
			if r.Header().Rrtype != dns.TypeAAAA {
				out = append(out, r)
			}
		}
		return out
	}
	msg.Answer = filter(msg.Answer)
	msg.Ns = filter(msg.Ns)
	msg.Extra = filter(msg.Extra)
	msg.AuthenticatedData = false
	msg.Compress = true
	out, err := msg.Pack()
	if err != nil {
		return m
	}
	return out
}

// relayUDP preserves datagram boundaries and closes both sides on cancellation,
// idle timeout, or an I/O error. Activity in either direction refreshes the timer.
func relayUDP(ctx context.Context, a, b net.Conn, idle time.Duration) {
	defer a.Close()
	defer b.Close()
	activity := make(chan struct{}, 1)
	done := make(chan struct{}, 2)
	copyPackets := func(dst, src net.Conn) {
		defer func() { done <- struct{}{} }()
		buf := make([]byte, 65535)
		for {
			n, err := src.Read(buf)
			if err != nil {
				return
			}
			if _, err = dst.Write(buf[:n]); err != nil {
				return
			}
			select {
			case activity <- struct{}{}:
			default:
			}
		}
	}
	go copyPackets(a, b)
	go copyPackets(b, a)
	timer := time.NewTimer(idle)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-done:
			return
		case <-timer.C:
			return
		case <-activity:
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
			timer.Reset(idle)
		}
	}
}

// StopTunVpn 停止沙箱转发并关闭 TUN 描述符。
func StopTunVpn() {
	tunMu.Lock()
	cancel := tunCancel
	f := tunFile
	tunCancel = nil
	tunFile = nil
	tunMu.Unlock()
	if cancel != nil {
		cancel()
	}
	if f != nil {
		f.Close()
	}
}

func TunVpnRunning() bool {
	tunMu.Lock()
	defer tunMu.Unlock()
	return tunCancel != nil
}

// relay 双向拷贝一条 TCP 连接（任意一侧结束即关闭两侧）。
func relay(a, b net.Conn) {
	defer a.Close()
	defer b.Close()
	done := make(chan struct{}, 2)
	go func() { io.Copy(a, b); done <- struct{}{} }()
	go func() { io.Copy(b, a); done <- struct{}{} }()
	<-done
}

// osNewFile 隔离 syscall 依赖，便于审查。
func osNewFile(fd int) io.ReadWriteCloser {
	if fd < 0 {
		return nil
	}
	return os.NewFile(uintptr(fd), "tun-vpn")
}
