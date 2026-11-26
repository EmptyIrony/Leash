#!/usr/bin/env bash
# Builds Tailnet.xcframework (iOS device + simulator, iOS 16+) with gomobile.
# Run on the Mac with Xcode (command line tools selected) and Go on PATH.
# Output: $OUT (default: ../ios/Frameworks/Tailnet.xcframework, git-ignored).
set -euo pipefail
cd "$(dirname "$0")"

OUT="${OUT:-../ios/Frameworks/Tailnet.xcframework}"
IOSVERSION="${IOSVERSION:-16.0}"
TARGETS="${TARGETS:-ios,iossimulator}"

command -v go >/dev/null || { echo "go not found on PATH" >&2; exit 1; }
[ "$(uname -s)" = Darwin ] || { echo "build-ios.sh must run on macOS with Xcode" >&2; exit 1; }
command -v xcrun >/dev/null && xcrun --sdk iphoneos --show-sdk-path >/dev/null \
  || { echo "Xcode with the iOS SDK is required (xcode-select -s /Applications/Xcode.app)" >&2; exit 1; }

# gomobile and gobind at the versions pinned in go.mod (tools.go).
GOBIN="$(go env GOPATH)/bin"
GOFLAGS=-mod=mod go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export PATH="$GOBIN:$PATH"

TAGS="$(tr -d '\n' < build-tags.txt)"
mkdir -p "$(dirname "$OUT")"
rm -rf "$OUT"
GOFLAGS=-mod=mod gomobile bind \
  -target "$TARGETS" -iosversion "$IOSVERSION" \
  -tags "$TAGS" -trimpath -ldflags "-s -w" \
  -o "$OUT" .
echo "built $OUT"
