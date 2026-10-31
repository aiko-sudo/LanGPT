package tailscale

import (
	"context"
	"errors"
	"testing"

	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

type fakeExitClient struct {
	status             *ipnstate.Status
	statusErr, editErr error
	edits              []*ipn.MaskedPrefs
	wrongPrefs         bool
}

func (f *fakeExitClient) Status(context.Context) (*ipnstate.Status, error) {
	return f.status, f.statusErr
}
func (f *fakeExitClient) EditPrefs(_ context.Context, p *ipn.MaskedPrefs) (*ipn.Prefs, error) {
	f.edits = append(f.edits, p)
	if f.editErr != nil {
		return nil, f.editErr
	}
	if f.wrongPrefs {
		return &ipn.Prefs{}, nil
	}
	return &p.Prefs, nil
}
func TestExitSelection(t *testing.T) {
	st := &ipnstate.Status{Peer: map[key.NodePublic]*ipnstate.PeerStatus{
		key.NewNode().Public(): {ID: tailcfg.StableNodeID("good"), HostName: "NAS", Online: true, ExitNodeOption: true},
		key.NewNode().Public(): {ID: tailcfg.StableNodeID("offline"), Online: false, ExitNodeOption: true},
		key.NewNode().Public(): {ID: tailcfg.StableNodeID("ordinary"), Online: true},
	}}
	f := &fakeExitClient{status: st}
	ctx := context.Background()
	if len(exitOptions(st)) != 2 {
		t.Fatal("must enumerate approved options")
	}
	// “offline” 仍应可被接受：冷启动后 netmap 的 peer 在线状态可能滞后，
	// 强制在线校验会把刚选好的出口拒掉（V1.4.8 行为变更）。
	for _, id := range []string{"missing", "ordinary"} {
		if setExitNode(ctx, f, id) == nil {
			t.Fatalf("accepted %s", id)
		}
	}
	if len(f.edits) != 0 {
		t.Fatal("edited prefs for invalid node")
	}
	if err := setExitNode(ctx, f, "good"); err != nil {
		t.Fatal(err)
	}
	p := f.edits[0]
	if p.ExitNodeID != "good" || !p.ExitNodeIDSet || !p.ExitNodeIPSet || !p.AutoExitNodeSet || p.RouteAllSet {
		t.Fatal("incorrect mask", p)
	}
	if err := setExitNode(ctx, f, ""); err != nil || f.edits[1].ExitNodeID != "" {
		t.Fatal("clear failed", err)
	}
	f.wrongPrefs = true
	if setExitNode(ctx, f, "good") == nil {
		t.Fatal("accepted unconfirmed preference")
	}
	f.wrongPrefs = false
	f.statusErr = errors.New("status")
	if setExitNode(ctx, f, "good") == nil {
		t.Fatal("ignored status error")
	}
	f.editErr = errors.New("edit")
	if setExitNode(ctx, f, "") == nil {
		t.Fatal("ignored edit error")
	}
}
