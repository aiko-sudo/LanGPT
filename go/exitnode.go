package tailscale

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
)

type exitOption struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Online bool   `json:"online"`
}

func exitOptions(st *ipnstate.Status) []exitOption {
	options := []exitOption{}
	for _, peer := range st.Peer {
		if peer == nil || !peer.ExitNodeOption || peer.ID == "" {
			continue
		}
		name := peer.DNSName
		if name == "" {
			name = peer.HostName
		}
		options = append(options, exitOption{string(peer.ID), name, peer.Online})
	}
	sort.Slice(options, func(i, j int) bool {
		if options[i].Name == options[j].Name {
			return options[i].ID < options[j].ID
		}
		return options[i].Name < options[j].Name
	})
	return options
}

type exitClient interface {
	Status(context.Context) (*ipnstate.Status, error)
	EditPrefs(context.Context, *ipn.MaskedPrefs) (*ipn.Prefs, error)
}

func localExitClient() (*local.Client, error) {
	mu.Lock()
	defer mu.Unlock()
	if srv == nil {
		return nil, fmt.Errorf("tsnet not running")
	}
	return srv.LocalClient()
}

// ExitNodesJSON lists exit nodes offered and approved in this client's netmap.
func ExitNodesJSON() (string, error) {
	lc, err := localExitClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(exitOptions(st))
	return string(data), err
}

func setExitNode(ctx context.Context, lc exitClient, id string) error {
	if id != "" {
		st, err := lc.Status(ctx)
		if err != nil {
			return err
		}
		found := false
		for _, option := range exitOptions(st) {
			// 不要求 online：手机冷启动后 netmap 的 peer 在线状态可能滞后，
			// 强制在线校验会把刚选好的出口拒掉（表现为“节点出口操作失败”）。
			if option.ID == id {
				found = true
				break
			}
		}
		if !found {
			return fmt.Errorf("exit node is unavailable")
		}
	}
	// Clear all competing selectors while leaving unrelated preferences untouched.
	mp := &ipn.MaskedPrefs{ExitNodeIDSet: true, ExitNodeIPSet: true, AutoExitNodeSet: true}
	mp.ExitNodeID = tailcfg.StableNodeID(id)
	prefs, err := lc.EditPrefs(ctx, mp)
	if err != nil {
		return err
	}
	if prefs == nil || string(prefs.ExitNodeID) != id || prefs.ExitNodeIP.IsValid() || prefs.AutoExitNode != "" {
		return fmt.Errorf("exit node preference was not confirmed")
	}
	return nil
}

// SetExitNode selects an online approved node by stable ID; empty clears it.
// EditPrefs 成功即视为设置成功（软等待最多 3 秒观察生效，超时不报错：
// 手机冷启动后 netmap 的 peer online 状态可能滞后，硬确认会把启动卡死）。
func SetExitNode(id string) error {
	lc, err := localExitClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := setExitNode(ctx, lc, id); err != nil {
		return err
	}
	if id == "" {
		return nil
	}
	// 软等待：让 WireGuard 配置有机会更新（首个连接不走旧路由）。
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		st, err := lc.Status(ctx)
		if err == nil && st.ExitNodeStatus != nil && string(st.ExitNodeStatus.ID) == id {
			return nil
		}
		time.Sleep(300 * time.Millisecond)
	}
	return nil
}
