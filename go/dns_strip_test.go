package tailscale

import (
	"bytes"
	"testing"
)

// buildDNSResponse builds a minimal wire-format DNS response with the given
// answer records. qname is a simple single-label-safe name like "example.com.".
func buildDNSResponse(t *testing.T, qname string, answers [][]byte) []byte {
	t.Helper()
	var b bytes.Buffer
	// Header: id 0x1234, flags 0x8180, qd=1, an=len(answers), ns=0, ar=0
	b.Write([]byte{0x12, 0x34, 0x81, 0x80, 0x00, 0x01, byte(len(answers) >> 8), byte(len(answers)), 0, 0, 0, 0})
	for _, label := range splitLabels(qname) {
		b.WriteByte(byte(len(label)))
		b.WriteString(label)
	}
	b.WriteByte(0)
	b.Write([]byte{0x00, 0x01, 0x00, 0x01}) // QTYPE=A QCLASS=IN
	b.Write(bytes.Join(answers, nil))
	return b.Bytes()
}

func splitLabels(name string) []string {
	var out []string
	cur := ""
	for _, r := range name {
		if r == '.' {
			if cur != "" {
				out = append(out, cur)
				cur = ""
			}
		} else {
			cur += string(r)
		}
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}

func aRecord(ip [4]byte) []byte {
	// name pointer to offset 12, type A, class IN, ttl 300, rdlength 4, rdata
	return []byte{0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x01, 0x2C, 0x00, 0x04, ip[0], ip[1], ip[2], ip[3]}
}

func aaaaRecord(ip [16]byte) []byte {
	rec := []byte{0xC0, 0x0C, 0x00, 0x1C, 0x00, 0x01, 0x00, 0x00, 0x01, 0x2C, 0x00, 0x10}
	return append(rec, ip[:]...)
}

func TestStripAAAARemovesV6Only(t *testing.T) {
	msg := buildDNSResponse(t, "www.google.com.", [][]byte{
		aRecord([4]byte{1, 2, 3, 4}),
		aaaaRecord([16]byte{0x20, 0x01, 0x48, 0x60}),
	})
	got := stripAAAA(msg)
	if an := int(got[6])<<8 | int(got[7]); an != 1 {
		t.Fatalf("ANCOUNT = %d, want 1", an)
	}
	if bytes.Contains(got, []byte{0x00, 0x1C, 0x00, 0x01}) {
		t.Fatalf("AAAA record still present")
	}
	if !bytes.Contains(got, []byte{1, 2, 3, 4}) {
		t.Fatalf("A record lost")
	}
}

func TestStripAAAAKeepsQuestionAndFlags(t *testing.T) {
	msg := buildDNSResponse(t, "example.com.", [][]byte{aRecord([4]byte{9, 9, 9, 9})})
	got := stripAAAA(msg)
	if got[0] != 0x12 || got[1] != 0x34 || got[2] != 0x81 {
		t.Fatalf("header damaged")
	}
	if !bytes.Contains(got, []byte("example")) {
		t.Fatalf("question lost")
	}
}

func TestStripAAAAMalformedUntouched(t *testing.T) {
	bad := []byte{0x12, 0x34}
	if !bytes.Equal(stripAAAA(bad), bad) {
		t.Fatalf("short message must pass through")
	}
}
