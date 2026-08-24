#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
version="$(node -p "require('$project_dir/package.json').version")"
zimlet_version="$(node -p "require('$project_dir/package.json').zimletVersion")"
[[ "$zimlet_version" =~ ^[0-9]+(\.[0-9]+){1,3}$ ]]
classic_zip="$project_dir/pkg-classic/fr_franckchalon_nextcloud_classic.zip"

[[ -f "$classic_zip" ]]
unzip -tq "$classic_zip" >/dev/null
descriptor="$(unzip -p "$classic_zip" fr_franckchalon_nextcloud_classic.xml)"
grep -Fq 'name="fr_franckchalon_nextcloud_classic"' <<<"$descriptor"
grep -Fq "version=\"$zimlet_version\"" <<<"$descriptor"
grep -Fq 'target="main compose-window view-window"' <<<"$descriptor"
grep -Fq '<include>fr_franckchalon_nextcloud_classic.js</include>' <<<"$descriptor"
grep -Fq '<resource>fr_franckchalon_nextcloud_classic_app.js</resource>' <<<"$descriptor"
grep -Fq '<handlerObject>fr_franckchalon_nextcloud_classic_HandlerObject</handlerObject>' <<<"$descriptor"
for entry in \
  fr_franckchalon_nextcloud_classic.xml \
  fr_franckchalon_nextcloud_classic_app.js \
  fr_franckchalon_nextcloud_classic.js \
  fr_franckchalon_nextcloud_classic.css \
  fr_franckchalon_nextcloud_classic.properties \
  fr_franckchalon_nextcloud_classic_fr.properties \
  nextcloud-classic.svg \
  nextcloud-classic-chat.svg; do
  unzip -Z1 "$classic_zip" | grep -Fxq "$entry"
done

runtime="$(unzip -p "$classic_zip" fr_franckchalon_nextcloud_classic_app.js)"
grep -Fq 'FranckChalonNextcloudClassicApp' <<<"$runtime"
grep -Fq "$version" <<<"$runtime"
grep -Fq 'data-nextcloud-classic-root' <<<"$runtime"
grep -Fq 'mountChat' <<<"$runtime"
grep -Fq '/api/talk/overview' <<<"$runtime"
grep -Fq '/api/talk/conversation' <<<"$runtime"
grep -Fq '/api/talk/delete-message' <<<"$runtime"

handler_source="$(unzip -p "$classic_zip" fr_franckchalon_nextcloud_classic.js)"
grep -Fq 'function fr_franckchalon_nextcloud_classic_HandlerObject()' <<<"$handler_source"
grep -Fq "getResource(fr_franckchalon_nextcloud_classic_HandlerObject.ZIMLET_ID + '_app.js')" <<<"$handler_source"
grep -Fq 'fr-nextcloud-classic-chat-launcher' <<<"$handler_source"
grep -Fq 'fr-nextcloud-classic-chat-panel' <<<"$handler_source"
grep -Fq 'runtime.mountChat' <<<"$handler_source"
classic_css="$(unzip -p "$classic_zip" fr_franckchalon_nextcloud_classic.css)"
grep -Fq '#fr-nextcloud-classic-chat-root[data-nextcloud-classic-root="chat"] > div' <<<"$classic_css"

