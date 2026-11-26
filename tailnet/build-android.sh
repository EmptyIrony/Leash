#!/usr/bin/env bash
# Builds build/tailnet.aar (arm64-v8a + x86_64, API 29+) with gomobile.
#
# Needs: Go (same version as go.mod or newer) on PATH, the Android SDK with an NDK
# (ANDROID_HOME, and ANDROID_NDK_HOME or an NDK under $ANDROID_HOME/ndk).
# Output: $OUT (default: tailnet/build/tailnet.aar); Java package
# dev.leash.personal.tailnet, class Tailnet.
set -euo pipefail
cd "$(dirname "$0")"

OUT="${OUT:-build/tailnet.aar}"
JAVAPKG="${JAVAPKG:-dev.leash.personal}"
ANDROIDAPI="${ANDROIDAPI:-29}"
TARGETS="${TARGETS:-android/arm64,android/amd64}"

command -v go >/dev/null || { echo "go not found on PATH" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -n "$SDK" ] && [ -d "$SDK/ndk" ]; then
    ANDROID_NDK_HOME="$SDK/ndk/$(ls "$SDK/ndk" | sort -V | tail -n 1)"
  fi
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "Android NDK not found: set ANDROID_NDK_HOME (or ANDROID_HOME with an ndk/ directory)." >&2
  exit 1
fi
export ANDROID_NDK_HOME
[ -n "$SDK" ] && export ANDROID_HOME="$SDK"

# gomobile and gobind at the versions pinned in go.mod (tools.go).
GOBIN="$(go env GOPATH)/bin"
GOFLAGS=-mod=mod go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export PATH="$GOBIN:$PATH"

TAGS="$(tr -d '\n' < build-tags.txt)"
mkdir -p "$(dirname "$OUT")"
# -s -w drops symbol and DWARF tables, -trimpath makes the build reproducible.
GOFLAGS=-mod=mod gomobile bind \
  -target "$TARGETS" -androidapi "$ANDROIDAPI" \
  -javapkg "$JAVAPKG" \
  -tags "$TAGS" -trimpath -ldflags "-s -w" \
  -o "$OUT" .
ls -l "$OUT"
