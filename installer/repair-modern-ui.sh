#!/usr/bin/env bash
set -euo pipefail

bundle_dir="$(cd "$(dirname "$0")" && pwd)"
cloud_config_path="/opt/zimbra/conf/nextcloud-zimlet.properties"
# shellcheck source=i18n.sh
source "$bundle_dir/i18n.sh"
# shellcheck source=zimlet-cos.sh
source "$bundle_dir/zimlet-cos.sh"
cloud_read_language

if [[ "${EUID}" -ne 0 ]]; then
  cloud_msg root_required >&2
  exit 1
fi

frontend_zip="$bundle_dir/frontend/com_nextcloud_connector.zip"
chat_frontend_zip="$bundle_dir/frontend/com_nextcloud_connector_chat.zip"

if [[ ! -x /opt/zimbra/bin/zmzimletctl || ! -f "$frontend_zip" || ! -f "$chat_frontend_zip" ]]; then
  cloud_msg ui_tools_missing >&2
  exit 1
fi

cloud_msg remove_modern
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl disable com_nextcloud_connector_chat 2>/dev/null || true
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl undeploy com_nextcloud_connector_chat 2>/dev/null || true
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl disable com_nextcloud_connector 2>/dev/null || true
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl undeploy com_nextcloud_connector 2>/dev/null || true

cloud_msg clean_modern
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl deploy "$frontend_zip"
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl enable com_nextcloud_connector
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl deploy "$chat_frontend_zip"
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl enable com_nextcloud_connector_chat
if ! cloud_sync_chat_cos_assignments || ! cloud_sync_chat_account_assignments; then
  cloud_msg chat_cos_sync_failed >&2
  exit 1
fi
cloud_msgf chat_cos_synced "$CHAT_ZIMLET_COS_UPDATED" "$CLOUD_ZIMLET_COS_TOTAL"; echo
cloud_msgf chat_accounts_synced "$CHAT_ZIMLET_ACCOUNT_UPDATED" "$CLOUD_ZIMLET_ACCOUNT_TOTAL"; echo

if ! runuser -u zimbra -- /opt/zimbra/bin/zmprov fc zimlet; then
  cloud_msg zimlet_cache_warning >&2
fi

echo
cloud_msg ui_repaired
cloud_msg private_window
