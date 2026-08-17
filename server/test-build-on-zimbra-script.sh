#!/usr/bin/env bash
set -euo pipefail

server_dir="$(cd "$(dirname "$0")" && pwd)"
temporary="$(mktemp -d "$server_dir/.zimbra-build-test.XXXXXX")"

cleanup() {
  if [[ -d "$temporary" ]]; then
    find "$temporary" -depth -mindepth 1 -delete
    rmdir "$temporary"
  fi
}
trap cleanup EXIT

mkdir -p "$temporary/classes" "$temporary/jars"
mapfile -t stub_sources < <(find "$server_dir/compile-stubs" -name '*.java' -print | sort)
java com.sun.tools.javac.Main --release 11 -encoding UTF-8 -d "$temporary/classes" "${stub_sources[@]}"
java sun.tools.jar.Main cf "$temporary/jars/zimbra-api-test.jar" -C "$temporary/classes" .

java_binary="$(readlink -f "$(command -v java)")"
java_home="$(cd "$(dirname "$java_binary")/.." && pwd)"
ZIMBRA_LIB_DIR="$temporary/jars" ZIMBRA_JAVA_HOME="$java_home" "$server_dir/build-on-zimbra.sh" >/dev/null

if java sun.tools.jar.Main tf "$server_dir/build/com_nextcloud_connector.jar" | grep -Eq '^(com/zimbra/|javax/servlet/)'; then
  echo "Erreur : le JAR final contient des classes appartenant à Zimbra ou Servlet." >&2
  exit 1
fi

echo "Zimbra-compatible build script: OK"
