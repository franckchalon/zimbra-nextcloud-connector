import { createElement, Component } from 'preact';
import { api, talkGifUrl } from '../../api';
import { createTranslator, localeFor } from '../../i18n';
import { isTalkSoundEnabled, setTalkSoundEnabled, TALK_SOUND_EVENT } from '../../talk-navigation';
import style from './style.less';

const REACTIONS = ['👍', '❤️', '😂', '🎉', '😮', '😢'];
const cache = new Map();

function sessionKey(scope) {
	return `com_nextcloud_connector:chat-state:${String(scope || 'default').slice(0, 160)}`;
}

function loadSession(scope) {
	try {
		const value = globalThis.sessionStorage && globalThis.sessionStorage.getItem(sessionKey(scope));
		const parsed = value ? JSON.parse(value) : {};
		return parsed && typeof parsed === 'object' ? parsed : {};
	} catch (error) {
		return {};
	}
}

function saveSession(scope, value) {
	try {
		if (globalThis.sessionStorage) globalThis.sessionStorage.setItem(sessionKey(scope), JSON.stringify(value));
	} catch (error) {}
}

function accountKey(profileId, token) {
	return `${profileId || ''}:${token || ''}`;
}

function asArray(value) {
	return Array.isArray(value) ? value : [];
}

function number(value, fallback = 0) {
	const parsed = Number(value);
	return Number.isFinite(parsed) ? parsed : fallback;
}

function plainMessage(message, fallback) {
	let text = String(message && message.message || '');
	const parameters = message && message.messageParameters && typeof message.messageParameters === 'object'
		? message.messageParameters : {};
	Object.keys(parameters).forEach(key => {
		const parameter = parameters[key] || {};
		const replacement = parameter.name || parameter.displayName || parameter.id || fallback;
		text = text.split(`{${key}}`).join(String(replacement));
	});
	return text;
}

function richFiles(message) {
	const parameters = message && message.messageParameters && typeof message.messageParameters === 'object'
		? message.messageParameters : {};
	return Object.keys(parameters).map(key => parameters[key]).filter(parameter => {
		const type = String(parameter && (parameter.type || parameter.objectType) || '').toLowerCase();
		return type === 'file' || type === 'deck-card' || Boolean(parameter && parameter.path && parameter.link);
	});
}

function linkParts(text) {
	return String(text || '').split(/(https?:\/\/[^\s<]+)/gi);
}

function giphyImageUrl(value) {
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
}

function gifPreviewUrls(gif) {
	const resourceUrl = String(gif && gif.resourceUrl || '').trim();
	const convertedResource = giphyImageUrl(resourceUrl);
	return [String(gif && gif.thumbnailUrl || '').trim(), convertedResource, resourceUrl]
		.filter((value, index, values) => value && values.indexOf(value) === index);
}

function mergeGifItems(current, incoming) {
	const byUrl = new Map();
	asArray(current).concat(asArray(incoming)).forEach(gif => {
		const resourceUrl = String(gif && gif.resourceUrl || '').trim();
		if (resourceUrl && !byUrl.has(resourceUrl)) byUrl.set(resourceUrl, gif);
	});
	return Array.from(byUrl.values());
}

function conversationName(conversation, t) {
	return String(conversation && conversation.displayName || '').trim() || t('conversations');
}

function avatarText(value) {
	const words = String(value || '?').trim().split(/\s+/).filter(Boolean);
	return words.slice(0, 2).map(word => word.charAt(0).toUpperCase()).join('') || '?';
}

function timeLabel(timestamp, language) {
	const value = number(timestamp, 0);
	if (!value) return '';
	try {
		return new Intl.DateTimeFormat(localeFor(language), { hour: '2-digit', minute: '2-digit' })
			.format(new Date(value * 1000));
	} catch (error) { return ''; }
}

function mergeMessages(current, incoming) {
	const byId = new Map();
	asArray(current).filter(message => message && typeof message === 'object').forEach(message => byId.set(number(message.id), message));
	asArray(incoming).filter(message => message && typeof message === 'object').forEach(message => byId.set(number(message.id), message));
	return Array.from(byId.values()).filter(message => number(message.id) > 0)
		.sort((left, right) => number(left.id) - number(right.id));
}

