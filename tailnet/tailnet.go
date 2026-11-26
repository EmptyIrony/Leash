// Package tailnet lets an app join a Tailscale network from inside its own
// process (tsnet, userspace netstack): no VpnService, no NetworkExtension, no
// TUN device. Only connections the app explicitly sends through ProxyPort use
// the tailnet.
//
// The exported API is deliberately gomobile-friendly: package-level functions
// with string / int / error parameters and results only. There is one node per
// process.
package tailnet

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

// State values reported in the "state" field of Status.
const (
	// StateStopped: Start has not been called, or Stop/Logout ran.
	StateStopped = "stopped"
	// StateStarting: the node is registering or reconnecting.
	StateStarting = "starting"
	// StateConnected: the node is on the tailnet and ProxyPort connections work.
	StateConnected = "connected"
	// StateNeedsKey: the node is not logged in and cannot become so without a
	// (new) auth key: the key was rejected, expired, or none was given and no
	// state is persisted. "error" says what control reported.
	StateNeedsKey = "needs_key"
	// StateError: Start failed or the backend is in a state this package does
	// not expect; "error" has the details.
	StateError = "error"
)

// statusJSON is the shape of the string returned by Status.
type statusJSON struct {
	State string `json:"state"`
	// BackendState is Tailscale's own state name (Running, NeedsLogin, ...).
	BackendState string   `json:"backendState,omitempty"`
	IPs          []string `json:"ips"`
	// Name is the node's short host name on the tailnet; DNSName its MagicDNS FQDN.
	Name    string `json:"name,omitempty"`
	DNSName string `json:"dnsName,omitempty"`
	Tailnet string `json:"tailnet,omitempty"`
	// KeyExpiry is RFC 3339; empty when the node key does not expire.
	KeyExpiry string   `json:"keyExpiry,omitempty"`
	Error     string   `json:"error,omitempty"`
	Health    []string `json:"health,omitempty"`
}

var (
	mu   sync.Mutex // guards node
	node *nodeState
)

type nodeState struct {
	srv      *tsnet.Server
	lc       *local.Client
	stateDir string
	cfgKey   string // hash-free fingerprint of the Start arguments, see sameConfig
	cancel   context.CancelFunc
	logs     *ring

	pmu      sync.Mutex // guards the fields below
	backend  string
	loginURL string
	lastErr  string
	proxies  map[string]*forwarder // keyed by "host:port"
}

// Start brings the node up and returns as soon as the embedded server is
// initialised; it does not wait for the tailnet. Poll Status, or call
// WaitConnected, to follow the registration.
//
//   - stateDir: a directory used only by this package, in the app's private
//     storage. Node identity lives here; keep it across launches.
//   - authKey: a Tailscale auth key (tskey-auth-...). Only needed until the node
//     is registered; may be empty afterwards. Never logged.
//   - controlURL: empty for Tailscale's own control plane, or a Headscale URL.
//   - hostname: the name shown in the admin console.
//
// Calling Start again with the same arguments while running is a no-op; with
// different arguments it restarts the node (persisted state is kept).
func Start(stateDir, authKey, controlURL, hostname string) error {
	mu.Lock()
	defer mu.Unlock()

	stateDir = strings.TrimSpace(stateDir)
	if stateDir == "" || !filepath.IsAbs(stateDir) {
		return errors.New("tailnet: stateDir must be an absolute path")
	}
	authKey = strings.TrimSpace(authKey)
	controlURL = strings.TrimSpace(controlURL)
	hostname = strings.TrimSpace(hostname)
	if hostname == "" {
		hostname = "leash"
	}
	cfg := strings.Join([]string{stateDir, authKey, controlURL, hostname}, "\x00")
	if node != nil {
		if node.cfgKey == cfg {
			return nil
		}
		stopLocked()
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return fmt.Errorf("tailnet: create state dir: %w", err)
	}

	logs := &ring{max: 300}
	srv := &tsnet.Server{
		Dir:        stateDir,
		Hostname:   hostname,
		AuthKey:    authKey,
		ControlURL: controlURL,
		Logf:       logs.logf,
		UserLogf:   logs.logf,
	}
	if err := srv.Start(); err != nil {
		srv.Close()
		return fmt.Errorf("tailnet: start: %s", redact(err.Error()))
	}
	lc, err := srv.LocalClient()
	if err != nil {
		srv.Close()
		return fmt.Errorf("tailnet: local client: %s", redact(err.Error()))
	}
	ctx, cancel := context.WithCancel(context.Background())
	n := &nodeState{
		srv: srv, lc: lc, stateDir: stateDir, cfgKey: cfg, cancel: cancel, logs: logs,
		backend: "NoState", proxies: map[string]*forwarder{},
	}
	node = n
	go n.watch(ctx)
	return nil
}

