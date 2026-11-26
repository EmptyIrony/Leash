#!/usr/bin/env bash
# The remote Android build syncs only android/, so copy the Go sources (single source of truth: ../../tailnet) into
# tailnet/go/ (git-ignored) before android_build / android_gradle / android_test.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../../tailnet"
rm -rf "$here/go"
mkdir -p "$here/go"
for f in "$src"/*.go "$src"/go.mod "$src"/go.sum "$src"/build-tags.txt; do
  case "$f" in *_test.go) continue ;; esac
  cp "$f" "$here/go/"
done
ls "$here/go"
