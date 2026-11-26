package tailnet

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
)

const testAuthKey = "tskey-auth-testkey0000-ABCDEFGHIJKLMNOP"

// startControl runs an in-process control server and DERP/STUN server. With
// requireKey it accepts only testAuthKey on registration; testcontrol applies
// that to every register request, including a restarted node that already has
// a valid node key, so the restart test uses an open control server instead.
func startControl(t *testing.T, requireKey bool) (string, *testcontrol.Server) {
	t.Helper()
	netns.SetEnabled(false) // no interface binding on loopback-only tests
	t.Cleanup(func() { netns.SetEnabled(true) })

	derpMap := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	control := &testcontrol.Server{
		DERPMap:        derpMap,
		DNSConfig:      &tailcfg.DNSConfig{Proxied: true},
		MagicDNSDomain: "tail-scale.ts.net",
		Logf:           t.Logf,
	}
	if requireKey {
		control.RequireAuthKey = testAuthKey
	}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Start()
	t.Cleanup(control.HTTPTestServer.Close)
	return control.HTTPTestServer.URL, control
}

// startEchoNode joins a second tsnet node and serves a TCP echo on port 7777.
func startEchoNode(t *testing.T, controlURL string) (ip, dnsName string) {
	t.Helper()
	s := &tsnet.Server{
		Dir:        t.TempDir(),
		ControlURL: controlURL,
		Hostname:   "echo",
		AuthKey:    testAuthKey,
		Ephemeral:  true,
	}
	t.Cleanup(func() { s.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	st, err := s.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := s.Listen("tcp", ":7777")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	// Wait until the echo node holds a DERP connection, or the first packets
	// from the node under test are dropped (see tsnet's own tests).
	h := s.Sys().HealthTracker.Get()
	ms := s.Sys().MagicSock.Get()
	for ctx.Err() == nil {
		if r := ms.GetLastNetcheckReport(ctx); r != nil && r.PreferredDERP != 0 &&
			!h.GetDERPRegionReceivedTime(r.PreferredDERP).IsZero() {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	return st.TailscaleIPs[0].String(), strings.TrimSuffix(st.Self.DNSName, ".")
}

func parseStatus(t *testing.T) statusJSON {
	t.Helper()
	var s statusJSON
	if err := json.Unmarshal([]byte(Status()), &s); err != nil {
		t.Fatalf("Status is not JSON: %v", err)
	}
	return s
}

// waitState polls Status until pred holds.
func waitState(t *testing.T, d time.Duration, pred func(statusJSON) bool) statusJSON {
	t.Helper()
	deadline := time.Now().Add(d)
	for {
		s := parseStatus(t)
		if pred(s) {
			return s
		}
		if time.Now().After(deadline) {
			t.Fatalf("timed out; last status %+v\nlogs:\n%s", s, Logs())
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func echoRoundTrip(t *testing.T, port int, msg string) {
	t.Helper()
	c, err := net.DialTimeout("tcp", "127.0.0.1:"+strconv.Itoa(port), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(30 * time.Second))
	if _, err := c.Write([]byte(msg)); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatalf("read echo: %v\nlogs:\n%s", err, Logs())
	}
	if string(got) != msg {
		t.Fatalf("echo = %q, want %q", got, msg)
	}
}

func TestNotStarted(t *testing.T) {
	Stop()
	if s := parseStatus(t); s.State != StateStopped || len(s.IPs) != 0 {
		t.Fatalf("status = %+v", s)
	}
	if _, err := ProxyPort("100.64.0.1", 80); err == nil {
		t.Fatal("ProxyPort before Start succeeded")
	}
	if err := WaitConnected(1); err == nil {
		t.Fatal("WaitConnected before Start succeeded")
	}
	if err := Logout(); err == nil {
		t.Fatal("Logout before Start succeeded")
	}
	if err := Start("relative/dir", "", "", "x"); err == nil {
		t.Fatal("relative stateDir accepted")
	}
	Stop() // idempotent
}

func TestProxyEchoAndRestart(t *testing.T) {
	controlURL, control := startControl(t, false)
	echoIP, echoDNS := startEchoNode(t, controlURL)
	dir := filepath.Join(t.TempDir(), "state")
	t.Cleanup(Stop)

	if err := Start(dir, testAuthKey, controlURL, "leash-test"); err != nil {
		t.Fatal(err)
	}
	if err := Start(dir, testAuthKey, controlURL, "leash-test"); err != nil {
		t.Fatalf("repeated identical Start: %v", err)
	}
	if err := WaitConnected(60); err != nil {
		t.Fatalf("%v\nlogs:\n%s", err, Logs())
	}
	s := parseStatus(t)
	if s.State != StateConnected || len(s.IPs) == 0 || s.Name != "leash-test" || s.Error != "" {
		t.Fatalf("status = %+v", s)
	}
	firstIP := s.IPs[0]

	port, err := ProxyPort(echoIP, 7777)
	if err != nil {
		t.Fatal(err)
	}
	if again, _ := ProxyPort(echoIP, 7777); again != port {
		t.Fatalf("same target got port %d then %d", port, again)
	}
	echoRoundTrip(t, port, "hello through the tailnet")
	echoRoundTrip(t, port, "second connection")

	// A MagicDNS name resolves inside tsnet.
	dnsPort, err := ProxyPort(echoDNS, 7777)
	if err != nil {
		t.Fatal(err)
	}
	if dnsPort == port {
		t.Fatal("different targets share a port")
	}
	echoRoundTrip(t, dnsPort, "by magicdns name")

	// Stop closes the listeners and reports stopped.
	Stop()
	if s := parseStatus(t); s.State != StateStopped {
		t.Fatalf("after Stop: %+v", s)
	}
	if c, err := net.DialTimeout("tcp", "127.0.0.1:"+strconv.Itoa(port), time.Second); err == nil {
		c.Close()
		t.Fatal("proxy port still open after Stop")
	}
	if _, err := os.Stat(filepath.Join(dir, "tailscaled.state")); err != nil {
		t.Fatalf("no persisted state: %v", err)
	}

	// Restart without an auth key: identity comes from the state dir.
	if err := Start(dir, "", controlURL, "leash-test"); err != nil {
		t.Fatal(err)
	}
	if err := WaitConnected(60); err != nil {
		t.Fatalf("restart: %v\nlogs:\n%s", err, Logs())
	}
	if s := parseStatus(t); len(s.IPs) == 0 || s.IPs[0] != firstIP {
		t.Fatalf("restart changed identity: %+v, first IP %s", s, firstIP)
	}
	port, err = ProxyPort(echoIP, 7777)
	if err != nil {
		t.Fatal(err)
	}
	echoRoundTrip(t, port, "after restart")

	// Logout wipes the state; a start without a key then needs one (here the
	// control server asks for an interactive login, which Leash never does).
	control.RequireAuth = true
	if err := Logout(); err != nil {
		t.Fatalf("Logout: %v", err)
	}
	if ents, _ := os.ReadDir(dir); len(ents) != 0 {
		t.Fatalf("state dir not empty after Logout: %v", ents)
	}
	if err := Start(dir, "", controlURL, "leash-test"); err != nil {
		t.Fatal(err)
	}
	s = waitState(t, 30*time.Second, func(s statusJSON) bool { return s.State == StateNeedsKey })
	if s.Error == "" {
		t.Fatalf("needs_key without a message: %+v", s)
	}
}

func TestBadAuthKey(t *testing.T) {
	controlURL, _ := startControl(t, true)
	dir := filepath.Join(t.TempDir(), "state")
	t.Cleanup(Stop)
	bad := "tskey-auth-wrongwrong-0123456789ABCDEF"

	if err := Start(dir, bad, controlURL, "leash-test"); err != nil {
		t.Fatal(err)
	}
	s := waitState(t, 30*time.Second, func(s statusJSON) bool {
		return s.State != StateStarting
	})
	if s.State != StateNeedsKey || s.Error == "" || len(s.IPs) != 0 {
		t.Fatalf("status = %+v\nlogs:\n%s", s, Logs())
	}
	t.Logf("bad key status: %+v", s)
	if err := WaitConnected(5); err == nil || !strings.Contains(err.Error(), s.Error) {
		t.Fatalf("WaitConnected = %v, want error containing %q", err, s.Error)
	}
	for _, text := range []string{Status(), Logs()} {
		if strings.Contains(text, "wrongwrong") {
			t.Fatal("auth key leaked into Status or Logs")
		}
	}

	// Recover by restarting with the right key.
	if err := Start(dir, testAuthKey, controlURL, "leash-test"); err != nil {
		t.Fatal(err)
	}
	if err := WaitConnected(60); err != nil {
		t.Fatalf("good key after bad: %v\nlogs:\n%s", err, Logs())
	}
}

func TestRedact(t *testing.T) {
	got := redact("login failed with tskey-auth-abc123-DEF_456 for node")
	if strings.Contains(got, "abc123") || !strings.Contains(got, "tskey-REDACTED") {
		t.Fatalf("redact = %q", got)
	}
}

var _ key.NodePublic // keep import grouping stable if tests change
