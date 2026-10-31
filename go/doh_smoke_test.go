package tailscale

import (
	"context"
	"net"
	"testing"
	"time"
)

// Smoke test against real DoH resolvers via the host network path (same
// egress the sandbox uses: transparent proxy -> exit node).
func TestDoHRealResolve(t *testing.T) {
	if testing.Short() {
		t.Skip("network test")
	}
	d := &net.Dialer{Timeout: 5 * time.Second}
	dialTCP := func(ctx context.Context, addr string) (net.Conn, error) {
		return d.DialContext(ctx, "tcp", addr)
	}
	q := buildQuery(t, "www.google.com")
	for _, u := range []struct{ ip, sni string }{{"8.8.8.8:443", "dns.google"}, {"1.1.1.1:443", "cloudflare-dns.com"}} {
		ans, ok := dnsOverHTTPS(context.Background(), dialTCP, u.ip, u.sni, q)
		if !ok {
			t.Logf("%s: FAILED (no answer)", u.sni)
			continue
		}
		t.Logf("%s: OK, answer len=%d, last4=%d.%d.%d.%d", u.sni, len(ans), ans[len(ans)-4], ans[len(ans)-3], ans[len(ans)-2], ans[len(ans)-1])
		return
	}
	t.Fatal("both DoH upstreams failed")
}

func buildQuery(t *testing.T, name string) []byte {
	t.Helper()
	// minimal wire-format A query
	q := []byte{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	q = append(q, 3)
	q = append(q, "www"...)
	q = append(q, 6)
	q = append(q, "google"...)
	q = append(q, 3)
	q = append(q, "com"...)
	q = append(q, 0, 0, 1, 0, 1)
	return q
}
