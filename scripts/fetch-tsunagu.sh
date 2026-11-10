#!/usr/bin/env bash
# Downloads a released Tsunagu core into android/app/libs/tsunagu.aar, with the
# sandbox.proto it was built from. The version defaults to ./tsunagu.version.
# To build the core from a local checkout instead, use scripts/build-tsunagu.sh.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
version=${1:-$(tr -d '[:space:]' < "$root/tsunagu.version")}
base="https://github.com/moku-project/Tsunagu/releases/download/v$version"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for f in tsunagu-android.aar sandbox.proto sha256sums.txt; do
  curl -fsSL -o "$tmp/$f" "$base/$f"
done
(cd "$tmp" && grep -E ' (tsunagu-android\.aar|sandbox\.proto)$' sha256sums.txt | sha256sum -c --quiet -)

mkdir -p "$root/android/app/libs" "$root/android/app/src/main/proto/sandbox/v1"
cp "$tmp/tsunagu-android.aar" "$root/android/app/libs/tsunagu.aar"
cp "$tmp/sandbox.proto" "$root/android/app/src/main/proto/sandbox/v1/sandbox.proto"
echo "fetched Tsunagu $version"
