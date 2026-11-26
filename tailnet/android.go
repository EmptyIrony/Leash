//go:build android

package tailnet

// Android denies app UIDs the NETLINK_ROUTE socket and /proc/net (since SDK 30, golang/go#40569), so net.Interfaces
// always fails and tailscale's network monitor cannot start; there is also no /etc/resolv.conf for name resolution.
// tsnet does not link the Android fallbacks by default: this blank import registers an interface fallback for netmon
// (used only when net.Interfaces fails, so it is inert elsewhere) and the Android DNS resolver.
import _ "tailscale.com/feature/androidbin"
