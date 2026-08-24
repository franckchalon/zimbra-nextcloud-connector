#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
version="$(node -p "require('$project_dir/package.json').version")"
zimlet_version="$(node -p "require('$project_dir/package.json').zimletVersion")"
[[ "$zimlet_version" =~ ^[0-9]+(\.[0-9]+){1,3}$ ]]
frontend_zip="$project_dir/pkg/com_nextcloud_connector.zip"
chat_frontend_zip="$project_dir/pkg-chat/com_nextcloud_connector_chat.zip"

node - "$project_dir" <<'NODE'
const fs = require('fs');
const vm = require('vm');
const root = process.argv[2];
let source = fs.readFileSync(`${root}/src/i18n.js`, 'utf8').replace(/\bexport\s+/g, '');
source += '\nglobalThis.__dictionaries = DICTIONARIES;';
const context = {};
vm.runInNewContext(source, context);
const dictionaries = context.__dictionaries;
const expected = Object.keys(dictionaries.fr).sort();
for (const [language, dictionary] of Object.entries(dictionaries)) {
  const keys = Object.keys(dictionary).sort();
  if (JSON.stringify(keys) !== JSON.stringify(expected)) {
    const missing = expected.filter(key => !keys.includes(key));
    const extra = keys.filter(key => !expected.includes(key));
    throw new Error(`${language}: missing=${missing.join(',')} extra=${extra.join(',')}`);
  }
}

