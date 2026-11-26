# tailnet

Join a Tailscale network from inside the app process, with no VPN service: Go module
around [`tsnet`](https://pkg.go.dev/tailscale.com/tsnet) (userspace netstack, pinned
`tailscale.com v1.106.1`). Only connections that go through `ProxyPort` use the tailnet.
Built into an AAR (Android) and an xcframework (iOS) with `gomobile bind`.

## API

Package-level functions only (one node per process), restricted to gomobile-supported
types (string, int, error). Names below are the Go ones; gomobile renames them
(Java `Tailnet.start(...)`, Swift `TailnetStart(...)`).

- `Start(stateDir, authKey, controlURL, hostname string) error` creates the node and returns
  once the server is initialised, not when the tailnet is reachable. `stateDir` is an absolute
  path in the app's private storage (node identity lives there). `authKey` (`tskey-auth-...`) is
  only needed until the node is registered; pass `""` afterwards. `controlURL` is `""` for
  Tailscale or a Headscale URL. Same arguments again is a no-op; different arguments restart
  the node and keep the state.
- `Status() string` returns JSON, never blocks long:
  `{"state","backendState","ips":[],"name","dnsName","tailnet","keyExpiry","error","health"}`.
  `state` is one of `stopped`, `starting`, `connected`, `needs_key` (key rejected or expired, or no
  key and no saved state; `error` has control's message, e.g. "invalid authkey") or `error`.
  `ips` is always an array.
- `WaitConnected(timeoutSeconds int) error` blocks until connected; the error carries the reason.
- `ProxyPort(targetHost string, targetPort int) (int, error)` listens on `127.0.0.1:0` and pipes
  each accepted connection to the target through the tailnet. `targetHost` is a tailnet IP or a
  MagicDNS name (resolved inside tsnet). The same target returns the same port until `Stop`.
  The app then connects to `ws://127.0.0.1:<port>/ws`.
- `Stop()` closes the proxies and leaves the tailnet; state is kept. `Logout() error` also logs the
  node out at control and deletes the state, so the next `Start` needs a new key.
- `Logs() string` returns the last 300 diagnostic lines. Anything that looks like a `tskey-...`
  is redacted in logs, errors and status; the auth key is never logged.

Any app on the device can connect to a proxy port, so the target must authenticate itself (the
bridge checks its bearer token).

## Test

Needs Go 1.27+ (installed under `~/.local/go` in the dev container):

    go vet ./... && go test ./...

The tests run tailscale's in-process control server and DERP/STUN server (no network access):
Start, ProxyPort and a TCP echo on a second tsnet node (by IP and by MagicDNS name), Stop,
restart from persisted state without a key, Logout, a bad auth key (`needs_key`, "invalid
authkey", key not leaked) and recovery. `testcontrol` with `RequireAuthKey` rejects every
register request, so the restart test uses an open control server.

## Build

`build-tags.txt` lists the `ts_omit_*` feature tags that drop unused Tailscale features (SSH
server, taildrop, drive, serve/funnel, ACME, kube, aws, ...) from the binary. Both scripts
use it with `-trimpath -ldflags "-s -w"`. If a tailscale upgrade breaks a tag, remove it there.

- `./build-android.sh` needs the Android SDK with an NDK (`ANDROID_HOME`, or `ANDROID_NDK_HOME`).
  Output `build/tailnet.aar` (arm64-v8a + x86_64, API 29, Java package `dev.leash.personal.tailnet`).
- `./build-ios.sh` runs on the Mac with Xcode. Output `../ios/Frameworks/Tailnet.xcframework`
  (device + simulator, iOS 16), git-ignored.

Both install `gomobile`/`gobind` from the version pinned in `go.mod` (`tools.go`).
