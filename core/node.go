package core

// Embedded Tailscale node (tsnet, userspace networking — no VpnService needed).

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

// StatusListener receives Tailscale backend state changes.
// state is an ipn.State name: NoState, NeedsLogin, NeedsMachineAuth, Stopped, Starting, Running.
// authURL is non-empty when the user must open it in a browser to log in.
type StatusListener interface {
	OnStatus(state string, authURL string)
	OnLog(line string)
}

type Node struct {
	mu       sync.Mutex
	dir      string
	hostname string
	srv      *tsnet.Server
	cancel   context.CancelFunc
}

func NewNode(dataDir, hostname string) *Node {
	return &Node{dir: dataDir, hostname: hostname}
}

// Start brings the node up in the background. authKey may be empty (interactive login).
func (n *Node) Start(authKey string, l StatusListener) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.srv != nil {
		return nil
	}
	// Do not upload logs to Tailscale's log server.
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")

	dir := filepath.Join(n.dir, "tsnet")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	srv := &tsnet.Server{
		Dir:      dir,
		Hostname: n.hostname,
		AuthKey:  authKey,
		Logf:     func(string, ...any) {},
		UserLogf: func(format string, args ...any) {
			if l != nil {
				l.OnLog(fmt.Sprintf(format, args...))
			}
		},
	}
	if err := srv.Start(); err != nil {
		return err
	}
	n.srv = srv
	ctx, cancel := context.WithCancel(context.Background())
	n.cancel = cancel
	go n.watch(ctx, srv, l)
	return nil
}

func (n *Node) watch(ctx context.Context, srv *tsnet.Server, l StatusListener) {
	lc, err := srv.LocalClient()
	if err != nil {
		if l != nil {
			l.OnStatus("Error", "")
			l.OnLog(err.Error())
		}
		return
	}
	for ctx.Err() == nil {
		w, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState)
		if err != nil {
			time.Sleep(time.Second)
			continue
		}
		state, url := "", ""
		loginRequested := false
		for {
			nt, err := w.Next()
			if err != nil {
				break
			}
			if nt.State != nil {
				state = nt.State.String()
			}
			if nt.BrowseToURL != nil {
				url = *nt.BrowseToURL
			}
			if state == "Running" {
				url = ""
				loginRequested = false
			}
			if state == "NeedsLogin" && url == "" && !loginRequested {
				loginRequested = true
				go lc.StartLoginInteractive(ctx)
			}
			if l != nil && state != "" {
				l.OnStatus(state, url)
			}
		}
		w.Close()
	}
}

func (n *Node) server() *tsnet.Server {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.srv
}

type peerJSON struct {
	Name   string `json:"name"`
	DNS    string `json:"dns"`
	IP     string `json:"ip"`
	OS     string `json:"os"`
	Online bool   `json:"online"`
}

// PeersJSON returns the tailnet peers as a JSON array.
func (n *Node) PeersJSON() (string, error) {
	srv := n.server()
	if srv == nil {
		return "[]", errors.New("tailscale not started")
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return "[]", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return "[]", err
	}
	out := []peerJSON{}
	for _, p := range st.Peer {
		pj := peerJSON{
			Name:   p.HostName,
			DNS:    strings.TrimSuffix(p.DNSName, "."),
			OS:     p.OS,
			Online: p.Online,
		}
		if len(p.TailscaleIPs) > 0 {
			pj.IP = p.TailscaleIPs[0].String()
		}
		out = append(out, pj)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Online != out[j].Online {
			return out[i].Online
		}
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	b, err := json.Marshal(out)
	return string(b), err
}

func (n *Node) Logout() error {
	srv := n.server()
	if srv == nil {
		return errors.New("tailscale not started")
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return lc.Logout(ctx)
}

func (n *Node) Close() {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.cancel != nil {
		n.cancel()
	}
	if n.srv != nil {
		n.srv.Close()
		n.srv = nil
	}
}

func (n *Node) dial(ctx context.Context, addr string) (net.Conn, error) {
	srv := n.server()
	if srv == nil {
		return nil, errors.New("tailscale not started")
	}
	return srv.Dial(ctx, "tcp", addr)
}
