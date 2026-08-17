import { createElement, Component } from 'preact';
import { MenuItem } from '@zimbra-client/components';

import App from './components/app';
import CloudAttacher from './components/cloud-attacher';
import { registerComposeBridge, unregisterComposeBridge, updateComposeBridge } from './components/cloud-attacher/compose-bridge';
import { api, setApiLanguage, talkGifUrl } from './api';
import { languageFromContext, translate } from './i18n';
import { CLOUD_VIEW_EVENT, setCloudView, TALK_NAVIGATION_EVENT } from './talk-navigation';

const CLOUD_SLUG = 'cloud';
const CHAT_SLUG = 'chat';
const CHAT_LAUNCHER_ID = 'com-nextcloud-connector-chat-launcher';
const CHAT_PANEL_ID = 'com-nextcloud-connector-quick-chat';
const LEGACY_CHAT_TAB_ID = 'com-nextcloud-connector-chat-tab';
const CHAT_LAUNCHER_RUNTIME_KEY = '__comNextcloudConnectorChatLauncherRuntime';
const CHAT_LAUNCHER_RUNTIME_MODE = 'quick-chat-panel-v5';
const CHAT_LAUNCHER_REFRESH_INTERVAL = 20000;
const CHAT_PANEL_MESSAGE_INTERVAL = 10000;

function zimbraHostWindow() {
	const sandboxWindow = globalThis.window;
	try {
		if (sandboxWindow && sandboxWindow.parent && sandboxWindow.parent.document) return sandboxWindow.parent;
	} catch (error) {}
	return sandboxWindow || globalThis;
}

function zimbraHostDocument() {
	try {
		const hostWindow = zimbraHostWindow();
		return hostWindow && hostWindow.document ? hostWindow.document : globalThis.document;
	} catch (error) {
		return globalThis.document;
	}
}

function modernChatPath(location) {
	const pathname = String(location && location.pathname || '');
	const modernIndex = pathname.indexOf('/modern');
	const prefix = modernIndex >= 0 ? pathname.slice(0, modernIndex) : '';
	return `${prefix}/modern/${CLOUD_SLUG}/${CHAT_SLUG}`;
}

class NextcloudErrorBoundary extends Component {
	state = { failed: false };

	componentDidCatch(error) {
		this.setState({ failed: true });
		if (globalThis.console && typeof globalThis.console.error === 'function') {
			globalThis.console.error(`[com_nextcloud_connector] ${translate(this.props.language, 'isolatedConsoleError')}`, error);
		}
	}

	render() {
		if (this.state.failed) {
			return (
				<div role="alert" style="margin:24px;padding:18px;border:1px solid #d9a7a3;border-radius:10px;background:#fff4f3;color:#7b1f19">
					<strong>{translate(this.props.language, 'nextcloudDisplayError')}</strong>
					<div>{translate(this.props.language, 'zimbraStillAvailable')}</div>
				</div>
			);
		}
		return <App workspaceScope={this.props.workspaceScope} userLanguage={this.props.language} initialView={this.props.initialView} />;
	}
}

