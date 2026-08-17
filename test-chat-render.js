#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const babel = require('@babel/core');

const root = __dirname;
const sessionValues = new Map();
globalThis.sessionStorage = {
	getItem(key) { return sessionValues.has(key) ? sessionValues.get(key) : null; },
	setItem(key, value) { sessionValues.set(key, String(value)); }
};
const source = fs.readFileSync(path.join(root, 'src/components/chat/index.js'), 'utf8');
const compiled = babel.transformSync(source, {
	filename: 'src/components/chat/index.js',
	plugins: [
		[require('@babel/plugin-transform-react-jsx'), { pragma: 'createElement' }],
		require('@babel/plugin-transform-class-properties'),
		require('@babel/plugin-transform-modules-commonjs')
	]
}).code;

function createElement(type, props, ...children) {
	return { type, props: { ...(props || {}), children } };
}

function flattenTree(value) {
	if (Array.isArray(value)) return value.flatMap(flattenTree);
	if (!value || typeof value !== 'object') return [];
	const children = value.props && Array.isArray(value.props.children) ? value.props.children : [];
	return [value, ...children.flatMap(flattenTree)];
}

class Component {
	constructor(props) { this.props = props || {}; this.state = this.state || {}; }
	setState(update, callback) {
		this.state = { ...this.state, ...(typeof update === 'function' ? update(this.state, this.props) : update) };
		if (callback) callback();
	}
}

const styles = new Proxy({}, { get: (_, key) => String(key) });
const moduleValue = { exports: {} };
const localRequire = request => {
	if (request === 'preact') return { createElement, Component };
	if (request === '../../api') return { api: async () => ({}), talkGifUrl: (_, url) => `/proxy?url=${encodeURIComponent(url)}` };
	if (request === '../../i18n') return {
		createTranslator: () => (key, variables) => variables && variables.name ? `${key}:${variables.name}` : key,
		localeFor: () => 'fr-FR'
	};
	if (request === '../../talk-navigation') return {
		isTalkSoundEnabled: () => true,
		setTalkSoundEnabled() {},
		TALK_SOUND_EVENT: 'talk-sound'
	};
	if (request === './style.less') return styles;
	throw new Error(`Unexpected Chat dependency: ${request}`);
};

new Function('require', 'module', 'exports', compiled)(localRequire, moduleValue, moduleValue.exports);
const Chat = moduleValue.exports.default;
const chat = new Chat({ workspaceScope: 'render-test', userLanguage: 'fr', onClose() {}, onOverview() {} });
chat.state = {
	...chat.state,
	loading: false,
	overview: {
		available: true,
		unread: 1,
		accounts: [{
			available: true,
			profileId: 'profile-1',
			label: 'Cloud test',
			server: 'https://cloud.example.test',
			maxMessageLength: 32000,
			fileSharing: true,
			deleteMessages: true,
			conversations: [{ token: 'conversation-1', displayName: 'Test', unreadMessages: 1 }]
		}]
	},
	profileId: 'profile-1',
	token: 'conversation-1',
	messages: [null, { id: 1, actorDisplayName: 'Franck', actorId: 'franck', actorType: 'users', timestamp: 1, message: 'Bonjour' }]
};

const tree = chat.render();
if (!tree || tree.type !== 'div') throw new Error('Chat render did not return its root element');
if (chat.renderMessage(null) !== null) throw new Error('A null Talk message must be ignored safely');
const gifTree = chat.renderMessage({ id: 2, actorDisplayName: 'Marie', actorId: 'marie', timestamp: 2, message: 'https://giphy.com/gifs/funny-cat-AbCd1234' });
const gifImage = flattenTree(gifTree).find(node => node.type === 'img' && String(node.props.src || '').includes('/proxy?url='));
if (!gifImage || !String(gifImage.props.src).includes(encodeURIComponent('https://media.giphy.com/media/AbCd1234/giphy.gif'))) {
	throw new Error('A historical Giphy page URL was not rendered through the secure GIF proxy');
}
chat.state = {
	...chat.state,
	showGif: true,
	gifs: [{ title: 'Chat GIF', thumbnailUrl: 'https://cloud.example.test/index.php/apps/integration_giphy/gif/1', resourceUrl: 'https://giphy.com/gifs/funny-cat-Full123' }],
	gifCursor: 18,
	gifHasMore: true
};
const gifPickerTree = chat.renderGifPicker();
const gifPreview = flattenTree(gifPickerTree).find(node => node.type === 'img' && node.props['data-preview-index'] === '0');
if (!gifPreview) throw new Error('The full Chat GIF picker did not render its preview');
const previewNode = {
	src: gifPreview.props.src,
	previewIndex: '0',
	getAttribute() { return this.previewIndex; },
	setAttribute(name, value) { if (name === 'data-preview-index') this.previewIndex = value; }
};
gifPreview.props.onError({ currentTarget: previewNode });
if (!String(previewNode.src).includes(encodeURIComponent('https://media.giphy.com/media/Full123/giphy.gif'))) {
	throw new Error('The full Chat GIF picker did not fall back from a broken Nextcloud thumbnail');
}
let fullGifLoadMoreCalls = 0;
chat.loadMoreGifs = () => { fullGifLoadMoreCalls += 1; };
const gifGrid = flattenTree(gifPickerTree).find(node => node.props && typeof node.props.onScroll === 'function');
if (!gifGrid) throw new Error('The full Chat GIF picker has no infinite-scroll handler');
gifGrid.props.onScroll({ currentTarget: { scrollHeight: 500, scrollTop: 360, clientHeight: 100 } });
if (fullGifLoadMoreCalls !== 1) throw new Error('Scrolling near the end of the full Chat GIF picker did not request another page');
chat.openCreateConversation();
if (!chat.renderConversationCreator()) throw new Error('The new-conversation form was not rendered');
chat.setState({ creatingConversation: false });

chat.loadMessages = () => {};
chat.state = { ...chat.state, profileId: 'profile-1', token: 'conversation-1', draft: 'Brouillon un', drafts: {}, loadingMessages: true };
chat.messagesController = { aborted: false, abort() { this.aborted = true; } };
const previousController = chat.messagesController;
chat.selectConversation('profile-1', 'conversation-2');
if (!previousController.aborted || chat.state.loadingMessages) throw new Error('Changing conversation did not cancel the previous message request');
if (chat.state.draft !== '') throw new Error('A draft leaked into another conversation');
chat.updateDraft({ currentTarget: { value: 'Brouillon deux' } });
chat.selectConversation('profile-1', 'conversation-1');
if (chat.state.draft !== 'Brouillon un') throw new Error('Conversation draft was not restored');
chat.selectConversation('profile-1', 'conversation-2');
if (chat.state.draft !== 'Brouillon deux') throw new Error('Second conversation draft was not restored');
chat.componentWillUnmount();
const restoredChat = new Chat({ workspaceScope: 'render-test', userLanguage: 'fr', onClose() {}, onOverview() {} });
if (restoredChat.state.profileId !== 'profile-1' || restoredChat.state.token !== 'conversation-2' || restoredChat.state.draft !== 'Brouillon deux') {
	throw new Error('The active conversation draft did not survive a Zimbra tab change');
}

console.log('ChatRenderTest: OK (messages nuls ignorés, création, runtime Preact et brouillons)');
