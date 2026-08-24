#!/usr/bin/env bash

# Keep the optional Chat navigation Zimlet available on every COS that already
# exposes the main Cloud Zimlet. Zimbra only grants a newly deployed Zimlet to
# the default COS, so a second package otherwise remains invisible to users on
# custom COSes.

CLOUD_ZIMLET_NAME="com_nextcloud_connector"
CHAT_ZIMLET_NAME="com_nextcloud_connector_chat"

cloud_zmprov() {
  # Read and write LDAP locally. The SOAP-backed default path can fail on
  # system accounts or a proxy-only endpoint and used to abort the whole
  # installation with provisioning_query_failed.
  runuser -u zimbra -- /opt/zimbra/bin/zmprov -l "$@"
}

cloud_attr_has_zimlet() {
  local attributes="$1" zimlet_name="$2"
  awk -v zimlet_name="$zimlet_name" '
    $1 == "zimbraZimletAvailableZimlets:" {
      value=$2
      sub(/^\+/, "", value)
      if (value == zimlet_name) found=1
    }
    END { exit found ? 0 : 1 }
  ' <<<"$attributes"
}

cloud_count_nonempty_lines() {
  awk 'NF { count++ } END { print count + 0 }'
}

cloud_account_has_zimlet_filter() {
  local zimlet_name="$1"
  printf '(|(zimbraZimletAvailableZimlets=+%s)(zimbraZimletAvailableZimlets=%s))' \
    "$zimlet_name" "$zimlet_name"
}

cloud_account_lacks_zimlet_filter() {
  local zimlet_name="$1"
  printf '(&(!(zimbraZimletAvailableZimlets=+%s))(!(zimbraZimletAvailableZimlets=%s)))' \
    "$zimlet_name" "$zimlet_name"
}

cloud_search_account_assignments() {
  # Search only explicit account-level assignments in two LDAP operations.
  # Accounts inheriting Cloud and Chat from their COS need no account write.
  # This avoids starting one zmprov JVM per mailbox during every upgrade.
  local cloud_filter chat_missing_filter all_cloud_accounts missing_chat_accounts
  cloud_filter="$(cloud_account_has_zimlet_filter "$CLOUD_ZIMLET_NAME")"
  chat_missing_filter="$(cloud_account_lacks_zimlet_filter "$CHAT_ZIMLET_NAME")"

  if ! all_cloud_accounts="$(cloud_zmprov sa "$cloud_filter" 2>/dev/null)"; then return 1; fi
  if ! missing_chat_accounts="$(cloud_zmprov sa "(&$cloud_filter$chat_missing_filter)" 2>/dev/null)"; then return 1; fi

  CLOUD_ZIMLET_ACCOUNT_TOTAL="$(cloud_count_nonempty_lines <<<"$all_cloud_accounts")"
  CHAT_ZIMLET_ACCOUNT_MISSING="$(cloud_count_nonempty_lines <<<"$missing_chat_accounts")"
  CHAT_ZIMLET_ACCOUNT_TOTAL=$((CLOUD_ZIMLET_ACCOUNT_TOTAL - CHAT_ZIMLET_ACCOUNT_MISSING))
  CLOUD_ZIMLET_ACCOUNT_NAMES="$all_cloud_accounts"
  CHAT_ZIMLET_ACCOUNT_MISSING_NAMES="$missing_chat_accounts"
}

cloud_scan_chat_account_assignments_legacy() {
  local all_accounts account attributes
  CLOUD_ZIMLET_ACCOUNT_TOTAL=0
  CHAT_ZIMLET_ACCOUNT_TOTAL=0
  CHAT_ZIMLET_ACCOUNT_MISSING=0

  if ! all_accounts="$(cloud_zmprov gaa 2>/dev/null)"; then return 1; fi
  while IFS= read -r account; do
    [[ -n "$account" ]] || continue
    if ! attributes="$(cloud_zmprov ga "$account" zimbraZimletAvailableZimlets 2>/dev/null)"; then
      return 1
    fi
    if cloud_attr_has_zimlet "$attributes" "$CLOUD_ZIMLET_NAME"; then
      CLOUD_ZIMLET_ACCOUNT_TOTAL=$((CLOUD_ZIMLET_ACCOUNT_TOTAL + 1))
      if cloud_attr_has_zimlet "$attributes" "$CHAT_ZIMLET_NAME"; then
        CHAT_ZIMLET_ACCOUNT_TOTAL=$((CHAT_ZIMLET_ACCOUNT_TOTAL + 1))
      else
        CHAT_ZIMLET_ACCOUNT_MISSING=$((CHAT_ZIMLET_ACCOUNT_MISSING + 1))
      fi
    fi
  done <<<"$all_accounts"
}

cloud_parse_cos_assignments() {
  awk -v cloud_name="$CLOUD_ZIMLET_NAME" -v chat_name="$CHAT_ZIMLET_NAME" '
    function emit() {
      if (cos_name != "" && cloud_enabled) print cos_name "\t" (chat_enabled ? 1 : 0)
    }
    /^# name / {
      emit()
      cos_name=$3
      cloud_enabled=0
      chat_enabled=0
      next
    }
    $1 == "zimbraZimletAvailableZimlets:" {
      value=$2
      sub(/^\+/, "", value)
      if (value == cloud_name) cloud_enabled=1
      if (value == chat_name) chat_enabled=1
    }
    END { emit() }
  '
}