export default class Chat extends Component {
	constructor(props) {
		super(props);
		this.t = createTranslator(props.userLanguage);
		const saved = { ...loadSession(props.workspaceScope), ...(cache.get(props.workspaceScope) || {}) };
		const drafts = saved.drafts && typeof saved.drafts === 'object' ? saved.drafts : {};
		this.state = {
			overview: saved.overview || null,
			profileId: saved.profileId || '',
			token: saved.token || '',
			messages: asArray(saved.messages).filter(message => message && typeof message === 'object'),
			loading: !saved.overview,
			loadingMessages: false,
			sending: false,
			error: '',
			query: '',
			drafts,
			draft: String(drafts[accountKey(saved.profileId, saved.token)] || ''),
			replyTo: null,
			showGif: false,
			gifQuery: '',
			gifs: [],
			gifLoading: false,
			gifLoadingMore: false,
			gifCursor: null,
			gifHasMore: false,
			gifLoadMoreError: false,
			gifUnavailable: false,
			picker: null,
			pickerLoading: false,
			pickerPath: '/',
			pickerItems: [],
			pickerSelected: null,
			sharing: false,
			creatingConversation: false,
			createConversationProfileId: saved.profileId || '',
			createConversationType: 'group',
			createConversationName: '',
			createConversationInvite: '',
			createConversationBusy: false,
			deletingMessageId: 0,
			toast: '',
			notificationSound: isTalkSoundEnabled(props.workspaceScope)
		};
	}

	componentDidMount() {
		this.mounted = true;
		this.eventTarget = globalThis.window || globalThis;
		if (this.eventTarget && typeof this.eventTarget.addEventListener === 'function') {
			this.eventTarget.addEventListener(TALK_SOUND_EVENT, this.updateSoundPreference);
		}
		this.loadOverview(true);
		this.overviewTimer = setInterval(() => this.loadOverview(false), 20000);
		this.messagesTimer = setInterval(() => this.pollMessages(), 7000);
	}

	componentWillUnmount() {
		this.mounted = false;
		this.cancelMessagesRequest();
		clearInterval(this.overviewTimer);
		clearInterval(this.messagesTimer);
		clearTimeout(this.toastTimer);
		clearTimeout(this.gifSearchTimer);
		this.gifRequestId = number(this.gifRequestId) + 1;
		if (this.eventTarget && typeof this.eventTarget.removeEventListener === 'function') {
			this.eventTarget.removeEventListener(TALK_SOUND_EVENT, this.updateSoundPreference);
		}
		this.persistSession();
	}

	updateSoundPreference = event => {
		const detail = event && event.detail || {};
		if (detail.scope && String(detail.scope) !== String(this.props.workspaceScope || 'default')) return;
		this.setState({ notificationSound: Boolean(detail.enabled) });
	};

	toggleNotificationSound = () => {
		setTalkSoundEnabled(this.props.workspaceScope, !this.state.notificationSound);
	};

	persistSession = () => {
		const drafts = { ...this.state.drafts };
		const key = accountKey(this.state.profileId, this.state.token);
		if (this.state.draft) drafts[key] = this.state.draft;
		else delete drafts[key];
		const saved = {
			overview: this.state.overview,
			profileId: this.state.profileId,
			token: this.state.token,
			messages: this.state.messages,
			drafts
		};
		cache.set(this.props.workspaceScope, saved);
		saveSession(this.props.workspaceScope, saved);
	};

	cancelMessagesRequest = () => {
		this.messagesRequestId = (this.messagesRequestId || 0) + 1;
		if (this.messagesController && typeof this.messagesController.abort === 'function') this.messagesController.abort();
		this.messagesController = null;
	};

	draftsForSwitch = (profileId, token) => {
		const drafts = { ...this.state.drafts };
		const currentKey = accountKey(this.state.profileId, this.state.token);
		if (this.state.draft) drafts[currentKey] = this.state.draft;
		else delete drafts[currentKey];
		return { drafts, draft: String(drafts[accountKey(profileId, token)] || '') };
	};

	updateDraft = event => {
		const draft = String(event && event.currentTarget ? event.currentTarget.value : '');
		const drafts = { ...this.state.drafts };
		const key = accountKey(this.state.profileId, this.state.token);
		if (draft) drafts[key] = draft;
		else delete drafts[key];
		this.setState({ draft, drafts }, this.persistSession);
	};

	currentAccount = () => asArray(this.state.overview && this.state.overview.accounts)
		.find(account => account.profileId === this.state.profileId);

	currentConversation = () => {
		const account = this.currentAccount();
		return asArray(account && account.conversations).find(conversation => conversation.token === this.state.token);
	};

	emitOverview = overview => {
		if (typeof this.props.onOverview === 'function') this.props.onOverview(overview);
	};

	loadOverview = async initial => {
		if (globalThis.document && globalThis.document.hidden && !initial) return;
		try {
			const overview = await api('/api/talk/overview', { profileId: '' });
			if (!this.mounted) return;
			const accounts = asArray(overview.accounts).filter(account => account.available);
			let profileId = this.state.profileId;
			let token = this.state.token;
			let account = accounts.find(item => item.profileId === profileId) || accounts[0];
			if (account) profileId = account.profileId;
			const conversations = asArray(account && account.conversations);
			if (!conversations.some(item => item.token === token)) token = conversations[0] ? conversations[0].token : '';
			const changed = profileId !== this.state.profileId || token !== this.state.token;
			const switchedDraft = changed ? this.draftsForSwitch(profileId, token) : {};
			if (changed) this.cancelMessagesRequest();
			this.setState({ overview, profileId: profileId || '', token: token || '', loading: false, error: '', ...(changed ? { loadingMessages: false } : {}), ...switchedDraft }, () => {
				if ((initial || changed) && token) this.loadMessages(true);
				this.persistSession();
			});
			this.emitOverview(overview);
		} catch (error) {
			if (!this.mounted) return;
			this.setState({ loading: false, error: error.message || this.t('talkError') });
		}
	};

