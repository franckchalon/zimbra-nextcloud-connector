#!/usr/bin/env bash
set -euo pipefail

server_dir="$(cd "$(dirname "$0")" && pwd)"
test_classes="$server_dir/build/test-classes"

"$server_dir/build-local.sh" >/dev/null
rm -rf "$test_classes"
mkdir -p "$test_classes"
mapfile -t test_sources < <(find "$server_dir/test" -name '*.java' -print | sort)
mapfile -t stub_sources < <(find "$server_dir/compile-stubs" -name '*.java' -print | sort)

java com.sun.tools.javac.Main \
  --release 11 \
  -encoding UTF-8 \
  -cp "$server_dir/build/classes" \
  -d "$test_classes" \
  "${stub_sources[@]}" \
  "${test_sources[@]}"

java -cp "$server_dir/build/classes:$test_classes" fr.franckchalon.zimbra.nextcloud.ServerUnitTest
"$server_dir/test-build-on-zimbra-script.sh"