cloud_search_cos_assignments() {
  # `gac -v` returns every COS and its attributes in one JVM invocation.
  # Keep a format check so an older/custom zmprov transparently uses the
  # established per-COS fallback instead of silently returning empty totals.
  local verbose_cos rows cos has_chat
  CLOUD_ZIMLET_COS_TOTAL=0
  CHAT_ZIMLET_COS_TOTAL=0
  CHAT_ZIMLET_COS_MISSING=0
  CHAT_ZIMLET_COS_MISSING_NAMES=""

  if ! verbose_cos="$(cloud_zmprov gac -v 2>/dev/null)"; then return 1; fi
  if ! grep -Eq '^# name[[:space:]]+' <<<"$verbose_cos"; then return 1; fi
  rows="$(cloud_parse_cos_assignments <<<"$verbose_cos")"
  while IFS=$'\t' read -r cos has_chat; do
    [[ -n "$cos" ]] || continue
    CLOUD_ZIMLET_COS_TOTAL=$((CLOUD_ZIMLET_COS_TOTAL + 1))
    if [[ "$has_chat" == 1 ]]; then
      CHAT_ZIMLET_COS_TOTAL=$((CHAT_ZIMLET_COS_TOTAL + 1))
    else
      CHAT_ZIMLET_COS_MISSING=$((CHAT_ZIMLET_COS_MISSING + 1))
      CHAT_ZIMLET_COS_MISSING_NAMES+="${CHAT_ZIMLET_COS_MISSING_NAMES:+$'\n'}$cos"
    fi
  done <<<"$rows"
}

cloud_scan_chat_cos_assignments() {
  local all_cos cos attributes
  CLOUD_ZIMLET_COS_TOTAL=0
  CHAT_ZIMLET_COS_TOTAL=0
  CHAT_ZIMLET_COS_MISSING=0

  if ! all_cos="$(cloud_zmprov gac 2>/dev/null)"; then return 1; fi
  while IFS= read -r cos; do
    [[ -n "$cos" ]] || continue
    if ! attributes="$(cloud_zmprov gc "$cos" zimbraZimletAvailableZimlets 2>/dev/null)"; then
      return 1
    fi
    if cloud_attr_has_zimlet "$attributes" "$CLOUD_ZIMLET_NAME"; then
      CLOUD_ZIMLET_COS_TOTAL=$((CLOUD_ZIMLET_COS_TOTAL + 1))
      if cloud_attr_has_zimlet "$attributes" "$CHAT_ZIMLET_NAME"; then
        CHAT_ZIMLET_COS_TOTAL=$((CHAT_ZIMLET_COS_TOTAL + 1))
      else
        CHAT_ZIMLET_COS_MISSING=$((CHAT_ZIMLET_COS_MISSING + 1))
      fi
    fi
  done <<<"$all_cos"
}

cloud_sync_chat_cos_assignments() {
  local all_cos cos attributes
  CHAT_ZIMLET_COS_UPDATED=0

  if cloud_search_cos_assignments; then
    while IFS= read -r cos; do
      [[ -n "$cos" ]] || continue
      if ! cloud_zmprov mc "$cos" +zimbraZimletAvailableZimlets "+$CHAT_ZIMLET_NAME" >/dev/null; then
        return 1
      fi
      CHAT_ZIMLET_COS_TOTAL=$((CHAT_ZIMLET_COS_TOTAL + 1))
      CHAT_ZIMLET_COS_UPDATED=$((CHAT_ZIMLET_COS_UPDATED + 1))
    done <<<"$CHAT_ZIMLET_COS_MISSING_NAMES"
    [[ "$CLOUD_ZIMLET_COS_TOTAL" -gt 0 && "$CHAT_ZIMLET_COS_TOTAL" -eq "$CLOUD_ZIMLET_COS_TOTAL" ]]
    return
  fi

  CLOUD_ZIMLET_COS_TOTAL=0
  CHAT_ZIMLET_COS_TOTAL=0

  if ! all_cos="$(cloud_zmprov gac 2>/dev/null)"; then return 1; fi
  while IFS= read -r cos; do
    [[ -n "$cos" ]] || continue
    if ! attributes="$(cloud_zmprov gc "$cos" zimbraZimletAvailableZimlets 2>/dev/null)"; then
      return 1
    fi
    if ! cloud_attr_has_zimlet "$attributes" "$CLOUD_ZIMLET_NAME"; then continue; fi

    CLOUD_ZIMLET_COS_TOTAL=$((CLOUD_ZIMLET_COS_TOTAL + 1))
    if cloud_attr_has_zimlet "$attributes" "$CHAT_ZIMLET_NAME"; then
      CHAT_ZIMLET_COS_TOTAL=$((CHAT_ZIMLET_COS_TOTAL + 1))
      continue
    fi
    if ! cloud_zmprov mc "$cos" +zimbraZimletAvailableZimlets "+$CHAT_ZIMLET_NAME" >/dev/null; then
      return 1
    fi
    CHAT_ZIMLET_COS_TOTAL=$((CHAT_ZIMLET_COS_TOTAL + 1))
    CHAT_ZIMLET_COS_UPDATED=$((CHAT_ZIMLET_COS_UPDATED + 1))
  done <<<"$all_cos"

  [[ "$CLOUD_ZIMLET_COS_TOTAL" -gt 0 && "$CHAT_ZIMLET_COS_TOTAL" -eq "$CLOUD_ZIMLET_COS_TOTAL" ]]
}