const sourceFiles = [
  'src/components/app/index.js', 'src/components/app/advanced.js',
	  'src/components/cloud-attacher/index.js', 'src/components/cloud-attacher/compose-bridge.js',
	  'src/components/cloud-picker/index.js', 'src/classic-entry.js',
	  'src/components/floating-windows/index.js', 'src/components/chat/index.js', 'src/index.js', 'src/chat-nav-index.js'
];
const singularUsages = new Set();
const pluralUsages = new Set();
for (const relative of sourceFiles) {
  const source = fs.readFileSync(`${root}/${relative}`, 'utf8');
  for (const pattern of [/\b(?:this\.)?t\(\s*['"]([^'"]+)['"]/g, /\btranslate\([^,]+,\s*['"]([^'"]+)['"]/g]) {
    let match;
    while ((match = pattern.exec(source))) singularUsages.add(match[1]);
  }
  for (const pattern of [/\b(?:this\.)?tp\([^,]+,\s*['"]([^'"]+)['"]/g, /\btranslatePlural\([^,]+,[^,]+,\s*['"]([^'"]+)['"]/g]) {
    let match;
    while ((match = pattern.exec(source))) pluralUsages.add(match[1]);
  }
}
const dynamicKeys = [
  'smartFiles', 'smartFavorites', 'smartRecent', 'smartSharedByMe', 'smartSharedWithMe', 'smartPublicLinks',
  'uploadStatus_waiting', 'uploadStatus_uploading', 'uploadStatus_done', 'uploadStatus_error', 'uploadStatus_cancelled',
  'diagnostic_nextcloudCapabilities', 'diagnostic_webdav',
  'capability_webdav', 'capability_search', 'capability_favorites', 'capability_versions', 'capability_comments',
  'capability_chunkedUpload', 'capability_sharing', 'capability_publicLinks', 'capability_federatedSharing',
  'capability_activity', 'capability_unifiedSearch', 'capability_directDownload', 'capability_office'
];
for (const [language, dictionary] of Object.entries(dictionaries)) {
  const missingLiteral = [...singularUsages, ...dynamicKeys].filter(key => !(key in dictionary));
  const missingPlural = [...pluralUsages].filter(key => !(`${key}One` in dictionary) || !(`${key}Other` in dictionary));
  if (missingLiteral.length || missingPlural.length) {
    throw new Error(`${language}: missing literal=${missingLiteral.join(',')} plural=${missingPlural.join(',')}`);
  }
}
NODE

grep -Fq "const CLOUD_SLUG = 'cloud';" "$project_dir/src/index.js"
grep -Fq 'path={`/${CLOUD_SLUG}`}' "$project_dir/src/index.js"
grep -Fq 'path={`/${CLOUD_SLUG}/${CHAT_SLUG}`}' "$project_dir/src/index.js"
grep -Fq 'initialView="files"' "$project_dir/src/index.js"
grep -Fq 'initialView="chat"' "$project_dir/src/index.js"
grep -Fq 'href={`/${CLOUD_SLUG}`}' "$project_dir/src/index.js"
test "$(grep -Fc "plugins.register('slot::vertical-menu-item'" "$project_dir/src/index.js")" -eq 1
grep -Fq 'plugins.register('\''slot::vertical-menu-item'\'', CloudNavigation)' "$project_dir/src/index.js"
grep -Fq 'function CloudNavigation()' "$project_dir/src/index.js"
if grep -Fq 'ChatNavigation' "$project_dir/src/index.js"; then
  echo "Erreur : Chat doit être accessible uniquement par le lanceur flottant, sans entrée de navigation Zimbra." >&2
  exit 1
fi
grep -Fq 'function zimbraHostWindow()' "$project_dir/src/index.js"
grep -Fq 'sandboxWindow.parent.document' "$project_dir/src/index.js"
grep -Fq 'const document = zimbraHostDocument();' "$project_dir/src/index.js"
grep -Fq 'const runtimeHost = zimbraHostWindow() || globalThis;' "$project_dir/src/index.js"
if grep -Fq 'data-nextcloud-chat-access="true"' "$project_dir/src/index.js"; then
  echo "Erreur : MenuItem Cloud ne doit contenir aucun contrôle interactif imbriqué." >&2
  exit 1
fi
if grep -Fq 'class CloudNavigation extends Component' "$project_dir/src/index.js"; then
  echo "Erreur : Zimbra 10.1.20 exige une entrée Cloud fonctionnelle directe." >&2
  exit 1
fi
grep -Fq "const CHAT_LAUNCHER_ID = 'com-nextcloud-connector-chat-launcher';" "$project_dir/src/index.js"
grep -Fq "const CHAT_PANEL_ID = 'com-nextcloud-connector-quick-chat';" "$project_dir/src/index.js"
grep -Fq "const CHAT_LAUNCHER_RUNTIME_MODE = 'quick-chat-panel-v5';" "$project_dir/src/index.js"
grep -Fq 'document.body.appendChild(button)' "$project_dir/src/index.js"
grep -Fq 'document.body.appendChild(container)' "$project_dir/src/index.js"
grep -Fq "api('/api/talk/overview', { profileId: '' })" "$project_dir/src/index.js"
grep -Fq "api('/api/talk/message'" "$project_dir/src/index.js"
grep -Fq "api('/api/talk/read'" "$project_dir/src/index.js"
grep -Fq 'renderConversationList' "$project_dir/src/index.js"
grep -Fq 'renderPanelMessages' "$project_dir/src/index.js"
grep -Fq 'loadPanelMessages' "$project_dir/src/index.js"
grep -Fq 'loadPanelGifs' "$project_dir/src/index.js"
grep -Fq 'renderPanelGifPicker' "$project_dir/src/index.js"
grep -Fq 'panelGifLoadingMore' "$project_dir/src/index.js"
grep -Fq 'loadPanelGifs(panelGifQuery, true)' "$project_dir/src/index.js"
grep -Fq 'handleGifScroll' "$project_dir/src/components/chat/index.js"
grep -Fq 'gifLoadMoreError' "$project_dir/src/components/chat/index.js"
grep -Fq 'talkGifUrl(panelProfileId' "$project_dir/src/index.js"
grep -Fq 'location.assign(target)' "$project_dir/src/index.js"
grep -Fq "eventTarget.addEventListener(CLOUD_VIEW_EVENT, updateCloudView)" "$project_dir/src/index.js"
grep -Fq "launcherCloudView === 'chat'" "$project_dir/src/index.js"
if grep -Fq 'installNavigationTab' "$project_dir/src/index.js" || grep -Fq 'cloudLink.parentNode.insertBefore' "$project_dir/src/index.js"; then
  echo "Erreur : le mini-chat ne doit injecter aucun onglet dans la navigation Zimbra." >&2
  exit 1
fi
if grep -Fq "label.style.display = 'none'" "$project_dir/src/index.js" || grep -Fq 'compactNavigation' "$project_dir/src/index.js"; then
  echo "Erreur : le mini-chat ne doit pas modifier le bouton Cloud natif." >&2
  exit 1
fi
test "$(grep -Fc 'ensureChatLauncherRuntime();' "$project_dir/src/index.js")" -eq 3
grep -B8 -F 'return {' "$project_dir/src/index.js" | grep -Fq 'ensureChatLauncherRuntime();'
grep -A12 -F 'init() {' "$project_dir/src/index.js" | grep -Fq 'ensureChatLauncherRuntime();'
if grep -Fq "plugins.register('slot::vertical-menu-item'" "$project_dir/src/chat-nav-index.js"; then
  echo "Erreur : le paquet auxiliaire historique doit rester inerte et ne plus créer d’onglet." >&2
  exit 1
fi
grep -Fq 'return { init() {} };' "$project_dir/src/chat-nav-index.js"
if grep -Fq "style={{ display: 'contents' }}" "$project_dir/src/index.js"; then
  echo "Erreur : le point d’extension Zimbra ne doit recevoir aucun conteneur autour des MenuItem." >&2
  exit 1
fi
grep -Fq "api('/api/talk/settings'" "$project_dir/src/components/app/index.js"
grep -Fq '"/api/talk/settings".equals(route)' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudConnectorHandler.java"
grep -Fq 'withTalkEnabled(profile.id, enabled)' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudConnectorHandler.java"
grep -Fq 'talkEnabledProfileIds' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/ProfileSet.java"
grep -Fq "if (enabled) setCloudView(this.props.workspaceScope, 'chat');" "$project_dir/src/components/app/index.js"
grep -Fq '<Chat workspaceScope={this.props.workspaceScope}' "$project_dir/src/components/app/index.js"
grep -Fq "showChat: initialView === 'chat'" "$project_dir/src/components/app/index.js"
if rg -q 'currentCloudView' "$project_dir/src/components/app/index.js"; then
  echo "Erreur : la route /cloud ne doit plus restaurer la dernière vue Chat mémorisée." >&2
  exit 1
fi
if rg -q 'refreshTalkOverview|talkOverviewTimer' "$project_dir/src/components/app/index.js"; then
  echo "Erreur : l’écran Fichiers ne doit plus dupliquer les interrogations Talk du mini-chat." >&2
  exit 1
fi
grep -Fq "talkTemporaryUnavailable" "$project_dir/src/index.js"
grep -Fq "refreshAllowedAfter" "$project_dir/src/index.js"
grep -Fq "talk.request_timeout_seconds" "$project_dir/server/resources/config.example.properties"
grep -Fq ".timeout(config.talkRequestTimeout)" "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudTalkClient.java"
grep -Fq 'this.cancelMessagesRequest();' "$project_dir/src/components/chat/index.js"
grep -Fq 'draftsForSwitch' "$project_dir/src/components/chat/index.js"
grep -Fq 'sessionStorage.setItem(sessionKey(scope)' "$project_dir/src/components/chat/index.js"
grep -Fq 'setTalkSoundEnabled(this.props.workspaceScope' "$project_dir/src/components/chat/index.js"
grep -Fq '.avatar, .avatarLarge' "$project_dir/src/components/chat/style.less"
grep -Fq "this.t('enableChat')" "$project_dir/src/components/app/index.js"
grep -Fq "this.t('disableChat')" "$project_dir/src/components/app/index.js"
if rg -q '<>|</>' "$project_dir/src/components/chat/index.js"; then
  echo "Erreur : les fragments JSX non pris en charge par l’ancien runtime Preact Zimbra sont interdits dans le Chat." >&2
  exit 1
fi
grep -Fq "api('/api/talk/overview', { profileId: '' })" "$project_dir/src/components/chat/index.js"
grep -Fq "api('/api/talk/message'" "$project_dir/src/components/chat/index.js"
grep -Fq "api('/api/talk/conversation'" "$project_dir/src/components/chat/index.js"
grep -Fq "api('/api/talk/delete-message'" "$project_dir/src/components/chat/index.js"
grep -Fq "api('/api/talk/reaction'" "$project_dir/src/components/chat/index.js"
grep -Fq "api('/api/talk/share-file'" "$project_dir/src/components/chat/index.js"
grep -Fq 'if (!message || typeof message !== '\''object'\'') return null;' "$project_dir/src/components/chat/index.js"
grep -Fq 'deleteMessages' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudTalkClient.java"
grep -Fq 'createConversation(int roomType' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudTalkClient.java"
grep -Fq 'void deleteMessage(String rawToken' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudTalkClient.java"
grep -Fq '"/api/talk/conversation".equals(route)' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudConnectorHandler.java"
grep -Fq '"/api/talk/delete-message".equals(route)' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudConnectorHandler.java"
grep -Fq '"/ocs/v2.php/apps/spreed/api/v1"' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudTalkClient.java"
grep -Fq '"/ocs/v2.php/apps/spreed/api/v4"' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudTalkClient.java"
grep -Fq 'media[0-9]*\\.giphy\\.com' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudTalkClient.java"
grep -Fq 'builder.header("Authorization", basicAuth)' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudTalkClient.java"
if rg -q 'spreed/api/.*/call|signaling/api|joinCall|leaveCall' "$project_dir/src" "$project_dir/server/src"; then
  echo "Erreur : la version Chat ne doit contenir aucune intégration audio, vidéo ou signalisation." >&2
  exit 1
fi
grep -Fq 'workspaceScope={workspaceScope}' "$project_dir/src/index.js"
grep -Fq 'sessionStorage.setItem(workspaceStateKey(scope)' "$project_dir/src/workspace-state.js"
grep -Fq "<h1>{this.t('myCloudSpace')}</h1>" "$project_dir/src/components/app/index.js"
grep -Fq 'overflow-y: auto;' "$project_dir/src/components/app/style.less"
grep -Fq 'const MAX_THUMBNAIL_REQUESTS = 4;' "$project_dir/src/components/app/index.js"
grep -Fq '<LazyThumbnail file={file} />' "$project_dir/src/components/app/index.js"
grep -Fq "this.setSearchScope('account')" "$project_dir/src/components/app/index.js"
grep -Fq 'mediaFiles: this.mediaItemsForFloatingWindow()' "$project_dir/src/components/app/index.js"
grep -Fq 'grid-template-columns: repeat(8, minmax(0, 1fr));' "$project_dir/src/components/app/style.less"
grep -Fq "plugins.register('slot::compose-attachment-action-menu'" "$project_dir/src/index.js"
grep -Fq "plugins.register('slot::compose-footer-right-btn', ComposeInsertionBridge)" "$project_dir/src/index.js"
grep -Fq 'insertComposeContent(composeBridge, editor' "$project_dir/src/components/cloud-attacher/index.js"
if grep -Fq 'navigator.clipboard.writeText' "$project_dir/src/components/cloud-attacher/index.js"; then
  echo "Erreur : l’insertion de liens ne doit plus dépendre du focus de l’API Clipboard." >&2
  exit 1
fi
grep -Fq "onAttachFiles={files => editor.addAttachments(files, true)}" "$project_dir/src/components/cloud-attacher/index.js"
grep -Fq "const quota = await api('/api/quota')" "$project_dir/src/components/app/index.js"
grep -Fq "<option value=\"created\">{this.t('sortByCreation')}</option>" "$project_dir/src/components/app/index.js"
grep -Fq 'requestFullscreen' "$project_dir/src/components/floating-windows/index.js"
grep -Fq "this.ownerDocument.addEventListener('mousemove', resizeWindow, true)" "$project_dir/src/components/floating-windows/index.js"
grep -Fq "this.ownerDocument.addEventListener('mouseup', stop, true)" "$project_dir/src/components/floating-windows/index.js"
grep -Fq "handle.addEventListener('mousedown', event => this.startResize(event, direction))" "$project_dir/src/components/floating-windows/index.js"
grep -Fq 'windowClass: style.mediaWindow' "$project_dir/src/components/floating-windows/index.js"
grep -Fq 'windowClass: style.editorWindow' "$project_dir/src/components/floating-windows/index.js"
grep -Fq 'interactionShield(this.ownerDocument' "$project_dir/src/components/floating-windows/index.js"
grep -Fq 'right: 12px;' "$project_dir/src/components/floating-windows/style.less"
grep -Fq 'bottom: 4px;' "$project_dir/src/components/floating-windows/style.less"
grep -Fq 'left: 12px;' "$project_dir/src/components/floating-windows/style.less"
if grep -Fq 'width: min(1500px' "$project_dir/src/components/floating-windows/style.less"; then
  echo "Erreur : la fenêtre bureautique ne doit plus être limitée à 1500 px." >&2
  exit 1
fi
grep -Fq 'profile.officeUrl' "$project_dir/src/components/app/index.js"
grep -Fq "name=\"officeMode\"" "$project_dir/src/components/app/index.js"
grep -Fq "name=\"officeJwtSecret\"" "$project_dir/src/components/app/index.js"
grep -Fq "profile.officeJwtSecretSet" "$project_dir/src/components/app/index.js"
grep -Fq "officeLabel: profile.officeLabel || this.t('onlineEditor')" "$project_dir/src/components/app/index.js"
grep -Fq "const activationResult = await api('/api/activate', { method: 'POST', json: {} });" "$project_dir/src/components/app/index.js"
grep -Fq "profile.accountMode === 'managed'" "$project_dir/src/components/app/index.js"
grep -Fq "t('activateMyCloudAccount')" "$project_dir/src/components/app/index.js"
grep -Fq "t('passwordShownOnce')" "$project_dir/src/components/app/index.js"
grep -Fq 'activationResult: null' "$project_dir/src/components/app/index.js"
grep -Fq 'dismissible={false}' "$project_dir/src/components/app/index.js"
if grep -Fq 'profile.onlyofficeUrl' "$project_dir/src/components/app/index.js"; then
  echo "Erreur : l’interface doit afficher le moteur bureautique choisi sans dépendre d’ONLYOFFICE." >&2
  exit 1
fi
grep -Fq "this.header.addEventListener('mousedown', event => this.startMove(event))" "$project_dir/src/components/floating-windows/index.js"
grep -Fq 'event.target.closest(`.${style.headerActions}`)' "$project_dir/src/components/floating-windows/index.js"
grep -Fq "this.ownerDocument.addEventListener('mousemove', moveWindow, true)" "$project_dir/src/components/floating-windows/index.js"
grep -Fq '.header {' "$project_dir/src/components/floating-windows/style.less"
grep -Fq '.editorWindow .header {' "$project_dir/src/components/floating-windows/style.less"
grep -Fq 'min-height: 36px;' "$project_dir/src/components/floating-windows/style.less"
grep -Fq "['w', style.resizeHandleW]" "$project_dir/src/components/floating-windows/index.js"
grep -Fq '.resizeHandleSE::after' "$project_dir/src/components/floating-windows/style.less"
grep -Fq 'top: 64px;' "$project_dir/src/components/floating-windows/style.less"
grep -Fq 'pointer-events: none;' "$project_dir/src/components/floating-windows/style.less"
grep -Fq '.layerInactive { visibility: hidden; }' "$project_dir/src/components/floating-windows/style.less"
grep -Fq 'const sameIdentity = Boolean(this.editor && this.editor.identity === identity);' "$project_dir/src/components/floating-windows/index.js"
grep -Fq 'const sameIdentity = Boolean(this.preview && this.preview.identity === identity);' "$project_dir/src/components/floating-windows/index.js"
grep -Fq 'if (!sameIdentity) iframe.src = editorUrl(file.path, profileId);' "$project_dir/src/components/floating-windows/index.js"
grep -Fq 'if (!sameIdentity) this.renderPreviewMedia();' "$project_dir/src/components/floating-windows/index.js"
grep -Fq "this.hostNode = ownerDocument.getElementById(HOST_ID);" "$project_dir/src/components/floating-windows/index.js"
grep -Fq "this.hostNode.appendChild(this.layer);" "$project_dir/src/components/floating-windows/index.js"
if grep -Fq "from 'preact'" "$project_dir/src/components/floating-windows/index.js"; then
  echo "Erreur : le gestionnaire persistant ne doit pas dépendre d’une seconde racine Preact Zimbra." >&2
  exit 1
fi
grep -Fq 'setCloudRouteActive(this.props.workspaceScope, false' "$project_dir/src/components/app/index.js"
grep -Fq 'openFloatingEditor' "$project_dir/src/components/app/index.js"
grep -Fq 'openFloatingPreview' "$project_dir/src/components/app/index.js"
grep -Fq "updateStoredWindow(scope, 'editorFile', file)" "$project_dir/src/components/floating-windows/index.js"
if rg -q '<Preview|<Editor' "$project_dir/src/components/app/index.js"; then
	  echo "Erreur : les fenêtres persistantes ne doivent pas rester attachées à la route Cloud." >&2
	  exit 1
fi

if grep -Fq 'resize: both;' "$project_dir/src/components/cloud-attacher/style.less"; then
  echo "Erreur : le sélecteur Cloud ne doit pas être redimensionnable." >&2
  exit 1
fi
grep -Fq 'resize: none !important;' "$project_dir/src/components/cloud-attacher/style.less"
grep -Fq 'event.target === event.currentTarget' "$project_dir/src/components/app/index.js"
grep -Fq 'setPageNode = node =>' "$project_dir/src/components/app/index.js"
grep -Fq "this.contextMenuDocument.addEventListener('mousedown', this.closeContextMenuFromOutside, true)" "$project_dir/src/components/app/index.js"
grep -Fq "this.contextMenuDocument.removeEventListener('mousedown', this.closeContextMenuFromOutside, true)" "$project_dir/src/components/app/index.js"
grep -Fq 'ref={this.setPageNode}' "$project_dir/src/components/app/index.js"
if grep -Fq "globalThis.document.addEventListener('mousedown', this.closeContextMenuFromOutside" "$project_dir/src/components/app/index.js"; then
  echo "Erreur : le clic extérieur ne doit pas être attaché avant que la page Cloud connaisse son document réel." >&2
  exit 1
fi
if rg -q "onPointerDown|setPointerCapture|addEventListener\('pointermove'" "$project_dir/src/components/app/index.js" "$project_dir/src/components/floating-windows/index.js"; then
  echo "Erreur : le redimensionnement ne doit plus dépendre des événements Pointer incompatibles avec certains contextes Zimbra." >&2
  exit 1
fi
grep -Fq 'this.props.onAttachmentOptionSelection(editor =>' "$project_dir/src/components/cloud-attacher/index.js"
grep -Fq 'this.composeBridge = resolveComposeBridge(editor)' "$project_dir/src/components/cloud-attacher/index.js"
grep -Fq 'context.zimletRedux.actions.zimlets.addModal' "$project_dir/src/components/cloud-attacher/index.js"
grep -Fq 'footer={false}' "$project_dir/src/components/cloud-attacher/index.js"
grep -Fq 'class={style.modalDialog}' "$project_dir/src/components/cloud-attacher/index.js"
grep -Fq "target.addEventListener('focus', this.handleProfileReturn)" "$project_dir/src/components/app/index.js"
grep -Fq "this.profileDocument.addEventListener('visibilitychange', this.handleProfileReturn)" "$project_dir/src/components/app/index.js"
grep -Fq "this.profileEventTarget.addEventListener('focus', this.handleProfileReturn)" "$project_dir/src/components/cloud-picker/index.js"
grep -Fq "this.visibilityDocument.addEventListener('visibilitychange', this.handleBrowserReturn)" "$project_dir/src/components/chat/index.js"
grep -Fq "settingsPersist: 'Ces réglages sont liés à votre compte Zimbra.'" "$project_dir/src/i18n.js"
grep -Fq ":global([data-nextcloud-classic-root='picker']) .picker" "$project_dir/src/components/cloud-attacher/style.less"
grep -Fq ":global([data-nextcloud-classic-root='picker']) > div" "$project_dir/src/components/cloud-attacher/style.less"
grep -Fq 'frNextcloudClassicPickerView' "$project_dir/classic/fr_franckchalon_nextcloud_classic.js"
grep -Fq 'pickerView.setSize(dimensions.width, dimensions.height)' "$project_dir/classic/fr_franckchalon_nextcloud_classic.js"
grep -Fq '<ActionMenuItem icon="cloud"' "$project_dir/src/components/cloud-attacher/index.js"
grep -Fq "{translate(language, 'cloud')}" "$project_dir/src/components/cloud-attacher/index.js"
grep -Fq 'const result = await api(`/api/list?path=' "$project_dir/src/components/cloud-picker/index.js"
grep -Fq 'onContextMenu={event => this.openContextMenu(event, file)}' "$project_dir/src/components/app/index.js"
grep -Fq 'bottom: 40px;' "$project_dir/src/components/app/style.less"
grep -Fq "this.t('details')" "$project_dir/src/components/app/index.js"
grep -Fq "this.t('selectVisibleItems')" "$project_dir/src/components/app/index.js"
grep -Fq "this.t('deselectVisibleItems')" "$project_dir/src/components/app/index.js"
grep -Fq "const MAX_BULK_ITEMS = 200;" "$project_dir/src/components/app/index.js"
grep -Fq "api('/api/batch'" "$project_dir/src/components/app/index.js"
grep -Fq "this.openBulkAction('move')" "$project_dir/src/components/app/index.js"
grep -Fq "this.openBulkAction('copy')" "$project_dir/src/components/app/index.js"
grep -Fq "this.deleteSelected" "$project_dir/src/components/app/index.js"
grep -Fq '<FolderPicker' "$project_dir/src/components/app/index.js"
grep -Fq "destinationConflicts(items, path)" "$project_dir/src/components/app/index.js"
grep -Fq 'class={style.cardCheckbox}' "$project_dir/src/components/app/index.js"
grep -Fq 'aria-label={this.t('\''selectItem'\''' "$project_dir/src/components/app/index.js"
grep -Fq '.selectionDangerButton' "$project_dir/src/components/app/style.less"
grep -Fq "api('/api/capabilities')" "$project_dir/src/components/app/index.js"
grep -Fq "api('/api/templates')" "$project_dir/src/components/app/index.js"
grep -Fq "api('/api/login-flow/start'" "$project_dir/src/components/app/index.js"
grep -Fq "api('/api/login-flow/poll'" "$project_dir/src/components/app/index.js"
grep -Fq "api('/api/upload/start'" "$project_dir/src/components/app/index.js"
grep -Fq "api('/api/upload/empty'" "$project_dir/src/components/app/index.js"
grep -Fq 'api(`/api/upload/chunk' "$project_dir/src/components/app/index.js"
grep -Fq "api('/api/upload/finish'" "$project_dir/src/components/app/index.js"
grep -Fq "message: this.t('uploadChunkProgress'" "$project_dir/src/components/app/index.js"
if grep -Fq "message: t(this.language(), 'uploadChunkProgress'" "$project_dir/src/components/app/index.js"; then
  echo "Erreur : le suivi d’envoi ne doit pas appeler une fonction de traduction inexistante." >&2
  exit 1
fi
grep -Fq '<UploadCenter jobs={uploads}' "$project_dir/src/components/app/index.js"
grep -Fq '<SmartNavigation active={smartView}' "$project_dir/src/components/app/index.js"
grep -Fq '<AdvancedSearchPanel values={advancedFilters}' "$project_dir/src/components/app/index.js"
grep -Fq '<ItemDetails language={language}' "$project_dir/src/components/app/index.js"
grep -Fq '<DiagnosticsPanel language={language}' "$project_dir/src/components/app/index.js"
grep -Fq 'archiveUrl(Array.from(parents)[0]' "$project_dir/src/components/app/index.js"
grep -Fq 'const blob = await fetchDownload(url);' "$project_dir/src/components/app/index.js"
grep -Fq 'export async function fetchDownload(url' "$project_dir/src/api.js"
grep -Fq 'response.blob()' "$project_dir/src/api.js"
grep -Fq '"?accept=zip&files="' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudClient.java"
if grep -Fq 'X-NC-Files' "$project_dir/server/src/fr/franckchalon/zimbra/nextcloud/NextcloudClient.java"; then
  echo "Erreur : le téléchargement ZIP ne doit plus dépendre des en-têtes WebDAV répétés." >&2
  exit 1
fi
grep -Fq "api('/api/mail-limits')" "$project_dir/src/components/cloud-picker/index.js"
grep -Fq 'insertReadOnlyLinks' "$project_dir/src/components/cloud-picker/index.js"
grep -Fq "collisionPolicy: 'keep-both'" "$project_dir/src/components/app/index.js"
grep -Fq '.folderPickerPolicy' "$project_dir/src/components/app/style.less"
grep -Fq 'const primaryLabel =' "$project_dir/src/components/app/index.js"
grep -Fq 'const handleModalKeyDown = event =>' "$project_dir/src/components/app/index.js"
grep -Fq 'aria-label={title}' "$project_dir/src/components/app/index.js"
grep -Fq 'selectedItems.length > 0 ? style.filesViewWithSelection' "$project_dir/src/components/app/index.js"
grep -Fq '.filesViewWithSelection { padding-bottom: 78px; }' "$project_dir/src/components/app/style.less"
grep -Fq '.selectionBar { position: fixed;' "$project_dir/src/components/app/style.less"
grep -Fq 'bottom: 16px;' "$project_dir/src/components/app/style.less"
grep -Fq 'transform: translateX(-50%);' "$project_dir/src/components/app/style.less"
if grep -Fq '.selectionBar { position: sticky;' "$project_dir/src/components/app/style.less"; then
  echo "Erreur : la barre d’actions sélectionnées ne doit plus disparaître lors du défilement." >&2
  exit 1
fi
grep -Fq '.folderPickerList' "$project_dir/src/components/app/style.less"
grep -Fq "this.t('createReadOnlyLink')" "$project_dir/src/components/app/index.js"
grep -Fq "this.t('deletedFiles')" "$project_dir/src/components/app/index.js"
grep -Fq '<option value="odt">' "$project_dir/src/components/app/index.js"
grep -Fq '<option value="ods">' "$project_dir/src/components/app/index.js"
grep -Fq '<option value="odp">' "$project_dir/src/components/app/index.js"
for locale in fr_FR es_ES es_AR it_IT de_DE pt_PT pt_BR hi_IN ms_MY ru_RU; do
  unzip -l "$frontend_zip" | grep -Fq "com_nextcloud_connector_${locale}.properties"
  unzip -l "$chat_frontend_zip" | grep -Fq "com_nextcloud_connector_chat_${locale}.properties"
done
grep -Fq "languageFromContext(context, 'fr')" "$project_dir/src/index.js"
grep -Fq 'X-Zimbra-Zimlet-Language' "$project_dir/src/api.js"
grep -Fq 'X-Nextcloud-Profile' "$project_dir/src/api.js"
grep -Fq "api('/api/profile/select'" "$project_dir/src/components/app/index.js"
grep -Fq "api('/api/profile/select'" "$project_dir/src/components/cloud-picker/index.js"
grep -Fq "setActiveProfile(profile.activeProfileId" "$project_dir/src/components/cloud-picker/index.js"
grep -Fq "api('/api/profile/delete'" "$project_dir/src/components/app/index.js"
grep -Fq "this.t('addCloudAccount')" "$project_dir/src/components/app/index.js"
grep -Fq 'profile={settingsProfile}' "$project_dir/src/components/app/index.js"
grep -Fq 'adding={addingAccount}' "$project_dir/src/components/app/index.js"

if rg -q 'Préparer pour un e-mail|attachment-queue|queueCloudAttachments|peekCloudAttachments|clearCloudAttachments' "$project_dir/src"; then
  echo "Erreur : l’ancien mécanisme de préparation des pièces jointes est encore présent." >&2
  exit 1
fi

if grep -REq "routes\.slugs|/email/nextcloud|route\(['\"]\/email\/(compose|Inbox)|provide\(context\)|querySelector.*[Nn]ouveau|querySelector.*[Nn]ew.message" "$project_dir/src"; then
  echo "Erreur : une ancienne forme de routage est encore présente." >&2
  exit 1
fi

unzip -p "$frontend_zip" com_nextcloud_connector.xml \
  | grep -Fq "version=\"$zimlet_version\""
unzip -p "$frontend_zip" com_nextcloud_connector.xml \
  | grep -Fq 'label="Cloud"'
unzip -p "$chat_frontend_zip" com_nextcloud_connector_chat.xml \
  | grep -Fq "version=\"$zimlet_version\""
unzip -p "$chat_frontend_zip" com_nextcloud_connector_chat.xml \
  | grep -Fq 'label="Chat"'
chat_javascript="$(unzip -p "$chat_frontend_zip" index.js)"
grep -Fq 'zimlet(function' <<<"$chat_javascript"
grep -Eq '\.g\.shims=[A-Za-z_$][A-Za-z0-9_$]*' <<<"$chat_javascript"
if grep -Eq '^\(\(\)=>\{.*\.g\.shims\.preact' <<<"$chat_javascript" \
  && ! grep -Fq 'zimlet(function' <<<"$chat_javascript"; then
  echo "Erreur : le module Chat utilise Preact sans bootstrap Zimlet." >&2
  exit 1
fi

node "$project_dir/test-floating-windows.js"
node "$project_dir/test-compose-links.js"
node "$project_dir/test-chat-render.js"
node "$project_dir/test-navigation-render.js"
node "$project_dir/test-api-response.js"

echo "FrontendRouteTest: OK (lanceur Chat global, conversations, suppression, ZIP, routes et fenêtres, version $version)"
