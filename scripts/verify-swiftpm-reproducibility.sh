#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
zip="$root/codex-agent-runtime-ios/build/distributions/CodexAgent-0.2.0.xcframework.zip"
first=$(mktemp)
trap 'rm -f "$first"' EXIT

build() {
  "$root/gradlew" :codex-agent-runtime-ios:clean \
    :codex-agent-runtime-ios:packageCodexAgentSwiftPackageBinary \
    :codex-agent-runtime-ios:generateCodexAgentSwiftPackageChecksum \
    --no-configuration-cache
}

build
cp "$zip" "$first"
checksum=$(swift package compute-checksum "$zip")
grep -F "checksum: \"$checksum\"" "$root/Package.swift"
build
cmp "$first" "$zip"
test "$checksum" = "$(swift package compute-checksum "$zip")"
echo "Deterministic SwiftPM checksum: $checksum"
