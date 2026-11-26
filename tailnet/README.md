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
- `./build-ios.sh` runs on the Mac with Xcode (see "iOS" below). Output `../ios/Frameworks/Tailnet.xcframework`
  (device + simulator, iOS 17) and `../ios/Frameworks/Tailnet.xcconfig`, both git-ignored.

Both install `gomobile`/`gobind` from the version pinned in `go.mod` (`tools.go`).

## iOS

The Swift side is in `ios/Leash/Core/TailnetNode.swift` (the only file that imports the framework, behind
`#if canImport(Tailnet)`), `TailnetController.swift` and `Views/TailnetSettingsSection.swift`.

Mac steps (Xcode with the iOS and Simulator SDKs, Go on PATH, e.g. `brew install go`):

    cd ~/workspace/Leash/tailnet && ./build-ios.sh      # a few minutes; builds device + simulator slices
    open ~/workspace/Leash/ios/Leash.xcodeproj           # build and run the Leash scheme as usual

In the app: Settings > Built-in Tailscale > turn on, paste a Tailscale auth key (tskey-auth-..., from the admin
console), Save & connect. Under Advanced a Headscale control URL can be set. The bridge address is then its Tailscale
IP or MagicDNS name.

How the framework is linked: the xcframework contains static frameworks, so it is linked, not embedded, and only
into the Leash app target. A hard file reference to a framework that is not built yet would break every build, so
the project does not reference it. `ios/Config/Leash.xcconfig` (base configuration of the Leash target) does
`#include? "../Frameworks/Tailnet.xcconfig"`; build-ios.sh writes that file with the per-SDK framework search paths
and `-framework Tailnet`. Without it the app builds with the feature disabled and Settings says so.

Notes:

- `gomobile init` is deliberately not run: in this gomobile it installs `gobind@latest` over the pinned version, and
  `bind` needs nothing else for iOS.
- The 5a failure "can't find export data" (e.g. "bufio: buffer full") on `GOOS=ios` was a damaged Go build cache,
  not the tags: with an empty `GOCACHE`, `GOOS=ios GOARCH=arm64 CGO_ENABLED=0 go build -tags "ios,$(cat build-tags.txt)" ./...`
  succeeds in the Linux container. `go clean -cache` fixes it. The real cgo/Xcode link was not run here (no Xcode).
- If the app's link step reports undefined symbols from the Go runtime, add the named system framework to
  `OTHER_LDFLAGS` in build-ios.sh.
- The node runs only while Leash is on screen, kept alive by the Live Activity audio, or doing background work
  (`AppModel.connectionWanted`); otherwise `AppModel.backgroundWork` closes the socket and stops it.
