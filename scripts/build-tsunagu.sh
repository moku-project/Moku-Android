#!/usr/bin/env bash
# Builds Tsunagu's embedded core into android/app/libs/tsunagu.aar.
# Run inside `nix develop`. TSUNAGU_SRC points at a Tsunagu checkout (default ../Tsunagu).
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
src=${TSUNAGU_SRC:-$root/../Tsunagu}
out=$root/android/app/libs
targets=${TSUNAGU_TARGETS:-android/arm64,android/amd64}

mkdir -p "$out"
cd "$src/mobile"
version=$(git describe --tags --always --dirty 2>/dev/null || echo dev)

# gomobile looks for gobind on PATH.
bin=$(mktemp -d)
trap 'rm -rf "$bin"' EXIT
go build -o "$bin/gobind" golang.org/x/mobile/cmd/gobind

PATH=$bin:$PATH go tool gomobile bind \
  -target="$targets" \
  -androidapi 26 \
  -javapkg dev.moku.tsunagu \
  -ldflags "-s -w -X tsunagu/backend/embedded.Version=$version" \
  -o "$out/tsunagu.aar" \
  tsunagu/backend/embedded

proto=$root/android/app/src/main/proto/sandbox/v1
mkdir -p "$proto"
cp "$src/proto/sandbox/v1/sandbox.proto" "$proto/"

echo "built $out/tsunagu.aar ($version)"
