import { createElement, Component } from 'preact';

import { api, fileUrl, setActiveProfile } from '../../api';
import { iconFor } from '../../file-types';
import { localeFor, normalizeLanguage, translate, translatePlural } from '../../i18n';
import { buildReadOnlyLinkContent } from '../cloud-attacher/compose-bridge';
import style from '../cloud-attacher/style.less';

function parentPath(path) {
	const parts = String(path || '/').split('/').filter(Boolean);
	parts.pop();
	return parts.length ? `/${parts.join('/')}` : '/';
}

function formatSize(value, locale) {
	const size = Number(value || 0);
	if (!Number.isFinite(size) || size < 0) return '—';
	const units = locale.startsWith('fr') ? ['o', 'Ko', 'Mo', 'Go'] : ['B', 'KB', 'MB', 'GB'];
	let amount = size;
	let index = 0;
	while (amount >= 1024 && index < units.length - 1) { amount /= 1024; index += 1; }
	return `${amount >= 10 || index === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[index]}`;
}

function profileFingerprint(profile) {
	if (!profile || typeof profile !== 'object') return '';
	return JSON.stringify({
		configured: Boolean(profile.configured),
		activeProfileId: String(profile.activeProfileId || ''),
		accounts: (Array.isArray(profile.accounts) ? profile.accounts : []).map(account => ({
			id: String(account && account.id || ''),
			label: String(account && account.label || ''),
			username: String(account && account.username || ''),
			nextcloudUrl: String(account && account.nextcloudUrl || ''),
			talkEnabled: Boolean(account && account.talkEnabled)
		}))
	});
}

export default class CloudPickerCore extends Component {
	constructor(props) {
		super(props);
		this.language = normalizeLanguage(props.language || 'fr', 'fr');
		this.locale = localeFor(this.language);
		this.t = (key, variables) => translate(this.language, key, variables);
		this.state = {
			path: '/', items: [], loading: true, attaching: false, error: '',
			selected: [], profile: null, switchingAccount: false,
			mailLimits: { maxAttachments: 20, maxBytes: 100 * 1024 * 1024 },
			search: '', smartView: 'files', progress: '', manualLinksText: '',
			generatedLinks: [], generatedSelectionKey: ''
		};
	}

	componentDidMount() {
		this.mounted = true;
		this.profileEventTarget = globalThis.window || globalThis;
		this.profileDocument = globalThis.document;
		if (this.profileEventTarget && typeof this.profileEventTarget.addEventListener === 'function') {
			this.profileEventTarget.addEventListener('focus', this.handleProfileReturn);
		}
		if (this.profileDocument && typeof this.profileDocument.addEventListener === 'function') {
			this.profileDocument.addEventListener('visibilitychange', this.handleProfileReturn);
		}
		this.initialize();
	}

	componentWillUnmount() {
		this.mounted = false;
		if (this.searchTimer) globalThis.clearTimeout(this.searchTimer);
		if (this.profileEventTarget && typeof this.profileEventTarget.removeEventListener === 'function') {
			this.profileEventTarget.removeEventListener('focus', this.handleProfileReturn);
		}
		if (this.profileDocument && typeof this.profileDocument.removeEventListener === 'function') {
			this.profileDocument.removeEventListener('visibilitychange', this.handleProfileReturn);
		}
	}

	handleProfileReturn = () => {
		if (!this.mounted || (this.profileDocument && this.profileDocument.hidden)) return;
		const now = Date.now();
		if (now - Number(this.profileReturnAt || 0) < 1000) return;
		this.profileReturnAt = now;
		this.refreshProfile();
	};

	refreshProfile = async () => {
		if (this.profileRefreshInFlight) return;
		this.profileRefreshInFlight = true;
		try {
			const profile = await api('/api/profile');
			if (!this.mounted) return;
			const previous = this.state.profile;
			if (profileFingerprint(previous) === profileFingerprint(profile)) return;
			const activeChanged = String(previous && previous.activeProfileId || '') !== String(profile.activeProfileId || '');
			setActiveProfile(profile.activeProfileId || '');
			this.setState({ profile, selected: [], error: '' }, () => {
				if (activeChanged && profile.configured) this.load('/');
			});
		} catch (error) {
			// A background refresh must not replace a usable picker with an error.
		} finally {
			this.profileRefreshInFlight = false;
		}
	};

	initialize = async () => {
		try {
			const profile = await api('/api/profile');
			setActiveProfile(profile.activeProfileId || '');
			let mailLimits = this.state.mailLimits;
			try { mailLimits = await api('/api/mail-limits'); } catch (error) {}
			this.setState({ profile, mailLimits });
			await this.load('/');
		} catch (error) {
			this.setState({ loading: false, error: error.message || this.t('loadFolderError') });
		}
	};

