#!/usr/bin/env bash
set -euo pipefail

bundle_dir="$(cd "$(dirname "$0")" && pwd)"
cloud_config_path="/opt/zimbra/conf/nextcloud-zimlet.properties"
# shellcheck source=i18n.sh
source "$bundle_dir/i18n.sh"
cloud_read_language

if [[ "${EUID}" -ne 0 ]]; then
  cloud_msg root_required >&2
  exit 1
fi

purge="false"
if [[ "${1:-}" == "--purge" ]]; then purge="true"; fi

runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl disable fr_franckchalon_nextcloud_classic || true
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl undeploy fr_franckchalon_nextcloud_classic || true
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl disable com_nextcloud_connector_chat || true
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl undeploy com_nextcloud_connector_chat || true
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl disable com_nextcloud_connector || true
runuser -u zimbra -- /opt/zimbra/bin/zmzimletctl undeploy com_nextcloud_connector || true

extension_backup_root="/opt/zimbra/data/nextcloud-zimlet-extension-backups"
install -d -o zimbra -g zimbra -m 0750 "$extension_backup_root"
if [[ -d /opt/zimbra/lib/ext/com_nextcloud_connector ]]; then
  mv /opt/zimbra/lib/ext/com_nextcloud_connector \
    "$extension_backup_root/com_nextcloud_connector.disabled.$(date +%Y%m%d%H%M%S).$$"
fi

if [[ "$purge" == "true" ]]; then
  if [[ -f /opt/zimbra/conf/nextcloud-zimlet.properties ]]; then
    mv /opt/zimbra/conf/nextcloud-zimlet.properties \
      "/opt/zimbra/conf/nextcloud-zimlet.properties.deleted.$(date +%Y%m%d%H%M%S)"
  fi
  if [[ -d /opt/zimbra/data/nextcloud-zimlet ]]; then
    mv /opt/zimbra/data/nextcloud-zimlet \
      "/opt/zimbra/data/nextcloud-zimlet.deleted.$(date +%Y%m%d%H%M%S)"
  fi
else
  cloud_msg data_kept
fi

runuser -u zimbra -- /opt/zimbra/bin/zmmailboxdctl restart
sleep 15
runuser -u zimbra -- /opt/zimbra/bin/zmmailboxdctl status
if ! runuser -u zimbra -- /opt/zimbra/bin/zmprov fc all; then
  cloud_msg uninstall_cache_warning >&2
fi
cloud_msg uninstall_done