	selectConversation = (profileId, token) => {
		if (profileId === this.state.profileId && token === this.state.token) return;
		const switchedDraft = this.draftsForSwitch(profileId, token);
		this.cancelMessagesRequest();
		this.setState({ profileId, token, messages: [], loadingMessages: false, replyTo: null, showGif: false, error: '', ...switchedDraft }, () => {
			this.persistSession();
			this.loadMessages(true);
		});
	};

	loadMessages = async replace => {
		const { profileId, token } = this.state;
		if (!profileId || !token || (!replace && this.state.loadingMessages)) return;
		if (replace) this.cancelMessagesRequest();
		const requestId = (this.messagesRequestId || 0) + 1;
		this.messagesRequestId = requestId;
		const controller = typeof globalThis.AbortController === 'function' ? new globalThis.AbortController() : null;
		this.messagesController = controller;
		this.setState({ loadingMessages: true });
		try {
			const lastKnown = replace || !this.state.messages.length
				? 0 : Math.max(...this.state.messages.map(message => number(message && message.id)));
			const result = await api(`/api/talk/messages?token=${encodeURIComponent(token)}&lastKnownMessageId=${lastKnown}&limit=${replace ? 100 : 50}&future=${replace ? 'false' : 'true'}`,
				{ profileId, signal: controller && controller.signal });
			if (!this.mounted || requestId !== this.messagesRequestId || profileId !== this.state.profileId || token !== this.state.token) return;
			const messages = replace ? asArray(result.items).filter(message => message && typeof message === 'object')
				.sort((a, b) => number(a.id) - number(b.id))
				: mergeMessages(this.state.messages, result.items);
			this.setState({ messages, loadingMessages: false, error: '' }, () => {
				this.persistSession();
				this.scrollToLatest();
				this.markRead();
			});
		} catch (error) {
			if (this.mounted && requestId === this.messagesRequestId) {
				this.setState({ loadingMessages: false, ...(error && error.name === 'AbortError' ? {} : { error: error.message || this.t('talkError') }) });
			}
		} finally {
			if (requestId === this.messagesRequestId) this.messagesController = null;
		}
	};

	pollMessages = () => {
		if (globalThis.document && globalThis.document.hidden) return;
		if (!this.state.loadingMessages && this.state.profileId && this.state.token) this.loadMessages(false);
	};

	markRead = async () => {
		const account = this.currentAccount();
		if (account && account.readMarker === false) return;
		const last = this.state.messages.length ? number(this.state.messages[this.state.messages.length - 1].id) : 0;
		if (!last) return;
		try {
			await api('/api/talk/read', {
				method: 'POST', profileId: this.state.profileId,
				json: { token: this.state.token, lastReadMessage: last }
			});
		} catch (error) {}
	};

	scrollToLatest = () => {
		if (this.messagesNode) this.messagesNode.scrollTop = this.messagesNode.scrollHeight;
	};

	sendMessage = async event => {
		if (event) event.preventDefault();
		const message = this.state.draft.trim();
		if (!message || this.state.sending) return;
		const profileId = this.state.profileId;
		const token = this.state.token;
		this.setState({ sending: true });
		try {
			const result = await api('/api/talk/message', {
				method: 'POST', profileId,
				json: { token, message, replyTo: number(this.state.replyTo && this.state.replyTo.id) }
			});
			if (!this.mounted) return;
			this.setState(state => {
				const drafts = { ...state.drafts };
				delete drafts[accountKey(profileId, token)];
				const current = profileId === state.profileId && token === state.token;
				return {
					drafts,
					sending: false,
					...(current ? { draft: '', replyTo: null, messages: mergeMessages(state.messages, [result.message]) } : {})
				};
			}, () => { this.persistSession(); this.scrollToLatest(); });
			this.loadOverview(false);
		} catch (error) {
			if (this.mounted) this.setState({ sending: false, error: error.message || this.t('talkError') });
		}
	};

	openCreateConversation = () => {
		const accounts = asArray(this.state.overview && this.state.overview.accounts).filter(account => account && account.available);
		const preferred = accounts.some(account => account.profileId === this.state.profileId)
			? this.state.profileId : (accounts[0] && accounts[0].profileId || '');
		this.setState({
			creatingConversation: true,
			createConversationProfileId: preferred,
			createConversationType: 'group',
			createConversationName: '',
			createConversationInvite: '',
			createConversationBusy: false,
			error: ''
		});
	};

