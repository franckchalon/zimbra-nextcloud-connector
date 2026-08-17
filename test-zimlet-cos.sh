#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=installer/zimlet-cos.sh
source "$project_dir/installer/zimlet-cos.sh"

cloud_attr_has_zimlet $'zimbraZimletAvailableZimlets: +com_nextcloud_connector\n' com_nextcloud_connector
cloud_attr_has_zimlet $'zimbraZimletAvailableZimlets: com_nextcloud_connector\n' com_nextcloud_connector
if cloud_attr_has_zimlet $'zimbraZimletAvailableZimlets: -com_nextcloud_connector\n' com_nextcloud_connector; then
  echo "Disabled Zimlet incorrectly detected as enabled." >&2
  exit 1
fi

mock_root="$(mktemp -d)"
trap 'find "$mock_root" -depth -mindepth 1 -delete; rmdir "$mock_root"' EXIT
mock_log="$mock_root/zmprov.log"
mock_sa_supported=true
mock_gac_verbose_supported=true

cloud_zmprov() {
  case "$1" in
    gac)
      printf '%s\n' "$*" >>"$mock_log"
      if [[ "${2:-}" == -v ]]; then
        [[ "$mock_gac_verbose_supported" == true ]] || return 1
        printf '%s\n' \
          '# name default' \
          'zimbraZimletAvailableZimlets: +com_nextcloud_connector' \
          'zimbraZimletAvailableZimlets: +com_nextcloud_connector_chat' \
          '# name prem2go' \
          'zimbraZimletAvailableZimlets: +com_nextcloud_connector' \
          '# name without_cloud' \
          'zimbraZimletAvailableZimlets: +com_example_other'
      else
        printf '%s\n' default prem2go without_cloud
      fi
      ;;
    gc)
      printf '%s\n' "$*" >>"$mock_log"
      case "$2" in
        default)
          printf '%s\n' \
            'zimbraZimletAvailableZimlets: +com_nextcloud_connector' \
            'zimbraZimletAvailableZimlets: +com_nextcloud_connector_chat'
          ;;
        prem2go)
          printf '%s\n' 'zimbraZimletAvailableZimlets: +com_nextcloud_connector'
          ;;
        without_cloud)
          printf '%s\n' 'zimbraZimletAvailableZimlets: +com_example_other'
          ;;
      esac
      ;;
    mc)
      printf '%s\n' "$*" >>"$mock_log"
      ;;
    sa)
      printf '%s\n' "$*" >>"$mock_log"
      [[ "$mock_sa_supported" == true ]] || return 1
      if [[ "$2" == *"$CHAT_ZIMLET_NAME"* ]]; then
        printf '%s\n' cloud-user@example.test
      else
        printf '%s\n' cloud-user@example.test existing-chat@example.test
      fi
      ;;
    gaa)
      printf '%s\n' "$*" >>"$mock_log"
      printf '%s\n' cloud-user@example.test no-cloud@example.test existing-chat@example.test
      ;;
    ga)
      printf '%s\n' "$*" >>"$mock_log"
      case "$2" in
        cloud-user@example.test)
          printf '%s\n' 'zimbraZimletAvailableZimlets: +com_nextcloud_connector'
          ;;
        no-cloud@example.test)
          printf '%s\n' 'zimbraZimletAvailableZimlets: +com_example_other'
          ;;
        existing-chat@example.test)
          printf '%s\n' \
            'zimbraZimletAvailableZimlets: +com_nextcloud_connector' \
            'zimbraZimletAvailableZimlets: +com_nextcloud_connector_chat'
          ;;
      esac
      ;;
    ma)
      printf '%s\n' "$*" >>"$mock_log"
      ;;
    *) return 1 ;;
  esac
}

cloud_sync_chat_cos_assignments
[[ "$CLOUD_ZIMLET_COS_TOTAL" -eq 2 ]]
[[ "$CHAT_ZIMLET_COS_TOTAL" -eq 2 ]]
[[ "$CHAT_ZIMLET_COS_UPDATED" -eq 1 ]]
grep -Fxq 'mc prem2go +zimbraZimletAvailableZimlets +com_nextcloud_connector_chat' "$mock_log"
[[ "$(grep -c '^gac -v$' "$mock_log")" -eq 1 ]]
if grep -Eq '^gc ' "$mock_log"; then
  echo "Fast COS synchronization unexpectedly queried COSes one by one." >&2
  exit 1
fi
if grep -Fq 'without_cloud' "$mock_log"; then
  echo "Chat was assigned to a COS without Cloud." >&2
  exit 1
fi

: >"$mock_log"
mock_gac_verbose_supported=false
cloud_sync_chat_cos_assignments
[[ "$CLOUD_ZIMLET_COS_TOTAL" -eq 2 ]]
[[ "$CHAT_ZIMLET_COS_TOTAL" -eq 2 ]]
[[ "$CHAT_ZIMLET_COS_UPDATED" -eq 1 ]]
grep -Fxq 'mc prem2go +zimbraZimletAvailableZimlets +com_nextcloud_connector_chat' "$mock_log"
[[ "$(grep -c '^gc ' "$mock_log")" -eq 3 ]]

: >"$mock_log"

cloud_sync_chat_account_assignments
[[ "$CLOUD_ZIMLET_ACCOUNT_TOTAL" -eq 2 ]]
[[ "$CHAT_ZIMLET_ACCOUNT_TOTAL" -eq 2 ]]
[[ "$CHAT_ZIMLET_ACCOUNT_UPDATED" -eq 1 ]]
grep -Fxq 'ma cloud-user@example.test +zimbraZimletAvailableZimlets +com_nextcloud_connector_chat' "$mock_log"
[[ "$(grep -c '^sa ' "$mock_log")" -eq 2 ]]
if grep -Eq '^(gaa|ga) ' "$mock_log"; then
  echo "Fast account synchronization unexpectedly scanned accounts one by one." >&2
  exit 1
fi
if grep -Fq 'ma no-cloud@example.test' "$mock_log"; then
  echo "Chat was assigned to an account without Cloud." >&2
  exit 1
fi

: >"$mock_log"
mock_sa_supported=false
cloud_sync_chat_account_assignments
[[ "$CLOUD_ZIMLET_ACCOUNT_TOTAL" -eq 2 ]]
[[ "$CHAT_ZIMLET_ACCOUNT_TOTAL" -eq 2 ]]
[[ "$CHAT_ZIMLET_ACCOUNT_UPDATED" -eq 1 ]]
grep -Fxq 'ma cloud-user@example.test +zimbraZimletAvailableZimlets +com_nextcloud_connector_chat' "$mock_log"

echo "ZimletCosAssignmentTest: OK"