node - "$project_dir" <<'NODE'
const fs = require('fs');
const vm = require('vm');
const root = process.argv[2];
const roots = {};
function register(node) {
	if (!node || node.nodeType !== 1) return;
	if (node.id) roots[node.id] = node;
	(node.children || []).forEach(register);
}
function makeElement(tagName) {
	const element = {
		nodeType: 1,
		tagName: String(tagName || '').toUpperCase(),
		children: [],
		style: {},
		attributes: {},
		listeners: {},
		className: '',
		textContent: '',
		firstChild: null,
		appendChild(child) {
			this.children.push(child);
			if (!this.firstChild) this.firstChild = child;
			register(child);
			return child;
		},
		setAttribute(name, value) { this.attributes[name] = String(value); },
		getAttribute(name) { return this.attributes[name]; },
		addEventListener(name, listener) { this.listeners[name] = listener; },
		querySelector(selector) {
			const className = selector.charAt(0) === '.' ? selector.slice(1) : '';
			const pending = this.children.slice();
			while (pending.length) {
				const child = pending.shift();
				if (child && className && String(child.className || '').split(/\s+/).includes(className)) return child;
				if (child && child.children) pending.push(...child.children);
			}
			return null;
		}
	};
	let html = '';
	Object.defineProperty(element, 'innerHTML', {
		get() { return html; },
		set(value) {
			html = String(value || '');
			const match = html.match(/id="([^"]+)"[^>]*class="([^"]*)"[^>]*style="height:([0-9]+)px"/);
			if (!match) return;
			const child = makeElement('div');
			child.id = match[1];
			child.className = match[2];
			child.style.height = `${match[3]}px`;
			element.children = [child];
			element.firstChild = child;
			register(child);
		}
	});
	return element;
}
const body = makeElement('body');
const head = makeElement('head');
const mounted = [];
const quickChats = [];
const unmounted = [];
const createdApps = [];
const attachMenuItems = [];
const pickerViews = [];
const pickerDialogs = [];
const pickerMounts = [];
const app = {
  setContent(html) {
    this.html = html;
	const match = String(html).match(/id="([^"]+)"/);
	if (match) {
		const root = makeElement('div');
		root.id = match[1];
		roots[root.id] = root;
	}
  }
};
function ZmZimletBase() {}
const DwtMenuItem = {
	create(options) {
		const item = {
			options,
			addSelectionListener(listener) { this.listener = listener; },
			setImage(image) { this.image = image; },
			setToolTipContent(tooltip) { this.tooltip = tooltip; }
		};
		attachMenuItems.push(item);
		return item;
	}
};
const context = {
  console,
  window: {
    console,
	innerWidth: 1280,
	innerHeight: 900,
	setTimeout(callback) { callback(); },
    FranckChalonNextcloudClassicApp: {
      mount(node, options) {
		if (node.textContent) throw new Error('Classic application loading label was not cleared');
		mounted.push({ node, options });
	  },
	  mountChat(node, options) {
		if (node.textContent) throw new Error('Classic quick-chat loading label was not cleared');
		quickChats.push({ node, options });
	  },
	  mountPicker(node, options) { pickerMounts.push({ node, options }); },
	  unmount(node) { unmounted.push(node); }
    },
    dispatchEvent() {}
  },
  document: {
	body,
	head,
	createElement: makeElement,
	createTextNode(value) { return { nodeType: 3, textContent: String(value) }; },
	getElementById(id) { return roots[id] || null; }
  },
  ZmZimletBase,
  ZmSetting: { LOCALE_NAME: 'locale' },
  ZmId: { VIEW_COMPOSE: 'COMPOSE' },
	ZmOperation: { SEND: 'SEND', ATTACHMENT: 'ATTACHMENT' },
  appCtxt: {
    getApp() { return app; },
    get() { return 'fr_FR'; },
    getUsername() { return 'classic@example.test'; },
    getActiveAccount() { return { id: 'account-id' }; },
    getViewTypeFromId(value) { return value; }
  },
  CustomEvent: function CustomEvent(name, options) { this.type = name; this.detail = options.detail; },
  AjxListener: function AjxListener(target, method, args) { this.target = target; this.method = method; this.args = args; },
  DwtMenuItem
};
context.DwtComposite = function DwtComposite() {
	this.element = makeElement('div');
	pickerViews.push(this);
};
context.DwtComposite.prototype.setSize = function (width, height) { this.width = width; this.height = height; };
context.DwtComposite.prototype.getHtmlElement = function () { return this.element; };
context.ZmDialog = function ZmDialog(options) {
	this.options = options;
	pickerDialogs.push(this);
};
context.ZmDialog.prototype.popup = function () { this.poppedUp = true; };
context.ZmDialog.prototype.popdown = function () { this.poppedDown = true; };
context.globalThis = context;
context.window.ZmId = context.ZmId;
context.window.ZmOperation = context.ZmOperation;
context.window.DwtMenuItem = DwtMenuItem;
vm.runInNewContext(fs.readFileSync(`${root}/classic/fr_franckchalon_nextcloud_classic.js`, 'utf8'), context);
const Handler = context.fr_franckchalon_nextcloud_classic_HandlerObject;
if (typeof Handler !== 'function') throw new Error('Classic handler was not registered');
if (Handler !== context.FrFranckChalonNextcloudClassic) throw new Error('Classic compatibility alias does not target the handler constructor');
if (Handler !== context.window.fr_franckchalon_nextcloud_classic_HandlerObject) throw new Error('Classic handler was not exported on window');
const handler = new Handler();
handler.createApp = (label, icon, tooltip) => {
	const name = `NextcloudClassicApp${createdApps.length + 1}`;
	createdApps.push({ name, label, icon, tooltip });
	return name;
};
handler.getMessage = () => '???';
handler.init();
if (createdApps.length !== 2) throw new Error(`Expected two Classic applications, received ${createdApps.length}`);
if (handler._cloudAppName !== createdApps[0].name || createdApps[0].label !== 'Cloud') throw new Error('Classic Cloud application was not created');
if (handler._chatAppName !== createdApps[1].name || createdApps[1].label !== 'Chat') throw new Error('Classic Chat application was not created');
if (createdApps[0].icon === createdApps[1].icon) throw new Error('Cloud and Chat applications must have distinct icons');
const launcher = roots['fr-nextcloud-classic-chat-launcher'];
const panel = roots['fr-nextcloud-classic-chat-panel'];
if (!launcher || !panel) throw new Error('Classic quick-chat launcher was not installed');
launcher.listeners.click();
if (quickChats.length !== 1 || quickChats[0].options.workspaceScope !== 'account-id') throw new Error('Classic quick-chat was not mounted');
if (launcher.attributes['aria-expanded'] !== 'true') throw new Error('Quick-chat accessibility state was not updated');
handler._closeQuickChat();
if (!unmounted.includes(quickChats[0].node) || launcher.attributes['aria-expanded'] !== 'false') throw new Error('Quick-chat did not close cleanly');
handler.onSelectApp(handler._cloudAppName);
if (mounted.length !== 1 || mounted[0].options.initialView !== 'files') throw new Error('Shared Cloud application was not mounted');
if (mounted[0].options.language !== 'fr-FR') throw new Error(`Unexpected language ${mounted[0].options.language}`);
handler.onSelectApp(handler._chatAppName);
if (mounted.length !== 2 || mounted[1].options.initialView !== 'chat') throw new Error('Shared Talk application was not mounted');