	createConversation = async event => {
		if (event) event.preventDefault();
		if (this.state.createConversationBusy) return;
		const profileId = this.state.createConversationProfileId;
		const direct = this.state.createConversationType === 'direct';
		const roomName = this.state.createConversationName.trim();
		const invite = this.state.createConversationInvite.trim();
		if (!profileId || (direct ? !invite : !roomName)) return;
		this.setState({ createConversationBusy: true, error: '' });
		try {
			const result = await api('/api/talk/conversation', {
				method: 'POST', profileId,
				json: { roomType: direct ? 1 : 3, roomName, invite }
			});
			const conversation = result && result.conversation || {};
			const token = String(conversation.token || '');
			if (!token) throw new Error(this.t('talkError'));
			if (!this.mounted) return;
			this.cancelMessagesRequest();
			this.setState({
				creatingConversation: false,
				createConversationBusy: false,
				profileId,
				token,
				messages: [],
				loadingMessages: false,
				replyTo: null
			}, () => {
				this.showToast(this.t('conversationCreated'));
				this.loadOverview(true);
			});
		} catch (error) {
			if (this.mounted) this.setState({ createConversationBusy: false, error: error.message || this.t('talkError') });
		}
	};

	deleteMessage = async message => {
		const messageId = number(message && message.id);
		if (!messageId || this.state.deletingMessageId) return;
		if (typeof globalThis.confirm === 'function' && !globalThis.confirm(this.t('deleteMessageConfirm'))) return;
		this.setState({ deletingMessageId: messageId, error: '' });
		try {
			await api('/api/talk/delete-message', {
				method: 'POST', profileId: this.state.profileId,
				json: { token: this.state.token, messageId }
			});
			if (!this.mounted) return;
			this.setState(state => ({
				deletingMessageId: 0,
				messages: state.messages.filter(item => number(item && item.id) !== messageId),
				replyTo: number(state.replyTo && state.replyTo.id) === messageId ? null : state.replyTo
			}), () => {
				this.showToast(this.t('messageDeleted'));
				this.loadMessages(true);
			});
		} catch (error) {
			if (this.mounted) this.setState({ deletingMessageId: 0, error: error.message || this.t('talkError') });
		}
	};

	setReaction = async (message, reaction) => {
		const self = asArray(message.reactionsSelf).includes(reaction);
		try {
			await api('/api/talk/reaction', {
				method: 'POST', profileId: this.state.profileId,
				json: { token: this.state.token, messageId: number(message.id), reaction, remove: self }
			});
			this.loadMessages(true);
		} catch (error) { this.setState({ error: error.message || this.t('talkError') }); }
	};

	loadGifs = async (query, append = false) => {
		if (append && (this.state.gifLoading || this.state.gifLoadingMore || !this.state.gifHasMore)) return;
		const requestId = number(this.gifRequestId) + 1;
		this.gifRequestId = requestId;
		const cursor = append ? number(this.state.gifCursor) : 0;
		this.setState({
			gifLoading: !append,
			gifLoadingMore: append,
			gifUnavailable: false,
			gifLoadMoreError: false,
			gifQuery: query,
			...(append ? {} : { gifCursor: null, gifHasMore: false })
		});
		try {
			const result = await api(`/api/talk/gifs?q=${encodeURIComponent(query || '')}&limit=18&cursor=${cursor}`, { profileId: this.state.profileId });
			if (this.mounted && requestId === this.gifRequestId) {
				const page = asArray(result && result.items).filter(gif => gif && gif.resourceUrl);
				const nextCursor = result && result.cursor;
				const hasCursor = nextCursor !== null && nextCursor !== undefined && String(nextCursor) !== ''
					&& number(nextCursor, -1) > cursor;
				this.setState(state => ({
					gifs: append ? mergeGifItems(state.gifs, page) : mergeGifItems([], page),
					gifCursor: hasCursor ? nextCursor : null,
					gifHasMore: Boolean(page.length && hasCursor),
					gifLoading: false,
					gifLoadingMore: false,
					gifLoadMoreError: false
				}));
			}
		} catch (error) {
			if (this.mounted && requestId === this.gifRequestId) this.setState(append
				? { gifLoadingMore: false, gifLoadMoreError: true }
				: { gifs: [], gifLoading: false, gifLoadingMore: false, gifUnavailable: true, gifHasMore: false });
		}
	};

	loadMoreGifs = () => this.loadGifs(this.state.gifQuery, true);

	handleGifScroll = event => {
		const element = event && event.currentTarget;
		if (!element) return;
		if (number(element.scrollHeight) - number(element.scrollTop) - number(element.clientHeight) <= 90) this.loadMoreGifs();
	};

