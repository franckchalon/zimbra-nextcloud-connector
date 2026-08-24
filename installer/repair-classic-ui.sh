#!/usr/bin/env bash
set -euo pipefail

bundle_dir="$(cd "$(dirname "$0")" && pwd)"
classic_zip="$bundle_dir/frontend/fr_franckchalon_nextcloud_classic.zip"
# shellcheck source=zimlet-cos.sh
source "$bundle_dir/zimlet-cos.sh"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script with sudo or as root." >&2
  exit 1
fi
if [[ ! -x /opt/zimbra/bin/zmzimletctl || ! -f "$classic_zip" ]]; then
  echo "Zimbra tools or the Classic UI ZIP are missing." >&2
  exit 1
fi

runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl disable fr_franckchalon_nextcloud_classic >/dev/null 2>&1 || true
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl undeploy fr_franckchalon_nextcloud_classic >/dev/null 2>&1 || true
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl deploy "$classic_zip"
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl enable fr_franckchalon_nextcloud_classic
modern_source_present="false"
if runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl listZimlets 2>/dev/null \
  | grep -Eq '^[[:space:]]*com_nextcloud_connector[[:space:]]*$'; then
  modern_source_present="true"
fi
if cloud_sync_companion_cos_assignments fr_franckchalon_nextcloud_classic \
  && cloud_sync_companion_account_assignments fr_franckchalon_nextcloud_classic; then
  printf 'Classic assignments synchronized: %s COS and %s explicit account assignment(s) updated.\n' \
    "$CHAT_ZIMLET_COS_UPDATED" "$CHAT_ZIMLET_ACCOUNT_UPDATED"
elif [[ "$modern_source_present" == "true" ]]; then
  echo "Error: the Classic Zimlet could not be assigned everywhere the Modern Cloud Zimlet is assigned." >&2
  exit 1
else
  echo "No Modern Cloud assignment set was found; the Classic Zimlet keeps the default COS assignment created by Zimbra."
fi
runuser -u zimbra -- /opt/zimbra/bin/zmprov fc all || true

echo "Classic UI 3.2.0-beta.7 redeployed. Close every Zimbra tab, sign in to Classic UI again and refresh the browser cache."
