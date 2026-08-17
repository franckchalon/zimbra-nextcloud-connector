#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const babel = require('@babel/core');

const root = __dirname;
const domNodes = new Map();
let assignedLocation = '';
let oldRuntimeStopped = false;
const apiCalls = [];
let overviewFailure = null;
const sandboxListeners = {};

function fakeNode(tagName) {
	const node = {
		tagName: String(tagName || '').toLowerCase(),
		children: [],
		style: {},
		listeners: {},
		textContent: '',
		value: '',
		setAttribute(name, value) { this[name] = value; },
		removeAttribute(name) { delete this[name]; },
		getAttribute(name) { return this[name] == null ? null : String(this[name]); },
		appendChild(child) { this.children.push(child); child.parentNode = this; return child; },
		insertBefore(child, reference) {
			const index = reference ? this.children.indexOf(reference) : -1;
			if (index < 0) this.children.push(child);
			else this.children.splice(index, 0, child);
			child.parentNode = this;
			return child;
		},
		removeChild(child) {
			this.children = this.children.filter(candidate => candidate !== child);
			if (child.id) domNodes.delete(child.id);
			child.parentNode = null;
			return child;
		},
		addEventListener(name, listener) { this.listeners[name] = listener; },
		removeEventListener(name) { delete this.listeners[name]; }
	};
	Object.defineProperty(node, 'id', {
		get() { return this._id || ''; },
		set(value) {
			if (this._id) domNodes.delete(this._id);
			this._id = value;
			if (value) domNodes.set(value, this);
		}
	});
	Object.defineProperty(node, 'firstChild', { get() { return this.children[0] || null; } });
	Object.defineProperty(node, 'nextSibling', {
		get() {
			if (!this.parentNode) return null;
			const index = this.parentNode.children.indexOf(this);
			return index >= 0 ? (this.parentNode.children[index + 1] || null) : null;
		}
	});
	Object.defineProperty(node, 'scrollHeight', { get() { return this.children.length * 40; } });
	return node;
}

function descendants(parent) {
	const result = [];
	(parent && parent.children || []).forEach(child => {
		result.push(child);
		result.push(...descendants(child));
	});
	return result;
}

const body = fakeNode('body');
const topNavigation = fakeNode('nav');
const cloudAnchor = fakeNode('a');
cloudAnchor.setAttribute('href', '/modern/cloud');
cloudAnchor.className = 'zimbra-client_menu-item_navItem';
const cloudLabel = fakeNode('span');
cloudLabel.className = 'zimbra-client_menu-item_inner';
cloudLabel.textContent = 'Cloud';
cloudAnchor.appendChild(cloudLabel);
topNavigation.appendChild(cloudAnchor);
body.appendChild(topNavigation);

const hostDocument = {
	body,
	createElement: tagName => fakeNode(tagName),
	getElementById: id => domNodes.get(id) || null,
	querySelectorAll(selector) {
		if (selector === '[id="com-nextcloud-connector-chat-tab"]') {
			return descendants(body).filter(node => node.id === 'com-nextcloud-connector-chat-tab');
		}
		return [];
	}
};
const sandboxDocument = { body: fakeNode('sandbox-body'), hidden: false };
const hostWindow = {
	document: hostDocument,
	location: { pathname: '/modern/email/Inbox', assign(target) { assignedLocation = target; } },
	__comNextcloudConnectorChatLauncherRuntime: {
		scope: 'account-1', mode: 'legacy-tabs', stop() { oldRuntimeStopped = true; }
	}
};
hostWindow.parent = hostWindow;
globalThis.document = sandboxDocument;
globalThis.window = {
	document: sandboxDocument,
	parent: hostWindow,
	location: { pathname: '/sandbox/zimlet' },
	addEventListener(name, listener) { sandboxListeners[name] = listener; },
	removeEventListener(name) { delete sandboxListeners[name]; }
};

function compile(relative) {
	return babel.transformSync(fs.readFileSync(path.join(root, relative), 'utf8'), {
		filename: relative,
		plugins: [
			[require('@babel/plugin-transform-react-jsx'), { pragma: 'createElement' }],
			require('@babel/plugin-transform-class-properties'),
			require('@babel/plugin-transform-modules-commonjs')
		]
	}).code;
}

function createElement(type, props, ...children) {
	return { type, props: { ...(props || {}), children } };
}
class Component {
	constructor(props) { this.props = props || {}; this.state = this.state || {}; }
	setState(update) { this.state = { ...this.state, ...(typeof update === 'function' ? update(this.state, this.props) : update) }; }
}
function MenuItem() {}

