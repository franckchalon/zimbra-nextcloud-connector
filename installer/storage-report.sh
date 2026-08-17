#!/usr/bin/env bash
set -euo pipefail

bundle_dir="$(cd "$(dirname "$0")" && pwd)"
cloud_config_path="/opt/zimbra/conf/nextcloud-zimlet.properties"
# shellcheck source=i18n.sh
source "$bundle_dir/i18n.sh"
cloud_read_language

config_path="/opt/zimbra/conf/nextcloud-zimlet.properties"
default_storage="/opt/zimbra/data/nextcloud-zimlet"
backup_root="/opt/zimbra/data/nextcloud-zimlet-extension-backups"
account="${1:-}"

if [[ "${EUID}" -ne 0 ]]; then
  cloud_msg report_root >&2
  exit 1
fi

storage_dir="$default_storage"
if [[ -r "$config_path" ]]; then
  configured_storage="$(sed -n 's/^storage\.dir=//p' "$config_path" | tail -n 1)"
  if [[ -n "$configured_storage" && "$configured_storage" == /* ]]; then storage_dir="$configured_storage"; fi
fi

human_size() {
  local target="$1"
  if [[ -e "$target" ]]; then du -sh "$target" 2>/dev/null | awk '{print $1}'; else echo "0"; fi
}

file_size() {
  local target="$1"
  if [[ -f "$target" ]]; then stat -c '%s' "$target"; else echo "0"; fi
}

cloud_msg storage_title
cloud_msgf encrypted_profiles "$(human_size "$storage_dir")" "$storage_dir"; echo
cloud_msgf temporary_files "$(human_size "$storage_dir/tmp")"; echo
cloud_msgf module_backups "$(human_size "$backup_root")" "$backup_root"; echo

if [[ -n "$account" ]]; then
  if [[ ! "$account" =~ ^[A-Za-z0-9._%+@-]+$ ]]; then
    cloud_msg invalid_account >&2
    exit 1
  fi
  zimbra_id="$(runuser -u zimbra -- /opt/zimbra/bin/zmprov ga "$account" zimbraId 2>/dev/null | awk '$1 == "zimbraId:" {print $2; exit}')"
  if [[ -z "$zimbra_id" ]]; then
    cloud_msgf account_missing "$account" >&2; echo >&2
    exit 1
  fi
  profile_hash="$(printf '%s' "$zimbra_id" | sha256sum | awk '{print $1}')"
  profile_file="$storage_dir/profiles/$profile_hash.enc"
  cloud_msgf profile_size "$account" "$(file_size "$profile_file")"; echo
  cloud_msg profile_contents
else
  profile_count="0"
  if [[ -d "$storage_dir/profiles" ]]; then
    profile_count="$(find "$storage_dir/profiles" -maxdepth 1 -type f -name '*.enc' | wc -l)"
  fi
  cloud_msgf profile_count "$profile_count"; echo
  cloud_msg report_account_help
fi

cloud_msg no_cloud_cache
cloud_msg draft_quota