	updateGifQuery = event => {
		const query = String(event && event.currentTarget && event.currentTarget.value || '');
		this.gifRequestId = number(this.gifRequestId) + 1;
		this.setState({ gifQuery: query, gifHasMore: false, gifLoadMoreError: false });
		clearTimeout(this.gifSearchTimer);
		this.gifSearchTimer = setTimeout(() => {
			this.gifSearchTimer = null;
			if (this.mounted && this.state.showGif) this.loadGifs(query);
		}, 450);
	};

	toggleGif = () => this.setState({ showGif: !this.state.showGif }, () => {
		if (this.state.showGif && !this.state.gifs.length) this.loadGifs('');
	});

	sendGif = gif => {
		const url = String(gif.resourceUrl || '').trim();
		if (!url) return;
		this.setState({ draft: url, showGif: false }, () => this.sendMessage());
	};

	openFilePicker = () => this.setState({ picker: true, pickerPath: '/', pickerSelected: null }, () => this.loadPicker('/'));

	loadPicker = async path => {
		this.setState({ pickerLoading: true, pickerPath: path, pickerSelected: null });
		try {
			const result = await api(`/api/list?path=${encodeURIComponent(path)}&limit=500`, { profileId: this.state.profileId });
			if (this.mounted) this.setState({ pickerItems: asArray(result.items), pickerLoading: false });
		} catch (error) {
			if (this.mounted) this.setState({ pickerLoading: false, error: error.message || this.t('loadFolderError') });
		}
	};

	shareSelectedFile = async () => {
		const file = this.state.pickerSelected;
		if (!file || file.isDirectory || this.state.sharing) return;
		this.setState({ sharing: true });
		try {
			await api('/api/talk/share-file', {
				method: 'POST', profileId: this.state.profileId,
				json: { token: this.state.token, path: file.path, replyTo: number(this.state.replyTo && this.state.replyTo.id) }
			});
			this.setState({ picker: null, sharing: false, replyTo: null });
			this.showToast(this.t('attachmentSent'));
			this.loadMessages(false);
		} catch (error) {
			this.setState({ sharing: false, error: error.message || this.t('talkError') });
		}
	};

	showToast = toast => {
		clearTimeout(this.toastTimer);
		this.setState({ toast });
		this.toastTimer = setTimeout(() => this.mounted && this.setState({ toast: '' }), 3200);
	};

	renderConversation = (account, conversation) => {
		const selected = account.profileId === this.state.profileId && conversation.token === this.state.token;
		const unread = number(conversation.unreadMessages);
		const last = conversation.lastMessage && plainMessage(conversation.lastMessage, this.t('file'));
		const name = conversationName(conversation, this.t);
		return (
			<button type="button" class={`${style.conversation} ${selected ? style.conversationActive : ''}`}
				onClick={() => this.selectConversation(account.profileId, conversation.token)}
				aria-label={this.t('selectConversation', { name })}>
				<span class={style.avatar} title={name} aria-hidden="true">{avatarText(name)}</span>
				<span class={style.conversationText}>
					<strong>{name}</strong>
					<small>{last || this.t('noMessages')}</small>
				</span>
				{unread > 0 && <span class={style.unread} title={this.t('unreadCount', { count: unread })}>{unread > 99 ? '99+' : unread}</span>}
			</button>
		);
	};

