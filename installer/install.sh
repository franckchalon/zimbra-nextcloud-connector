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
classic_frontend_zip="$bundle_dir/frontend/fr_franckchalon_nextcloud_classic.zip"
prebuilt_extension_jar="$bundle_dir/server/com_nextcloud_connector.jar"
source_build_dir="$bundle_dir/server/source-build"
extension_jar="$prebuilt_extension_jar"
config_path="$cloud_config_path"
extension_dir="/opt/zimbra/lib/ext/com_nextcloud_connector"
extension_backup_root="/opt/zimbra/data/nextcloud-zimlet-extension-backups"
expected_version="3.2.0-beta.7"
backup_dir=""
backend_only="false"
ui_only="false"

for argument in "$@"; do
  case "$argument" in
    --ui=modern|--ui=classic|--ui=both) CLOUD_UI_MODE="${argument#--ui=}" ;;
    --backend-only) backend_only="true" ;;
    --ui-only) ui_only="true" ;;
    --help|-h)
      cat <<'EOF'
Usage: sudo ./install.sh [--ui=modern|--ui=classic|--ui=both] [--backend-only] [--ui-only]

Without --ui, the installer asks which web interfaces to deploy.
--backend-only installs/restarts only the mailboxd extension on this node and
does not modify Zimlets in LDAP (for additional mailbox servers).
--ui-only changes only the deployed web interfaces. It does not rebuild or
restart the mailboxd extension and is primarily used by configure.sh.
EOF
      exit 0
      ;;
    *) printf 'Unknown option: %s\n' "$argument" >&2; exit 2 ;;
  esac
done

if [[ "$backend_only" == "true" && "$ui_only" == "true" ]]; then
  echo "--backend-only and --ui-only cannot be used together." >&2
  exit 2
fi

if [[ ! -x /opt/zimbra/bin/zmmailboxdctl ]]; then
  cloud_msg zimbra_tools_missing >&2
  exit 1
fi
if [[ "$backend_only" != "true" && ! -x /opt/zimbra/bin/zmzimletctl ]]; then
  cloud_msg zimbra_tools_missing >&2
  exit 1
fi
if [[ "$ui_only" != "true" && ! -f "$prebuilt_extension_jar" ]]; then
  cloud_msg incomplete_package >&2
  exit 1
fi

if [[ ! -f "$config_path" ]] || ! grep -Eq '^ui\.default_language=(fr|en-US|es|es-AR|it|de|pt-PT|pt-BR|hi-IN|ms-MY|ru-RU)$' "$config_path"; then
  cloud_select_language
  CLOUD_INSTALL_LANGUAGE="$UI_LANGUAGE"
  export CLOUD_INSTALL_LANGUAGE
fi

if [[ "$backend_only" != "true" ]]; then
  cloud_select_ui_mode
  case "$CLOUD_UI_MODE" in
    modern)
      [[ -f "$frontend_zip" && -f "$chat_frontend_zip" ]] || { cloud_msg incomplete_package >&2; exit 1; }
      ;;
    classic)
      [[ -f "$classic_frontend_zip" ]] || { cloud_msg incomplete_package >&2; exit 1; }
      ;;
    both)
      [[ -f "$frontend_zip" && -f "$chat_frontend_zip" && -f "$classic_frontend_zip" ]] || { cloud_msg incomplete_package >&2; exit 1; }
      ;;
  esac
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