cloud_scan_chat_account_assignments() {
  # Diagnostics keep the exhaustive effective-value scan so their totals also
  # include accounts inheriting the Zimlets from a COS. Installation uses the
  # fast explicit-assignment search below.
  cloud_scan_chat_account_assignments_legacy
}

cloud_sync_chat_account_assignments() {
  local account
  CHAT_ZIMLET_ACCOUNT_UPDATED=0

  if ! cloud_search_account_assignments; then
    # Compatibility fallback for old or customized zmprov installations.
    local all_accounts attributes
    CLOUD_ZIMLET_ACCOUNT_TOTAL=0
    CHAT_ZIMLET_ACCOUNT_TOTAL=0
    if ! all_accounts="$(cloud_zmprov gaa 2>/dev/null)"; then return 1; fi
    while IFS= read -r account; do
      [[ -n "$account" ]] || continue
      if ! attributes="$(cloud_zmprov ga "$account" zimbraZimletAvailableZimlets 2>/dev/null)"; then
        return 1
      fi
      if ! cloud_attr_has_zimlet "$attributes" "$CLOUD_ZIMLET_NAME"; then continue; fi

      CLOUD_ZIMLET_ACCOUNT_TOTAL=$((CLOUD_ZIMLET_ACCOUNT_TOTAL + 1))
      if cloud_attr_has_zimlet "$attributes" "$CHAT_ZIMLET_NAME"; then
        CHAT_ZIMLET_ACCOUNT_TOTAL=$((CHAT_ZIMLET_ACCOUNT_TOTAL + 1))
        continue
      fi
      if ! cloud_zmprov ma "$account" +zimbraZimletAvailableZimlets "+$CHAT_ZIMLET_NAME" >/dev/null; then
        return 1
      fi
      CHAT_ZIMLET_ACCOUNT_TOTAL=$((CHAT_ZIMLET_ACCOUNT_TOTAL + 1))
      CHAT_ZIMLET_ACCOUNT_UPDATED=$((CHAT_ZIMLET_ACCOUNT_UPDATED + 1))
    done <<<"$all_accounts"
    [[ "$CHAT_ZIMLET_ACCOUNT_TOTAL" -eq "$CLOUD_ZIMLET_ACCOUNT_TOTAL" ]]
    return
  fi

  while IFS= read -r account; do
    [[ -n "$account" ]] || continue
    if ! cloud_zmprov ma "$account" +zimbraZimletAvailableZimlets "+$CHAT_ZIMLET_NAME" >/dev/null; then
      return 1
    fi
    CHAT_ZIMLET_ACCOUNT_TOTAL=$((CHAT_ZIMLET_ACCOUNT_TOTAL + 1))
    CHAT_ZIMLET_ACCOUNT_UPDATED=$((CHAT_ZIMLET_ACCOUNT_UPDATED + 1))
  done <<<"$CHAT_ZIMLET_ACCOUNT_MISSING_NAMES"

  [[ "$CHAT_ZIMLET_ACCOUNT_TOTAL" -eq "$CLOUD_ZIMLET_ACCOUNT_TOTAL" ]]
}

# Reuse the optimized Cloud-assignment mirror for another companion package,
# such as the Classic UI shell. The established Chat globals are restored so
# diagnostics and repair scripts keep their original semantics.
cloud_sync_companion_cos_assignments() {
  local companion="$1" previous="$CHAT_ZIMLET_NAME" result=0
  CHAT_ZIMLET_NAME="$companion"
  cloud_sync_chat_cos_assignments || result=$?
  CHAT_ZIMLET_NAME="$previous"
  return "$result"
}

cloud_sync_companion_account_assignments() {
  local companion="$1" previous="$CHAT_ZIMLET_NAME" result=0
  CHAT_ZIMLET_NAME="$companion"
  cloud_sync_chat_account_assignments || result=$?
  CHAT_ZIMLET_NAME="$previous"
  return "$result"
}

cloud_scan_companion_cos_assignments() {
  local companion="$1" previous="$CHAT_ZIMLET_NAME" result=0
  CHAT_ZIMLET_NAME="$companion"
  cloud_scan_chat_cos_assignments || result=$?
  CHAT_ZIMLET_NAME="$previous"
  return "$result"
}

cloud_scan_companion_account_assignments() {
  local companion="$1" previous="$CHAT_ZIMLET_NAME" result=0
  CHAT_ZIMLET_NAME="$companion"
  cloud_scan_chat_account_assignments || result=$?
  CHAT_ZIMLET_NAME="$previous"
  return "$result"
}
