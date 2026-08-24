#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
cloud_config_path="/opt/zimbra/conf/nextcloud-zimlet.properties"
# shellcheck source=i18n.sh
source "$script_dir/i18n.sh"
cloud_read_language

settings_only="false"
requested_ui_mode=""
for argument in "$@"; do
  case "$argument" in
    --settings-only) settings_only="true" ;;
    --ui=modern|--ui=classic|--ui=both) requested_ui_mode="${argument#--ui=}" ;;
    --help|-h)
      cat <<'EOF'
Usage: sudo ./configure.sh [--settings-only] [--ui=modern|--ui=classic|--ui=both]

Without options, server settings are updated and the script then asks which
Zimbra web interfaces to keep installed. With --ui, only the selected web
interfaces are changed; the backend configuration and mailboxd are untouched.
--settings-only updates only server settings (used internally by install.sh).
EOF
      exit 0
      ;;
    *) printf 'Unknown option: %s\n' "$argument" >&2; exit 2 ;;
  esac
done

if [[ "$settings_only" == "true" && -n "$requested_ui_mode" ]]; then
  echo "--settings-only and --ui cannot be used together." >&2
  exit 2
fi

if [[ "${EUID}" -ne 0 ]]; then
  cloud_msg root_required >&2
  exit 1
fi

config_path="$cloud_config_path"
storage_dir="/opt/zimbra/data/nextcloud-zimlet"

if [[ ! -d /opt/zimbra ]]; then
  cloud_msg zimbra_missing >&2
  exit 1
fi

if [[ -n "$requested_ui_mode" ]]; then
  exec "$script_dir/install.sh" "--ui=$requested_ui_mode" --ui-only
fi

read_property() {
  local key="$1"
  if [[ -f "$config_path" ]]; then
    awk -F= -v wanted="$key" '$1 == wanted { sub(/^[^=]*=/, ""); value=$0 } END { print value }' "$config_path"
  fi
}

# Preserve advanced storage/privacy settings across reconfiguration. They can
# also be adjusted directly in the root-only properties file when deploying a
# shared mailbox cluster or a custom document-template directory.
configured_storage_dir="$(read_property storage.dir || true)"
storage_dir="${configured_storage_dir:-$storage_dir}"
storage_shared="$(read_property storage.shared || true)"
storage_shared="${storage_shared:-false}"
templates_dir="$(read_property templates.dir || true)"
remote_backgrounds="$(read_property ui.remote_backgrounds || true)"
remote_backgrounds="${remote_backgrounds:-false}"
install_mode="$(read_property ui.install_mode || true)"
case "$install_mode" in modern|classic|both) ;; *) install_mode="modern" ;; esac
node_role="$(read_property deployment.node_role || true)"
case "$node_role" in primary|backend-only) ;; *) node_role="primary" ;; esac

ask() {
  local label="$1"
  local default_value="$2"
  local answer
  if [[ -n "$default_value" ]]; then
    read -r -p "$label [$default_value] : " answer
    printf '%s' "${answer:-$default_value}"
  else
    while true; do
      read -r -p "$label : " answer
      [[ -n "$answer" ]] && { printf '%s' "$answer"; return; }
      cloud_msg required_value >&2
    done
  fi
}

validate_https_url() {
  local label="$1"
  local value="$2"
  if [[ ! "$value" =~ ^https://[^/[:space:]]+ ]]; then
    cloud_msgf https_required "$label" >&2; echo >&2
    exit 1
  fi
  if [[ "$value" == *$'\n'* || "$value" == *$'\r'* ]]; then
    cloud_msg invalid_address >&2
    exit 1
  fi
}

extract_host() {
  printf '%s' "$1" | sed -E 's#^https://([^/:]+).*#\1#'
}

case "${CLOUD_INSTALL_LANGUAGE:-}" in
  fr|en-US|es|es-AR|it|de|pt-PT|pt-BR|hi-IN|ms-MY|ru-RU) UI_LANGUAGE="$CLOUD_INSTALL_LANGUAGE"; export UI_LANGUAGE ;;
  *) cloud_select_language ;;
