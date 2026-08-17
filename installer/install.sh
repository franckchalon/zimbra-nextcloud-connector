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
prebuilt_extension_jar="$bundle_dir/server/com_nextcloud_connector.jar"
source_build_dir="$bundle_dir/server/source-build"
extension_jar="$prebuilt_extension_jar"
config_path="$cloud_config_path"
extension_dir="/opt/zimbra/lib/ext/com_nextcloud_connector"
extension_backup_root="/opt/zimbra/data/nextcloud-zimlet-extension-backups"
expected_version="3.1.23"
backup_dir=""

if [[ ! -x /opt/zimbra/bin/zmzimletctl || ! -x /opt/zimbra/bin/zmmailboxdctl ]]; then
  cloud_msg zimbra_tools_missing >&2
  exit 1
fi
if [[ ! -f "$frontend_zip" || ! -f "$chat_frontend_zip" || ! -f "$prebuilt_extension_jar" ]]; then
  cloud_msg incomplete_package >&2
  exit 1
fi

if [[ ! -f "$config_path" ]] || ! grep -Eq '^ui\.default_language=(fr|en-US|es|es-AR|it|de|pt-PT|pt-BR|hi-IN|ms-MY|ru-RU)$' "$config_path"; then
  cloud_select_language
  CLOUD_INSTALL_LANGUAGE="$UI_LANGUAGE"
  export CLOUD_INSTALL_LANGUAGE
fi

wait_for_extension() {
  local attempt response
  for attempt in $(seq 1 90); do
    if runuser -u zimbra -- /opt/zimbra/bin/zmmailboxdctl status 2>/dev/null | grep -q 'mailboxd is running'; then
      response="$(curl -k -sS --max-time 3 \
        https://127.0.0.1:8443/service/extension/nextcloud-connector/public/ping 2>/dev/null || true)"
      if ! grep -Fq '"version":"'"$expected_version"'"' <<<"$response"; then
        response="$(curl -sS --max-time 3 \
          http://127.0.0.1:8080/service/extension/nextcloud-connector/public/ping 2>/dev/null || true)"
      fi
      if grep -Fq '"version":"'"$expected_version"'"' <<<"$response"; then return 0; fi
    fi
    sleep 2
  done
  return 1
}

quarantine_legacy_extension_copies() {
  local legacy destination
  local -a legacy_directories=(
    /opt/zimbra/lib/ext/com_nextcloud_connector.backup.*
    /opt/zimbra/lib/ext/com_nextcloud_connector.failed.*
    /opt/zimbra/lib/ext/com_nextcloud_connector.disabled.*
  )
  for legacy in "${legacy_directories[@]}"; do
    [[ -d "$legacy" ]] || continue
    destination="$extension_backup_root/$(basename "$legacy")"
    if [[ -e "$destination" ]]; then destination="${destination}.$(date +%Y%m%d%H%M%S).$$"; fi
    cloud_msgf quarantine "$legacy"; echo
    mv "$legacy" "$destination"
  done
}

update_remote_backgrounds_setting() {
  local value="$1" temporary
  temporary="$(mktemp /opt/zimbra/conf/nextcloud-zimlet.properties.tmp.XXXXXX)"
  if ! awk -v value="$value" '
    BEGIN { written=0 }
    /^ui\.remote_backgrounds=/ {
      if (!written) print "ui.remote_backgrounds=" value
      written=1
      next
    }
    { print }
    END { if (!written) print "ui.remote_backgrounds=" value }
  ' "$config_path" > "$temporary"; then
    rm -f -- "$temporary"
    return 1
  fi
  chown zimbra:zimbra "$temporary"
  chmod 0600 "$temporary"
  mv -f -- "$temporary" "$config_path"
}

rollback_extension() {
  cloud_msg rollback >&2
  if [[ -d "$extension_dir" ]]; then
    mv "$extension_dir" "$extension_backup_root/com_nextcloud_connector.failed.$(date +%Y%m%d%H%M%S).$$"
  fi
  if [[ -n "$backup_dir" && -d "$backup_dir" ]]; then mv "$backup_dir" "$extension_dir"; fi
  runuser -u zimbra -- /opt/zimbra/bin/zmmailboxdctl restart || true
}

if [[ -x "$source_build_dir/build-on-zimbra.sh" ]]; then
  cloud_msg compatibility_build
  if ! "$source_build_dir/build-on-zimbra.sh"; then
    cloud_msg build_failed >&2
    exit 1
  fi
  extension_jar="$source_build_dir/build/com_nextcloud_connector.jar"
  if [[ ! -f "$extension_jar" ]]; then
    cloud_msg jar_missing >&2
    exit 1
  fi
fi

if [[ ! -f "$config_path" ]] || ! grep -Eq '^office\.provider=(onlyoffice|eurooffice)$' "$config_path" \
  || ! grep -Eq '^nextcloud\.account_mode=(manual|managed)$' "$config_path" \
  || ! grep -Eq '^ui\.default_language=(fr|en-US|es|es-AR|it|de|pt-PT|pt-BR|hi-IN|ms-MY|ru-RU)$' "$config_path"; then
  cloud_msg server_migration
  "$bundle_dir/configure.sh"
  cloud_read_language
else
  current_remote_backgrounds="$(sed -n 's/^ui\.remote_backgrounds=//p' "$config_path" | tail -n 1)"
  cloud_choose_remote_backgrounds "${current_remote_backgrounds:-false}"
  update_remote_backgrounds_setting "$CLOUD_REMOTE_BACKGROUNDS_SELECTION"
fi

install -d -o zimbra -g zimbra -m 0750 "$extension_backup_root"
quarantine_legacy_extension_copies

if [[ -d "$extension_dir" ]]; then
  backup_dir="$extension_backup_root/com_nextcloud_connector.active.$(date +%Y%m%d%H%M%S).$$"
  mv "$extension_dir" "$backup_dir"
fi
install -d -o zimbra -g zimbra -m 0750 "$extension_dir"
install -o zimbra -g zimbra -m 0640 "$extension_jar" "$extension_dir/com_nextcloud_connector.jar"

cloud_msg loading_extension
runuser -u zimbra -- /opt/zimbra/bin/zmmailboxdctl restart
if ! wait_for_extension; then
  cloud_msg extension_failed >&2
  rollback_extension
  exit 1
fi

cloud_msg deploy_modern
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

if ! runuser -u zimbra -- /opt/zimbra/bin/zmprov fc all; then
  cloud_msg cache_warning >&2
fi

echo
cloud_msg install_done
runuser -u zimbra -- /opt/zimbra/bin/zmmailboxdctl status
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl listZimlets | grep -A2 -B2 com_nextcloud_connector || true
cloud_msg reconnect