// watch follows the IPN bus so Status can report why login failed: the bus is
// the only place control's rejection message shows up.
func (n *nodeState) watch(ctx context.Context) {
	for ctx.Err() == nil {
		w, err := n.lc.WatchIPNBus(ctx, ipn.NotifyInitialState)
		if err != nil {
			select {
			case <-ctx.Done():
				return
			case <-time.After(500 * time.Millisecond):
			}
			continue
		}
		for {
			m, err := w.Next()
			if err != nil {
				break
			}
			n.pmu.Lock()
			if m.State != nil {
				n.backend = m.State.String()
				if *m.State == ipn.Running {
					n.lastErr, n.loginURL = "", ""
				}
			}
			if m.ErrMessage != nil {
				n.lastErr = redact(*m.ErrMessage)
			}
			if m.BrowseToURL != nil {
				n.loginURL = *m.BrowseToURL
			}
			n.pmu.Unlock()
		}
		w.Close()
	}
}

// Status returns a JSON object describing the node; see statusJSON for the
// fields. It never blocks for long and is safe to call at any time.
func Status() string {
	mu.Lock()
	n := node
	mu.Unlock()
	return statusString(buildStatus(n))
}

func statusString(s statusJSON) string {
	if s.IPs == nil {
		s.IPs = []string{}
	}
	b, _ := json.Marshal(s)
	return string(b)
}

func buildStatus(n *nodeState) statusJSON {
	if n == nil {
		return statusJSON{State: StateStopped}
	}
	n.pmu.Lock()
	backend, lastErr, loginURL := n.backend, n.lastErr, n.loginURL
	n.pmu.Unlock()

	s := statusJSON{BackendState: backend, Error: lastErr}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if st, err := n.lc.StatusWithoutPeers(ctx); err == nil {
		backend = st.BackendState
		s.BackendState = backend
		for _, ip := range st.TailscaleIPs {
			s.IPs = append(s.IPs, ip.String())
		}
		if st.Self != nil {
			s.Name = st.Self.HostName
			s.DNSName = strings.TrimSuffix(st.Self.DNSName, ".")
			if st.Self.KeyExpiry != nil {
				s.KeyExpiry = st.Self.KeyExpiry.UTC().Format(time.RFC3339)
			}
		}
		if st.CurrentTailnet != nil {
			s.Tailnet = st.CurrentTailnet.Name
		}
		for _, h := range st.Health {
			s.Health = append(s.Health, redact(h))
		}
		if st.AuthURL != "" {
			loginURL = st.AuthURL
		}
	}

	if backend != "Running" {
		// Before registration Self only carries the local host name.
		s.Name, s.DNSName, s.Tailnet, s.KeyExpiry = "", "", "", ""
	}

	switch backend {
	case "Running":
		s.State = StateConnected
		s.Error = ""
	case "NeedsLogin":
		// "NeedsLogin" is also the brief state before a given key is used, so
		// only call it a key problem once control has said something, or has
		// asked for an interactive login (which this package never does).
		if s.Error != "" || loginURL != "" {
			s.State = StateNeedsKey
			if s.Error == "" {
				s.Error = "a new auth key is needed"
			}
		} else {
			s.State = StateStarting
		}
	case "NeedsMachineAuth":
		s.State = StateError
		s.Error = "this node must be approved by a tailnet admin"
	case "Stopped":
		s.State = StateError
		if s.Error == "" {
			s.Error = "the node is stopped"
		}
	default: // NoState, Starting
		if s.Error != "" {
			s.State = StateError
		} else {
			s.State = StateStarting
		}
	}
	return s
}

// WaitConnected blocks until the node is connected, needs a new key, hits an
// error, or timeoutSeconds elapse. It returns nil only when connected;
// otherwise the error carries the reason (also available from Status).
func WaitConnected(timeoutSeconds int) error {
	deadline := time.Now().Add(time.Duration(timeoutSeconds) * time.Second)
	for {
		mu.Lock()
		n := node
		mu.Unlock()
		s := buildStatus(n)
		switch s.State {
		case StateConnected:
			return nil
		case StateStopped:
			return errors.New("tailnet: not started")
		case StateNeedsKey, StateError:
			return errors.New("tailnet: " + s.Error)
		}
		if time.Now().After(deadline) {
			return errors.New("tailnet: timed out while connecting")
		}
		time.Sleep(100 * time.Millisecond)
	}
}

