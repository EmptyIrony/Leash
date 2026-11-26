//go:build tools

// Pins the gomobile toolchain in go.mod / go.sum; the build scripts install
// gomobile and gobind from these versions.
package tailnet

import (
	_ "golang.org/x/mobile/bind"
	_ "golang.org/x/mobile/cmd/gobind"
	_ "golang.org/x/mobile/cmd/gomobile"
)
