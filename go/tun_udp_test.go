package tailscale

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"syscall"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// Inject IPv4 UDP packets; with an unavailable upstream no response is produced.
func TestSandboxUDPUnavailableUpstream(t *testing.T) {
	s := stack.New(stack.Options{NetworkProtocols: []stack.NetworkProtocolFactory{ipv4.NewProtocol}, TransportProtocols: []stack.TransportProtocolFactory{udp.NewProtocol}})
	defer s.Close()
	link := channel.New(32, 1500, "")
	if err := s.CreateNIC(1, link); err != nil {
		t.Fatal(err)
	}
	if err := s.SetPromiscuousMode(1, true); err != nil {
		t.Fatal(err)
	}
	s.SetSpoofing(1, true)
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	installUDPForwarder(s, ctx, func(context.Context, netip.AddrPort) (net.Conn, error) {
		return nil, errors.New("test upstream unavailable")
	}, func(context.Context, string) (net.Conn, error) {
		return nil, errors.New("test upstream unavailable")
	})
	inject := func(port uint16) {
		p := make([]byte, 20+8+1)
		p[0] = 0x40
		ip := header.IPv4(p)
		ip.Encode(&header.IPv4Fields{TotalLength: uint16(len(p)), TTL: 64, Protocol: uint8(header.UDPProtocolNumber), SrcAddr: tcpip.AddrFrom4([4]byte{198, 18, 0, 2}), DstAddr: tcpip.AddrFrom4([4]byte{203, 0, 113, 1})})
		ip.SetChecksum(^ip.CalculateChecksum())
		header.UDP(p[20:]).Encode(&header.UDPFields{SrcPort: 12345, DstPort: port, Length: 9})
		p[28] = 42
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(p)})
		link.InjectInbound(header.IPv4ProtocolNumber, pkt)
		pkt.DecRef()
	}
	for _, port := range []uint16{8443, 53, 53} {
		inject(port)
	}
	deadline, stop := context.WithTimeout(ctx, 150*time.Millisecond)
	defer stop()
	if pkt := link.ReadContext(deadline); pkt != nil {
		pkt.DecRef()
		t.Fatal("UDP packet unexpectedly produced an outbound response")
	}
}

func TestUDPForwarderMultipleDatagrams(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if errors.Is(err, syscall.EPERM) || errors.Is(err, syscall.EACCES) {
		t.Skipf("local UDP socket unavailable: %v", err)
	}
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	go func() {
		buf := make([]byte, 1024)
		for {
			n, peer, err := server.ReadFromUDP(buf)
			if err != nil {
				return
			}
			server.WriteToUDP(buf[:n], peer)
		}
	}()
	s := stack.New(stack.Options{NetworkProtocols: []stack.NetworkProtocolFactory{ipv4.NewProtocol}, TransportProtocols: []stack.TransportProtocolFactory{udp.NewProtocol}})
	defer s.Close()
	link := channel.New(32, 1500, "")
	if err := s.CreateNIC(1, link); err != nil {
		t.Fatal(err)
	}
	s.SetPromiscuousMode(1, true)
	s.SetSpoofing(1, true)
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	calls := make(chan netip.AddrPort, 4)
	installUDPForwarder(s, ctx, func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
		calls <- dst
		return (&net.Dialer{}).DialContext(ctx, "udp4", server.LocalAddr().String())
	}, func(context.Context, string) (net.Conn, error) {
		return nil, errors.New("no tcp in test")
	})
	for _, payload := range []string{"first", "second", "third"} {
		p := make([]byte, 28+len(payload))
		ip := header.IPv4(p)
		ip.Encode(&header.IPv4Fields{TotalLength: uint16(len(p)), TTL: 64, Protocol: uint8(header.UDPProtocolNumber), SrcAddr: tcpip.AddrFrom4([4]byte{198, 18, 0, 2}), DstAddr: tcpip.AddrFrom4([4]byte{203, 0, 113, 1})})
		ip.SetChecksum(^ip.CalculateChecksum())
		header.UDP(p[20:]).Encode(&header.UDPFields{SrcPort: 12345, DstPort: 8443, Length: uint16(8 + len(payload))})
		copy(p[28:], payload)
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(p)})
		link.InjectInbound(header.IPv4ProtocolNumber, pkt)
		pkt.DecRef()
		readCtx, stop := context.WithTimeout(ctx, time.Second)
		response := link.ReadContext(readCtx)
		stop()
		if response == nil {
			t.Fatal("response timeout")
		}
		b := response.ToBuffer()
		data := b.Flatten()
		response.DecRef()
		b.Release()
		if len(data) < 28 || string(data[28:]) != payload {
			t.Fatalf("unexpected response %q", data)
		}
	}
	select {
	case dst := <-calls:
		if dst.String() != "203.0.113.1:8443" {
			t.Fatalf("destination: %v", dst)
		}
	default:
		t.Fatal("no dial")
	}
	select {
	case <-calls:
		t.Fatal("more than one session dial")
	default:
	}
}

func TestUDPRelayCancellationAndIdle(t *testing.T) {
	for _, cancelFirst := range []bool{true, false} {
		t.Run(fmt.Sprint(cancelFirst), func(t *testing.T) {
			a, peerA := net.Pipe()
			b, peerB := net.Pipe()
			defer peerA.Close()
			defer peerB.Close()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan struct{})
			go func() { relayUDP(ctx, a, b, 50*time.Millisecond); close(done) }()
			if cancelFirst {
				cancel()
			}
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("relay did not stop")
			}
		})
	}
}