async function mockApi(endpoint, options = {}) {
	apiCalls.push({ endpoint, options });
	if (endpoint === '/api/profile') return { talkAnyEnabled: true };
	if (endpoint === '/api/talk/overview') {
		if (overviewFailure) {
			const error = overviewFailure;
			overviewFailure = null;
			throw error;
		}
		return {
			unread: 0,
			accounts: [{
				available: true,
				profileId: 'profile-1',
				label: 'Compte principal',
				username: 'franck',
				conversations: [{
					token: 'room-1',
					displayName: 'Marie',
					unreadMessages: 2,
					lastMessage: { message: 'Salut Franck' }
				}]
			}]
		};
	}
	if (endpoint.startsWith('/api/talk/messages?')) {
		return { items: [
			{ id: 10, actorId: 'marie', actorDisplayName: 'Marie', timestamp: 1786800000, message: 'Bonjour depuis Talk' },
			{ id: 11, actorId: 'marie', actorDisplayName: 'Marie', timestamp: 1786800001, message: 'https://giphy.com/gifs/funny-cat-AbCd1234' }
		] };
	}
	if (endpoint.startsWith('/api/talk/gifs?')) {
		if (endpoint.includes('cursor=18')) return {
			items: [
				{ title: 'Chat GIF', resourceUrl: 'https://giphy.com/gifs/funny-cat-Full123' },
				{ title: 'Second GIF', resourceUrl: 'https://giphy.com/gifs/second-cat-Next456' }
			],
			cursor: null
		};
		return { items: [{ title: 'Chat GIF', thumbnailUrl: 'https://cloud.example.test/index.php/apps/integration_giphy/gif/1', resourceUrl: 'https://giphy.com/gifs/funny-cat-Full123' }], cursor: 18 };
	}
	if (endpoint === '/api/talk/message') {
		return { message: { id: 11, actorId: 'franck', actorDisplayName: 'Franck', timestamp: 1786800010, message: options.json.message } };
	}
	if (endpoint === '/api/talk/read') return { status: 'ok' };
	throw new Error(`Unexpected API endpoint: ${endpoint}`);
}

const localRequire = request => {
	if (request === 'preact') return { createElement, Component };
	if (request === '@zimbra-client/components') return { MenuItem };
	if (request === './components/app' || request === './components/cloud-attacher') return function Stub() {};
	if (request === './components/cloud-attacher/compose-bridge') return { registerComposeBridge() {}, unregisterComposeBridge() {}, updateComposeBridge() {} };
	if (request === './api') return { api: mockApi, setApiLanguage() {}, talkGifUrl: (profileId, url) => `/service/extension/nextcloud-connector/api/talk/gif?profileId=${profileId}&url=${encodeURIComponent(url)}` };
	if (request === './i18n') return { languageFromContext: () => 'fr', translate: (_, key) => key };
	if (request === './talk-navigation') return { CLOUD_VIEW_EVENT: 'cloud-view', setCloudView() {}, TALK_NAVIGATION_EVENT: 'talk-navigation' };
	throw new Error(`Unexpected navigation dependency: ${request}`);
};

function registrationsFor(relative) {
	const registrations = [];
	const moduleValue = { exports: {} };
	new Function('require', 'module', 'exports', compile(relative))(localRequire, moduleValue, moduleValue.exports);
	moduleValue.exports.default({
		getAccount: () => ({ id: 'account-1' }),
		plugins: { register(slot, component) { registrations.push({ slot, component }); } }
	}).init();
	return registrations;
}

const cloudRegistrations = registrationsFor('src/index.js');
if (!oldRuntimeStopped) throw new Error('The upgrade must stop the legacy tab-injection runtime');
const runtime = hostWindow.__comNextcloudConnectorChatLauncherRuntime;
if (!runtime || runtime.mode !== 'quick-chat-panel-v5') throw new Error('The parent Zimbra window does not own the quick-chat runtime');

const cloudMenus = cloudRegistrations.filter(item => item.slot === 'slot::vertical-menu-item');
if (cloudMenus.length !== 1) throw new Error('The main package must register only the Cloud navigation icon');
const cloudTree = cloudMenus[0].component();
if (!cloudTree || cloudTree.type !== MenuItem || cloudTree.props.href !== '/cloud') throw new Error('Cloud navigation must stay a native MenuItem');
if (cloudTree.props.children.length !== 0 || cloudTree.props.icon !== 'cloud' || cloudTree.props.title !== 'cloud') throw new Error('Cloud navigation must be an accessible icon without a visible label');
const cloudRoutes = cloudRegistrations.filter(item => item.slot === 'slot::routes');
const routeTrees = cloudRoutes[0] && cloudRoutes[0].component();
if (!Array.isArray(routeTrees) || routeTrees.length !== 2 || routeTrees[0].props.path !== '/cloud' || routeTrees[1].props.path !== '/cloud/chat') {
	throw new Error('Cloud and full Chat routes must remain available');
}

