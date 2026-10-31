//go:build !android

package tailscale

import (
	"errors"
	"tailscale.com/net/netmon"
)

// The ioctl implementation is Android-only; other platforms use netmon's default getter.
func siocgifconfInterfaces() ([]netmon.Interface, error) {
	return nil, errors.New("Android interface fallback unavailable")
}
