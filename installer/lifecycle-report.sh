#!/usr/bin/env bash
set -euo pipefail

config_path="/opt/zimbra/conf/nextcloud-zimlet.properties"
if [[ "${EUID}" -ne 0 ]]; then
  echo "ERROR root_required"
  exit 1
fi

storage_root="$(sed -n 's/^storage\.dir=//p' "$config_path" 2>/dev/null | tail -n 1)"
storage_root="${storage_root:-/opt/zimbra/data/nextcloud-zimlet}"
profiles_dir="$storage_root/profiles"

echo "zimbra-nextcloud-connector profile lifecycle report"
echo "This command is read-only. It never removes profiles or revokes tokens."
[[ -d "$profiles_dir" ]] || { echo "No encrypted profiles directory: $profiles_dir"; exit 0; }

known_hashes_file="$(mktemp)"
trap 'find "$known_hashes_file" -maxdepth 0 -type f -delete' EXIT

while IFS= read -r account; do
  [[ -n "$account" ]] || continue
  account_id="$(runuser -u zimbra -- /opt/zimbra/bin/zmprov ga "$account" zimbraId 2>/dev/null | awk '/^zimbraId:/ {print $2; exit}')"
  [[ -n "$account_id" ]] || continue
  printf '%s %s\n' "$(printf '%s' "$account_id" | sha256sum | awk '{print $1}')" "$account" >> "$known_hashes_file"
done < <(runuser -u zimbra -- /opt/zimbra/bin/zmprov -l gaa 2>/dev/null)

active=0
orphaned=0
while IFS= read -r profile; do
  filename="$(basename "$profile")"
  digest="${filename%.enc}"
  account="$(awk -v digest="$digest" '$1 == digest {print $2; exit}' "$known_hashes_file")"
  if [[ -n "$account" ]]; then
    printf 'ACTIVE   %-64s %s\n' "$digest" "$account"
    active=$((active + 1))
  else
    printf 'ORPHAN?  %-64s review manually\n' "$digest"
    orphaned=$((orphaned + 1))
  fi
done < <(find "$profiles_dir" -maxdepth 1 -type f -name '*.enc' -print | sort)

echo "Active profiles: $active"
echo "Profiles to review: $orphaned"
echo "No file was changed. Account aliases/restores can produce false positives; review before any manual removal."
