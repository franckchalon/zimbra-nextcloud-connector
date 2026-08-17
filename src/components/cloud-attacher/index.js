import { createElement, Component } from 'preact';
import { ActionMenuItem, ModalDialog } from '@zimbra-client/components';

import { api, fileUrl, setActiveProfile } from '../../api';
import { iconFor } from '../../file-types';
import { languageFromContext, localeFor, translate, translatePlural } from '../../i18n';
import { buildReadOnlyLinkContent, insertComposeContent, resolveComposeBridge } from './compose-bridge';
import style from './style.less';

const MODAL_ID = 'com-nextcloud-connector-cloud-picker';

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

class CloudPicker extends Component {
	constructor(props) {
		super(props);
		this.language = languageFromContext(props.context, 'fr');
		this.locale = localeFor(this.language);
		this.t = (key, variables) => translate(this.language, key, variables);
		this.state = {
			path: '/', items: [], loading: true, attaching: false, error: '',
			selected: [], profile: null, switchingAccount: false, mailLimits: { maxAttachments: 20, maxBytes: 100 * 1024 * 1024 },
			search: '', smartView: 'files', progress: '', manualLinksText: '', generatedLinks: [], generatedSelectionKey: ''
		};
	}

	componentDidMount() { this.initialize(); }
	componentWillUnmount() { if (this.searchTimer) globalThis.clearTimeout(this.searchTimer); }

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
		} catch (error) { this.setState({ loading: false, error: error.message || this.t('loadFolderError') }); }
	};

	searchAccount = async event => {
		const search = event.target.value;
		this.setState({ search });
		if (this.searchTimer) globalThis.clearTimeout(this.searchTimer);
		if (!search.trim()) { if (this.state.smartView === 'search') this.load('/'); return; }
		this.searchTimer = globalThis.setTimeout(async () => {
			this.setState({ loading: true, smartView: 'search', error: '' });
			try { const result = await api(`/api/search?q=${encodeURIComponent(search.trim())}`); this.setState({ loading: false, items: result.items || [] }); }
			catch (error) { this.setState({ loading: false, error: error.message }); }
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
		if (!selected.length) return;
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
				const ParentFile = globalThis.parent && globalThis.parent.File ? globalThis.parent.File : globalThis.File;
				files.push(new ParentFile([blob], item.name, {
					type: blob.type || item.mimeType || 'application/octet-stream',
					lastModified: Date.now()
				}));
			}
			this.props.editor.addAttachments(files, true);
			this.props.onClose();
		} catch (error) {
			this.setState({ attaching: false, error: error.message || String(error) });
		}
	};

	insertReadOnlyLinks = async () => {
		const selected = this.state.selected;
		if (!selected.length) return;
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
			const inserted = insertComposeContent(this.props.composeBridge, this.props.editor, content.html, content.text);
			if (inserted) this.props.onClose();
			else this.setState({ attaching: false, progress: '', generatedLinks: links, generatedSelectionKey: selectionKey, manualLinksText: content.text });
		} catch (error) { this.setState({ attaching: false, progress: '', error: error.message || String(error) }); }
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
		return (
			<ModalDialog
				title={this.t('chooseCloudFiles')}
				onClose={this.props.onClose}
				class={style.modalDialog}
				contentClass={style.modalContent}
				innerClass={style.modalInner}
				cancelButton={false}
				footer={false}
			>
				<div class={style.picker}>
					{profile && Array.isArray(profile.accounts) && profile.accounts.length > 1 && (
						<div class={style.accountBar}>
							<label>
								<span>{this.t('cloudAccount')}</span>
								<select value={profile.activeProfileId || ''} onChange={this.switchAccount} disabled={loading || attaching || switchingAccount}>
									{profile.accounts.map(account => (
										<option value={account.id} key={account.id}>{account.label || account.username} — {account.nextcloudUrl}</option>
									))}
								</select>
							</label>
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
					{manualLinksText && (
						<div class={style.manualLinks}>
							<strong>{this.t('linksManualFallback')}</strong>
							<textarea ref={this.setManualLinksNode} readOnly value={manualLinksText} rows="4" />
							<button type="button" onClick={this.copyGeneratedLinks}>{this.t('copyGeneratedLinks')}</button>
						</div>
					)}
					{loading ? <div class={style.pickerState}>{this.t('loading')}</div> : (
						<div class={style.treeList}>
							{items.map(file => file.isDirectory ? (
								<button type="button" class={style.folderRow} onClick={() => this.load(file.path)} key={file.path}>
									<span>📁</span><strong>{file.name}</strong><span>›</span>
								</button>
							) : (
								<label class={`${style.fileRow} ${selected.some(item => item.path === file.path) ? style.selectedRow : ''}`} key={file.path}>
									<input type="checkbox" checked={selected.some(item => item.path === file.path)} onChange={() => this.toggle(file)} />
									<span>{iconFor(file)}</span>
									<span><strong>{file.name}</strong><small>{formatSize(file.size, this.locale)}</small></span>
								</label>
							))}
							{!items.length && <div class={style.pickerState}>{this.t('folderEmptyPeriod')}</div>}
						</div>
					)}
					<div class={style.pickerFooter}>
						<div><strong>{translatePlural(this.language, selected.length, 'selected')}</strong><small>{formatSize(total, this.locale)}</small></div>
						<button type="button" class={style.cancelButton} onClick={this.props.onClose} disabled={attaching}>{this.t('cancel')}</button>
						<button type="button" class={style.linkButton} onClick={this.insertReadOnlyLinks} disabled={!selected.length || attaching}>
							{this.t('insertReadOnlyLinks')}
						</button>
						<button type="button" class={style.attachButton} onClick={this.attach} disabled={!selected.length || attaching}>
							{attaching ? this.t('addingFiles') : this.t('addToMessage')}
						</button>
					</div>
				</div>
			</ModalDialog>
		);
	}
}

export default class CloudAttacher extends Component {
	closePicker = () => {
		const { context } = this.props;
		context.store.dispatch(context.zimletRedux.actions.zimlets.removeModal({ id: MODAL_ID }));
	};

	openPicker = editor => {
		if (!editor || typeof editor.addAttachments !== 'function') return;
		const { context } = this.props;
		const modal = <CloudPicker context={context} editor={editor} composeBridge={this.composeBridge} onClose={this.closePicker} />;
		context.store.dispatch(context.zimletRedux.actions.zimlets.addModal({ id: MODAL_ID, modal }));
	};

	chooseCloudFiles = () => {
		if (typeof this.props.onAttachmentOptionSelection === 'function') {
			this.props.onAttachmentOptionSelection(editor => {
				this.composeBridge = resolveComposeBridge(editor);
				this.openPicker(editor);
			});
		}
	};

	render() {
		const language = languageFromContext(this.props.context, 'fr');
		return (
			<ActionMenuItem icon="cloud" onClick={this.chooseCloudFiles}>
				{translate(language, 'cloud')}
			</ActionMenuItem>
		);
	}
}
