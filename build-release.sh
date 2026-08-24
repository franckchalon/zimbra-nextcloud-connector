#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
version="3.2.0-beta.7"
zimlet_version="$(node -p "require('$project_dir/package.json').zimletVersion")"
dist_dir="$project_dir/dist"
release_name="zimbra-nextcloud-connector-${version}"
release_dir="$dist_dir/$release_name"
stage_dir="$(mktemp -d "$project_dir/.release-stage.XXXXXX")"

cleanup() {
  if [[ -d "$stage_dir" ]]; then
    find "$stage_dir" -depth -mindepth 1 -delete
    rmdir "$stage_dir"
  fi
}
trap cleanup EXIT

cd "$project_dir"
if [[ ! "$zimlet_version" =~ ^[0-9]+(\.[0-9]+){1,3}$ ]]; then
  echo "Zimbra package metadata version must be numeric: $zimlet_version" >&2
  exit 1
fi
private_marker="$(printf '%s%s' '3' 'tech')"
if rg -n -i "$private_marker" . \
  --glob '!.git/**' --glob '!node_modules/**' --glob '!build/**' --glob '!pkg/**' \
  --glob '!build-chat/**' --glob '!pkg-chat/**' --glob '!classic-build/**' --glob '!pkg-classic/**' --glob '!dist/**'; then
  echo "Private infrastructure marker found; release aborted." >&2
  exit 1
fi
for generated_dir in "$project_dir/build" "$project_dir/pkg" "$project_dir/build-chat" "$project_dir/pkg-chat" "$project_dir/classic-build" "$project_dir/pkg-classic"; do
  if [[ -d "$generated_dir" ]]; then
    find "$generated_dir" -depth -mindepth 1 -delete
    rmdir "$generated_dir"
  fi
done
"$project_dir/node_modules/.bin/zimlet" build
"$project_dir/node_modules/.bin/zimlet" package \
  --name com_nextcloud_connector \
  --label "Cloud" \
  --description "Nextcloud files, Talk chat, ONLYOFFICE and Euro-Office integration for Zimbra Modern UI." \
  --pkg-version "$zimlet_version" \
  --zimbraXVersion ">=0.0.1"
"$project_dir/node_modules/.bin/zimlet" build --config zimlet.chat.config.js --dest build-chat
node package-chat.js
"$project_dir/node_modules/.bin/webpack" --config webpack.classic.config.js
node package-classic.js
bash ./test-frontend-build.sh
bash ./test-classic-build.sh
bash ./test-installer.sh
./server/test-local.sh