	renderMessage = message => {
		if (!message || typeof message !== 'object') return null;
		const account = this.currentAccount();
		const own = account && String(message.actorId || '') === String(account.username || '');
		const supportsReactions = Boolean(account && account.reactions);
		const supportsReplies = Boolean(account && account.replies);
		const author = own ? this.t('you') : (message.actorDisplayName || message.actorId || this.t('unknownUser'));
		const system = Boolean(message.systemMessage) || message.messageType === 'system';
		const text = plainMessage(message, this.t('file'));
		const gifUrl = giphyImageUrl(text);
		const displayText = gifUrl ? text.replace(/https?:\/\/[^\s<]+/gi, match => giphyImageUrl(match) ? '' : match).trim() : text;
		const reactions = message.reactions && typeof message.reactions === 'object' ? message.reactions : {};
		const files = richFiles(message);
		const canDelete = Boolean(account && account.deleteMessages) && !system && number(message.id) > 0;
		return (
			<div class={`${style.messageRow} ${own ? style.messageOwn : ''} ${system ? style.messageSystem : ''}`} key={message.id}>
				<div class={style.messageBubble}>
					<div class={style.messageMeta}><strong>{system ? this.t('systemMessage') : author}</strong><span>{timeLabel(message.timestamp, this.props.userLanguage)}</span>{message.lastEditTimestamp && <em>{this.t('edited')}</em>}</div>
					{message.parent && !message.parent.deleted && <div class={style.parentMessage}><strong>{message.parent.actorDisplayName || this.t('unknownUser')}</strong> {plainMessage(message.parent, this.t('file'))}</div>}
					{displayText && <div class={style.messageText}>{linkParts(displayText).map((part, index) => /^https?:\/\//i.test(part)
						? <a key={index} href={part} target="_blank" rel="noopener noreferrer">{part}</a> : part)}</div>}
					{gifUrl && <img class={style.gifMessage} src={talkGifUrl(this.state.profileId, gifUrl)} alt={this.t('gif')} loading="lazy" />}
					{files.map((file, index) => <a class={style.fileCard} key={index} href={file.link || '#'} target="_blank" rel="noopener noreferrer">📄 <span>{file.name || file.path || this.t('file')}</span></a>)}
					{Object.keys(reactions).length > 0 && <div class={style.reactionSummary}>{Object.keys(reactions).map(emoji => <button type="button" key={emoji} disabled={!supportsReactions} class={asArray(message.reactionsSelf).includes(emoji) ? style.reactionSelf : ''} onClick={() => supportsReactions && this.setReaction(message, emoji)}>{emoji} {reactions[emoji]}</button>)}</div>}
					{!system && <div class={style.messageActions}>
						{supportsReplies && message.isReplyable !== false && <button type="button" onClick={() => this.setState({ replyTo: message })}>{this.t('reply')}</button>}
						{canDelete && <button type="button" class={style.deleteMessageButton} disabled={this.state.deletingMessageId === number(message.id)} onClick={() => this.deleteMessage(message)}>{this.state.deletingMessageId === number(message.id) ? this.t('processing') : this.t('deleteMessage')}</button>}
						{supportsReactions && <span class={style.quickReactions}>{REACTIONS.map(emoji => <button type="button" key={emoji} title={this.t('emojiReaction', { emoji })} onClick={() => this.setReaction(message, emoji)}>{emoji}</button>)}</span>}
					</div>}
				</div>
			</div>
		);
	};