// ProxyPort returns a TCP port on 127.0.0.1 that forwards every connection to
// targetHost:targetPort over the tailnet. targetHost may be a tailnet IP or a
// MagicDNS name (host.tailnet.ts.net, or a bare host name). Asking again for
// the same target returns the same port. The listener lives until Stop.
//
// Any app on the device can connect to the returned port; the target is
// expected to do its own authentication.
func ProxyPort(targetHost string, targetPort int) (int, error) {
	targetHost = strings.TrimSpace(targetHost)
	if targetHost == "" || targetPort < 1 || targetPort > 65535 {
		return 0, errors.New("tailnet: invalid target")
	}
	mu.Lock()
	n := node
	mu.Unlock()
	if n == nil {
		return 0, errors.New("tailnet: not started")
	}
	target := net.JoinHostPort(targetHost, strconv.Itoa(targetPort))

	n.pmu.Lock()
	defer n.pmu.Unlock()
	if n.proxies == nil {
		return 0, errors.New("tailnet: stopped")
	}
	if f, ok := n.proxies[target]; ok {
		return f.port, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("tailnet: listen: %w", err)
	}
	f := &forwarder{srv: n.srv, ln: ln, target: target, port: ln.Addr().(*net.TCPAddr).Port, conns: map[net.Conn]struct{}{}}
	n.proxies[target] = f
	go f.serve()
	return f.port, nil
}

// Stop shuts the node down: closes the proxy listeners and their connections
// and leaves the tailnet. Persisted state is kept. Safe to call at any time.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()
}

func stopLocked() {
	n := node
	node = nil
	if n == nil {
		return
	}
	n.cancel()
	n.pmu.Lock()
	for _, f := range n.proxies {
		f.close()
	}
	n.proxies = nil
	n.pmu.Unlock()
	n.srv.Close()
}

// Logout logs the node out of the tailnet (control invalidates its key), stops
// it and deletes the persisted state in stateDir, so the next Start needs a
// fresh auth key. The node must have been started. If control cannot be
// reached the local state is deleted anyway and the error says so; the node
// then stays listed in the admin console until removed there.
func Logout() error {
	mu.Lock()
	defer mu.Unlock()
	n := node
	if n == nil {
		return errors.New("tailnet: not started")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	lerr := n.lc.Logout(ctx)
	dir := n.stateDir
	stopLocked()
	if err := removeContents(dir); err != nil {
		return fmt.Errorf("tailnet: remove state: %w", err)
	}
	if lerr != nil {
		return fmt.Errorf("tailnet: control logout failed, local state removed: %s", redact(lerr.Error()))
	}
	return nil
}

// Logs returns the most recent diagnostic lines (auth keys are redacted).
// Empty when not started.
func Logs() string {
	mu.Lock()
	n := node
	mu.Unlock()
	if n == nil {
		return ""
	}
	return n.logs.String()
}

func removeContents(dir string) error {
	ents, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	for _, e := range ents {
		if err := os.RemoveAll(filepath.Join(dir, e.Name())); err != nil {
			return err
		}
	}
	return nil
}

// forwarder pipes connections accepted on a loopback listener to one tailnet target.
type forwarder struct {
	srv    *tsnet.Server
	ln     net.Listener
	target string
	port   int

	cmu    sync.Mutex
	conns  map[net.Conn]struct{}
	closed bool
}

func (f *forwarder) serve() {
	for {
		c, err := f.ln.Accept()
		if err != nil {
			return
		}
		if !f.track(c) {
			c.Close()
			return
		}
		go f.pipe(c)
	}
}

func (f *forwarder) track(c net.Conn) bool {
	f.cmu.Lock()
	defer f.cmu.Unlock()
	if f.closed {
		return false
	}
	f.conns[c] = struct{}{}
	return true
}

func (f *forwarder) untrack(c net.Conn) {
	f.cmu.Lock()
	delete(f.conns, c)
	f.cmu.Unlock()
	c.Close()
}

func (f *forwarder) close() {
	f.ln.Close()
	f.cmu.Lock()
	f.closed = true
	for c := range f.conns {
		c.Close()
	}
	f.cmu.Unlock()
}

// dialTimeout bounds one outbound connect, including the wait for the node to
// reach the tailnet when the first connection races start-up.
const dialTimeout = 20 * time.Second

func (f *forwarder) pipe(local net.Conn) {
	defer f.untrack(local)
	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	remote, err := f.srv.Dial(ctx, "tcp", f.target)
	cancel()
	if err != nil {
		return
	}
	if !f.track(remote) {
		remote.Close()
		return
	}
	defer f.untrack(remote)

	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		} else {
			dst.Close()
		}
		done <- struct{}{}
	}
	go cp(remote, local)
	go cp(local, remote)
	<-done
	<-done
}

// ring keeps the last max log lines.
type ring struct {
	mu    sync.Mutex
	max   int
	lines []string
}

func (r *ring) logf(format string, args ...any) {
	line := redact(strings.TrimRight(fmt.Sprintf(format, args...), "\n"))
	r.mu.Lock()
	r.lines = append(r.lines, time.Now().UTC().Format("15:04:05 ")+line)
	if len(r.lines) > r.max {
		r.lines = r.lines[len(r.lines)-r.max:]
	}
	r.mu.Unlock()
}

func (r *ring) String() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return strings.Join(r.lines, "\n")
}

var keyRE = regexp.MustCompile(`tskey-[A-Za-z0-9_-]+`)

// redact removes anything that looks like a Tailscale key.
func redact(s string) string { return keyRE.ReplaceAllString(s, "tskey-REDACTED") }
