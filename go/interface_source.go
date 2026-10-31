package tailscale

import (
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"tailscale.com/net/netmon"
)

// InterfaceSource is implemented in Android using java.net.NetworkInterface.
// Each snapshot reflects the currently active interfaces and exact prefixes.
type InterfaceSource interface{ SnapshotJSON() string }

var sourceMu sync.RWMutex
var interfaceSource InterfaceSource

func SetInterfaceSource(s InterfaceSource) { sourceMu.Lock(); interfaceSource = s; sourceMu.Unlock() }

type interfaceRecord struct {
	Name     string   `json:"name"`
	Index    int      `json:"index"`
	Up       bool     `json:"up"`
	Loopback bool     `json:"loopback"`
	Addrs    []string `json:"addrs"`
}

func parseInterfaces(data string) ([]netmon.Interface, error) {
	var records []interfaceRecord
	if err := json.Unmarshal([]byte(data), &records); err != nil {
		return nil, err
	}
	out := make([]netmon.Interface, 0, len(records))
	for _, r := range records {
		if r.Name == "" || r.Index <= 0 || !r.Up || r.Loopback {
			continue
		}
		addresses := make([]net.Addr, 0, len(r.Addrs))
		for _, s := range r.Addrs {
			p, err := netip.ParsePrefix(s)
			if err != nil {
				continue
			}
			ip := net.IP(p.Addr().AsSlice())
			addresses = append(addresses, &net.IPNet{IP: ip, Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())})
		}
		if len(addresses) == 0 {
			continue
		}
		out = append(out, netmon.Interface{Interface: &net.Interface{Name: r.Name, Index: r.Index, Flags: net.FlagUp}, AltAddrs: addresses})
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no usable Android interfaces")
	}
	return out, nil
}

func androidInterfaces() ([]netmon.Interface, error) {
	sourceMu.RLock()
	source := interfaceSource
	sourceMu.RUnlock()
	if source != nil {
		if result, err := parseInterfaces(source.SnapshotJSON()); err == nil {
			return result, nil
		}
	}
	return siocgifconfInterfaces()
}
