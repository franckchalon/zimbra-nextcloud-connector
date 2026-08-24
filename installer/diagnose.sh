#!/usr/bin/env bash
set -euo pipefail

bundle_dir="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=zimlet-cos.sh
source "$bundle_dir/zimlet-cos.sh"
config_path="/opt/zimbra/conf/nextcloud-zimlet.properties"
extension_root="/opt/zimbra/lib/ext"
mailbox_log="/opt/zimbra/log/mailbox.log"
expected_version="3.2.0-beta.7"
problems=0

if [[ "${EUID}" -ne 0 ]]; then
  echo "ERROR root_required"
  exit 1
fi

check() {
  local name="$1" status="$2" detail="$3"
  printf '%-30s %-5s %s\n' "$name" "$status" "$detail"
  [[ "$status" == "OK" ]] || problems=$((problems + 1))
}

echo "zimbra-nextcloud-connector diagnostics"
echo "UTC $(date -u +%Y-%m-%dT%H:%M:%SZ)"

mailbox_status="$(runuser -u zimbra -- /opt/zimbra/bin/zmmailboxdctl status 2>&1 || true)"
if grep -q 'mailboxd is running' <<<"$mailbox_status"; then check mailboxd OK running; else check mailboxd FAIL "$mailbox_status"; fi

ping_response="$(curl -sS --max-time 5 http://127.0.0.1:8080/service/extension/nextcloud-connector/public/ping 2>&1 || true)"
if grep -Fq '"version":"'"$expected_version"'"' <<<"$ping_response"; then check extension_ping OK "$ping_response"; else check extension_ping FAIL "$ping_response"; fi

jar_count="$(find "$extension_root" -maxdepth 3 -type f -name 'com_nextcloud_connector.jar' 2>/dev/null | wc -l)"
if [[ "$jar_count" -eq 1 ]]; then check active_extension_jar OK 1; else check active_extension_jar FAIL "$jar_count copies under $extension_root"; fi

install_mode="modern"
node_role="primary"
if [[ -f "$config_path" ]]; then
  config_mode="$(stat -c '%a' "$config_path")"
  config_owner="$(stat -c '%U:%G' "$config_path")"
  if [[ "$config_mode" == "600" && "$config_owner" == "zimbra:zimbra" ]]; then
    check configuration_permissions OK "$config_owner $config_mode"
  else
    check configuration_permissions FAIL "$config_owner $config_mode (expected zimbra:zimbra 600)"
  fi
  for key in storage.dir zimbra.public_url office.provider office.public_url office.security_mode security.ticket_secret; do
    if grep -q "^${key}=" "$config_path"; then check "config_$key" OK present; else check "config_$key" FAIL missing; fi
  done
  install_mode="$(sed -n 's/^ui\.install_mode=//p' "$config_path" | tail -n 1)"
  case "$install_mode" in modern|classic|both) ;; *) install_mode="modern" ;; esac
  node_role="$(sed -n 's/^deployment\.node_role=//p' "$config_path" | tail -n 1)"
  case "$node_role" in primary|backend-only) ;; *) node_role="primary" ;; esac
  check config_deployment.node_role OK "$node_role"
  if [[ "$node_role" != "backend-only" ]]; then check config_ui.install_mode OK "$install_mode"; fi
else
  check configuration FAIL missing
fi

zimlet_list=""
if [[ "$node_role" != "backend-only" ]]; then
  zimlet_list="$(runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl listZimlets 2>&1 || true)"