esac
cloud_msg config_title
echo

zimbra_url="$(ask "$(cloud_msg zimbra_url)" "$(read_property zimbra.public_url || true)")"
validate_https_url "Zimbra" "$zimbra_url"

cloud_choose_remote_backgrounds "$remote_backgrounds"
remote_backgrounds="$CLOUD_REMOTE_BACKGROUNDS_SELECTION"

current_account_mode="$(read_property nextcloud.account_mode || true)"
if [[ "$current_account_mode" == "managed" ]]; then account_mode_default="2"; else account_mode_default="1"; fi
cloud_msg account_mode
echo "  1) $(cloud_msg account_personal)"
echo "  2) $(cloud_msg account_managed)"
while true; do
  read -r -p "$(cloud_msg your_choice) [$account_mode_default] : " account_mode_choice
  account_mode_choice="${account_mode_choice:-$account_mode_default}"
  case "$account_mode_choice" in
    1) nextcloud_account_mode="manual"; break ;;
    2) nextcloud_account_mode="managed"; break ;;
    *) cloud_msg choose_1_2 >&2 ;;
  esac
done

managed_nextcloud_url=""
managed_admin_username=""
managed_admin_password=""
managed_group=""
managed_quota=""
managed_language="fr"
if [[ "$nextcloud_account_mode" == "managed" ]]; then
  echo
  managed_nextcloud_url="$(ask "$(cloud_msg managed_url)" "$(read_property managed.nextcloud_url || true)")"
  validate_https_url "Nextcloud" "$managed_nextcloud_url"
  managed_admin_username="$(ask "$(cloud_msg service_account)" "$(read_property managed.admin_username || true)")"
  old_managed_password="$(read_property managed.admin_app_password || true)"
  if [[ -n "$old_managed_password" ]]; then
    read -r -s -p "$(cloud_msg keep_service_password)" managed_admin_password
    echo
    managed_admin_password="${managed_admin_password:-$old_managed_password}"
  else
    while true; do
      read -r -s -p "$(cloud_msg service_password)" managed_admin_password
      echo
      [[ -n "$managed_admin_password" ]] && break
      cloud_msg required_value >&2
    done
  fi
  if [[ "$managed_admin_password" == *$'\n'* || "$managed_admin_password" == *$'\r'* ]]; then
    cloud_msg invalid_password >&2
    exit 1
  fi

  managed_group_default="$(read_property managed.group || true)"
  read -r -p "$(cloud_msg managed_group)${managed_group_default:+ [$managed_group_default]} : " managed_group
  managed_group="${managed_group:-$managed_group_default}"
  managed_quota_default="$(read_property managed.quota || true)"
  read -r -p "$(cloud_msg managed_quota)${managed_quota_default:+ [$managed_quota_default]} : " managed_quota
  managed_quota="${managed_quota:-$managed_quota_default}"
  managed_language_default="$(read_property managed.language || true)"
  case "$UI_LANGUAGE" in
    en-US) managed_language_suggestion="en" ;; es-AR) managed_language_suggestion="es_AR" ;;
    pt-PT) managed_language_suggestion="pt_PT" ;; pt-BR) managed_language_suggestion="pt_BR" ;;
    hi-IN) managed_language_suggestion="hi" ;; ms-MY) managed_language_suggestion="ms" ;; ru-RU) managed_language_suggestion="ru" ;;
    *) managed_language_suggestion="$UI_LANGUAGE" ;;
  esac
  managed_language="$(ask "$(cloud_msg managed_language)" "${managed_language_default:-$managed_language_suggestion}")"
fi

current_provider="$(read_property office.provider || true)"
if [[ -z "$current_provider" ]]; then current_provider="onlyoffice"; fi
if [[ "$current_provider" == "eurooffice" ]]; then provider_default="2"; else provider_default="1"; fi
cloud_msg office_provider
echo "  1) ONLYOFFICE"
echo "  2) Euro-Office"
while true; do
  read -r -p "$(cloud_msg your_choice) [$provider_default] : " provider_choice
  provider_choice="${provider_choice:-$provider_default}"
  case "$provider_choice" in
    1) office_provider="onlyoffice"; office_label="ONLYOFFICE"; break ;;
    2) office_provider="eurooffice"; office_label="Euro-Office"; break ;;
    *) cloud_msg choose_1_2 >&2 ;;
  esac
