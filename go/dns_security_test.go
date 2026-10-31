package tailscale

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
)

func dnsFixture(t *testing.T) ([]byte, []byte) {
	t.Helper()
	q := new(dns.Msg)
	q.SetQuestion("example.org.", dns.TypeA)
	q.Id = 1234
	r := new(dns.Msg)
	r.SetReply(q)
	r.Compress = true
	for _, text := range []string{"removed.example.org. 60 IN AAAA 2001:db8::1", "removed.example.org. 60 IN CNAME other.example.org.", "other.example.org. 60 IN A 1.2.3.4"} {
		rr, e := dns.NewRR(text)
		if e != nil {
			t.Fatal(e)
		}
		r.Answer = append(r.Answer, rr)
	}
	qb, e := q.Pack()
	if e != nil {
		t.Fatal(e)
	}
	rb, e := r.Pack()
	if e != nil {
		t.Fatal(e)
	}
	return qb, rb
}
func TestDNSMalformedAndCompression(t *testing.T) {
	q, r := dnsFixture(t)
	for i := 0; i < len(r); i++ {
		_, ok := processDNSAnswer(context.Background(), q, r[:i])
		if ok {
			t.Fatalf("accepted truncated length %d", i)
		}
		_ = stripAAAA(r[:i])
	}
	out, ok := processDNSAnswer(context.Background(), q, r)
	if !ok {
		t.Fatal("valid response rejected")
	}
	var m dns.Msg
	if e := m.Unpack(out); e != nil {
		t.Fatal(e)
	}
	if len(m.Answer) != 2 || m.Answer[0].Header().Name != "removed.example.org." {
		t.Fatalf("compression damaged: %v", m.Answer)
	}
	for _, r := range m.Answer {
		if r.Header().Rrtype == dns.TypeAAAA {
			t.Fatal("AAAA not removed")
		}
	}
}
func TestDNSValidation(t *testing.T) {
	q, r := dnsFixture(t)
	for _, mutate := range []func(*dns.Msg){func(m *dns.Msg) { m.Id++ }, func(m *dns.Msg) { m.Question[0].Name = "wrong.org." }, func(m *dns.Msg) { m.Response = false }, func(m *dns.Msg) { m.Truncated = true }, func(m *dns.Msg) { m.Rcode = dns.RcodeServerFailure }} {
		var m dns.Msg
		if err := m.Unpack(r); err != nil {
			t.Fatal(err)
		}
		mutate(&m)
		b, err := m.Pack()
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := processDNSAnswer(context.Background(), q, b); ok {
			t.Fatal("invalid answer accepted")
		}
	}
}
func TestDoHChunkedAndFallback(t *testing.T) {
	q, r := dnsFixture(t)
	for _, tc := range []struct {
		name string
		body []byte
		code int
		good bool
	}{{"chunked", r, 200, true}, {"bad-id", append([]byte{0, 0}, r[2:]...), 200, false}, {"malformed", []byte{0, 1}, 200, false}, {"oversized", bytes.Repeat([]byte{1}, maxDNSMessage+1), 200, false}, {"http-error", r, 503, false}} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				if req.Method != "POST" {
					t.Error("wrong method")
				}
				got, _ := io.ReadAll(req.Body)
				if !bytes.Equal(got, q) {
					t.Error("wrong query")
				}
				w.Header().Set("Content-Type", "application/dns-message")
				w.WriteHeader(tc.code)
				w.(http.Flusher).Flush()
				_, _ = w.Write(tc.body)
			}))
			defer srv.Close()
			dial := func(ctx context.Context, addr string) (net.Conn, error) {
				return (&net.Dialer{}).DialContext(ctx, "tcp", srv.Listener.Addr().String())
			}
			config := srv.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
			config.ServerName = "127.0.0.1"
			_, ok := dnsOverHTTPSConfig(context.Background(), dial, srv.Listener.Addr().String(), "example.org", q, config)
			if ok != tc.good {
				t.Fatalf("ok=%v", ok)
			}
		})
	}
}
func TestIPv6AdaptiveTransitions(t *testing.T) {
	q, r := dnsFixture(t)
	state := new(atomic.Bool)
	ctx := context.WithValue(context.Background(), v6StateKey{}, state)
	fast6 := true
	fail6 := false
	dial := func(ctx context.Context, addr string) (net.Conn, error) {
		is6 := addr[0] == '['
		if is6 && fail6 {
			return nil, fmt.Errorf("offline")
		}
		if is6 != fast6 {
			time.Sleep(5 * time.Millisecond)
		}
		a, b := net.Pipe()
		b.Close()
		return a, nil
	}
	check := func(want bool) {
		t.Helper()
		if state.Load() != want {
			t.Fatalf("state=%v want %v", state.Load(), want)
		}
		out, ok := processDNSAnswer(ctx, q, r)
		if !ok {
			t.Fatal("bad answer")
		}
		var m dns.Msg
		if e := m.Unpack(out); e != nil {
			t.Fatal(e)
		}
		has := false
		for _, rr := range m.Answer {
			has = has || rr.Header().Rrtype == dns.TypeAAAA
		}
		if has != want {
			t.Fatalf("AAAA=%v want %v", has, want)
		}
	}
	check(false)
	probeDualStack(ctx, dial)
	check(true)
	fast6 = false
	probeDualStack(ctx, dial)
	check(false)
	fast6 = true
	probeDualStack(ctx, dial)
	check(true)
	fail6 = true
	probeDualStack(ctx, dial)
	check(false)
}
func TestDNSTCPFallback(t *testing.T) {
	q, r := dnsFixture(t)
	calls := 0
	dial := func(ctx context.Context, addr string) (net.Conn, error) {
		calls++
		a, b := net.Pipe()
		go func() {
			defer b.Close()
			h := make([]byte, 2)
			if _, e := io.ReadFull(b, h); e != nil {
				return
			}
			body := make([]byte, int(h[0])<<8|int(h[1]))
			if _, e := io.ReadFull(b, body); e != nil {
				return
			}
			response := append([]byte(nil), r...)
			if addr == "bad" {
				response[0] ^= 1
			}
			_, _ = b.Write(append([]byte{byte(len(response) >> 8), byte(len(response))}, response...))
		}()
		return a, nil
	}
	if _, ok := dnsOverTCP(context.Background(), dial, "bad", q); ok {
		t.Fatal("bad response accepted")
	}
	if _, ok := dnsOverTCP(context.Background(), dial, "good", q); !ok {
		t.Fatal("fallback failed")
	}
	if calls != 2 {
		t.Fatal(calls)
	}
}