fi
if [[ "$node_role" != "backend-only" && ( "$install_mode" == "modern" || "$install_mode" == "both" ) ]]; then
  if grep -Eq '^[[:space:]]*com_nextcloud_connector[[:space:]]*$' <<<"$zimlet_list"; then check modern_cloud_zimlet OK deployed; else check modern_cloud_zimlet FAIL not_deployed; fi
  if grep -Eq '^[[:space:]]*com_nextcloud_connector_chat[[:space:]]*$' <<<"$zimlet_list"; then check modern_chat_zimlet OK deployed; else check modern_chat_zimlet FAIL not_deployed; fi
  if cloud_scan_chat_cos_assignments; then
    if [[ "$CLOUD_ZIMLET_COS_TOTAL" -gt 0 && "$CHAT_ZIMLET_COS_MISSING" -eq 0 ]]; then
      check modern_chat_cos OK "$CHAT_ZIMLET_COS_TOTAL/$CLOUD_ZIMLET_COS_TOTAL Cloud COS assignments"
    else
      check modern_chat_cos FAIL "$CHAT_ZIMLET_COS_MISSING missing of $CLOUD_ZIMLET_COS_TOTAL Cloud COS assignments"
    fi
  else
    check modern_chat_cos FAIL provisioning_query_failed
  fi
  if cloud_scan_chat_account_assignments; then
    if [[ "$CHAT_ZIMLET_ACCOUNT_MISSING" -eq 0 ]]; then
      check modern_chat_accounts OK "$CHAT_ZIMLET_ACCOUNT_TOTAL/$CLOUD_ZIMLET_ACCOUNT_TOTAL Cloud account assignments"
    else
      check modern_chat_accounts FAIL "$CHAT_ZIMLET_ACCOUNT_MISSING missing of $CLOUD_ZIMLET_ACCOUNT_TOTAL Cloud account assignments"
    fi
  else
    check modern_chat_accounts FAIL provisioning_query_failed
  fi
fi

if [[ "$node_role" != "backend-only" && ( "$install_mode" == "classic" || "$install_mode" == "both" ) ]]; then
  if grep -Eq '^[[:space:]]*fr_franckchalon_nextcloud_classic[[:space:]]*$' <<<"$zimlet_list"; then
    check classic_zimlet OK deployed
  else
    check classic_zimlet FAIL not_deployed
  fi
  if [[ "$install_mode" == "both" ]]; then
    if cloud_scan_companion_cos_assignments fr_franckchalon_nextcloud_classic; then
      if [[ "$CLOUD_ZIMLET_COS_TOTAL" -gt 0 && "$CHAT_ZIMLET_COS_MISSING" -eq 0 ]]; then
        check classic_cos OK "$CHAT_ZIMLET_COS_TOTAL/$CLOUD_ZIMLET_COS_TOTAL Cloud COS assignments"
      else
        check classic_cos FAIL "$CHAT_ZIMLET_COS_MISSING missing of $CLOUD_ZIMLET_COS_TOTAL Cloud COS assignments"
      fi
    else
      check classic_cos FAIL provisioning_query_failed
    fi
    if cloud_scan_companion_account_assignments fr_franckchalon_nextcloud_classic; then
      if [[ "$CHAT_ZIMLET_ACCOUNT_MISSING" -eq 0 ]]; then
        check classic_accounts OK "$CHAT_ZIMLET_ACCOUNT_TOTAL/$CLOUD_ZIMLET_ACCOUNT_TOTAL Cloud account assignments"
      else
        check classic_accounts FAIL "$CHAT_ZIMLET_ACCOUNT_MISSING missing of $CLOUD_ZIMLET_ACCOUNT_TOTAL Cloud account assignments"
      fi
    else
      check classic_accounts FAIL provisioning_query_failed
    fi
  fi
fi

profile_root="$(sed -n 's/^storage\.dir=//p' "$config_path" 2>/dev/null | tail -n 1)"
profile_root="${profile_root:-/opt/zimbra/data/nextcloud-zimlet}"
if [[ -d "$profile_root" ]]; then
  profile_size="$(du -sh "$profile_root" 2>/dev/null | awk '{print $1}')"
  check encrypted_profile_storage OK "${profile_size:-0}"
else
  check encrypted_profile_storage FAIL missing
fi

if [[ -r "$mailbox_log" ]]; then
  recent_errors="$(tail -n 5000 "$mailbox_log" | grep -iE 'NextcloudConnector|nextcloud-connector' | grep -iE 'ERROR|SEVERE|Exception' | tail -n 20 || true)"
  if [[ -z "$recent_errors" ]]; then check recent_connector_errors OK none; else check recent_connector_errors WARN "$(wc -l <<<"$recent_errors") recent line(s)"; printf '%s\n' "$recent_errors"; fi
fi

echo
if [[ "$problems" -eq 0 ]]; then echo "RESULT OK"; else echo "RESULT DEGRADED ($problems check(s))"; fi
exit "$problems"
