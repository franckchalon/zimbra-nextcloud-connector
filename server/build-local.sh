#!/usr/bin/env bash
set -euo pipefail

server_dir="$(cd "$(dirname "$0")" && pwd)"
build_dir="$server_dir/build"
classes_dir="$build_dir/classes"

mkdir -p "$classes_dir"
find "$classes_dir" -depth -mindepth 1 -delete

mapfile -t stub_sources < <(find "$server_dir/compile-stubs" -name '*.java' -print | sort)
mapfile -t app_sources < <(find "$server_dir/src" -name '*.java' -print | sort)

java com.sun.tools.javac.Main \
  --release 11 \
  -encoding UTF-8 \
  -d "$classes_dir" \
  "${stub_sources[@]}" \
  "${app_sources[@]}"

rm -rf "$classes_dir/com/zimbra" "$classes_dir/javax"
cp -R "$server_dir/resources/." "$classes_dir/"

java sun.tools.jar.Main cfm "$build_dir/com_nextcloud_connector.jar" "$server_dir/META-INF/MANIFEST.MF" -C "$classes_dir" .
echo "$build_dir/com_nextcloud_connector.jar"