mkdir -p "$stage_dir/$release_name/frontend" "$stage_dir/$release_name/server" "$stage_dir/$release_name/source"
install -m 0755 installer/configure.sh "$stage_dir/$release_name/configure.sh"
install -m 0755 installer/install.sh "$stage_dir/$release_name/install.sh"
install -m 0755 installer/repair-modern-ui.sh "$stage_dir/$release_name/repair-modern-ui.sh"
install -m 0755 installer/repair-classic-ui.sh "$stage_dir/$release_name/repair-classic-ui.sh"
install -m 0755 installer/uninstall.sh "$stage_dir/$release_name/uninstall.sh"
install -m 0755 installer/storage-report.sh "$stage_dir/$release_name/storage-report.sh"
install -m 0755 installer/diagnose.sh "$stage_dir/$release_name/diagnose.sh"
install -m 0755 installer/lifecycle-report.sh "$stage_dir/$release_name/lifecycle-report.sh"
install -m 0644 installer/i18n.sh "$stage_dir/$release_name/i18n.sh"
install -m 0644 installer/zimlet-cos.sh "$stage_dir/$release_name/zimlet-cos.sh"
install -m 0644 README.md "$stage_dir/$release_name/README.md"
install -m 0644 README_FR.md "$stage_dir/$release_name/README_FR.md"
install -m 0644 TESTING.md "$stage_dir/$release_name/TESTING.md"
install -m 0644 PUBLISHING.md "$stage_dir/$release_name/PUBLISHING.md"
install -m 0644 RELEASE_NOTES_BETA.md "$stage_dir/$release_name/RELEASE_NOTES_BETA.md"
install -m 0644 CONTRIBUTING.md "$stage_dir/$release_name/CONTRIBUTING.md"
install -m 0644 SECURITY.md "$stage_dir/$release_name/SECURITY.md"
install -m 0644 CHANGELOG.md "$stage_dir/$release_name/CHANGELOG.md"
install -m 0644 LICENSE "$stage_dir/$release_name/LICENSE"
install -m 0644 pkg/com_nextcloud_connector.zip "$stage_dir/$release_name/frontend/com_nextcloud_connector.zip"
install -m 0644 pkg-chat/com_nextcloud_connector_chat.zip "$stage_dir/$release_name/frontend/com_nextcloud_connector_chat.zip"
install -m 0644 pkg-classic/fr_franckchalon_nextcloud_classic.zip "$stage_dir/$release_name/frontend/fr_franckchalon_nextcloud_classic.zip"
install -m 0644 server/build/com_nextcloud_connector.jar "$stage_dir/$release_name/server/com_nextcloud_connector.jar"
install -m 0600 server/resources/config.example.properties "$stage_dir/$release_name/server/config.example.properties"
mkdir -p "$stage_dir/$release_name/server/source-build"
cp -R server/src server/resources server/META-INF "$stage_dir/$release_name/server/source-build/"
install -m 0755 server/build-on-zimbra.sh "$stage_dir/$release_name/server/source-build/build-on-zimbra.sh"

source_zip="$stage_dir/$release_name/source/zimbra-nextcloud-connector-source-${version}.zip"
zip -q -r "$source_zip" . \
  -x 'node_modules/*' 'build/*' 'pkg/*' 'build-chat/*' 'pkg-chat/*' 'classic-build/*' 'pkg-classic/*' 'dist/*' 'server/build/*' \
     'server/.zimbra-build-test.*/*' '.release-stage.*/*' '.git/*' \
     '.env' '.env.*' '*.local.properties' 'nextcloud-zimlet.properties' \
     '*.log' '*.har' '*.pem' '*.key' '*.p12' '*.enc' '*.zip'
if unzip -Z1 "$source_zip" | grep -E '(^|/)(node_modules|\.git|dist|build|pkg|build-chat|pkg-chat|classic-build|pkg-classic)/|(^|/)nextcloud-zimlet\.properties$|\.(enc|log|har|pem|key|p12)$'; then
  echo "Forbidden private/generated file found in source archive; release aborted." >&2
  exit 1
fi
(cd "$stage_dir/$release_name" && sha256sum \
  frontend/com_nextcloud_connector.zip \
  frontend/com_nextcloud_connector_chat.zip \
  frontend/fr_franckchalon_nextcloud_classic.zip \
  server/com_nextcloud_connector.jar \
  source/zimbra-nextcloud-connector-source-${version}.zip > SHA256SUMS)

mkdir -p "$dist_dir"
if [[ -d "$release_dir" ]]; then
  find "$release_dir" -depth -mindepth 1 -delete
  rmdir "$release_dir"
fi
mv "$stage_dir/$release_name" "$release_dir"

bundle="$dist_dir/zimbra-nextcloud-connector-v${version}.zip"
if [[ -f "$bundle" ]]; then
  find "$bundle" -maxdepth 0 -type f -delete
fi
checksum="$bundle.sha256"
if [[ -f "$checksum" ]]; then
  find "$checksum" -maxdepth 0 -type f -delete
fi
(cd "$dist_dir" && zip -q -r "$(basename "$bundle")" "$release_name")
(cd "$dist_dir" && sha256sum "$(basename "$bundle")" > "$(basename "$checksum")")

echo "$bundle"
echo "$checksum"
