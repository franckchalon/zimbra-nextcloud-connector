#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
installer="$project_dir/installer/install.sh"
configure="$project_dir/installer/configure.sh"
uninstaller="$project_dir/installer/uninstall.sh"
storage_report="$project_dir/installer/storage-report.sh"
i18n="$project_dir/installer/i18n.sh"
repair="$project_dir/installer/repair-modern-ui.sh"
diagnose="$project_dir/installer/diagnose.sh"
lifecycle_report="$project_dir/installer/lifecycle-report.sh"
zimlet_cos="$project_dir/installer/zimlet-cos.sh"
version="$(node -p "require('$project_dir/package.json').version")"

bash -n "$installer" "$configure" "$uninstaller" "$storage_report" "$repair" "$diagnose" "$lifecycle_report" "$i18n" "$zimlet_cos"
grep -Fq "expected_version=\"$version\"" "$installer"
grep -Fq 'extension_backup_root="/opt/zimbra/data/nextcloud-zimlet-extension-backups"' "$installer"
grep -Fq 'quarantine_legacy_extension_copies' "$installer"
grep -Fq 'chat_frontend_zip="$bundle_dir/frontend/com_nextcloud_connector_chat.zip"' "$installer"
grep -Fq 'zmzimletctl enable com_nextcloud_connector_chat' "$installer"
grep -Fq 'zmzimletctl undeploy com_nextcloud_connector_chat' "$uninstaller"
grep -Fq 'zmzimletctl enable com_nextcloud_connector_chat' "$repair"
grep -Fq 'modern_chat_zimlet' "$diagnose"
grep -Fq 'cloud_sync_chat_cos_assignments' "$installer"
grep -Fq 'cloud_sync_chat_account_assignments' "$installer"
grep -Fq 'cloud_sync_chat_cos_assignments' "$repair"
grep -Fq 'cloud_sync_chat_account_assignments' "$repair"
grep -Fq 'modern_chat_cos' "$diagnose"
grep -Fq 'modern_chat_accounts' "$diagnose"
grep -Fq 'zmprov -l "$@"' "$zimlet_cos"
grep -Fq '"version":"' "$installer"
grep -Fq '^office\.provider=(onlyoffice|eurooffice)$' "$installer"
grep -Fq '^nextcloud\.account_mode=(manual|managed)$' "$installer"
grep -Fq '^ui\.default_language=(fr|en-US|es|es-AR|it|de|pt-PT|pt-BR|hi-IN|ms-MY|ru-RU)$' "$installer"
grep -Fq 'nextcloud_account_mode="manual"' "$configure"
grep -Fq 'nextcloud_account_mode="managed"' "$configure"
grep -Fq "printf 'nextcloud.account_mode=%s\\n'" "$configure"
grep -Fq "printf 'managed.nextcloud_url=%s\\n'" "$configure"
grep -Fq "printf 'managed.admin_app_password=%s\\n'" "$configure"
grep -Fq "printf 'ui.default_language=%s\\n'" "$configure"
grep -Fq "printf 'ui.remote_backgrounds=%s\\n'" "$configure"
grep -Fq 'cloud_choose_remote_backgrounds "$remote_backgrounds"' "$configure"
grep -Fq 'cloud_choose_remote_backgrounds "${current_remote_backgrounds:-false}"' "$installer"
grep -Fq 'update_remote_backgrounds_setting "$CLOUD_REMOTE_BACKGROUNDS_SELECTION"' "$installer"
grep -Fq 'CLOUD_UNSPLASH=true|false' "$i18n"
grep -Fq 'cloud_select_language' "$configure"
grep -Fq 'account_personal)' "$i18n"
grep -Fq 'account_managed)' "$i18n"
grep -Fq 'office_provider="onlyoffice"' "$configure"
grep -Fq 'office_provider="eurooffice"' "$configure"
grep -Fq "printf 'office.provider=%s\\n'" "$configure"
grep -Fq "printf 'office.security_mode=%s\\n'" "$configure"
grep -Fq 'jwt_recommended)' "$i18n"
grep -Fq "expected_version=\"$version\"" "$diagnose"
grep -Fq 'This command is read-only' "$lifecycle_report"
grep -Fq "printf 'files.upload_chunk_bytes=8388608\\n'" "$configure"
grep -Fq "printf 'limits.max_concurrent_requests=24\\n'" "$configure"

mapfile -t message_keys < <(awk '
  /^cloud_msg\(\)/ { active=1; next }
  active && /^    \*\)/ { exit }
  active && /^    [a-z0-9_]+\)/ { line=$0; sub(/^    /, "", line); sub(/\).*/, "", line); print line }
' "$i18n")
for language in fr en-US es es-AR it de pt-PT pt-BR hi-IN ms-MY ru-RU; do
  (
    UI_LANGUAGE="$language"
    source "$i18n"
    for key in "${message_keys[@]}"; do
      value="$(cloud_msg "$key")"
      [[ -n "$value" && "$value" != "$key" ]] || {
        echo "Missing installer translation: $language/$key" >&2
        exit 1
      }
    done
  )
done

(
  UI_LANGUAGE=fr
  CLOUD_UNSPLASH=true
  source "$i18n"
  cloud_choose_remote_backgrounds false
  [[ "$CLOUD_REMOTE_BACKGROUNDS_SELECTION" == "true" ]]
)
(
  UI_LANGUAGE=fr
  CLOUD_UNSPLASH=false
  source "$i18n"
  cloud_choose_remote_backgrounds true
  [[ "$CLOUD_REMOTE_BACKGROUNDS_SELECTION" == "false" ]]
)
(
  UI_LANGUAGE=fr
  unset CLOUD_UNSPLASH
  source "$i18n"
  cloud_choose_remote_backgrounds true <<< $' \r\n'
  [[ "$CLOUD_REMOTE_BACKGROUNDS_SELECTION" == "true" ]]
)
(
  UI_LANGUAGE=fr
  unset CLOUD_UNSPLASH
  source "$i18n"
  cloud_choose_remote_backgrounds true <<< $' 2\r\n'
  [[ "$CLOUD_REMOTE_BACKGROUNDS_SELECTION" == "false" ]]
)

if grep -Eq 'mv .*\/opt\/zimbra\/lib\/ext\/com_nextcloud_connector\.(backup|failed|disabled)' "$installer" "$uninstaller"; then
  echo "Erreur : une sauvegarde de module pourrait rester dans le chemin actif de Zimbra." >&2
  exit 1
fi

bash "$project_dir/test-zimlet-cos.sh"

echo "InstallerSafetyTest: OK (sauvegardes hors /opt/zimbra/lib/ext, version $version exigée)"