	switchAccount = async event => {
		const profileId = String(event.target.value || '');
		if (!profileId || this.state.switchingAccount) return;
		this.setState({ switchingAccount: true, loading: true, error: '', selected: [], items: [], path: '/', manualLinksText: '', generatedLinks: [], generatedSelectionKey: '' });
		try {
			const profile = await api('/api/profile/select', { method: 'POST', json: { profileId } });
			setActiveProfile(profile.activeProfileId || profileId);
			this.setState({ profile, switchingAccount: false });
			await this.load('/');
		} catch (error) {
			this.setState({ switchingAccount: false, loading: false, error: error.message || this.t('loadFolderError') });
		}
	};

	load = async path => {
		this.setState({ loading: true, error: '', path, smartView: 'files', search: '', selected: [], manualLinksText: '', generatedLinks: [], generatedSelectionKey: '' });
		try {
			const result = await api(`/api/list?path=${encodeURIComponent(path)}`);
			this.setState({ loading: false, path: result.path || path, items: result.items || [] });
		} catch (error) {
			this.setState({ loading: false, error: error.message || this.t('loadFolderError') });
		}
	};

	loadSmartView = async smartView => {
		this.setState({ loading: true, error: '', selected: [], smartView, search: '', manualLinksText: '', generatedLinks: [], generatedSelectionKey: '' });
		try {
			const route = smartView === 'favorites' ? '/api/favorites' : '/api/recent?days=30';
			const result = await api(route);
			this.setState({ loading: false, path: '/', items: result.items || [] });
		} catch (error) {
			this.setState({ loading: false, error: error.message || this.t('loadFolderError') });
		}
	};

	searchAccount = event => {
		const search = event.target.value;
		this.setState({ search });
		if (this.searchTimer) globalThis.clearTimeout(this.searchTimer);
		if (!search.trim()) {
			if (this.state.smartView === 'search') this.load('/');
			return;
		}
		this.searchTimer = globalThis.setTimeout(async () => {
			this.setState({ loading: true, smartView: 'search', error: '' });
			try {
				const result = await api(`/api/search?q=${encodeURIComponent(search.trim())}`);
				this.setState({ loading: false, items: result.items || [] });
			} catch (error) {
				this.setState({ loading: false, error: error.message || this.t('loadFolderError') });
			}
		}, 350);
	};

	toggle = file => {
		const selected = this.state.selected.some(item => item.path === file.path)
			? this.state.selected.filter(item => item.path !== file.path)
			: this.state.selected.concat([file]);
		this.setState({ selected, error: '', manualLinksText: '', generatedLinks: [], generatedSelectionKey: '' });
	};

	attach = async () => {
		const selected = this.state.selected;
		const profile = this.state.profile;
		const total = selected.reduce((sum, file) => sum + Number(file.size || 0), 0);
		if (!selected.length || typeof this.props.onAttachFiles !== 'function') return;
		if (selected.length > Number(this.state.mailLimits.maxAttachments || 20) || total > Number(this.state.mailLimits.maxBytes || 100 * 1024 * 1024)) {
			this.setState({ error: this.t('attachmentLimitDynamic', { count: this.state.mailLimits.maxAttachments, size: formatSize(this.state.mailLimits.maxBytes, this.locale) }) });
			return;
		}
		this.setState({ attaching: true, error: '' });
		try {
			const files = [];
			for (let index = 0; index < selected.length; index += 1) {
				const item = selected[index];
				this.setState({ progress: this.t('attachmentProgress', { current: index + 1, total: selected.length }) });
				const response = await fetch(fileUrl(item.path, 'attachment', profile && profile.activeProfileId), {
					credentials: 'same-origin',
					headers: { 'X-Zimbra-Zimlet': 'com_nextcloud_connector' }
				});
				if (!response.ok) throw new Error(`${item.name} (${response.status})`);
				const blob = await response.blob();
				const FileConstructor = this.props.fileConstructor || globalThis.File;
				if (typeof FileConstructor === 'function') {
					files.push(new FileConstructor([blob], item.name, {
						type: blob.type || item.mimeType || 'application/octet-stream',
						lastModified: Date.now()
					}));
				} else {
					// Older Classic-compatible browsers can expose Blob without File.
					// Zimbra's attachment bridge only requires a Blob with a file name.
					try { Object.defineProperty(blob, 'name', { value: item.name, configurable: true }); }
					catch (error) { blob.name = item.name; }
					files.push(blob);
				}
			}
			await Promise.resolve(this.props.onAttachFiles(files));
			this.props.onClose();
		} catch (error) {
			this.setState({ attaching: false, progress: '', error: error.message || String(error) });
		}
	};

