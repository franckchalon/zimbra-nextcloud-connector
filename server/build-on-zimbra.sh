#!/usr/bin/env bash
set -euo pipefail

server_dir="$(cd "$(dirname "$0")" && pwd)"
if [[ -f "$server_dir/../../i18n.sh" ]]; then
  # Release bundle layout: server/source-build/build-on-zimbra.sh
  source "$server_dir/../../i18n.sh"
elif [[ -f "$server_dir/../installer/i18n.sh" ]]; then
  # Source tree layout: server/build-on-zimbra.sh
  source "$server_dir/../installer/i18n.sh"
fi
if declare -F cloud_read_language >/dev/null; then cloud_read_language; fi
build_message() {
  if declare -F cloud_msg >/dev/null; then cloud_msg "$1"; else printf '%s\n' "$2"; fi
}
build_messagef() {
  local key="$1" fallback="$2"
  shift 2
  if declare -F cloud_msgf >/dev/null; then cloud_msgf "$key" "$@"; else printf "$fallback" "$@"; fi
}
build_dir="$server_dir/build"
classes_dir="$build_dir/classes"
zimbra_jars="${ZIMBRA_LIB_DIR:-/opt/zimbra/lib/jars}"
if [[ -n "${ZIMBRA_JAVA_HOME:-}" && -x "${ZIMBRA_JAVA_HOME}/bin/java" ]]; then
  java_home="$ZIMBRA_JAVA_HOME"
elif [[ -x /opt/zimbra/common/lib/jvm/java/bin/java ]]; then
  java_home="/opt/zimbra/common/lib/jvm/java"
elif [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  java_home="$JAVA_HOME"
else
  build_message jdk_missing "Error: the Zimbra JDK was not found." >&2
  exit 1
fi

if [[ ! -d "$zimbra_jars" ]]; then
  build_messagef libraries_missing 'Error: Zimbra libraries are missing from %s' "$zimbra_jars" >&2; echo >&2
  exit 1
fi

classpath="$(find "$zimbra_jars" -maxdepth 1 \( -type f -o -type l \) -name '*.jar' -print | sort | paste -sd: -)"
if [[ -z "$classpath" ]]; then
  build_messagef compile_jars_missing 'Error: no compilation JAR found in %s' "$zimbra_jars" >&2; echo >&2
  exit 1
fi

mkdir -p "$classes_dir"
find "$classes_dir" -depth -mindepth 1 -delete
mapfile -t app_sources < <(find "$server_dir/src" -name '*.java' -print | sort)

if [[ -x "$java_home/bin/javac" ]]; then
  compiler=("$java_home/bin/javac")
else
  compiler=("$java_home/bin/java" com.sun.tools.javac.Main)
fi

"${compiler[@]}" \
  --release 11 \
  -encoding UTF-8 \
  -cp "$classpath" \
  -d "$classes_dir" \
  "${app_sources[@]}"

# Some Java compiler builds may copy implicitly resolved API classes into the
# output directory. Never package Zimbra or Servlet classes in the extension.
rm -rf "$classes_dir/com/zimbra" "$classes_dir/javax"
cp -R "$server_dir/resources/." "$classes_dir/"
if [[ -x "$java_home/bin/jar" ]]; then
  "$java_home/bin/jar" cfm "$build_dir/com_nextcloud_connector.jar" "$server_dir/META-INF/MANIFEST.MF" -C "$classes_dir" .
else
  "$java_home/bin/java" sun.tools.jar.Main cfm "$build_dir/com_nextcloud_connector.jar" "$server_dir/META-INF/MANIFEST.MF" -C "$classes_dir" .
fi
echo "$build_dir/com_nextcloud_connector.jar"