	renderGifPicker() {
		if (!this.state.showGif) return null;
		return <div class={style.gifPanel}>
			<div class={style.gifSearch}>
				<input value={this.state.gifQuery} placeholder={this.t('searchGifs')} onInput={this.updateGifQuery} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); clearTimeout(this.gifSearchTimer); this.gifSearchTimer = null; this.loadGifs(this.state.gifQuery); } }} />
				<button type="button" onClick={() => { clearTimeout(this.gifSearchTimer); this.gifSearchTimer = null; this.loadGifs(this.state.gifQuery); }}>{this.t('searchGifs')}</button>
				<button type="button" aria-label={this.t('close')} onClick={() => this.setState({ showGif: false })}>×</button>
			</div>
			<small class={style.privacy}>{this.t('giphyPrivacy')}</small>
			{this.state.gifLoading ? <div class={style.panelStatus}>{this.t('loading')}</div>
				: this.state.gifUnavailable ? <div class={style.panelStatus}>{this.t('gifsUnavailable')}</div>
					: <div class={style.gifGrid} onScroll={this.handleGifScroll}>{this.state.gifs.map((gif, index) => {
						const previews = gifPreviewUrls(gif);
						return <button type="button" key={`${gif.resourceUrl}-${index}`} onClick={() => this.sendGif(gif)} title={gif.title || this.t('gif')}>
							<img src={talkGifUrl(this.state.profileId, previews[0] || gif.resourceUrl)} alt={gif.title || this.t('gif')} loading="lazy" data-preview-index="0"
								onError={event => {
									const image = event.currentTarget;
									const nextIndex = number(image.getAttribute('data-preview-index')) + 1;
									if (nextIndex >= previews.length) return;
									image.setAttribute('data-preview-index', String(nextIndex));
									image.src = talkGifUrl(this.state.profileId, previews[nextIndex]);
								}} />
						</button>;
					})}
					{this.state.gifLoadingMore && <div class={style.gifGridStatus}>{this.t('loading')}</div>}
					{this.state.gifLoadMoreError && <button type="button" class={style.gifGridRetry} onClick={this.loadMoreGifs}>{this.t('retry')}</button>}
				</div>}
			{!this.state.gifLoading && !this.state.gifUnavailable && !this.state.gifs.length && <div class={style.panelStatus}>{this.t('noGif')}</div>}
		</div>;
	}

	renderFilePicker() {
		if (!this.state.picker) return null;
		const parent = this.state.pickerPath === '/' ? '' : this.state.pickerPath.replace(/\/[^/]+\/?$/, '') || '/';
		return <div class={style.modalBackdrop} onMouseDown={event => { if (event.target === event.currentTarget) this.setState({ picker: null }); }}>
			<div class={style.modal} role="dialog" aria-modal="true" aria-label={this.t('chooseFile')}>
				<header><div><strong>{this.t('shareCloudFile')}</strong><small>{this.state.pickerPath}</small></div><button type="button" aria-label={this.t('close')} onClick={() => this.setState({ picker: null })}>×</button></header>
				<div class={style.pickerList}>
					{parent && <button type="button" class={style.pickerItem} onClick={() => this.loadPicker(parent)}>↩ <span>{this.t('parentFolder')}</span></button>}
					{this.state.pickerLoading ? <div class={style.panelStatus}>{this.t('loading')}</div> : this.state.pickerItems.map(item => <button type="button" class={`${style.pickerItem} ${this.state.pickerSelected && this.state.pickerSelected.path === item.path ? style.pickerSelected : ''}`} key={item.path} onDoubleClick={() => item.isDirectory ? this.loadPicker(item.path) : this.setState({ pickerSelected: item })} onClick={() => item.isDirectory ? this.loadPicker(item.path) : this.setState({ pickerSelected: item })}>{item.isDirectory ? '📁' : '📄'} <span>{item.name}</span>{item.isDirectory && <em>›</em>}</button>) }
				</div>
				<footer><button type="button" onClick={() => this.setState({ picker: null })}>{this.t('cancel')}</button><button type="button" class={style.primaryButton} disabled={!this.state.pickerSelected || this.state.sharing} onClick={this.shareSelectedFile}>{this.state.sharing ? this.t('sharing') : this.t('shareThisFile')}</button></footer>
			</div>
		</div>;
	}

	renderConversationCreator() {
		if (!this.state.creatingConversation) return null;
		const accounts = asArray(this.state.overview && this.state.overview.accounts).filter(account => account && account.available);
		const direct = this.state.createConversationType === 'direct';
		const valid = Boolean(this.state.createConversationProfileId && (direct
			? this.state.createConversationInvite.trim() : this.state.createConversationName.trim()));
		return <div class={style.modalBackdrop} onMouseDown={event => {
			if (event.target === event.currentTarget && !this.state.createConversationBusy) this.setState({ creatingConversation: false });
		}}>
			<div class={style.modal} role="dialog" aria-modal="true" aria-label={this.t('newConversation')}>
				<header><div><strong>{this.t('newConversation')}</strong><small>{this.t('newConversationHelp')}</small></div><button type="button" aria-label={this.t('close')} disabled={this.state.createConversationBusy} onClick={() => this.setState({ creatingConversation: false })}>×</button></header>
				<form class={style.conversationForm} onSubmit={this.createConversation}>
					<label><span>{this.t('cloudAccount')}</span><select value={this.state.createConversationProfileId} onChange={event => this.setState({ createConversationProfileId: event.currentTarget.value })}>{accounts.map(item => <option key={item.profileId} value={item.profileId}>{item.label}</option>)}</select></label>
					<label><span>{this.t('conversationType')}</span><select value={this.state.createConversationType} onChange={event => this.setState({ createConversationType: event.currentTarget.value })}><option value="group">{this.t('groupConversation')}</option><option value="direct">{this.t('directConversation')}</option></select></label>
					{direct ? <label><span>{this.t('nextcloudParticipant')}</span><input type="text" maxlength="255" value={this.state.createConversationInvite} placeholder={this.t('nextcloudParticipantPlaceholder')} onInput={event => this.setState({ createConversationInvite: event.currentTarget.value })} /></label>
						: <label><span>{this.t('conversationName')}</span><input type="text" maxlength="255" value={this.state.createConversationName} placeholder={this.t('conversationNamePlaceholder')} onInput={event => this.setState({ createConversationName: event.currentTarget.value })} /></label>}
					<footer><button type="button" disabled={this.state.createConversationBusy} onClick={() => this.setState({ creatingConversation: false })}>{this.t('cancel')}</button><button type="submit" class={style.primaryButton} disabled={!valid || this.state.createConversationBusy}>{this.state.createConversationBusy ? this.t('creating') : this.t('createConversation')}</button></footer>
				</form>
			</div>
		</div>;
	}

	render() {
		const { overview, loading, error } = this.state;
		const accounts = asArray(overview && overview.accounts);
		const availableAccounts = accounts.filter(account => account.available);
		const normalizedQuery = this.state.query.trim().toLowerCase();
		const conversation = this.currentConversation();
		const account = this.currentAccount();
		const maxLength = Math.max(1, number(account && account.maxMessageLength, 32000));
		const readonly = Boolean(conversation && number(conversation.readOnly));

		if (loading) return <div class={style.centerState}><div class={style.spinner} /><strong>{this.t('loadingTalk')}</strong></div>;
		if (!overview || !overview.available) return <div class={style.centerState}><div class={style.emptyIcon}>💬</div><h1>{this.t('noTalk')}</h1><p>{this.t('noTalkHelp')}</p>{error && <p class={style.error}>{error}</p>}<button type="button" class={style.primaryButton} onClick={() => this.loadOverview(true)}>{this.t('retry')}</button></div>;

		return <div class={style.page}>
			<header class={style.topbar}>
				<div class={style.topbarTitle}>
					{typeof this.props.onClose === 'function' && <button type="button" class={style.backButton} title={this.t('backToCloud')} onClick={this.props.onClose}>←</button>}
					<div><h1>{this.t('chat')}</h1><span>{this.t('talkBeta')}</span></div>
				</div>
				<div class={style.chatOnly}>💬 {this.t('chatOnlyNotice')}</div>
				<div class={style.topbarActions}>
					<button type="button" class={this.state.notificationSound ? style.soundEnabled : ''} title={this.t(this.state.notificationSound ? 'disableNotificationSound' : 'enableNotificationSound')} aria-pressed={this.state.notificationSound} onClick={this.toggleNotificationSound}>{this.state.notificationSound ? '🔔' : '🔕'}</button>
					<button type="button" title={this.t('refreshChat')} onClick={() => { this.loadOverview(false); this.loadMessages(true); }}>↻</button>
				</div>
			</header>
			{error && <div class={style.errorBanner}><span>{error}</span><button type="button" onClick={() => this.setState({ error: '' })}>×</button></div>}
			<div class={style.layout}>
				<aside class={style.sidebar}>
					<div class={style.sidebarTitle}><strong>{this.t('conversations')}</strong><div><button type="button" title={this.t('newConversation')} onClick={this.openCreateConversation}>＋</button>{number(overview.unread) > 0 && <span>{number(overview.unread)}</span>}</div></div>
					<input class={style.conversationSearch} value={this.state.query} placeholder={this.t('searchConversations')} onInput={event => this.setState({ query: event.currentTarget.value })} />
					<div class={style.conversationList}>{availableAccounts.map(currentAccount => {
						const conversations = asArray(currentAccount.conversations).filter(item => !normalizedQuery || conversationName(item, this.t).toLowerCase().includes(normalizedQuery));
						return <section class={style.accountGroup} key={currentAccount.profileId}>
							<div class={style.accountLabel}><span class={style.statusDot} /> <strong>{currentAccount.label}</strong><small>{currentAccount.server}</small></div>
							{conversations.map(item => this.renderConversation(currentAccount, item))}
						</section>;
					})}</div>
				</aside>
				<main class={style.chatPane}>
					{conversation ? <div class={style.conversationWorkspace}>
						<header class={style.conversationHeader}><span class={style.avatarLarge} title={conversationName(conversation, this.t)} aria-hidden="true">{avatarText(conversationName(conversation, this.t))}</span><div><h2>{conversationName(conversation, this.t)}</h2><span>{account && account.label} · {account && account.server}</span></div>{number(conversation.unreadMessages) > 0 && <em>{this.t('unreadCount', { count: number(conversation.unreadMessages) })}</em>}</header>
						<div class={style.messages} ref={node => { this.messagesNode = node; }}>
							{this.state.loadingMessages && !this.state.messages.length ? <div class={style.panelStatus}>{this.t('loading')}</div> : this.state.messages.map(this.renderMessage)}
							{!this.state.loadingMessages && !this.state.messages.length && <div class={style.emptyMessages}>💬<span>{this.t('noMessages')}</span></div>}
						</div>
						<div class={style.composerWrap}>
							{this.renderGifPicker()}
							{this.state.replyTo && <div class={style.replyBanner}><span>{this.t('replyingTo', { name: this.state.replyTo.actorDisplayName || this.t('unknownUser') })}<small>{plainMessage(this.state.replyTo, this.t('file'))}</small></span><button type="button" aria-label={this.t('cancelReply')} onClick={() => this.setState({ replyTo: null })}>×</button></div>}
							{readonly ? <div class={style.readOnly}>🔒 {this.t('readOnlyConversation')}</div> : <form class={style.composer} onSubmit={this.sendMessage}>
								<div class={style.composerActions}>{account && account.fileSharing !== false && <button type="button" title={this.t('shareCloudFile')} onClick={this.openFilePicker}>📎</button>}<button type="button" title={this.t('gif')} onClick={this.toggleGif}>GIF</button></div>
				<textarea value={this.state.draft} maxLength={maxLength} placeholder={this.t('writeMessage')} onInput={this.updateDraft} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); this.sendMessage(); } }} />
								<div class={style.sendArea}><small>{this.t('charactersRemaining', { count: maxLength - this.state.draft.length })}</small><button type="submit" class={style.primaryButton} disabled={!this.state.draft.trim() || this.state.sending}>{this.state.sending ? this.t('processing') : this.t('send')}</button></div>
							</form>}
						</div>
					</div> : <div class={style.centerState}><div class={style.emptyIcon}>💭</div><h2>{this.t('chooseConversation')}</h2><p>{this.t('chooseConversationHelp')}</p></div>}
				</main>
			</div>
			{this.renderFilePicker()}
			{this.renderConversationCreator()}
			{this.state.toast && <div class={style.toast}>{this.state.toast}</div>}
		</div>;
	}
}