export default function NextcloudZimlet(context) {
	const { plugins } = context;
	let account = {};
	try {
		account = typeof context.getAccount === 'function' ? (context.getAccount() || {}) : {};
	} catch (error) {}
	const workspaceScope = account.id || account.name || account.email || 'default';
	const language = languageFromContext(context, 'fr');
	setApiLanguage(language);

	function Router() {
		return [
			<NextcloudErrorBoundary key="cloud" path={`/${CLOUD_SLUG}`} workspaceScope={workspaceScope} language={language} initialView="files" />,
			<NextcloudErrorBoundary key="chat" path={`/${CLOUD_SLUG}/${CHAT_SLUG}`} workspaceScope={workspaceScope} language={language} initialView="chat" />
		];
	}

	function AttachmentMenuItem(props) {
		return <CloudAttacher {...props} context={context} />;
	}

	class ComposeInsertionBridge extends Component {
		componentDidMount() { this.bridge = registerComposeBridge(this.props); }
		componentDidUpdate() { updateComposeBridge(this.bridge, this.props); }
		componentWillUnmount() { unregisterComposeBridge(this.bridge); }
		render() { return null; }
	}

	function createChatLauncherRuntime() {
		let active = false;
		let launcher = null;
		let launcherBadge = null;
		let launcherUnread = 0;
		let launcherCloudView = 'files';
		let launcherRefreshTimer = null;
		let launcherRouteTimer = null;
		let panelMessageTimer = null;
		let eventTarget = null;
		let panel = null;
		let panelView = null;
		let panelOpen = false;
		let panelOverview = null;
		let panelProfileId = '';
		let panelToken = '';
		let panelMessages = [];
		let panelComposer = null;
		let panelMessagesNode = null;
		let panelRequestId = 0;
		let panelSending = false;
		let panelMessagesLoading = '';
		let panelLastReadMessage = 0;
		let panelReadTarget = 0;
		let panelGifOpen = false;
		let panelGifQuery = '';
		let panelGifs = [];
		let panelGifLoading = false;
		let panelGifLoadingMore = false;
		let panelGifCursor = null;
		let panelGifHasMore = false;
		let panelGifLoadMoreError = false;
		let panelGifScrollTop = 0;
		let panelGifUnavailable = false;
		let panelGifRequestId = 0;
		let panelGifSearchTimer = null;
		let refreshPromise = null;
		let refreshFailures = 0;
		let refreshAllowedAfter = 0;

		const asArray = value => Array.isArray(value) ? value : [];
		const numeric = value => {
			const parsed = Number(value);
			return Number.isFinite(parsed) ? parsed : 0;
		};
		const clearNode = node => {
			if (!node) return;
			while (node.firstChild) node.removeChild(node.firstChild);
		};
		const buttonStyle = {
			border: '1px solid #c9d9e4', borderRadius: '8px', background: '#fff', color: '#123b57',
			minHeight: '32px', padding: '0 10px', font: '600 12px/1 sans-serif', cursor: 'pointer'
		};
		const makeButton = (document, text, title) => {
			const button = document.createElement('button');
			button.type = 'button';
			button.textContent = text;
			button.title = title || text;
			button.setAttribute('aria-label', title || text);
			Object.assign(button.style, buttonStyle);
			return button;
		};
		const conversationTitle = conversation => String(conversation && conversation.displayName || '').trim() || translate(language, 'conversations');
		const plainMessage = message => {
			let text = String(message && message.message || '');
			const parameters = message && message.messageParameters && typeof message.messageParameters === 'object'
				? message.messageParameters : {};
			Object.keys(parameters).forEach(key => {
				const parameter = parameters[key] || {};
				const replacement = parameter.name || parameter.displayName || parameter.id || key;
				text = text.split(`{${key}}`).join(String(replacement));
			});
			return text;
		};
		const giphyImageUrl = value => {
			const urls = String(value || '').match(/https?:\/\/[^\s<]+/gi) || [];
			for (const raw of urls) {
				const candidate = raw.replace(/[),.;!?]+$/, '');
				try {
					const parsed = new URL(candidate);
					const host = String(parsed.hostname || '').toLowerCase();
					if (parsed.protocol === 'https:' && (host === 'i.giphy.com' || host === 'images.giphy.com' || /^media\d*\.giphy\.com$/.test(host))
						&& (parsed.pathname.startsWith('/media/') || parsed.pathname.startsWith('/gifs/'))) return candidate;
					if (parsed.protocol === 'https:' && (host === 'giphy.com' || host === 'www.giphy.com') && parsed.pathname.startsWith('/gifs/')) {
						const id = parsed.pathname.replace(/\/$/, '').split('-').pop();
						if (/^[A-Za-z0-9]+$/.test(id || '')) return `https://media.giphy.com/media/${id}/giphy.gif`;
					}
				} catch (error) {}
			}
			return '';
		};
		const resetPanelGif = () => {
			globalThis.clearTimeout(panelGifSearchTimer);
			panelGifSearchTimer = null;
			panelGifOpen = false;
			panelGifQuery = '';
			panelGifs = [];
			panelGifLoading = false;
			panelGifLoadingMore = false;
			panelGifCursor = null;
			panelGifHasMore = false;
			panelGifLoadMoreError = false;
			panelGifScrollTop = 0;
			panelGifUnavailable = false;
			panelGifRequestId += 1;
		};
		const gifPreviewUrls = gif => {
			const resourceUrl = String(gif && gif.resourceUrl || '').trim();
			const convertedResource = giphyImageUrl(resourceUrl);
			return [String(gif && gif.thumbnailUrl || '').trim(), convertedResource, resourceUrl]
				.filter((value, index, values) => value && values.indexOf(value) === index);
		};
		const mergeGifItems = (current, incoming) => {
			const byUrl = new Map();
			asArray(current).concat(asArray(incoming)).forEach(gif => {
				const resourceUrl = String(gif && gif.resourceUrl || '').trim();
				if (resourceUrl && !byUrl.has(resourceUrl)) byUrl.set(resourceUrl, gif);
			});
			return Array.from(byUrl.values());
		};
		const overviewUnread = overview => {
			const declared = Math.max(0, numeric(overview && overview.unread));
			const calculated = asArray(overview && overview.accounts).reduce((accountTotal, account) => accountTotal
				+ asArray(account && account.conversations).reduce((total, conversation) => total + Math.max(0, numeric(conversation && conversation.unreadMessages)), 0), 0);
			return Math.max(declared, calculated);
		};
		const timeLabel = timestamp => {
			if (!numeric(timestamp)) return '';
			try {
				return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(new Date(numeric(timestamp) * 1000));
			} catch (error) { return ''; }
		};
		const availableAccounts = () => asArray(panelOverview && panelOverview.accounts).filter(account => account && account.available);
		const currentAccount = () => availableAccounts().find(account => String(account.profileId || '') === String(panelProfileId));
		const currentConversation = () => {
			const account = currentAccount();
			return asArray(account && account.conversations).find(conversation => String(conversation.token || '') === String(panelToken));
		};

		const cleanupLegacyNavigation = () => {
			const document = zimbraHostDocument();
			if (!document) return;
			if (typeof document.querySelectorAll === 'function') {
				Array.from(document.querySelectorAll(`[id="${LEGACY_CHAT_TAB_ID}"]`)).forEach(element => {
					if (element && element.parentNode) element.parentNode.removeChild(element);
				});
				return;
			}
			const element = document.getElementById && document.getElementById(LEGACY_CHAT_TAB_ID);
			if (element && element.parentNode) element.parentNode.removeChild(element);
		};
		const onFullChatRoute = () => {
			const hostWindow = zimbraHostWindow();
			const location = hostWindow && hostWindow.location;
			const pathname = String(location && location.pathname || '').replace(/\/+$/, '');
			return pathname.endsWith(`/${CLOUD_SLUG}/${CHAT_SLUG}`)
				|| (pathname.endsWith(`/${CLOUD_SLUG}`) && launcherCloudView === 'chat');
		};

		const render = () => {
			if (!launcher) return;
			// L’accès au Chat ne doit pas disparaître lorsqu’un ancien serveur renvoie
			// momentanément un profil incomplet. La page Chat gère elle-même l’état
			// de configuration et reste donc toujours accessible.
			launcher.style.display = !onFullChatRoute() ? 'inline-flex' : 'none';
			const count = launcherUnread || 0;
			const launcherLabel = count > 0
				? `${translate(language, 'openChatLauncher')} · ${translate(language, 'unreadCount', { count })}`
				: translate(language, 'openChatLauncher');
			launcher.title = launcherLabel;
			launcher.setAttribute('aria-label', launcherLabel);
			if (launcherBadge) {
				launcherBadge.textContent = count > 99 ? '99+' : String(count);
				launcherBadge.style.display = count > 0 ? 'inline-flex' : 'none';
			}
		};

		const apply = (visible, unread) => {
			launcherUnread = Math.max(0, Number(unread || 0));
			render();
		};

		const openFullChat = () => {
			setCloudView(workspaceScope, 'chat');
			closePanel();
			const hostWindow = zimbraHostWindow();
			const location = hostWindow && hostWindow.location;
			const target = modernChatPath(location);
			if (location && typeof location.assign === 'function') location.assign(target);
			else if (location) location.href = target;
		};

		const friendlyTalkError = error => {
			const status = Number(error && error.status || 0);
			const message = String(error && error.message || '').trim();
			if (status === 502 || status === 503 || status === 504 || /^HTTP 5\d\d$/i.test(message)) {
				return translate(language, 'talkTemporaryUnavailable');
			}
			if (!message || message.length > 300 || /<(?:!doctype|html|head|body|title|h[1-6]|p|br)\b/i.test(message)) {
				return translate(language, 'talkError');
			}
			return message;
		};

		const renderPanelStatus = (message, retry) => {
			if (!panelView) return;
			clearNode(panelView);
			panelComposer = null;
			panelMessagesNode = null;
			const document = zimbraHostDocument();
			const status = document.createElement('div');
			Object.assign(status.style, { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '12px', padding: '28px 20px', color: '#476575', textAlign: 'center', font: '13px/1.45 sans-serif' });
			const copy = document.createElement('div');
			copy.textContent = message;
			status.appendChild(copy);
			if (typeof retry === 'function') {
				const retryButton = makeButton(document, translate(language, 'retry'));
				retryButton.addEventListener('click', retry);
				status.appendChild(retryButton);
			}
			panelView.appendChild(status);
		};

		const markPanelRead = async last => {
			const account = currentAccount();
			if (account && account.readMarker === false) return;
			if (!last || last <= panelLastReadMessage || last <= panelReadTarget) return;
			panelReadTarget = last;
			try {
				await api('/api/talk/read', { method: 'POST', profileId: panelProfileId, json: { token: panelToken, lastReadMessage: last } });
				panelLastReadMessage = Math.max(panelLastReadMessage, last);
				const conversation = currentConversation();
				const clearedUnread = Math.max(0, numeric(conversation && conversation.unreadMessages));
				if (conversation && clearedUnread > 0) {
					conversation.unreadMessages = 0;
					launcherUnread = Math.max(0, launcherUnread - clearedUnread);
					render();
				}
			} catch (error) {
			} finally {
				if (panelReadTarget === last) panelReadTarget = 0;
			}
		};

		const renderPanelGifPicker = document => {
			if (!panelGifOpen) return null;
			const picker = document.createElement('div');
			Object.assign(picker.style, {
				position: 'absolute', left: '8px', right: '8px', bottom: 'calc(100% + 6px)', zIndex: '3',
				maxHeight: '360px', overflow: 'hidden', border: '1px solid #c9d9e4', borderRadius: '12px',
				background: '#fff', boxShadow: '0 16px 42px rgba(20,65,88,.25)'
			});
			const searchRow = document.createElement('div');
			Object.assign(searchRow.style, { display: 'grid', gridTemplateColumns: 'minmax(0,1fr) auto 34px', gap: '6px', padding: '9px' });
			const search = document.createElement('input');
			search.value = panelGifQuery;
			search.placeholder = translate(language, 'searchGifs');
			search.setAttribute('aria-label', translate(language, 'searchGifs'));
			Object.assign(search.style, { minWidth: '0', border: '1px solid #b9cfdb', borderRadius: '8px', padding: '7px 9px', color: '#17384a', font: '12px sans-serif' });
			search.addEventListener('input', event => {
				panelGifQuery = String(event && event.currentTarget && event.currentTarget.value || search.value || '');
				panelGifRequestId += 1;
				panelGifHasMore = false;
				panelGifLoadMoreError = false;
				globalThis.clearTimeout(panelGifSearchTimer);
				panelGifSearchTimer = globalThis.setTimeout(() => {
					panelGifSearchTimer = null;
					if (panelGifOpen) loadPanelGifs(panelGifQuery);
				}, 450);
			});
			search.addEventListener('keydown', event => {
				if (event && event.key === 'Enter') {
					event.preventDefault();
					globalThis.clearTimeout(panelGifSearchTimer);
					panelGifSearchTimer = null;
					panelGifQuery = String(event.currentTarget && event.currentTarget.value || search.value || '');
					loadPanelGifs(panelGifQuery);
				}
			});
			const searchButton = makeButton(document, '⌕', translate(language, 'searchGifs'));
			searchButton.addEventListener('click', () => {
				globalThis.clearTimeout(panelGifSearchTimer);
				panelGifSearchTimer = null;
				panelGifQuery = String(search.value || '');
				loadPanelGifs(panelGifQuery);
			});
			const closeButton = makeButton(document, '×', translate(language, 'close'));
			closeButton.style.minWidth = '34px';
			closeButton.addEventListener('click', () => { panelGifOpen = false; renderPanelMessages(); });
			searchRow.appendChild(search);
			searchRow.appendChild(searchButton);
			searchRow.appendChild(closeButton);
			picker.appendChild(searchRow);

			const privacy = document.createElement('small');
			privacy.textContent = translate(language, 'giphyPrivacy');
			Object.assign(privacy.style, { display: 'block', padding: '0 10px 7px', color: '#6a808d', font: '10px/1.35 sans-serif' });
			picker.appendChild(privacy);
			if (panelGifLoading || panelGifUnavailable || !panelGifs.length) {
				const status = document.createElement('div');
				status.textContent = panelGifLoading ? translate(language, 'loading')
					: panelGifUnavailable ? translate(language, 'gifsUnavailable') : translate(language, 'noGif');
				Object.assign(status.style, { padding: '24px 12px', color: '#6a808d', textAlign: 'center', font: '12px sans-serif' });
				picker.appendChild(status);
				return picker;
			}
			const grid = document.createElement('div');
			Object.assign(grid.style, { display: 'grid', gridTemplateColumns: 'repeat(3,minmax(0,1fr))', gap: '6px', maxHeight: '270px', overflowY: 'auto', padding: '7px 9px 10px' });
			grid.addEventListener('scroll', event => {
				const element = event && event.currentTarget || grid;
				panelGifScrollTop = numeric(element.scrollTop);
				if (numeric(element.scrollHeight) - numeric(element.scrollTop) - numeric(element.clientHeight) <= 90) {
					loadPanelGifs(panelGifQuery, true);
				}
			});
			panelGifs.forEach(gif => {
				const resourceUrl = String(gif && gif.resourceUrl || '').trim();
				if (!resourceUrl) return;
				const result = document.createElement('button');
				result.type = 'button';
				result.title = String(gif.title || translate(language, 'gif'));
				result.setAttribute('aria-label', result.title);
				Object.assign(result.style, { border: '0', padding: '0', aspectRatio: '4/3', overflow: 'hidden', borderRadius: '8px', background: '#edf2f6', cursor: 'pointer' });
				const previews = gifPreviewUrls(gif);
				const image = document.createElement('img');
				image.src = talkGifUrl(panelProfileId, previews[0] || resourceUrl);
				image.alt = result.title;
				image.loading = 'lazy';
				image.setAttribute('data-preview-index', '0');
				Object.assign(image.style, { display: 'block', width: '100%', height: '100%', objectFit: 'cover' });
				image.addEventListener('error', () => {
					const nextIndex = numeric(image.getAttribute('data-preview-index')) + 1;
					if (nextIndex >= previews.length) return;
					image.setAttribute('data-preview-index', String(nextIndex));
					image.src = talkGifUrl(panelProfileId, previews[nextIndex]);
				});
				result.appendChild(image);
				result.addEventListener('click', () => sendPanelGif(gif));
				grid.appendChild(result);
			});
			if (panelGifLoadingMore || panelGifLoadMoreError) {
				const more = document.createElement(panelGifLoadMoreError ? 'button' : 'div');
				if (panelGifLoadMoreError) {
					more.type = 'button';
					more.addEventListener('click', () => loadPanelGifs(panelGifQuery, true));
				}
				more.textContent = translate(language, panelGifLoadMoreError ? 'retry' : 'loading');
				Object.assign(more.style, {
					gridColumn: '1 / -1', minHeight: '34px', border: panelGifLoadMoreError ? '1px solid #c9d9e4' : '0',
					borderRadius: '8px', background: panelGifLoadMoreError ? '#f4f9fc' : 'transparent', color: '#476575',
					font: '600 11px sans-serif', cursor: panelGifLoadMoreError ? 'pointer' : 'default'
				});
				grid.appendChild(more);
			}
			picker.appendChild(grid);
			globalThis.setTimeout(() => { grid.scrollTop = panelGifScrollTop; }, 0);
			return picker;
		};

		const renderPanelMessages = () => {
			if (!panelView || !panelOpen) return;
			const document = zimbraHostDocument();
			const conversation = currentConversation();
			const account = currentAccount();
			const preservedDraft = panelComposer ? String(panelComposer.value || '') : '';
			clearNode(panelView);

			const toolbar = document.createElement('div');
			Object.assign(toolbar.style, { display: 'flex', alignItems: 'center', gap: '8px', padding: '9px 10px', borderBottom: '1px solid #dbe7ee', background: '#f5f9fb' });
			const back = makeButton(document, '←', translate(language, 'back'));
			back.style.minWidth = '34px';
			back.addEventListener('click', () => {
				resetPanelGif();
				panelProfileId = '';
				panelToken = '';
				panelMessages = [];
				panelMessagesLoading = '';
				panelLastReadMessage = 0;
				panelReadTarget = 0;
				renderConversationList();
			});
			const title = document.createElement('div');
			title.textContent = conversationTitle(conversation);
			Object.assign(title.style, { flex: '1', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: '#123b57', font: '700 13px/1.2 sans-serif' });
			const refreshButton = makeButton(document, '↻', translate(language, 'refreshChat'));
			refreshButton.style.minWidth = '34px';
			refreshButton.addEventListener('click', () => loadPanelMessages(true));
			toolbar.appendChild(back);
			toolbar.appendChild(title);
			toolbar.appendChild(refreshButton);
			panelView.appendChild(toolbar);

			const messages = document.createElement('div');
			Object.assign(messages.style, { flex: '1', minHeight: '0', overflowY: 'auto', padding: '12px', background: 'linear-gradient(145deg,#f8fbfd,#edf7f3)' });
			if (!panelMessages.length) {
				const empty = document.createElement('div');
				empty.textContent = translate(language, 'noMessages');
				Object.assign(empty.style, { padding: '30px 10px', color: '#6a808d', textAlign: 'center', font: '12px sans-serif' });
				messages.appendChild(empty);
			}
			panelMessages.forEach(message => {
				if (!message || typeof message !== 'object') return;
				const own = Boolean(account && String(message.actorId || '') === String(account.username || ''));
				const bubble = document.createElement('div');
				Object.assign(bubble.style, {
					width: 'fit-content', maxWidth: '84%', margin: own ? '0 0 9px auto' : '0 auto 9px 0', padding: '8px 10px',
					border: `1px solid ${own ? '#a7dbcf' : '#d5e2e9'}`, borderRadius: '11px', background: own ? '#ddf4ed' : '#fff',
					boxShadow: '0 2px 8px rgba(24,68,91,.06)', color: '#17384a', font: '12px/1.38 sans-serif', overflowWrap: 'anywhere'
				});
				const meta = document.createElement('div');
				const actor = String(message.actorDisplayName || message.actorId || translate(language, 'unknown'));
				meta.textContent = `${actor}${timeLabel(message.timestamp) ? ` · ${timeLabel(message.timestamp)}` : ''}`;
				Object.assign(meta.style, { marginBottom: '3px', color: '#457086', fontSize: '10px', fontWeight: '700' });
				const messageText = plainMessage(message);
				const gifUrl = giphyImageUrl(messageText);
				const text = document.createElement('div');
				text.textContent = gifUrl ? messageText.replace(/https?:\/\/[^\s<]+/gi, match => giphyImageUrl(match) ? '' : match).trim() : messageText;
				bubble.appendChild(meta);
				if (text.textContent) bubble.appendChild(text);
				if (gifUrl) {
					const image = document.createElement('img');
					image.src = talkGifUrl(panelProfileId, gifUrl);
					image.alt = translate(language, 'gif');
					image.loading = 'lazy';
					Object.assign(image.style, { display: 'block', width: 'auto', maxWidth: '100%', maxHeight: '240px', marginTop: '5px', borderRadius: '9px', objectFit: 'contain' });
					image.addEventListener('error', () => { image.style.display = 'none'; });
					bubble.appendChild(image);
				}
				messages.appendChild(bubble);
			});
			panelView.appendChild(messages);
			panelMessagesNode = messages;

			const composerArea = document.createElement('div');
			Object.assign(composerArea.style, { position: 'relative', borderTop: '1px solid #dbe7ee', background: '#fff' });
			const picker = renderPanelGifPicker(document);
			if (picker) composerArea.appendChild(picker);
			const form = document.createElement('form');
			Object.assign(form.style, { display: 'flex', gap: '8px', padding: '10px' });
			const gifButton = makeButton(document, 'GIF', translate(language, 'gif'));
			gifButton.style.alignSelf = 'stretch';
			gifButton.addEventListener('click', () => {
				panelGifOpen = !panelGifOpen;
				renderPanelMessages();
				if (panelGifOpen && !panelGifs.length && !panelGifLoading) loadPanelGifs('');
			});
			const composer = document.createElement('textarea');
			composer.value = preservedDraft;
			composer.placeholder = translate(language, 'writeMessage');
			composer.rows = 2;
			Object.assign(composer.style, { flex: '1', minWidth: '0', resize: 'none', border: '1px solid #b9cfdb', borderRadius: '8px', padding: '8px', color: '#17384a', font: '12px/1.35 sans-serif' });
			const send = makeButton(document, translate(language, 'send'));
			send.style.alignSelf = 'stretch';
			send.style.background = '#158ca0';
			send.style.color = '#fff';
			form.addEventListener('submit', sendPanelMessage);
			composer.addEventListener('keydown', event => {
				if (event && event.key === 'Enter' && !event.shiftKey) {
					event.preventDefault();
					sendPanelMessage(event);
				}
			});
			form.appendChild(gifButton);
			form.appendChild(composer);
			form.appendChild(send);
			composerArea.appendChild(form);
			panelView.appendChild(composerArea);
			panelComposer = composer;
			globalThis.setTimeout(() => { if (panelMessagesNode) panelMessagesNode.scrollTop = panelMessagesNode.scrollHeight; }, 0);
		};

		const renderConversationList = () => {
			if (!panelView || !panelOpen) return;
			const document = zimbraHostDocument();
			clearNode(panelView);
			panelComposer = null;
			panelMessagesNode = null;
			const scroller = document.createElement('div');
			Object.assign(scroller.style, { flex: '1', minHeight: '0', overflowY: 'auto', padding: '8px', background: '#f7fafc' });
			const accounts = availableAccounts();
			let count = 0;
			accounts.forEach(account => {
				const heading = document.createElement('div');
				heading.textContent = String(account.label || account.server || translate(language, 'chat'));
				Object.assign(heading.style, { padding: '8px 7px 5px', color: '#567383', font: '700 10px/1 sans-serif', textTransform: 'uppercase', letterSpacing: '.04em' });
				scroller.appendChild(heading);
				asArray(account.conversations).forEach(conversation => {
					if (!conversation || !conversation.token) return;
					count += 1;
					const item = document.createElement('button');
					item.type = 'button';
					Object.assign(item.style, {
						display: 'grid', gridTemplateColumns: '38px minmax(0,1fr) auto', alignItems: 'center', gap: '9px', width: '100%',
						marginBottom: '5px', padding: '8px', border: '1px solid #d7e5ec', borderRadius: '10px', background: '#fff', color: '#17384a', textAlign: 'left', cursor: 'pointer'
					});
					const avatar = document.createElement('span');
					const name = conversationTitle(conversation);
					avatar.textContent = name.trim().slice(0, 1).toUpperCase() || '?';
					Object.assign(avatar.style, { display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: '36px', height: '36px', borderRadius: '50%', background: '#0d788e', color: '#fff', font: '700 13px sans-serif' });
					const copy = document.createElement('span');
					copy.style.minWidth = '0';
					const nameNode = document.createElement('span');
					nameNode.textContent = name;
					Object.assign(nameNode.style, { display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', font: '700 12px/1.3 sans-serif' });
					const preview = document.createElement('span');
					preview.textContent = plainMessage(conversation.lastMessage) || translate(language, 'noMessages');
					Object.assign(preview.style, { display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: '#64808f', font: '10px/1.35 sans-serif' });
					copy.appendChild(nameNode);
					copy.appendChild(preview);
					const unread = document.createElement('span');
					const unreadCount = Math.max(0, numeric(conversation.unreadMessages));
					unread.textContent = unreadCount > 99 ? '99+' : String(unreadCount || '');
					Object.assign(unread.style, { display: unreadCount ? 'inline-flex' : 'none', alignItems: 'center', justifyContent: 'center', minWidth: '22px', height: '22px', padding: '0 5px', borderRadius: '999px', background: '#e34f5f', color: '#fff', font: '700 10px sans-serif' });
					item.appendChild(avatar);
					item.appendChild(copy);
					item.appendChild(unread);
					item.addEventListener('click', () => selectPanelConversation(account.profileId, conversation.token));
					scroller.appendChild(item);
				});
			});
			if (!count) {
				const empty = document.createElement('div');
				empty.textContent = translate(language, 'noConversations');
				Object.assign(empty.style, { padding: '32px 12px', color: '#6a808d', textAlign: 'center', font: '12px sans-serif' });
				scroller.appendChild(empty);
			}
			panelView.appendChild(scroller);
		};

		const loadPanelMessages = async showLoading => {
			if (!panelOpen || !panelProfileId || !panelToken) return;
			const profileId = panelProfileId;
			const token = panelToken;
			const loadingPrefix = `${profileId}:${token}:`;
			if (panelMessagesLoading.startsWith(loadingPrefix)) return;
			const requestId = ++panelRequestId;
			const loadingKey = `${loadingPrefix}${requestId}`;
			panelMessagesLoading = loadingKey;
			if (showLoading) renderPanelStatus(translate(language, 'loadingTalk'));
			try {
				const result = await api(`/api/talk/messages?token=${encodeURIComponent(token)}&lastKnownMessageId=0&limit=50&future=false`, { profileId });
				if (!active || !panelOpen || requestId !== panelRequestId || profileId !== panelProfileId || token !== panelToken) return;
				const nextMessages = asArray(result && result.items).filter(message => message && typeof message === 'object')
					.sort((left, right) => numeric(left.id) - numeric(right.id));
				const previousSignature = panelMessages.map(message => `${numeric(message.id)}:${String(message.message || '')}`).join('|');
				const nextSignature = nextMessages.map(message => `${numeric(message.id)}:${String(message.message || '')}`).join('|');
				panelMessages = nextMessages;
				if (showLoading || previousSignature !== nextSignature) renderPanelMessages();
				const last = panelMessages.length ? numeric(panelMessages[panelMessages.length - 1] && panelMessages[panelMessages.length - 1].id) : 0;
				markPanelRead(last);
			} catch (error) {
				if (showLoading && requestId === panelRequestId) {
					renderPanelStatus(friendlyTalkError(error), () => loadPanelMessages(true));
				}
			} finally {
				if (panelMessagesLoading === loadingKey) panelMessagesLoading = '';
			}
		};

		const selectPanelConversation = (profileId, token) => {
			resetPanelGif();
			panelProfileId = String(profileId || '');
			panelToken = String(token || '');
			panelMessages = [];
			panelLastReadMessage = 0;
			panelReadTarget = 0;
			loadPanelMessages(true);
		};

		async function loadPanelGifs(query, append = false) {
			if (!panelOpen || !panelProfileId || !panelToken) return;
			if (append && (panelGifLoading || panelGifLoadingMore || !panelGifHasMore)) return;
			const profileId = panelProfileId;
			const token = panelToken;
			const requestId = ++panelGifRequestId;
			panelGifQuery = String(query || '').trim();
			const cursor = append ? numeric(panelGifCursor) : 0;
			panelGifLoading = !append;
			panelGifLoadingMore = append;
			panelGifLoadMoreError = false;
			if (!append) {
				panelGifCursor = null;
				panelGifHasMore = false;
				panelGifScrollTop = 0;
			}
			panelGifUnavailable = false;
			renderPanelMessages();
			try {
				const result = await api(`/api/talk/gifs?q=${encodeURIComponent(panelGifQuery)}&limit=18&cursor=${cursor}`, { profileId });
				if (!active || !panelOpen || !panelGifOpen || requestId !== panelGifRequestId || profileId !== panelProfileId || token !== panelToken) return;
				const page = asArray(result && result.items).filter(gif => gif && gif.resourceUrl);
				const nextCursor = result && result.cursor;
				const hasCursor = nextCursor !== null && nextCursor !== undefined && String(nextCursor) !== ''
					&& numeric(nextCursor) > cursor;
				panelGifs = append ? mergeGifItems(panelGifs, page) : mergeGifItems([], page);
				panelGifCursor = hasCursor ? nextCursor : null;
				panelGifHasMore = Boolean(page.length && hasCursor);
				panelGifUnavailable = false;
			} catch (error) {
				if (requestId !== panelGifRequestId) return;
				if (append) panelGifLoadMoreError = true;
				else {
					panelGifs = [];
					panelGifHasMore = false;
					panelGifUnavailable = true;
				}
			} finally {
				if (requestId === panelGifRequestId) {
					panelGifLoading = false;
					panelGifLoadingMore = false;
					if (panelOpen && panelGifOpen) renderPanelMessages();
				}
			}
		}

		function sendPanelGif(gif) {
			const url = String(gif && gif.resourceUrl || '').trim();
			if (!url || !panelComposer) return;
			panelComposer.value = url;
			panelGifOpen = false;
			renderPanelMessages();
			sendPanelMessage();
		}

		async function sendPanelMessage(event) {
			if (event && typeof event.preventDefault === 'function') event.preventDefault();
			const message = String(panelComposer && panelComposer.value || '').trim();
			if (!message || panelSending || !panelProfileId || !panelToken) return;
			panelSending = true;
			if (panelComposer) panelComposer.disabled = true;
			try {
				await api('/api/talk/message', { method: 'POST', profileId: panelProfileId, json: { token: panelToken, message, replyTo: 0 } });
				if (panelComposer) panelComposer.value = '';
				await loadPanelMessages(false);
				refresh();
			} catch (error) {
				renderPanelStatus(friendlyTalkError(error), () => loadPanelMessages(true));
			} finally {
				panelSending = false;
				if (panelComposer) panelComposer.disabled = false;
			}
		}

		function closePanel() {
			panelOpen = false;
			resetPanelGif();
			panelRequestId += 1;
			panelMessagesLoading = '';
			panelReadTarget = 0;
			globalThis.clearInterval(panelMessageTimer);
			panelMessageTimer = null;
			if (panel && panel.parentNode) panel.parentNode.removeChild(panel);
			panel = null;
			panelView = null;
			panelComposer = null;
			panelMessagesNode = null;
		}

		const createPanel = () => {
			const document = zimbraHostDocument();
			if (!document || !document.body || typeof document.createElement !== 'function') return;
			const previous = document.getElementById && document.getElementById(CHAT_PANEL_ID);
			if (previous && previous !== panel && previous.parentNode) previous.parentNode.removeChild(previous);
			if (panel && panel.parentNode) return;
			const container = document.createElement('section');
			container.id = CHAT_PANEL_ID;
			container.setAttribute('role', 'dialog');
			container.setAttribute('aria-label', translate(language, 'openChatLauncher'));
			Object.assign(container.style, {
				position: 'fixed', right: '18px', bottom: '78px', zIndex: '9997', display: 'flex', flexDirection: 'column',
				width: 'min(420px,calc(100vw - 24px))', height: 'min(620px,calc(100vh - 110px))', minHeight: '360px',
				border: '1px solid #b9cfdb', borderRadius: '14px', background: '#fff', overflow: 'hidden',
				boxShadow: '0 18px 52px rgba(15,55,77,.34)', color: '#17384a'
			});
			const header = document.createElement('header');
			Object.assign(header.style, { display: 'flex', alignItems: 'center', gap: '8px', padding: '10px 11px', background: 'linear-gradient(135deg,#123b57,#176f7e)', color: '#fff' });
			const title = document.createElement('strong');
			title.textContent = `💬 ${translate(language, 'chat')}`;
			Object.assign(title.style, { flex: '1', font: '700 14px/1 sans-serif' });
			const full = makeButton(document, '↗', `${translate(language, 'open')} ${translate(language, 'chat')}`);
			const close = makeButton(document, '×', translate(language, 'close'));
			Object.assign(full.style, { minWidth: '34px', background: 'rgba(255,255,255,.13)', color: '#fff', borderColor: 'rgba(255,255,255,.45)' });
			Object.assign(close.style, { minWidth: '34px', background: 'rgba(255,255,255,.13)', color: '#fff', borderColor: 'rgba(255,255,255,.45)', fontSize: '18px' });
			full.addEventListener('click', openFullChat);
			close.addEventListener('click', closePanel);
			header.appendChild(title);
			header.appendChild(full);
			header.appendChild(close);
			const view = document.createElement('div');
			Object.assign(view.style, { display: 'flex', flexDirection: 'column', flex: '1', minHeight: '0' });
			container.appendChild(header);
			container.appendChild(view);
			document.body.appendChild(container);
			panel = container;
			panelView = view;
			renderPanelStatus(translate(language, 'loadingTalk'));
		};

		const openPanel = () => {
			if (panelOpen) { closePanel(); return; }
			panelOpen = true;
			resetPanelGif();
			panelProfileId = '';
			panelToken = '';
			panelMessages = [];
			panelMessagesLoading = '';
			panelLastReadMessage = 0;
			panelReadTarget = 0;
			createPanel();
			if (panelOverview) renderConversationList();
			refresh(true, true);
			globalThis.clearInterval(panelMessageTimer);
			panelMessageTimer = globalThis.setInterval(() => {
				if (panelOpen && panelProfileId && panelToken) loadPanelMessages(false);
			}, CHAT_PANEL_MESSAGE_INTERVAL);
		};

		const install = () => {
			const document = zimbraHostDocument();
			if (!document || !document.body || typeof document.createElement !== 'function') return;
			cleanupLegacyNavigation();
			const previous = document.getElementById && document.getElementById(CHAT_LAUNCHER_ID);
			if (previous && previous !== launcher && previous.parentNode) previous.parentNode.removeChild(previous);
			if (launcher && launcher.parentNode && previous === launcher) { render(); return; }
			if (launcher && launcher.parentNode) launcher.parentNode.removeChild(launcher);
			launcher = null;
			launcherBadge = null;

			const button = document.createElement('button');
			button.id = CHAT_LAUNCHER_ID;
			button.type = 'button';
			button.title = translate(language, 'openChatLauncher');
			button.setAttribute('aria-label', translate(language, 'openChatLauncher'));
			Object.assign(button.style, {
				position: 'fixed', right: '22px', bottom: '22px', zIndex: '9996', display: 'inline-flex',
				alignItems: 'center', justifyContent: 'center', width: '46px', height: '46px', padding: '0', border: '1px solid rgba(255,255,255,.72)',
				borderRadius: '999px', background: 'linear-gradient(135deg,#1778b7,#269b78)', color: '#fff',
				boxShadow: '0 10px 30px rgba(20,74,101,.34)', font: '700 18px/1 sans-serif', cursor: 'pointer'
			});

			const icon = document.createElement('span');
			icon.textContent = '💬';
			icon.setAttribute('aria-hidden', 'true');
			const badge = document.createElement('span');
			badge.setAttribute('aria-hidden', 'true');
			Object.assign(badge.style, {
				position: 'absolute', top: '-5px', right: '-5px', display: 'none', minWidth: '20px', height: '20px', alignItems: 'center', justifyContent: 'center',
				padding: '0 5px', borderRadius: '999px', background: '#e34f5f', color: '#fff', fontSize: '10px'
			});
			button.appendChild(icon);
			button.appendChild(badge);
			button.addEventListener('click', openPanel);
			document.body.appendChild(button);
			launcher = button;
			launcherBadge = badge;
			render();
		};

		const updateFromEvent = event => {
			const detail = event && event.detail || {};
			if (detail.scope && String(detail.scope) !== String(workspaceScope)) return;
			apply(Boolean(detail.enabled), detail.unread);
		};

		const updateCloudView = event => {
			const detail = event && event.detail || {};
			if (detail.scope && String(detail.scope) !== String(workspaceScope)) return;
			launcherCloudView = detail.view === 'chat' ? 'chat' : 'files';
			if (launcherCloudView === 'chat') closePanel();
			render();
		};

		const refresh = (panelRequested = false, force = false) => {
			// Le grand espace Chat gère déjà son propre chargement et son propre
			// polling. Le lanceur n’effectue donc aucun appel concurrent sur sa route.
			if (!panelRequested && onFullChatRoute()) return Promise.resolve();
			if (!force && Date.now() < refreshAllowedAfter) {
				if (panelOpen && panelRequested && !panelOverview) {
					renderPanelStatus(translate(language, 'talkTemporaryUnavailable'), () => refresh(true, true));
				}
				return Promise.resolve();
			}
			if (refreshPromise) {
				return refreshPromise.then(() => {
					if (active && panelOpen && panelRequested && !panelToken && panelOverview) renderConversationList();
				});
			}
			refreshPromise = (async () => {
				try {
					const overview = await api('/api/talk/overview', { profileId: '' });
					if (!active) return;
					panelOverview = overview;
					refreshFailures = 0;
					refreshAllowedAfter = 0;
					apply(true, overviewUnread(overview));
					if (panelOpen && (panelRequested || !panelToken)) renderConversationList();
				} catch (error) {
					if (!active) return;
					refreshFailures += 1;
					refreshAllowedAfter = Date.now() + Math.min(120000, refreshFailures * 30000);
					apply(true, 0);
					if (panelOpen && (panelRequested || !panelOverview)) {
						const message = error && error.status === 409
							? translate(language, 'accountUnavailable') : friendlyTalkError(error);
						renderPanelStatus(message, () => refresh(true, true));
					}
				}
			})().finally(() => { refreshPromise = null; });
			return refreshPromise;
		};

		return {
			scope: String(workspaceScope),
			mode: CHAT_LAUNCHER_RUNTIME_MODE,
			start() {
				if (active) return;
				active = true;
				install();
				refresh();
				launcherRefreshTimer = globalThis.setInterval(refresh, CHAT_LAUNCHER_REFRESH_INTERVAL);
				launcherRouteTimer = globalThis.setInterval(() => {
					install();
					if (panelOpen && (!panel || !panel.parentNode)) createPanel();
					render();
				}, 1000);
				eventTarget = globalThis.window || globalThis;
				if (eventTarget && typeof eventTarget.addEventListener === 'function') {
					eventTarget.addEventListener(TALK_NAVIGATION_EVENT, updateFromEvent);
					eventTarget.addEventListener(CLOUD_VIEW_EVENT, updateCloudView);
				}
			},
			ensure() {
				install();
				if (panelOpen && (!panel || !panel.parentNode)) createPanel();
				render();
			},
			stop() {
				active = false;
				globalThis.clearInterval(launcherRefreshTimer);
				globalThis.clearInterval(launcherRouteTimer);
				closePanel();
				if (eventTarget && typeof eventTarget.removeEventListener === 'function') {
					eventTarget.removeEventListener(TALK_NAVIGATION_EVENT, updateFromEvent);
					eventTarget.removeEventListener(CLOUD_VIEW_EVENT, updateCloudView);
				}
				if (launcher && launcher.parentNode) launcher.parentNode.removeChild(launcher);
				cleanupLegacyNavigation();
				launcher = null;
				launcherBadge = null;
			}
		};
	}

	function ensureChatLauncherRuntime() {
		const runtimeHost = zimbraHostWindow() || globalThis;
		let runtime = runtimeHost[CHAT_LAUNCHER_RUNTIME_KEY];
		if (!runtime || runtime.scope !== String(workspaceScope) || runtime.mode !== CHAT_LAUNCHER_RUNTIME_MODE) {
			if (runtime && typeof runtime.stop === 'function') runtime.stop();
			runtime = createChatLauncherRuntime();
			runtimeHost[CHAT_LAUNCHER_RUNTIME_KEY] = runtime;
			runtime.start();
		} else {
			runtime.ensure();
		}
	}

	function CloudNavigation() {
		// Zimbra 10.1.20 appelle ce point d’extension comme une fonction directe.
		// Le runtime Chat est également vérifié ici si Zimbra reconstruit le menu.
		ensureChatLauncherRuntime();
		return (
			<MenuItem responsive icon="cloud" href={`/${CLOUD_SLUG}`} title={translate(language, 'cloud')}
				aria-label={translate(language, 'cloud')} onClick={() => setCloudView(workspaceScope, 'files')} />
		);
	}

	// Certaines révisions de Zimbra Modern enregistrent correctement les slots
	// sans rappeler le hook init() de la Zimlet. Démarrer le runtime directement
	// pendant la création du paquet garantit le bouton flottant, sans injecter ni
	// modifier aucun onglet de la barre principale Zimbra.
	ensureChatLauncherRuntime();

	return {
		init() {
			// Zimbra owns the router context. Wrapping this slot in another context
			// provider can replace it and freeze navigation outside the Zimlet.
			plugins.register('slot::vertical-menu-item', CloudNavigation);
			plugins.register('slot::routes', Router);
			plugins.register('slot::compose-footer-right-btn', ComposeInsertionBridge);
			plugins.register('slot::compose-attachment-action-menu', AttachmentMenuItem);
			ensureChatLauncherRuntime();
		}
	};
}