update_config_setting() {
  local key="$1" value="$2" temporary
  temporary="$(mktemp /opt/zimbra/conf/nextcloud-zimlet.properties.tmp.XXXXXX)"
  if ! awk -v key="$key" -v value="$value" '
    BEGIN { written=0 }
    index($0, key "=") == 1 {
      if (!written) print key "=" value
      written=1
      next
    }
    { print }
    END { if (!written) print key "=" value }
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

deploy_selected_interfaces() {
  local modern_source_present="false"
  if [[ "$CLOUD_UI_MODE" == "modern" || "$CLOUD_UI_MODE" == "both" ]]; then
    cloud_msg deploy_modern
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl deploy "$frontend_zip"
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl enable com_nextcloud_connector
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl deploy "$chat_frontend_zip"
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl enable com_nextcloud_connector_chat
    if ! cloud_sync_chat_cos_assignments || ! cloud_sync_chat_account_assignments; then
      cloud_msg chat_cos_sync_failed >&2
      return 1
    fi
    cloud_msgf chat_cos_synced "$CHAT_ZIMLET_COS_UPDATED" "$CLOUD_ZIMLET_COS_TOTAL"; echo
    cloud_msgf chat_accounts_synced "$CHAT_ZIMLET_ACCOUNT_UPDATED" "$CLOUD_ZIMLET_ACCOUNT_TOTAL"; echo
  fi

  if [[ "$CLOUD_UI_MODE" == "classic" || "$CLOUD_UI_MODE" == "both" ]]; then
    if runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl listZimlets 2>/dev/null \
      | grep -Eq '^[[:space:]]*com_nextcloud_connector[[:space:]]*$'; then
      modern_source_present="true"
    fi
    echo "Deploying the Classic interface…"
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl deploy "$classic_frontend_zip"
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl enable fr_franckchalon_nextcloud_classic
    if cloud_sync_companion_cos_assignments fr_franckchalon_nextcloud_classic \
      && cloud_sync_companion_account_assignments fr_franckchalon_nextcloud_classic; then
      printf 'Classic assignments synchronized: %s COS and %s explicit account assignment(s) updated.\n' \
        "$CHAT_ZIMLET_COS_UPDATED" "$CHAT_ZIMLET_ACCOUNT_UPDATED"
    elif [[ "$CLOUD_UI_MODE" == "both" || "$modern_source_present" == "true" ]]; then
      echo "Error: the Classic Zimlet could not be assigned everywhere the Modern Cloud Zimlet is assigned." >&2
      return 1
    else
      echo "No existing Modern Cloud assignment set was found; the Classic Zimlet keeps the default COS assignment created by Zimbra."
    fi
  fi

  if [[ "$CLOUD_UI_MODE" == "classic" ]]; then
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl disable com_nextcloud_connector_chat >/dev/null 2>&1 || true
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl undeploy com_nextcloud_connector_chat >/dev/null 2>&1 || true
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl disable com_nextcloud_connector >/dev/null 2>&1 || true
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl undeploy com_nextcloud_connector >/dev/null 2>&1 || true
  elif [[ "$CLOUD_UI_MODE" == "modern" ]]; then
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl disable fr_franckchalon_nextcloud_classic >/dev/null 2>&1 || true
    runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl undeploy fr_franckchalon_nextcloud_classic >/dev/null 2>&1 || true
  fi
}

if [[ "$ui_only" == "true" ]]; then
  if [[ ! -f "$config_path" ]]; then
    echo "Configuration missing: run ./configure.sh before using --ui-only." >&2
    exit 1
  fi
  update_config_setting ui.install_mode "$CLOUD_UI_MODE"
  update_config_setting deployment.node_role primary
  deploy_selected_interfaces
  if ! runuser -u zimbra -- /opt/zimbra/bin/zmprov fc all; then
    cloud_msg cache_warning >&2
  fi
  echo
  echo "Zimbra web interfaces updated: $CLOUD_UI_MODE."
  runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl listZimlets \
    | grep -A2 -B2 -E 'com_nextcloud_connector|fr_franckchalon_nextcloud_classic' || true
  cloud_msg reconnect
  exit 0
fi

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
  "$bundle_dir/configure.sh" --settings-only
  cloud_read_language
elif [[ "$backend_only" != "true" ]]; then
  current_remote_backgrounds="$(sed -n 's/^ui\.remote_backgrounds=//p' "$config_path" | tail -n 1)"
  cloud_choose_remote_backgrounds "${current_remote_backgrounds:-false}"
  update_config_setting ui.remote_backgrounds "$CLOUD_REMOTE_BACKGROUNDS_SELECTION"
fi

if [[ "$backend_only" != "true" ]]; then
  update_config_setting ui.install_mode "$CLOUD_UI_MODE"
  update_config_setting deployment.node_role primary
else
  update_config_setting deployment.node_role backend-only
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

if [[ "$backend_only" != "true" ]]; then
  deploy_selected_interfaces
fi

if ! runuser -u zimbra -- /opt/zimbra/bin/zmprov fc all; then
  cloud_msg cache_warning >&2
fi

echo
cloud_msg install_done
runuser -u zimbra -- /opt/zimbra/bin/zmmailboxdctl status
if [[ "$backend_only" == "true" ]]; then
  echo "Backend-only installation completed on this mailbox server."
else
  runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl listZimlets \
    | grep -A2 -B2 -E 'com_nextcloud_connector|fr_franckchalon_nextcloud_classic' || true
  cloud_msg reconnect
fi