const loadedRuntime = context.window.FranckChalonNextcloudClassicApp;
delete context.window.FranckChalonNextcloudClassicApp;
const lazyHandler = new Handler();
lazyHandler._cloudAppName = 'LazyCloudApp';
lazyHandler.getMessage = () => '???';
lazyHandler.getResource = name => `/service/zimlet/res/fr_franckchalon_nextcloud_classic/${name}`;
lazyHandler.onSelectApp(lazyHandler._cloudAppName);
if (head.children.length !== 1) throw new Error('Classic runtime was not requested lazily');
const runtimeScript = head.children[0];
if (!String(runtimeScript.src).endsWith('/fr_franckchalon_nextcloud_classic_app.js')) throw new Error(`Unexpected lazy runtime URL ${runtimeScript.src}`);
if (mounted.length !== 2) throw new Error('Application mounted before the Classic runtime finished loading');
context.window.FranckChalonNextcloudClassicApp = loadedRuntime;
runtimeScript.onload();
if (mounted.length !== 3 || mounted[2].options.initialView !== 'files') throw new Error('Application did not mount after lazy runtime loading');

if (typeof handler.initializeToolbar === 'function') {
	throw new Error('The obsolete top-right Classic compose toolbar integration is still installed');
}
const composeView = { _submitMyComputerAttachments() {} };
const attachMenu = {};
handler.initializeAttachPopup(attachMenu, composeView);
if (attachMenuItems.length !== 1) throw new Error('Cloud was not added to the native Classic Attach menu');
const cloudItem = attachMenuItems[0];
if (cloudItem.options.parent !== attachMenu || cloudItem.options.text !== 'Cloud') {
	throw new Error('The native Classic attachment item is not labelled Cloud');
}
if (cloudItem.value !== Handler.COMPOSE_OPERATION || cloudItem.image !== 'frNextcloudCloudIcon') {
	throw new Error('The native Classic attachment item metadata is incomplete');
}
if (!cloudItem.listener || cloudItem.listener.args[0] !== composeView) {
	throw new Error('The native Classic attachment item is not bound to its compose view');
}
handler.initializeAttachPopup(attachMenu, composeView);
if (attachMenuItems.length !== 1) throw new Error('The native Classic attachment item was duplicated');