done

office_url_default="$(read_property office.public_url || true)"
if [[ -z "$office_url_default" ]]; then office_url_default="$(read_property onlyoffice.public_url || true)"; fi
office_url="$(ask "$(cloud_msgf office_url "$office_label")" "$office_url_default")"
validate_https_url "$office_label" "$office_url"

current_security="$(read_property office.security_mode || true)"
if [[ "$current_security" == "none" ]]; then security_default="2"; else security_default="1"; fi
echo
cloud_msgf security_mode "$office_label"; echo
echo "  1) $(cloud_msg jwt_recommended)"
echo "  2) $(cloud_msg no_jwt_test)"
while true; do
  read -r -p "$(cloud_msg your_choice) [$security_default] : " security_choice
  security_choice="${security_choice:-$security_default}"
  case "$security_choice" in
    1) security_mode="jwt"; break ;;
    2) security_mode="none"; break ;;
    *) cloud_msg choose_1_2 >&2 ;;
  esac
done

if [[ "$security_mode" == "jwt" ]]; then
  jwt_header_default="$(read_property office.jwt_header || true)"
  if [[ -z "$jwt_header_default" ]]; then jwt_header_default="$(read_property onlyoffice.jwt_header || true)"; fi
  jwt_header="$(ask "$(cloud_msgf jwt_header "$office_label")" "${jwt_header_default:-Authorization}")"
  if [[ ! "$jwt_header" =~ ^[A-Za-z0-9-]{1,80}$ ]]; then
    cloud_msg invalid_jwt_header >&2
    exit 1
  fi

  old_jwt="$(read_property office.jwt_secret || true)"
  if [[ -z "$old_jwt" ]]; then old_jwt="$(read_property onlyoffice.jwt_secret || true)"; fi
  if [[ -n "$old_jwt" ]]; then
    read -r -s -p "$(cloud_msgf keep_jwt_secret "$office_label")" jwt_secret
    echo
    jwt_secret="${jwt_secret:-$old_jwt}"
  else
    while true; do
      read -r -s -p "$(cloud_msgf jwt_secret "$office_label")" jwt_secret
      echo
      [[ ${#jwt_secret} -ge 32 ]] && break
      cloud_msg jwt_minimum >&2
    done
  fi

  if [[ ${#jwt_secret} -lt 24 ]]; then
    cloud_msg jwt_existing_minimum >&2
    exit 1
  fi
  if [[ "$jwt_secret" == *$'\n'* || "$jwt_secret" == *$'\r'* || "$jwt_secret" =~ [[:space:]] ]]; then
    cloud_msg jwt_no_spaces >&2
    exit 1
  fi
else
  cloud_msgf no_jwt_warning "$office_label" >&2; echo >&2
  jwt_header="Authorization"
  jwt_secret=""
fi

legacy_jwt_secret="$jwt_secret"
if [[ ${#legacy_jwt_secret} -lt 24 ]]; then
  legacy_jwt_secret="$(read_property onlyoffice.jwt_secret || true)"
fi
if [[ ${#legacy_jwt_secret} -lt 24 ]]; then
  legacy_jwt_secret="$(openssl rand -hex 32)"
fi

ticket_secret="$(read_property security.ticket_secret || true)"
if [[ ${#ticket_secret} -lt 32 ]]; then ticket_secret="$(openssl rand -hex 32)"; fi
office_host="$(extract_host "$office_url")"
private_hosts="$(read_property nextcloud.private_hosts || true)"
if [[ "$nextcloud_account_mode" == "managed" ]]; then
  managed_host="$(extract_host "$managed_nextcloud_url")"
  if [[ ",$private_hosts," != *",$managed_host,"* ]]; then
    private_hosts="${private_hosts:+$private_hosts,}$managed_host"
  fi
fi

install -d -o zimbra -g zimbra -m 0700 "$storage_dir"
umask 077
temporary="$(mktemp /opt/zimbra/conf/nextcloud-zimlet.properties.tmp.XXXXXX)"
{
  printf 'storage.dir=%s\n' "$storage_dir"
  printf 'storage.shared=%s\n' "$storage_shared"
  printf 'templates.dir=%s\n' "$templates_dir"
  printf 'ui.remote_backgrounds=%s\n' "$remote_backgrounds"
  printf 'ui.default_language=%s\n' "$UI_LANGUAGE"
  # Installer-owned values must survive a later standalone reconfiguration.
  printf 'ui.install_mode=%s\n' "$install_mode"
  printf 'deployment.node_role=%s\n' "$node_role"
  printf 'zimbra.public_url=%s\n' "${zimbra_url%/}"
  printf 'nextcloud.account_mode=%s\n' "$nextcloud_account_mode"
  printf 'managed.nextcloud_url=%s\n' "${managed_nextcloud_url%/}"
  printf 'managed.admin_username=%s\n' "$managed_admin_username"
  printf 'managed.admin_app_password=%s\n' "$managed_admin_password"
  printf 'managed.group=%s\n' "$managed_group"
  printf 'managed.quota=%s\n' "$managed_quota"
  printf 'managed.language=%s\n' "$managed_language"
  printf 'office.provider=%s\n' "$office_provider"
  printf 'office.public_url=%s\n' "${office_url%/}"
  printf 'office.download_hosts=%s\n' "$office_host"
  printf 'office.allow_http_downloads=false\n'
  printf 'office.security_mode=%s\n' "$security_mode"
  printf 'office.jwt_secret=%s\n' "$jwt_secret"
  printf 'office.jwt_header=%s\n' "$jwt_header"
  # Compatibilité de retour arrière avec les versions antérieures à 2.0.19.
  printf 'onlyoffice.public_url=%s\n' "${office_url%/}"
  printf 'onlyoffice.download_hosts=%s\n' "$office_host"
  printf 'onlyoffice.allow_http_downloads=false\n'
  printf 'onlyoffice.jwt_secret=%s\n' "$legacy_jwt_secret"
  printf 'onlyoffice.jwt_header=%s\n' "$jwt_header"
  printf 'security.ticket_secret=%s\n' "$ticket_secret"
  printf 'security.ticket_ttl_seconds=604800\n'
  printf 'files.max_upload_bytes=1073741824\n'
  printf 'files.upload_chunk_bytes=8388608\n'
  printf 'files.max_office_download_bytes=1073741824\n'
  printf 'files.max_onlyoffice_download_bytes=1073741824\n'
  printf 'http.connect_timeout_seconds=15\n'
  printf 'http.request_timeout_seconds=1800\n'
  printf 'talk.request_timeout_seconds=8\n'
  printf 'nextcloud.allow_http=false\n'
  printf 'nextcloud.block_private_networks=true\n'
  printf 'nextcloud.private_hosts=%s\n' "$private_hosts"
  printf 'limits.max_concurrent_requests=24\n'
  printf 'limits.max_requests_per_account_minute=600\n'
  printf 'limits.max_directory_response_items=5000\n'
  printf 'mail.fallback_max_attachments=20\n'
  printf 'mail.fallback_max_attachment_bytes=104857600\n'
} > "$temporary"

chown zimbra:zimbra "$temporary"
chmod 0600 "$temporary"
mv -f "$temporary" "$config_path"

echo
cloud_msg config_saved
cloud_msgf selected_engine "$office_label" "$security_mode"; echo
if [[ "$nextcloud_account_mode" == "managed" ]]; then
  cloud_msg managed_summary
  cloud_msgf quota_summary "${managed_quota:+ ($managed_quota)}"; echo
else
  cloud_msg personal_summary
fi

if [[ "$settings_only" != "true" ]]; then
  CLOUD_UI_MODE_DEFAULT="$install_mode"
  unset CLOUD_UI_MODE
  cloud_select_ui_mode
  "$script_dir/install.sh" "--ui=$CLOUD_UI_MODE" --ui-only
fi