	insertReadOnlyLinks = async () => {
		const selected = this.state.selected;
		if (!selected.length || typeof this.props.onInsertLinks !== 'function') return;
		this.setState({ attaching: true, error: '', progress: this.t('creatingLinks') });
		try {
			const selectionKey = selected.map(item => item.path).sort().join('\n');
			let links = this.state.generatedSelectionKey === selectionKey ? this.state.generatedLinks : [];
			if (!links.length) {
				links = [];
				for (let index = 0; index < selected.length; index += 1) {
					const item = selected[index];
					this.setState({ progress: this.t('linkProgress', { current: index + 1, total: selected.length }) });
					const share = await api('/api/share/create', { method: 'POST', json: { path: item.path, shareType: 3, permissions: 1 } });
					links.push({ name: item.name, url: share.url });
				}
			}
			const content = buildReadOnlyLinkContent(links, this.t('cloudLinksIntro'), this.t('readOnlyText'));
			const inserted = await Promise.resolve(this.props.onInsertLinks(content));
			if (inserted !== false) this.props.onClose();
			else this.setState({ attaching: false, progress: '', generatedLinks: links, generatedSelectionKey: selectionKey, manualLinksText: content.text });
		} catch (error) {
			this.setState({ attaching: false, progress: '', error: error.message || String(error) });
		}
	};

	setManualLinksNode = node => { this.manualLinksNode = node; };

	copyGeneratedLinks = () => {
		const node = this.manualLinksNode;
		if (!node) return;
		node.focus();
		node.select();
		let copied = false;
		try { copied = Boolean(node.ownerDocument && node.ownerDocument.execCommand && node.ownerDocument.execCommand('copy')); } catch (error) {}
		this.setState({ progress: copied ? this.t('linksCopied') : this.t('copyLinksManually'), error: '' });
	};

	render() {
		const { path, items, selected, loading, attaching, error, profile, switchingAccount, search, smartView, progress, manualLinksText } = this.state;
		const total = selected.reduce((sum, file) => sum + Number(file.size || 0), 0);
		return <div class={style.picker}>
			{profile && Array.isArray(profile.accounts) && profile.accounts.length > 1 && (
				<div class={style.accountBar}>
					<label><span>{this.t('cloudAccount')}</span><select value={profile.activeProfileId || ''} onChange={this.switchAccount} disabled={loading || attaching || switchingAccount}>
						{profile.accounts.map(account => <option value={account.id} key={account.id}>{account.label || account.username} — {account.nextcloudUrl}</option>)}
					</select></label>
					<small>{this.t('chooseAccountForAttachments')}</small>
				</div>
			)}
			<div class={style.pickerToolbar}>
				<button type="button" onClick={() => this.load('/')} disabled={loading}>{this.t('myCloud')}</button>
				{path !== '/' && <button type="button" onClick={() => this.load(parentPath(path))} disabled={loading}>← {this.t('parentFolder')}</button>}
				<button type="button" class={smartView === 'favorites' ? style.activeTool : ''} onClick={() => this.loadSmartView('favorites')} disabled={loading}>★ {this.t('smartFavorites')}</button>
				<button type="button" class={smartView === 'recent' ? style.activeTool : ''} onClick={() => this.loadSmartView('recent')} disabled={loading}>◷ {this.t('smartRecent')}</button>
				<strong title={path}>{path}</strong>
			</div>
			<div class={style.pickerSearch}><span>⌕</span><input type="search" value={search} onInput={this.searchAccount} placeholder={this.t('searchWholeAccount')} /></div>
			{error && <div class={style.pickerError}>{error}</div>}
			{progress && <div class={style.pickerProgress}>{progress}</div>}
			{manualLinksText && <div class={style.manualLinks}>
				<strong>{this.t('linksManualFallback')}</strong>
				<textarea ref={this.setManualLinksNode} readOnly value={manualLinksText} rows="4" />
				<button type="button" onClick={this.copyGeneratedLinks}>{this.t('copyGeneratedLinks')}</button>
			</div>}
			{loading ? <div class={style.pickerState}>{this.t('loading')}</div> : <div class={style.treeList}>
				{items.map(file => file.isDirectory ? <button type="button" class={style.folderRow} onClick={() => this.load(file.path)} key={file.path}>
					<span>📁</span><strong>{file.name}</strong><span>›</span>
				</button> : <label class={`${style.fileRow} ${selected.some(item => item.path === file.path) ? style.selectedRow : ''}`} key={file.path}>
					<input type="checkbox" checked={selected.some(item => item.path === file.path)} onChange={() => this.toggle(file)} />
					<span>{iconFor(file)}</span><span><strong>{file.name}</strong><small>{formatSize(file.size, this.locale)}</small></span>
				</label>)}
				{!items.length && <div class={style.pickerState}>{this.t('folderEmptyPeriod')}</div>}
			</div>}
			<div class={style.pickerFooter}>
				<div><strong>{translatePlural(this.language, selected.length, 'selected')}</strong><small>{formatSize(total, this.locale)}</small></div>
				<button type="button" class={style.cancelButton} onClick={this.props.onClose} disabled={attaching}>{this.t('cancel')}</button>
				<button type="button" class={style.linkButton} onClick={this.insertReadOnlyLinks} disabled={!selected.length || attaching}>{this.t('insertReadOnlyLinks')}</button>
				<button type="button" class={style.attachButton} onClick={this.attach} disabled={!selected.length || attaching}>{attaching ? this.t('addingFiles') : this.t('addToMessage')}</button>
			</div>
		</div>;
	}
}