handler.getShell = () => body;
handler._openComposePicker(composeView);
if (pickerViews.length !== 1 || pickerDialogs.length !== 1 || !pickerDialogs[0].poppedUp) {
	throw new Error('Classic attachment picker dialog was not opened');
}
if (pickerViews[0].width !== 860 || pickerViews[0].height !== 620) {
	throw new Error(`Unexpected Classic picker size ${pickerViews[0].width}x${pickerViews[0].height}`);
}
if (!pickerViews[0].element.className.split(/\s+/).includes('frNextcloudClassicPickerView')) {
	throw new Error('Classic picker native view is missing its constrained layout class');
}
if (pickerViews[0].element.style.height !== '620px' || pickerViews[0].element.style.overflow !== 'hidden') {
	throw new Error('Classic picker native view is not height-constrained');
}
if (pickerMounts.length !== 1 || pickerMounts[0].node.style.height !== '620px') {
	throw new Error('Classic picker runtime was not mounted in the fixed-height root');
}
context.window.innerWidth = 600;
context.window.innerHeight = 500;
const compactSize = handler._pickerDimensions();
if (compactSize.width !== 552 || compactSize.height !== 350) {
	throw new Error(`Classic picker does not adapt to a small viewport: ${compactSize.width}x${compactSize.height}`);
}

let attachments = null;
handler._attachToCompose({ _submitMyComputerAttachments(files, node, inline) { attachments = { files, node, inline }; } }, ['file']);
if (!attachments || attachments.files[0] !== 'file' || attachments.inline !== false) throw new Error('Classic attachment bridge failed');

let html = '<p>Message</p><hr id="signature">';
const htmlEditor = { getMode: () => 'text/html', getContent: () => html, setContent: value => { html = value; } };
if (!handler._insertLinksIntoCompose({ getHtmlEditor: () => htmlEditor }, { html: '<ul><li>Cloud</li></ul>', text: 'Cloud' })) throw new Error('HTML link insertion failed');
if (html.indexOf('<ul><li>Cloud</li></ul>') < 0 || html.indexOf('<hr id="signature">') < 0) throw new Error('HTML insertion damaged the message');

let text = 'Message\r\n---- signature';
const textEditor = { getMode: () => 'text/plain', getContent: () => text, setContent: value => { text = value; } };
handler._insertLinksIntoCompose({ getHtmlEditor: () => textEditor }, { html: '<b>Cloud</b>', text: 'Cloud link' });
if (text.indexOf('Cloud link') < 0 || text.indexOf('---- signature') < 0) throw new Error('Text insertion failed');
NODE

echo "ClassicBuildTest: OK (application, Talk runtime, fixed-height native Attach picker, version $version)"