const auxiliaryRegistrations = registrationsFor('src/chat-nav-index.js');
if (auxiliaryRegistrations.length) throw new Error('The auxiliary package must no longer inject a Zimbra navigation tab');

const settle = () => new Promise(resolve => setTimeout(resolve, 0));

(async () => {
	await settle();
	const launcher = hostDocument.getElementById('com-nextcloud-connector-chat-launcher');
	if (!launcher || launcher.parentNode !== body || launcher.children.length !== 2) throw new Error('The compact floating Chat launcher is missing');
	if (launcher.children[1].textContent !== '2' || launcher.children[1].style.display !== 'inline-flex') {
		throw new Error('The floating Chat launcher does not show the calculated unread count');
	}
	hostWindow.location.pathname = '/modern/cloud';
	sandboxListeners['cloud-view']({ detail: { scope: 'account-1', view: 'chat' } });
	if (launcher.style.display !== 'none') throw new Error('The floating Chat launcher remained visible in the full Chat view on /modern/cloud');
	sandboxListeners['cloud-view']({ detail: { scope: 'account-1', view: 'files' } });
	if (launcher.style.display !== 'inline-flex') throw new Error('The floating Chat launcher did not return after leaving Chat');
	hostWindow.location.pathname = '/modern/cloud/chat';
	runtime.ensure();
	if (launcher.style.display !== 'none') throw new Error('The floating Chat launcher remained visible on the dedicated full Chat route');
	hostWindow.location.pathname = '/modern/email/Inbox';
	runtime.ensure();
	if (launcher.style.display !== 'inline-flex') throw new Error('The floating Chat launcher did not return outside Cloud');
	if (topNavigation.children.length !== 1 || topNavigation.children[0] !== cloudAnchor || cloudLabel.style.display) {
		throw new Error('The quick-chat runtime modified Zimbra top navigation');
	}
	if (hostDocument.getElementById('com-nextcloud-connector-chat-tab')) throw new Error('A legacy Chat tab was injected');

	launcher.listeners.click();
	await settle();
	await settle();
	const panel = hostDocument.getElementById('com-nextcloud-connector-quick-chat');
	if (!panel || panel.parentNode !== body || panel.getAttribute('role') !== 'dialog') throw new Error('The launcher did not open the quick-chat panel');
	const conversationButton = descendants(panel).find(node => node.tagName === 'button' && node.style.gridTemplateColumns);
	if (!conversationButton) throw new Error('The quick-chat panel did not list current conversations');

	conversationButton.listeners.click();
	await settle();
	await settle();
	const gifMessage = descendants(panel).find(node => node.tagName === 'img' && String(node.src || '').includes('/api/talk/gif?'));
	if (!gifMessage || !String(gifMessage.src).includes('AbCd1234')) throw new Error('A Giphy conversation message was not rendered as an image through the secure proxy');
	const gifButton = descendants(panel).find(node => node.tagName === 'button' && node.textContent === 'GIF');
	if (!gifButton) throw new Error('The quick-chat composer has no GIF search action');
	gifButton.listeners.click();
	await settle();
	await settle();
	const gifSearch = descendants(panel).find(node => node.tagName === 'input' && node.placeholder === 'searchGifs');
	if (!gifSearch) throw new Error('The GIF search panel did not open');
	gifSearch.value = 'chat';
	gifSearch.listeners.keydown({ key: 'Enter', currentTarget: gifSearch, preventDefault() {} });
	await settle();
	await settle();
	if (!apiCalls.some(call => call.endpoint.includes('/api/talk/gifs?q=chat&limit=18&cursor=0'))) throw new Error('The quick-chat GIF query was not sent to the Nextcloud integration');
	const gifResult = descendants(panel).find(node => node.tagName === 'button' && node.title === 'Chat GIF');
	if (!gifResult) throw new Error('The quick-chat GIF results were not displayed');
	const gifPreview = gifResult.children[0];
	if (!gifPreview || !String(gifPreview.src || '').includes(encodeURIComponent('https://cloud.example.test/index.php/apps/integration_giphy/gif/1'))) {
		throw new Error('The Nextcloud GIF preview was not initially loaded through the secure proxy');
	}
	gifPreview.listeners.error();
	if (!String(gifPreview.src || '').includes(encodeURIComponent('https://media.giphy.com/media/Full123/giphy.gif'))) {
		throw new Error('A broken Nextcloud GIF thumbnail did not fall back to its Giphy image preview');
	}
	const gifGrid = descendants(panel).find(node => node.tagName === 'div' && node.style.gridTemplateColumns === 'repeat(3,minmax(0,1fr))');
	if (!gifGrid || !gifGrid.listeners.scroll) throw new Error('The quick-chat GIF picker has no infinite-scroll handler');
	gifGrid.listeners.scroll({ currentTarget: { scrollHeight: 500, scrollTop: 360, clientHeight: 100 } });
	await settle();
	await settle();
	if (!apiCalls.some(call => call.endpoint.includes('/api/talk/gifs?q=chat&limit=18&cursor=18'))) {
		throw new Error('Scrolling the quick-chat GIF picker did not request the next cursor');
	}
	const secondGif = descendants(panel).find(node => node.tagName === 'button' && node.title === 'Second GIF');
	if (!secondGif) throw new Error('The next GIF page was not appended to the quick-chat results');
	const gifResultAfterPagination = descendants(panel).find(node => node.tagName === 'button' && node.title === 'Chat GIF');
	if (descendants(panel).filter(node => node.tagName === 'button' && node.title === 'Chat GIF').length !== 1) {
		throw new Error('GIF pagination did not remove duplicate results');
	}
	gifResultAfterPagination.listeners.click();
	await settle();
	await settle();
	if (!apiCalls.some(call => call.endpoint === '/api/talk/message' && call.options.json && call.options.json.message === 'https://giphy.com/gifs/funny-cat-Full123')) {
		throw new Error('Selecting a GIF did not send it to the current Talk conversation');
	}

	const composer = descendants(panel).find(node => node.tagName === 'textarea');
	const form = descendants(panel).find(node => node.tagName === 'form');
	if (!composer || !form) throw new Error('The selected conversation cannot be answered from the quick panel');
	composer.value = 'Réponse rapide';
	form.listeners.submit({ preventDefault() {} });
	await settle();
	await settle();
	const sent = apiCalls.find(call => call.endpoint === '/api/talk/message' && call.options.json && call.options.json.message === 'Réponse rapide');
	if (!sent || sent.options.profileId !== 'profile-1' || sent.options.json.token !== 'room-1') throw new Error('The quick reply did not use the selected Talk conversation');

	// A Zimbra nginx timeout must never be copied as a raw HTML document into
	// the panel. The user gets a stable localized message and a manual retry.
	panel.children[0].children[2].listeners.click();
	overviewFailure = Object.assign(new Error('<html><body><h2>HTTP ERROR 504</h2></body></html>'), { status: 504 });
	launcher.listeners.click();
	await settle();
	await settle();
	const timeoutPanel = hostDocument.getElementById('com-nextcloud-connector-quick-chat');
	const timeoutText = descendants(timeoutPanel).map(node => node.textContent).join(' ');
	if (!timeoutText.includes('talkTemporaryUnavailable') || timeoutText.includes('<html>')) {
		throw new Error('The quick panel exposed the raw upstream 504 response');
	}
	const retryButton = descendants(timeoutPanel).find(node => node.tagName === 'button' && node.textContent === 'retry');
	if (!retryButton) throw new Error('The transient Talk error has no retry action');
	retryButton.listeners.click();
	await settle();
	await settle();
	if (!descendants(timeoutPanel).some(node => node.tagName === 'button' && node.style.gridTemplateColumns)) {
		throw new Error('The quick panel did not recover after retry');
	}

	const fullChatButton = timeoutPanel.children[0].children[1];
	fullChatButton.listeners.click();
	if (assignedLocation !== '/modern/cloud/chat') throw new Error(`Full Chat navigation used an invalid target: ${assignedLocation}`);
	if (hostDocument.getElementById('com-nextcloud-connector-quick-chat')) throw new Error('The quick panel remained open after full Chat navigation');

	runtime.stop();
	if (hostDocument.getElementById('com-nextcloud-connector-chat-launcher')) throw new Error('The quick-chat runtime did not stop cleanly');
	if (topNavigation.children.length !== 1 || cloudLabel.textContent !== 'Cloud') throw new Error('Stopping quick chat altered Cloud navigation');
	console.log('NavigationRenderTest: OK (icône Cloud seule, bulle masquée dans Chat, badge, GIF et réponse rapide)');
})().catch(error => {
	console.error(error.stack || error);
	process.exitCode = 1;
});
