import { createElement, Component } from 'preact';

import { api, archiveUrl, fetchDownload, fileUrl, thumbnailUrl, setActiveProfile } from '../../api';
import {
	attachFloatingWindowCallbacks,
	closeFloatingWindows,
	detachFloatingWindowCallbacks,
	initializeFloatingWindows,
	openFloatingEditor,
	openFloatingPreview,
	setCloudRouteActive
} from '../floating-windows';
import Chat from '../chat';
import { canOpenInOnlyOffice, iconFor, previewKind } from '../../file-types';
import { localeFor, normalizeLanguage, translate, translatePlural } from '../../i18n';
import { resetWorkspace, restoredWorkspace, saveWorkspace } from '../../workspace-state';
import { CLOUD_VIEW_EVENT, notifyTalkNavigation, setCloudView } from '../../talk-navigation';
import {
	AdvancedSearchPanel,
	CollisionDialog,
	DiagnosticsPanel,
	ItemDetails,
	SmartNavigation,
	UploadCenter,
	feature
} from './advanced';
import style from './style.less';

const BACKGROUNDS = [
	'https://images.unsplash.com/photo-1470770841072-f978cf4d019e?auto=format&fit=crop&w=2000&q=82',
	'https://images.unsplash.com/photo-1441974231531-c6227db76b6e?auto=format&fit=crop&w=2000&q=82',
	'https://images.unsplash.com/photo-1518837695005-2083093ee35b?auto=format&fit=crop&w=2000&q=82',
	'https://images.unsplash.com/photo-1497366754035-f200968a6e72?auto=format&fit=crop&w=2000&q=82',
	'https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?auto=format&fit=crop&w=2000&q=82'
];

const MAX_THUMBNAIL_REQUESTS = 4;
const MAX_BULK_ITEMS = 200;
const SMART_VIEW_LABELS = { favorites: 'smartFavorites', recent: 'smartRecent', sharedByMe: 'smartSharedByMe', sharedWithMe: 'smartSharedWithMe', publicLinks: 'smartPublicLinks' };
const thumbnailQueue = [];
let activeThumbnailRequests = 0;

function pumpThumbnailQueue() {
	while (activeThumbnailRequests < MAX_THUMBNAIL_REQUESTS && thumbnailQueue.length) {
		const task = thumbnailQueue.shift();
		if (task.signal && task.signal.aborted) {
			task.reject(new DOMException('Thumbnail loading cancelled', 'AbortError'));
			continue;
		}
		activeThumbnailRequests += 1;
		fetch(task.url, {
			credentials: 'same-origin',
			headers: { 'X-Zimbra-Zimlet': 'com_nextcloud_connector' },
			signal: task.signal
		})
			.then(response => {
					if (!response.ok) throw new Error(`Thumbnail unavailable (${response.status})`);
					const contentType = response.headers.get('content-type') || '';
					if (!contentType.startsWith('image/')) throw new Error('Invalid thumbnail response');
				return response.blob();
			})
			.then(blob => task.resolve(URL.createObjectURL(blob)), task.reject)
			.finally(() => {
				activeThumbnailRequests -= 1;
				pumpThumbnailQueue();
			});
	}
}

function queuedThumbnail(url, signal) {
	return new Promise((resolve, reject) => {
		thumbnailQueue.push({ url, signal, resolve, reject });
		pumpThumbnailQueue();
	});
}

class LazyThumbnail extends Component {
	state = { src: '', failed: false };

	componentDidMount() {
		this.mounted = true;
		if (!this.props.file.fileId) return;
		if ('IntersectionObserver' in globalThis) {
			this.observer = new IntersectionObserver(entries => {
				if (entries.some(entry => entry.isIntersecting)) this.startLoading();
			}, { rootMargin: '220px' });
			this.observer.observe(this.node);
		} else {
			this.startLoading();
		}
	}

	componentWillUnmount() {
		this.mounted = false;
		if (this.observer) this.observer.disconnect();
		if (this.controller) this.controller.abort();
		if (this.state.src) URL.revokeObjectURL(this.state.src);
	}

	startLoading() {
		if (this.loading || this.state.src || this.state.failed) return;
		this.loading = true;
		if (this.observer) this.observer.disconnect();
		this.controller = new AbortController();
		queuedThumbnail(thumbnailUrl(this.props.file), this.controller.signal)
			.then(src => {
				if (this.mounted) this.setState({ src });
				else URL.revokeObjectURL(src);
			})
			.catch(error => {
				if (this.mounted && error.name !== 'AbortError') this.setState({ failed: true });
			});
	}

	render() {
		const { file } = this.props;
		return (
			<div ref={node => { this.node = node; }} class={style.thumbnailSlot}>
				{this.state.src
					? <img src={this.state.src} alt="" />
					: <span class={style.cardIcon}>{iconFor(file)}</span>}
			</div>
		);
	}
}

function storedPreference(key, fallback) {
	try {
		const value = globalThis.localStorage.getItem(`com_nextcloud_connector.${key}`);
		return value === null ? fallback : value;
	} catch (error) {
		return fallback;
	}
}

function savePreference(key, value) {
	try { globalThis.localStorage.setItem(`com_nextcloud_connector.${key}`, String(value)); }
	catch (error) {}
}

function profileFingerprint(profile) {
	if (!profile || typeof profile !== 'object') return '';
	return JSON.stringify({
		configured: Boolean(profile.configured),
		activeProfileId: String(profile.activeProfileId || ''),
		talkAnyEnabled: Boolean(profile.talkAnyEnabled),
		accounts: (Array.isArray(profile.accounts) ? profile.accounts : []).map(account => ({
			id: String(account && account.id || ''),
			label: String(account && account.label || ''),
			username: String(account && account.username || ''),
			nextcloudUrl: String(account && account.nextcloudUrl || ''),
			active: Boolean(account && account.active),
			talkEnabled: Boolean(account && account.talkEnabled)
		}))
	});
}

function initialState(scope, initialView) {
	const workspace = restoredWorkspace(scope);
	return {
		profile: null,
		path: workspace.path,
		items: [],
		loading: true,
		busy: false,
		error: '',
		showSettings: false,
		showNewItem: false,
		previewFile: workspace.previewFile,
		editorFile: workspace.editorFile,
		renameFile: null,
		viewMode: storedPreference('viewMode', 'grid'),
		backgroundEnabled: storedPreference('backgroundEnabled', 'true') !== 'false',
		backgroundIndex: Math.floor(Math.random() * BACKGROUNDS.length),
		search: workspace.search,
		searchScope: 'folder',
		searchResults: [],
		searching: false,
		quota: null,
		quotaLoading: false,
		sortField: storedPreference('sortField', 'name'),
		sortDirection: storedPreference('sortDirection', 'asc'),
		selectedItems: [],
		trashMode: false,
		trashItems: [],
		trashLoading: false,
		shareFile: null,
		shareResult: null,
		detailsFile: null,
		contextMenu: null,
		bulkAction: null,
		notice: '',
		activationResult: null,
		addingAccount: false,
		capabilities: null,
		templates: [],
		smartView: 'files',
		listTotal: 0,
		hasMore: false,
		loadingMore: false,
		showAdvancedSearch: false,
		advancedFilters: { query: '', category: 'all', modifiedAfter: '', minimumSizeMb: '', maximumSizeMb: '' },
		uploads: [],
		dragActive: false,
		collision: null,
		showDiagnostics: false,
		// A direct visit to /cloud must always open Files. Remembering the last
		// in-page view made /cloud reopen Talk after a refresh or a timeout.
		showChat: initialView === 'chat',
		talkOverview: null,
		talkBusy: false,
		loginFlowBusy: false
	};
}

class FullChatBoundary extends Component {
	state = { failed: false };

	componentDidCatch(error) {
		this.setState({ failed: true });
		if (globalThis.console && typeof globalThis.console.error === 'function') {
			globalThis.console.error('[com_nextcloud_connector] Nextcloud Talk UI error', error);
		}
	}

	render() {
		if (!this.state.failed) return this.props.children;
		return <div class={style.chatFailure} role="alert">
			<div>💬</div><strong>{translate(this.props.language, 'talkError')}</strong>
			<p>{translate(this.props.language, 'zimbraStillAvailable')}</p>
			<button type="button" class={style.secondaryButton} onClick={this.props.onClose}>{translate(this.props.language, 'backToCloud')}</button>
		</div>;
	}
}

function formatSize(value, language = 'fr') {
	const size = Number(value || 0);
	if (!Number.isFinite(size) || size < 0) return '—';
	const units = normalizeLanguage(language) === 'fr'
		? ['o', 'Ko', 'Mo', 'Go', 'To']
		: ['B', 'KB', 'MB', 'GB', 'TB'];
	let amount = size;
	let index = 0;
	while (amount >= 1024 && index < units.length - 1) {
		amount /= 1024;
		index += 1;
	}
	return `${amount >= 10 || index === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[index]}`;
}

function formatDate(value, language = 'fr') {
	if (!value || Date.parse(value) < 86400000) return '—';
	try {
		return new Intl.DateTimeFormat(localeFor(language), {
			dateStyle: 'short',
			timeStyle: 'short'
		}).format(new Date(value));
	} catch (error) {
		return value;
	}
}

function typeLabel(file, language = 'fr') {
	if (file.isDirectory) return translate(language, 'folder');
	const kind = previewKind(file);
	if (kind === 'image') return translate(language, 'image');
	if (kind === 'video') return translate(language, 'video');
	if (kind === 'audio') return translate(language, 'audioFile');
	if (kind === 'pdf') return translate(language, 'pdfDocument');
	if (kind === 'text') return translate(language, 'textFile');
	if (canOpenInOnlyOffice(file)) return translate(language, 'officeDocument');
	return translate(language, 'file');
}

function sortFiles(files, field, direction, language = 'fr') {
	const multiplier = direction === 'desc' ? -1 : 1;
	return files.slice().sort((left, right) => {
		if (left.isDirectory !== right.isDirectory) return left.isDirectory ? -1 : 1;
		let comparison = 0;
		if (field === 'created' || field === 'modified') {
			comparison = (Date.parse(left[field] || '') || 0) - (Date.parse(right[field] || '') || 0);
		} else if (field === 'size') {
			comparison = Number(left.size || 0) - Number(right.size || 0);
		} else {
			comparison = String(left.name || '').localeCompare(String(right.name || ''), localeFor(language), {
				numeric: true,
				sensitivity: 'base'
			});
		}
		if (comparison === 0 && field !== 'name') {
			comparison = String(left.name || '').localeCompare(String(right.name || ''), localeFor(language), {
				numeric: true,
				sensitivity: 'base'
			});
		}
		return comparison * multiplier;
	});
}

function joinPath(parent, name) {
	const base = parent === '/' ? '' : parent.replace(/\/$/, '');
	return `${base}/${name}`.replace(/\/+/g, '/');
}

function parentPath(path) {
	const parts = String(path || '/').split('/').filter(Boolean);
	parts.pop();
	return parts.length ? `/${parts.join('/')}` : '/';
}

function sameOrDescendant(path, parent) {
	const candidate = String(path || '/').replace(/\/$/, '') || '/';
	const ancestor = String(parent || '/').replace(/\/$/, '') || '/';
	return candidate === ancestor || (ancestor !== '/' && candidate.startsWith(`${ancestor}/`));
}

function destinationConflicts(items, destination) {
	return items.some(item => (
		joinPath(destination, item.name) === item.path ||
		(item.isDirectory && sameOrDescendant(destination, item.path))
	));
}

async function copyText(value) {
	try {
		await globalThis.navigator.clipboard.writeText(value);
		return true;
	} catch (error) {
		const input = document.createElement('textarea');
		input.value = value;
		input.style.position = 'fixed';
		input.style.opacity = '0';
		document.body.appendChild(input);
		input.select();
		const copied = document.execCommand('copy');
		document.body.removeChild(input);
		return copied;
	}
}

function Modal({ title, children, onClose, wide, fullscreen, resizable, movable, containerRef, headerActions, dismissible = true, language = 'fr' }) {
	let modalNode;
	let suppressOverlayClickUntil = 0;

	const assignModalNode = node => {
		modalNode = node;
		if (node) globalThis.setTimeout(() => {
			if (!node || !node.ownerDocument || node.contains(node.ownerDocument.activeElement)) return;
			const first = node.querySelector('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])');
			(first || node).focus();
		}, 0);
		if (typeof containerRef === 'function') containerRef(node);
		else if (containerRef && typeof containerRef === 'object') containerRef.current = node;
	};

	const startResize = (event, direction) => {
		if (!resizable || fullscreen || !modalNode || event.button !== 0) return;
		const ownerDocument = modalNode.ownerDocument || globalThis.document;
		const ownerWindow = ownerDocument.defaultView || globalThis;
		if (ownerDocument.fullscreenElement === modalNode) return;
		event.preventDefault();
		event.stopPropagation();
		const start = modalNode.getBoundingClientRect();
		const startX = event.clientX;
		const startY = event.clientY;
		const viewportWidth = ownerWindow.innerWidth || ownerDocument.documentElement.clientWidth;
		const viewportHeight = ownerWindow.innerHeight || ownerDocument.documentElement.clientHeight;
		const minimumWidth = Math.min(wide ? 520 : 360, viewportWidth - 16);
		const minimumHeight = Math.min(wide ? 340 : 220, viewportHeight - 16);
		const previousCursor = ownerDocument.body.style.cursor;
		const previousSelection = ownerDocument.body.style.userSelect;

		Object.assign(modalNode.style, {
			position: 'fixed',
			left: `${start.left}px`,
			top: `${start.top}px`,
			width: `${start.width}px`,
			height: `${start.height}px`,
			maxWidth: 'none',
			maxHeight: 'none',
			margin: '0'
		});
		ownerDocument.body.style.cursor = `${direction}-resize`;
		ownerDocument.body.style.userSelect = 'none';

		const resize = moveEvent => {
			moveEvent.preventDefault();
			const dx = moveEvent.clientX - startX;
			const dy = moveEvent.clientY - startY;
			let left = start.left;
			let top = start.top;
			let width = start.width;
			let height = start.height;

			if (direction.includes('e')) width = Math.max(minimumWidth, Math.min(start.width + dx, viewportWidth - 8 - start.left));
			if (direction.includes('w')) {
				width = Math.max(minimumWidth, Math.min(start.width - dx, start.right - 8));
				left = start.right - width;
			}
			if (direction.includes('s')) height = Math.max(minimumHeight, Math.min(start.height + dy, viewportHeight - 8 - start.top));
			if (direction.includes('n')) {
				height = Math.max(minimumHeight, Math.min(start.height - dy, start.bottom - 8));
				top = start.bottom - height;
			}

			Object.assign(modalNode.style, {
				left: `${Math.max(8, left)}px`,
				top: `${Math.max(8, top)}px`,
				width: `${width}px`,
				height: `${height}px`
			});
		};

		const stop = stopEvent => {
			if (stopEvent) {
				stopEvent.preventDefault();
				stopEvent.stopPropagation();
			}
			suppressOverlayClickUntil = Date.now() + 400;
			ownerDocument.removeEventListener('mousemove', resize, true);
			ownerDocument.removeEventListener('mouseup', stop, true);
			ownerWindow.removeEventListener('blur', stop);
			ownerDocument.body.style.cursor = previousCursor;
			ownerDocument.body.style.userSelect = previousSelection;
		};

		ownerDocument.addEventListener('mousemove', resize, true);
		ownerDocument.addEventListener('mouseup', stop, true);
		ownerWindow.addEventListener('blur', stop);
	};

	const startMove = event => {
		if (!movable || fullscreen || !modalNode || event.button !== 0) return;
		if (event.target && event.target.closest && event.target.closest(`.${style.modalHeaderActions}`)) return;
		const ownerDocument = modalNode.ownerDocument || globalThis.document;
		const ownerWindow = ownerDocument.defaultView || globalThis;
		if (ownerDocument.fullscreenElement === modalNode) return;
		event.preventDefault();
		event.stopPropagation();
		const start = modalNode.getBoundingClientRect();
		const startX = event.clientX;
		const startY = event.clientY;
		const viewportWidth = ownerWindow.innerWidth || ownerDocument.documentElement.clientWidth;
		const viewportHeight = ownerWindow.innerHeight || ownerDocument.documentElement.clientHeight;
		const previousCursor = ownerDocument.body.style.cursor;
		const previousSelection = ownerDocument.body.style.userSelect;

		Object.assign(modalNode.style, {
			position: 'fixed',
			left: `${start.left}px`,
			top: `${start.top}px`,
			width: `${start.width}px`,
			height: `${start.height}px`,
			maxWidth: 'none',
			maxHeight: 'none',
			margin: '0'
		});
		ownerDocument.body.style.cursor = 'move';
		ownerDocument.body.style.userSelect = 'none';

		const move = moveEvent => {
			moveEvent.preventDefault();
			const maximumLeft = Math.max(8, viewportWidth - start.width - 8);
			const maximumTop = Math.max(8, viewportHeight - start.height - 8);
			const left = Math.min(maximumLeft, Math.max(8, start.left + moveEvent.clientX - startX));
			const top = Math.min(maximumTop, Math.max(8, start.top + moveEvent.clientY - startY));
			Object.assign(modalNode.style, { left: `${left}px`, top: `${top}px` });
		};

		const stop = stopEvent => {
			if (stopEvent) {
				stopEvent.preventDefault();
				stopEvent.stopPropagation();
			}
			suppressOverlayClickUntil = Date.now() + 400;
			ownerDocument.removeEventListener('mousemove', move, true);
			ownerDocument.removeEventListener('mouseup', stop, true);
			ownerWindow.removeEventListener('blur', stop);
			ownerDocument.body.style.cursor = previousCursor;
			ownerDocument.body.style.userSelect = previousSelection;
		};

		ownerDocument.addEventListener('mousemove', move, true);
		ownerDocument.addEventListener('mouseup', stop, true);
		ownerWindow.addEventListener('blur', stop);
	};

	const handleModalKeyDown = event => {
		if (dismissible && event.key === 'Escape') { event.preventDefault(); onClose(); return; }
		if (event.key !== 'Tab' || !modalNode) return;
		const focusable = Array.from(modalNode.querySelectorAll('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'));
		if (!focusable.length) { event.preventDefault(); modalNode.focus(); return; }
		const first = focusable[0];
		const last = focusable[focusable.length - 1];
		const active = modalNode.ownerDocument.activeElement;
		if (event.shiftKey && (active === first || active === modalNode)) { event.preventDefault(); last.focus(); }
		else if (!event.shiftKey && active === last) { event.preventDefault(); first.focus(); }
	};

	return (
		<div class={style.overlay} role="presentation" onClick={event => {
			if (dismissible && event.target === event.currentTarget && Date.now() > suppressOverlayClickUntil) onClose();
		}}>
			<div
				ref={assignModalNode}
				class={`${style.modal} ${wide ? style.wideModal : ''} ${fullscreen ? style.fullscreenModal : ''} ${resizable && !fullscreen ? style.resizableModal : ''}`}
					role="dialog"
					aria-modal="true"
					aria-label={title}
					tabIndex="-1"
					onKeyDown={handleModalKeyDown}
				onClick={event => event.stopPropagation()}
			>
				<div
					class={`${style.modalHeader} ${movable && !fullscreen ? style.movableHeader : ''}`}
					onMouseDown={startMove}
				>
					<h2 title={title}>{title}</h2>
					<div class={style.modalHeaderActions}>
						{headerActions}
						{dismissible && <button type="button" class={style.iconButton} onClick={onClose} aria-label={translate(language, 'closeLabel')}>×</button>}
					</div>
				</div>
				<div class={style.modalBody}>{children}</div>
				{resizable && !fullscreen && [
					['n', style.resizeHandleN], ['ne', style.resizeHandleNE],
					['e', style.resizeHandleE], ['se', style.resizeHandleSE],
					['s', style.resizeHandleS], ['sw', style.resizeHandleSW],
					['w', style.resizeHandleW], ['nw', style.resizeHandleNW]
				].map(([direction, className]) => (
					<span
						key={direction}
						class={`${style.resizeHandle} ${className}`}
						onMouseDown={event => startResize(event, direction)}
						onClick={event => { event.preventDefault(); event.stopPropagation(); }}
						aria-hidden="true"
						title={direction === 'se' ? translate(language, 'resizeWindow') : undefined}
					/>
				))}
			</div>
		</div>
	);
}

function Settings({ profile, saving, error, onSave, onClose, onDelete, onLoginFlow, inline, adding, language = 'fr' }) {
	const t = (key, variables) => translate(language, key, variables);
	let form;
	let customOfficeFields;

	const submit = event => {
		event.preventDefault();
		const data = new FormData(form);
			onSave({
			profileId: adding ? '' : String(profile.activeProfileId || ''),
			label: String(data.get('label') || '').trim(),
			nextcloudUrl: String(data.get('nextcloudUrl') || '').trim(),
			username: String(data.get('username') || '').trim(),
			appPassword: String(data.get('appPassword') || ''),
			officeMode: String(data.get('officeMode') || 'global'),
			officeProvider: String(data.get('officeProvider') || 'onlyoffice'),
			officeUrl: String(data.get('officeUrl') || '').trim(),
			officeSecurityMode: String(data.get('officeSecurityMode') || 'jwt'),
			officeJwtHeader: String(data.get('officeJwtHeader') || 'Authorization').trim(),
			officeJwtSecret: String(data.get('officeJwtSecret') || '')
		});
	};

	const changeOfficeMode = event => {
		if (customOfficeFields) customOfficeFields.hidden = event.target.value !== 'custom';
	};

	const contents = (
			<form ref={node => { form = node; }} class={style.form} onSubmit={submit}>
				<p class={style.help}>
					{t('settingsPersist')}
				</p>
				<label>
					<span>{t('cloudAccountName')}</span>
					<input name="label" type="text" maxlength="80" disabled={profile.managed} defaultValue={profile.label || ''} placeholder={t('accountNamePlaceholder')} />
				</label>
				<label>
					<span>{t('nextcloudServerAddress')}</span>
					<input name="nextcloudUrl" type="url" required disabled={profile.managed} defaultValue={profile.nextcloudUrl || ''} placeholder="https://cloud.exemple.fr" />
				</label>
				<label>
					<span>{t('nextcloudIdentifier')}</span>
					<input name="username" type="text" required disabled={profile.managed} defaultValue={profile.username || ''} autocomplete="username" />
				</label>
				<label>
					<span>{t('nextcloudAppPassword')}</span>
					<input
						name="appPassword"
						type="password"
						required={!profile.passwordSet && !profile.managed}
						disabled={profile.managed}
						autocomplete="new-password"
						placeholder={profile.passwordSet ? t('keepCurrentPassword') : ''}
					/>
				</label>
				{profile.managed && <p class={style.help}>{t('managedConnectionLocked')}</p>}
				<p class={style.help}>
					{t('preferAppPassword')}
				</p>
				{!profile.managed && onLoginFlow && (
					<div class={style.secureLoginFlow}>
						<div><strong>{t('secureNextcloudLogin')}</strong><small>{t('secureNextcloudLoginHelp')}</small></div>
						<button type="button" class={style.secondaryButton} disabled={saving} onClick={() => {
							const data = new FormData(form);
							onLoginFlow({
								nextcloudUrl: String(data.get('nextcloudUrl') || '').trim(),
								label: String(data.get('label') || '').trim()
							});
						}}>{t('connectSecurely')}</button>
					</div>
				)}
				<fieldset class={style.officeSettings}>
					<legend>{t('officeConfiguration')}</legend>
					<label>
						<span>{t('officeConfigurationMode')}</span>
						<select name="officeMode" defaultValue={profile.officeMode || 'global'} onChange={changeOfficeMode}>
							<option value="global">{t('useAdminOffice')}</option>
							<option value="custom">{t('useCustomOffice')}</option>
						</select>
					</label>
					{profile.defaultOfficeUrl && (
						<div class={style.readonlySetting}>
							<span>{t('adminOfficeDefault')}</span>
							<strong>{profile.defaultOfficeLabel} · {profile.defaultOfficeUrl}</strong>
							<small>{t('security')} : {profile.defaultOfficeSecurityMode === 'jwt' ? t('jwtEnabled') : t('withoutJwt')}</small>
						</div>
					)}
					<div ref={node => { customOfficeFields = node; }} hidden={(profile.officeMode || 'global') !== 'custom'} class={style.customOfficeSettings}>
						<p class={style.help}>{t('customOfficeHelp')}</p>
						<label>
							<span>{t('officeProvider')}</span>
							<select name="officeProvider" defaultValue={profile.officeProvider || 'onlyoffice'}>
								<option value="onlyoffice">ONLYOFFICE Docs</option>
								<option value="eurooffice">Euro-Office</option>
							</select>
						</label>
						<label>
							<span>{t('officeAddress')}</span>
							<input name="officeUrl" type="url" defaultValue={profile.officeMode === 'custom' ? profile.officeUrl || '' : ''} placeholder="https://office.exemple.fr" />
						</label>
						<label>
							<span>{t('securityMode')}</span>
							<select name="officeSecurityMode" defaultValue={profile.officeMode === 'custom' ? profile.officeSecurityMode || 'jwt' : 'jwt'}>
								<option value="jwt">{t('jwtSecurity')}</option>
								<option value="none">{t('noJwtSecurity')}</option>
							</select>
						</label>
						<label>
							<span>{t('jwtHeader')}</span>
							<input name="officeJwtHeader" type="text" maxlength="80" defaultValue={profile.officeMode === 'custom' ? profile.officeJwtHeader || 'Authorization' : 'Authorization'} />
						</label>
						<label>
							<span>{t('jwtSecret')}</span>
							<input name="officeJwtSecret" type="password" autocomplete="new-password" placeholder={profile.officeJwtSecretSet ? t('keepCurrentSecret') : t('minimum32Characters')} />
						</label>
						<p class={style.officeWarning}>{t('officeMatchingWarning')}</p>
					</div>
				</fieldset>
				{error && <div class={style.error}>{error}</div>}
				<div class={style.formActions}>
					{profile.configured && !adding && !profile.managed && onDelete && <button type="button" class={style.dangerButton} onClick={onDelete}>{t('removeCloudAccount')}</button>}
					{onClose && <button type="button" class={style.secondaryButton} onClick={onClose}>{t('cancel')}</button>}
					<button type="submit" class={style.primaryButton} disabled={saving}>
						{saving ? t('verifying') : t('saveAndTest')}
					</button>
				</div>
			</form>
	);

	if (inline) {
		return (
			<section class={style.setupPanel} aria-labelledby="nextcloud-setup-title">
				<div class={style.setupHeader}>
					<div class={style.brandIcon}>☁</div>
					<div>
						<h2 id="nextcloud-setup-title">{t('connectCloudSpace')}</h2>
						<p>{t('personalServerHelp')}</p>
					</div>
				</div>
				{contents}
			</section>
		);
	}

	return <Modal title={adding ? t('addCloudAccount') : t('editConnection')} onClose={onClose} language={language}>{contents}</Modal>;
}

function ManagedActivation({ profile, saving, error, onActivate, language = 'fr' }) {
	const t = (key, variables) => translate(language, key, variables);
	return (
		<section class={`${style.setupPanel} ${style.managedActivation}`} aria-labelledby="nextcloud-activation-title">
			<div class={style.setupHeader}>
				<div class={style.brandIcon}>☁</div>
				<div>
					<h2 id="nextcloud-activation-title">{t('activateCloudSpace')}</h2>
					<p>{t('managedIntro')}</p>
				</div>
			</div>
			<div class={style.activationFacts}>
				<div><span>{t('server')}</span><strong>{profile.managedNextcloudUrl}</strong></div>
				<div><span>{t('identifier')}</span><strong>{profile.username}</strong></div>
				<div><span>{t('initialQuota')}</span><strong>{profile.managedQuota || t('defaultNextcloudQuota')}</strong></div>
			</div>
			<p class={style.help}>{t('oneTimePasswordHelp')}</p>
			{error && <div class={style.error}>{error}</div>}
			<div class={style.activationAction}>
				<button type="button" class={style.primaryButton} disabled={saving || !profile.activationAvailable} onClick={onActivate}>
					{saving ? t('creatingAccount') : t('activateMyCloudAccount')}
				</button>
			</div>
		</section>
	);
}

function ActivationCredentials({ result, onClose, language = 'fr' }) {
	const t = (key, variables) => translate(language, key, variables);
	const credentials = [
		t('credentialsServer', { value: result.nextcloudUrl }),
		t('credentialsIdentifier', { value: result.username }),
		t('credentialsPassword', { value: result.initialPassword })
	].join('\n');
	return (
		<Modal title={t('cloudAccountReady')} onClose={onClose} dismissible={false} language={language}>
			<div class={style.activationCredentials}>
				<div class={style.activationSuccess}>{t('accountCreated')}</div>
				<div class={style.credentialWarning}>
					<strong>{t('savePasswordNow')}</strong>
					<span>{t('passwordShownOnce')}</span>
				</div>
				<div class={style.credentialGrid}>
					<div><span>{t('nextcloudServer')}</span><input type="text" readonly value={result.nextcloudUrl} onFocus={event => event.target.select()} /></div>
					<div><span>{t('identifier')}</span><input type="text" readonly value={result.username} onFocus={event => event.target.select()} /></div>
					<div><span>{t('initialPassword')}</span><input class={style.passwordValue} type="text" readonly value={result.initialPassword} onFocus={event => event.target.select()} /></div>
				</div>
				<p class={style.help}>{t('separateAppPasswordHelp')}</p>
				<div class={style.formActions}>
					<button type="button" class={style.secondaryButton} onClick={async () => {
						if (await copyText(credentials)) globalThis.alert(t('credentialsCopied'));
					}}>{t('copyCredentials')}</button>
					<button type="button" class={style.primaryButton} onClick={onClose}>{t('credentialsSaved')}</button>
				</div>
			</div>
		</Modal>
	);
}

function NewItem({ directory, templates = [], onClose, onCreate, language = 'fr' }) {
	const t = (key, variables) => translate(language, key, variables);
	let form;

	const submit = event => {
		event.preventDefault();
		const data = new FormData(form);
		onCreate({
			kind: String(data.get('kind')),
			name: String(data.get('name') || '').trim(),
			templateId: String(data.get('templateId') || ''),
			collisionPolicy: String(data.get('collisionPolicy') || 'ask')
		});
	};

	return (
		<Modal title={t('createInNextcloud')} onClose={onClose} language={language}>
			<form ref={node => { form = node; }} class={style.form} onSubmit={submit}>
				<label>
					<span>{t('type')}</span>
					<select name="kind">
						<option value="folder">{t('folder')}</option>
						<option value="docx">{t('wordDocument')}</option>
						<option value="xlsx">{t('excelWorkbook')}</option>
						<option value="pptx">{t('powerpointPresentation')}</option>
						<option value="odt">{t('odtDocument')}</option>
						<option value="ods">{t('odsSpreadsheet')}</option>
						<option value="odp">{t('odpPresentation')}</option>
					</select>
				</label>
				<label>
					<span>{t('name')}</span>
					<input name="name" type="text" required autofocus placeholder={t('newDocument')} />
				</label>
				{templates.some(template => !template.builtIn) && <label>
					<span>{t('documentTemplate')}</span>
					<select name="templateId"><option value="">{t('blankDocument')}</option>{templates.filter(template => !template.builtIn).map(template => <option value={template.id} key={template.id}>{template.name} (.{template.kind})</option>)}</select>
				</label>}
				<label>
					<span>{t('ifNameExists')}</span>
					<select name="collisionPolicy"><option value="ask">{t('askWhatToDo')}</option><option value="keep-both">{t('keepBoth')}</option><option value="replace">{t('replaceExisting')}</option><option value="skip">{t('skipFile')}</option></select>
				</label>
				<p class={style.help}>{t('location')} : {directory}</p>
				<div class={style.formActions}>
					<button type="button" class={style.secondaryButton} onClick={onClose}>{t('cancel')}</button>
					<button type="submit" class={style.primaryButton}>{t('create')}</button>
				</div>
			</form>
		</Modal>
	);
}

function Rename({ file, onClose, onRename, language = 'fr' }) {
	const t = (key, variables) => translate(language, key, variables);
	let form;
	return (
		<Modal title={t('rename')} onClose={onClose} language={language}>
			<form
				ref={node => { form = node; }}
				class={style.form}
				onSubmit={event => {
					event.preventDefault();
					onRename(String(new FormData(form).get('name') || '').trim());
				}}
			>
				<label>
					<span>{t('newName')}</span>
					<input name="name" type="text" required defaultValue={file.name} autofocus />
				</label>
				<div class={style.formActions}>
					<button type="button" class={style.secondaryButton} onClick={onClose}>{t('cancel')}</button>
					<button type="submit" class={style.primaryButton}>{t('rename')}</button>
				</div>
			</form>
		</Modal>
	);
}

function ShareLink({ file, result, saving, error, onClose, onCreate, language = 'fr' }) {
	const t = (key, variables) => translate(language, key, variables);
	let form;
	return (
		<Modal title={t('publicLinkTitle', { name: file.name })} onClose={onClose} language={language}>
			{result ? (
				<div class={style.shareResult}>
					<div class={style.readOnlyBadge}>{t('readOnly')}</div>
					<p>{t('publicLinkReady')}</p>
					<input type="url" readonly value={result.url} onFocus={event => event.target.select()} />
					<small>{t('publicLinkCapabilities')}</small>
					{result.protected && <small>{t('linkPasswordProtected')}</small>}
					{result.expireDate && <small>{t('expiration', { date: result.expireDate })}</small>}
					<div class={style.formActions}>
						<button type="button" class={style.secondaryButton} onClick={onClose}>{t('close')}</button>
						<button type="button" class={style.primaryButton} onClick={async () => {
							if (await copyText(result.url)) globalThis.alert(t('linkCopied'));
						}}>{t('copyLink')}</button>
					</div>
				</div>
			) : (
				<form ref={node => { form = node; }} class={style.form} onSubmit={event => {
					event.preventDefault();
					const data = new FormData(form);
					onCreate({
						password: String(data.get('password') || ''),
						expireDate: String(data.get('expireDate') || '')
					});
				}}>
					<div class={style.readOnlyBadge}>{t('readOnly')}</div>
					<p class={style.help}>{t('publicLinkHelp')}</p>
					<label><span>{t('linkPassword')}</span><input name="password" type="password" autocomplete="new-password" /></label>
					<label><span>{t('expirationDate')}</span><input name="expireDate" type="date" min={new Date().toISOString().slice(0, 10)} /></label>
					{error && <div class={style.error}>{error}</div>}
					<div class={style.formActions}>
						<button type="button" class={style.secondaryButton} onClick={onClose}>{t('cancel')}</button>
						<button type="submit" class={style.primaryButton} disabled={saving}>{saving ? t('creating') : t('createPublicLink')}</button>
					</div>
				</form>
			)}
		</Modal>
	);
}

function Details({ file, onClose, language = 'fr' }) {
	const t = (key, variables) => translate(language, key, variables);
	return (
		<Modal title={t('detailsTitle', { name: file.name })} onClose={onClose} language={language}>
			<div class={style.detailsPanel}>
				<div class={style.detailsHero}><span>{iconFor(file)}</span><strong>{file.name}</strong></div>
				<dl class={style.detailsList}>
					<div><dt>{t('type')}</dt><dd>{typeLabel(file, language)}</dd></div>
					<div><dt>{t('location')}</dt><dd>{parentPath(file.path)}</dd></div>
					{!file.isDirectory && <div><dt>{t('size')}</dt><dd>{formatSize(file.size, language)}</dd></div>}
					<div><dt>{t('creation')}</dt><dd>{formatDate(file.created, language)}</dd></div>
					<div><dt>{t('lastModified')}</dt><dd>{formatDate(file.modified, language)}</dd></div>
					<div><dt>{t('mimeType')}</dt><dd>{file.mimeType || t('unknown')}</dd></div>
					<div><dt>{t('cloudPath')}</dt><dd>{file.path}</dd></div>
				</dl>
				<div class={style.formActions}><button type="button" class={style.primaryButton} onClick={onClose}>{t('close')}</button></div>
			</div>
		</Modal>
	);
}

class FolderPicker extends Component {
	constructor(props) {
		super(props);
		this.state = {
			path: props.startPath || '/',
			folders: [],
			loading: true,
			error: '',
			collisionPolicy: 'keep-both'
		};
	}

	componentDidMount() {
		this.mounted = true;
		this.load(this.state.path);
	}

	componentWillUnmount() {
		this.mounted = false;
		if (this.controller) this.controller.abort();
	}

	t = (key, variables) => translate(this.props.language, key, variables);

	tp = (count, key, variables) => translatePlural(this.props.language, count, key, variables);

	load = async path => {
		if (this.controller) this.controller.abort();
		this.controller = new AbortController();
		const controller = this.controller;
		this.setState({ loading: true, error: '' });
		try {
			const result = await api(`/api/list?path=${encodeURIComponent(path)}`, { signal: controller.signal });
			const folders = (result.items || []).filter(item => (
				item.isDirectory && !this.props.items.some(selected => (
					selected.isDirectory && sameOrDescendant(item.path, selected.path)
				))
			));
			if (this.mounted && controller === this.controller) {
				this.setState({ path: result.path || path, folders, loading: false });
			}
		} catch (error) {
			if (this.mounted && controller === this.controller && error.name !== 'AbortError') {
				this.setState({ loading: false, error: error.message });
			}
		}
	};

	renderBreadcrumbs() {
		const segments = this.state.path.split('/').filter(Boolean);
		let current = '';
		return (
			<nav class={style.folderPickerBreadcrumbs} aria-label={this.t('destinationFolder')}>
				<button type="button" onClick={() => this.load('/')}>{this.t('myFiles')}</button>
				{segments.map(segment => {
					current += `/${segment}`;
					const path = current;
					return [
						<span class={style.separator}>/</span>,
						<button type="button" onClick={() => this.load(path)} title={segment}>{segment}</button>
					];
				})}
			</nav>
		);
	}

	render() {
		const { action, items, busy, error, onClose, onConfirm } = this.props;
		const { path, folders, loading, collisionPolicy } = this.state;
		const conflict = destinationConflicts(items, path);
		const titleKey = action === 'copy' ? 'bulkCopyTitle' : 'bulkMoveTitle';
		const buttonKey = action === 'copy' ? 'copyHere' : 'moveHere';
		return (
			<Modal title={this.tp(items.length, titleKey)} onClose={onClose} language={this.props.language}>
				<div class={style.folderPicker}>
					<p class={style.help}>{this.t('chooseDestinationHelp')}</p>
					<div class={style.folderPickerPath}>
						<span>{this.t('destinationFolder')}</span>
						{this.renderBreadcrumbs()}
					</div>
					{path !== '/' && (
						<button type="button" class={style.folderPickerParent} onClick={() => this.load(parentPath(path))} disabled={loading || busy}>
							↩ {this.t('parentFolder')}
						</button>
					)}
					<div class={style.folderPickerList}>
						{folders.map(folder => (
							<button type="button" key={folder.path} onClick={() => this.load(folder.path)} disabled={loading || busy}>
								<span>📁</span><strong>{folder.name}</strong><span>›</span>
							</button>
						))}
						{!loading && !folders.length && <div class={style.folderPickerEmpty}>{this.t('noSubfolders')}</div>}
					</div>
					{loading && <div class={style.progress}>{this.t('loading')}</div>}
					<label class={style.folderPickerPolicy}><span>{this.t('ifNameExists')}</span><select value={collisionPolicy} onChange={event => this.setState({ collisionPolicy: event.target.value })}>
						<option value="keep-both">{this.t('keepBoth')}</option><option value="replace">{this.t('replaceExisting')}</option><option value="skip">{this.t('skipFile')}</option>
					</select></label>
					{conflict && <div class={style.destinationWarning}>{this.t('destinationConflict')}</div>}
					{(this.state.error || error) && <div class={style.error}>{this.state.error || error}</div>}
					<div class={style.formActions}>
						<button type="button" class={style.secondaryButton} onClick={onClose} disabled={busy}>{this.t('cancel')}</button>
						<button type="button" class={style.primaryButton} onClick={() => onConfirm(path, collisionPolicy)} disabled={loading || busy || conflict}>
							{busy ? this.t('processing') : this.t(buttonKey)}
						</button>
					</div>
				</div>
			</Modal>
		);
	}
}

export default class App extends Component {
	constructor(props) {
		super(props);
		this.state = initialState(props.workspaceScope, props.initialView);
	}

	componentDidMount() {
		this.mounted = true;
		const target = globalThis.window || globalThis;
		this.profileEventTarget = target;
		this.profileDocument = globalThis.document;
		if (target && typeof target.addEventListener === 'function') {
			target.addEventListener(CLOUD_VIEW_EVENT, this.handleCloudView);
			target.addEventListener('focus', this.handleProfileReturn);
		}
		if (this.profileDocument && typeof this.profileDocument.addEventListener === 'function') {
			this.profileDocument.addEventListener('visibilitychange', this.handleProfileReturn);
		}
		initializeFloatingWindows(globalThis.document);
		attachFloatingWindowCallbacks(this.props.workspaceScope, {
			onPreviewClose: this.handleFloatingPreviewClose,
			onPreviewNavigate: this.handleFloatingPreviewNavigate,
			onEditorClose: this.handleFloatingEditorClose
		});
		setCloudRouteActive(this.props.workspaceScope, !this.state.showChat, globalThis.document);
		this.loadProfile();
	}

	handleCloudView = event => {
		const detail = event && event.detail || {};
		if (String(detail.scope || '') !== String(this.props.workspaceScope || 'default')) return;
		const showChat = detail.view === 'chat';
		this.setState({ showChat, error: '', notice: '' });
		setCloudRouteActive(this.props.workspaceScope, !showChat, this.pageNode && this.pageNode.ownerDocument);
	};

	handleFloatingPreviewClose = () => {
		if (this.mounted) this.setState({ previewFile: null });
	};

	handleFloatingPreviewNavigate = file => {
		if (this.mounted) this.setState({ previewFile: file });
	};

	handleFloatingEditorClose = () => {
		if (!this.mounted) return;
		this.setState({ editorFile: null }, () => this.loadDirectory());
	};

	language = () => normalizeLanguage(this.props.userLanguage || (this.state.profile && this.state.profile.defaultLanguage), 'fr');

	t = (key, variables) => translate(this.language(), key, variables);

	tp = (count, key, variables) => translatePlural(this.language(), count, key, variables);

	formatSize = value => formatSize(value, this.language());

	formatDate = value => formatDate(value, this.language());

	componentDidUpdate(previousProps, previousState) {
		if (
			previousState.path !== this.state.path ||
			previousState.search !== this.state.search ||
			previousState.previewFile !== this.state.previewFile ||
			previousState.editorFile !== this.state.editorFile
		) saveWorkspace(this.state, this.props.workspaceScope);

		if (
			previousState.profile !== this.state.profile ||
			previousState.items !== this.state.items ||
			previousState.searchResults !== this.state.searchResults ||
			previousState.search !== this.state.search ||
			previousState.searchScope !== this.state.searchScope ||
			previousState.sortField !== this.state.sortField ||
			previousState.sortDirection !== this.state.sortDirection ||
			previousState.previewFile !== this.state.previewFile ||
			previousState.editorFile !== this.state.editorFile
		) this.syncFloatingWindows();
	}

	componentWillUnmount() {
		this.mounted = false;
		const target = this.profileEventTarget || globalThis.window || globalThis;
		if (target && typeof target.removeEventListener === 'function') {
			target.removeEventListener(CLOUD_VIEW_EVENT, this.handleCloudView);
			target.removeEventListener('focus', this.handleProfileReturn);
		}
		if (this.profileDocument && typeof this.profileDocument.removeEventListener === 'function') {
			this.profileDocument.removeEventListener('visibilitychange', this.handleProfileReturn);
		}
		this.detachContextMenuListener();
		detachFloatingWindowCallbacks(this.props.workspaceScope);
		setCloudRouteActive(this.props.workspaceScope, false, this.pageNode && this.pageNode.ownerDocument);
		if (this.searchTimer) globalThis.clearTimeout(this.searchTimer);
		if (this.searchController) this.searchController.abort();
		if (this.uploadControllers) this.uploadControllers.forEach(controller => controller.abort());
		if (this.collisionResolver) this.collisionResolver('cancel');
		saveWorkspace(this.state, this.props.workspaceScope);
	}

	mediaItemsForFloatingWindow = () => {
		const { items, search, searchScope, searchResults, sortField, sortDirection } = this.state;
		const query = search.trim().toLowerCase();
		const globalSearchActive = searchScope === 'account' && Boolean(query);
		const filteredItems = globalSearchActive
			? searchResults
			: (query ? items.filter(file => String(file.name || '').toLowerCase().includes(query)) : items);
		return sortFiles(filteredItems, sortField, sortDirection, this.language()).filter(file => {
			const kind = file.isDirectory ? null : previewKind(file);
			return kind === 'image' || kind === 'video' || kind === 'audio';
		});
	};

	syncFloatingWindows = () => {
		const { profile, previewFile, editorFile } = this.state;
		if (!profile || !profile.configured) return;
		const ownerDocument = this.pageNode && this.pageNode.ownerDocument;
		const common = {
			scope: this.props.workspaceScope,
			profileId: profile.activeProfileId || '',
			language: this.language()
		};
		if (previewFile) openFloatingPreview({ ...common, file: previewFile, mediaFiles: this.mediaItemsForFloatingWindow() }, ownerDocument);
		if (editorFile) openFloatingEditor({ ...common, file: editorFile, officeLabel: profile.officeLabel || this.t('onlineEditor') }, ownerDocument);
	};

	openContextMenu = (event, file) => {
		event.preventDefault();
		event.stopPropagation();
		this.setState({ contextMenu: { file } });
	};

	closeContextMenu = () => this.setState({ contextMenu: null });

	detachContextMenuListener = () => {
		if (!this.contextMenuDocument) return;
		this.contextMenuDocument.removeEventListener('mousedown', this.closeContextMenuFromOutside, true);
		this.contextMenuDocument = null;
	};

	setPageNode = node => {
		const nextDocument = node && node.ownerDocument;
		this.pageNode = node;
		if (nextDocument) {
			initializeFloatingWindows(nextDocument);
			setCloudRouteActive(this.props.workspaceScope, !this.state.showChat, nextDocument);
		}
		if (nextDocument === this.contextMenuDocument) return;
		this.detachContextMenuListener();
		if (nextDocument) {
			this.contextMenuDocument = nextDocument;
			this.contextMenuDocument.addEventListener('mousedown', this.closeContextMenuFromOutside, true);
		}
	};

	closeContextMenuFromOutside = event => {
		if (this.state.contextMenu && (!event.target || !event.target.closest || !event.target.closest(`.${style.actionLauncher}`))) {
			this.closeContextMenu();
		}
	};

	handleProfileReturn = () => {
		if (!this.mounted || (this.profileDocument && this.profileDocument.hidden)) return;
		const now = Date.now();
		if (now - Number(this.profileReturnAt || 0) < 1000) return;
		this.profileReturnAt = now;
		this.loadProfile({ quiet: true });
	};

	async loadProfile(options = {}) {
		const quiet = Boolean(options.quiet);
		const requestId = Number(this.profileRequestId || 0) + 1;
		this.profileRequestId = requestId;
		if (!quiet) this.setState({ loading: true, error: '' });
		try {
			const profile = await api('/api/profile');
			if (!this.mounted || requestId !== this.profileRequestId) return;
			const previousProfile = this.state.profile;
			const changed = profileFingerprint(previousProfile) !== profileFingerprint(profile);
			const activeChanged = String(previousProfile && previousProfile.activeProfileId || '') !== String(profile.activeProfileId || '');
			setActiveProfile(profile.activeProfileId || '');
			if (!profile.configured) {
				closeFloatingWindows(this.props.workspaceScope, this.pageNode && this.pageNode.ownerDocument);
				resetWorkspace(this.props.workspaceScope);
			}
			this.setState({
				profile,
				loading: false,
				showSettings: false,
				...(profile.configured && !activeChanged ? {} : {
					path: '/', search: '', searchResults: [], selectedItems: [], previewFile: null, editorFile: null
				})
			}, () => {
				if (!profile.talkAnyEnabled && this.state.showChat) setCloudView(this.props.workspaceScope, 'files');
				if (profile.configured && (!quiet || changed)) {
					this.loadDirectory(this.state.path);
					this.loadQuota();
					this.loadFeatureMetadata();
				}
			});
		} catch (error) {
			if (!this.mounted || requestId !== this.profileRequestId) return;
			if (!quiet) this.setState({ loading: false, error: error.message });
		}
	}

	setTalkEnabled = async enabled => {
		if (this.state.talkBusy) return;
		this.setState({ talkBusy: true, error: '', notice: '' });
		try {
			const activeProfileId = this.state.profile && this.state.profile.activeProfileId || '';
			const result = await api('/api/talk/settings', {
				method: 'POST',
				json: { enabled, profileId: activeProfileId },
				profileId: activeProfileId
			});
			const profile = result.profile || this.state.profile;
			const talkOverview = result.overview || null;
			this.setState({
				profile,
				talkOverview,
				talkBusy: false,
				notice: enabled ? this.t('chatEnabledNotice') : this.t('chatDisabledNotice')
			}, () => {
				notifyTalkNavigation({
					scope: this.props.workspaceScope,
					enabled: Boolean(profile.talkAnyEnabled),
					unread: Math.max(0, Number(talkOverview && talkOverview.unread || 0))
				});
				if (enabled) setCloudView(this.props.workspaceScope, 'chat');
			});
		} catch (error) {
			if (this.mounted) this.setState({ talkBusy: false, error: error && error.status === 409 ? this.t('noTalk') : (error.message || this.t('talkError')) });
		}
	};

	openChat = () => {
		if (this.state.profile && this.state.profile.talkAnyEnabled) setCloudView(this.props.workspaceScope, 'chat');
	};

	closeChat = () => setCloudView(this.props.workspaceScope, 'files');

	async loadFeatureMetadata() {
		const results = await Promise.allSettled([api('/api/capabilities'), api('/api/templates')]);
		if (!this.mounted) return;
		this.setState({
			capabilities: results[0].status === 'fulfilled' ? results[0].value : null,
			templates: results[1].status === 'fulfilled' ? (results[1].value.items || []) : []
		});
	}

	async loadQuota() {
		this.setState({ quotaLoading: true });
		try {
			const quota = await api('/api/quota');
			this.setState({ quota, quotaLoading: false });
		} catch (error) {
			this.setState({ quota: null, quotaLoading: false });
		}
	}

	openTrash = () => {
		closeFloatingWindows(this.props.workspaceScope, this.pageNode && this.pageNode.ownerDocument);
		this.setState({
			trashMode: true,
			selectedItems: [],
			previewFile: null,
			editorFile: null,
			error: ''
		}, this.loadTrash);
	};

	closeTrash = () => {
		this.setState({ trashMode: false, error: '' }, () => {
			this.loadDirectory(this.state.path);
			this.loadQuota();
		});
	};

	loadTrash = async () => {
		this.setState({ trashLoading: true, error: '' });
		try {
			const result = await api('/api/trash');
			this.setState({ trashItems: result.items || [], trashLoading: false });
		} catch (error) {
			this.setState({ trashLoading: false, error: error.message });
		}
	};

	restoreTrashItem = async item => {
		this.setState({ busy: true, error: '' });
		try {
			await api('/api/trash/restore', { method: 'POST', json: { trashId: item.trashId } });
			this.setState({ busy: false });
			this.loadTrash();
			this.loadQuota();
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	};

	deleteTrashItem = async item => {
		if (!globalThis.confirm(this.t('deleteForeverConfirm', { name: item.name }))) return;
		this.setState({ busy: true, error: '' });
		try {
			await api('/api/trash/delete', { method: 'POST', json: { trashId: item.trashId } });
			this.setState({ busy: false });
			this.loadTrash();
			this.loadQuota();
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	};

	emptyTrash = async () => {
		if (!this.state.trashItems.length) return;
		if (!globalThis.confirm(this.t('emptyTrashConfirm'))) return;
		this.setState({ busy: true, error: '' });
		try {
			await api('/api/trash/empty', { method: 'POST', json: {} });
			this.setState({ busy: false, trashItems: [] });
			this.loadQuota();
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	};

	async loadDirectory(path = this.state.path) {
		this.setState({ loading: true, error: '', notice: '', path, selectedItems: [], bulkAction: null, smartView: 'files' });
		try {
			const result = await api(`/api/list?path=${encodeURIComponent(path)}&offset=0&limit=250`);
			this.setState({ items: result.items || [], path: result.path || path, loading: false, listTotal: Number(result.total || 0), hasMore: Boolean(result.hasMore) });
			return null;
		} catch (error) {
			this.setState({ loading: false, error: error.message });
			return error;
		}
	}

	loadMore = async () => {
		if (this.state.loadingMore || !this.state.hasMore || this.state.smartView !== 'files') return;
		this.setState({ loadingMore: true, error: '' });
		try {
			const result = await api(`/api/list?path=${encodeURIComponent(this.state.path)}&offset=${this.state.items.length}&limit=250`);
			const known = new Set(this.state.items.map(item => item.path));
			this.setState({
				items: this.state.items.concat((result.items || []).filter(item => !known.has(item.path))),
				loadingMore: false,
				listTotal: Number(result.total || this.state.listTotal),
				hasMore: Boolean(result.hasMore)
			});
		} catch (error) { this.setState({ loadingMore: false, error: error.message }); }
	};

	navigateToDirectory = path => {
		if (this.searchTimer) globalThis.clearTimeout(this.searchTimer);
		if (this.searchController) this.searchController.abort();
		this.searchSequence = (this.searchSequence || 0) + 1;
		this.setState({
			search: '',
			searchScope: 'folder',
			searchResults: [],
			searching: false,
			smartView: 'files',
			showAdvancedSearch: false
		}, () => this.loadDirectory(path));
	};

	loadSmartView = async smartView => {
		if (smartView === 'files') { this.loadDirectory(this.state.path); return; }
		this.setState({ smartView, loading: true, error: '', notice: '', selectedItems: [], search: '', searchResults: [], hasMore: false });
		try {
			let items = [];
			if (smartView === 'favorites') items = (await api('/api/favorites')).items || [];
			else if (smartView === 'recent') items = (await api('/api/recent?days=30')).items || [];
			else {
				const sharedWithMe = smartView === 'sharedWithMe';
				const shares = (await api(`/api/shares?sharedWithMe=${sharedWithMe ? 'true' : 'false'}`)).items || [];
				const filtered = smartView === 'publicLinks' ? shares.filter(share => Number(share.shareType) === 3) : shares;
				const paths = Array.from(new Set(filtered.map(share => share.path).filter(Boolean))).slice(0, 100);
				const resolved = await Promise.allSettled(paths.map(path => api(`/api/stat?path=${encodeURIComponent(path)}`)));
				items = resolved.filter(result => result.status === 'fulfilled').map(result => result.value);
			}
			this.setState({ items, loading: false, listTotal: items.length });
		} catch (error) { this.setState({ loading: false, error: error.message }); }
	};

	updateAdvancedFilters = patch => this.setState({ advancedFilters: { ...this.state.advancedFilters, ...patch } });

	runAdvancedSearch = async () => {
		const values = this.state.advancedFilters;
		const scope = this.state.searchScope === 'folder' ? this.state.path : '/';
		const params = new URLSearchParams({ q: values.query || '', scope, category: values.category || 'all', limit: '500' });
		if (values.modifiedAfter) params.set('modifiedAfter', new Date(`${values.modifiedAfter}T00:00:00Z`).toUTCString());
		if (values.minimumSizeMb !== '') params.set('minimumSize', String(Math.max(0, Number(values.minimumSizeMb) || 0) * 1024 * 1024));
		if (values.maximumSizeMb !== '') params.set('maximumSize', String(Math.max(0, Number(values.maximumSizeMb) || 0) * 1024 * 1024));
		this.setState({ searching: true, error: '', searchResults: [], smartView: 'advanced' });
		try {
			const result = await api(`/api/search/advanced?${params.toString()}`);
			this.setState({ searching: false, searchResults: result.items || [], listTotal: (result.items || []).length });
		} catch (error) { this.setState({ searching: false, error: error.message }); }
	};

	resetAdvancedSearch = () => this.setState({ advancedFilters: { query: '', category: 'all', modifiedAfter: '', minimumSizeMb: '', maximumSizeMb: '' }, smartView: 'files', searchResults: [] }, () => this.loadDirectory(this.state.path));

	updateSearch = search => {
		this.setState({ search, error: '' }, () => {
			if (this.state.searchScope === 'account') this.scheduleGlobalSearch(search);
		});
	};

	setSearchScope = searchScope => {
		if (this.searchTimer) globalThis.clearTimeout(this.searchTimer);
		if (this.searchController) this.searchController.abort();
		this.searchSequence = (this.searchSequence || 0) + 1;
		this.setState({ searchScope, searchResults: [], searching: false, error: '' }, () => {
			if (searchScope === 'account') this.scheduleGlobalSearch(this.state.search);
		});
	};

	scheduleGlobalSearch(search) {
		if (this.searchTimer) globalThis.clearTimeout(this.searchTimer);
		if (this.searchController) this.searchController.abort();
		const query = String(search || '').trim();
		if (!query) {
			this.setState({ searchResults: [], searching: false });
			return;
		}
		this.searchTimer = globalThis.setTimeout(() => this.performGlobalSearch(query), 350);
	}

	async performGlobalSearch(query) {
		const sequence = (this.searchSequence || 0) + 1;
		this.searchSequence = sequence;
		this.searchController = new AbortController();
		this.setState({ searching: true, searchResults: [], error: '' });
		try {
			const result = await api(`/api/search?q=${encodeURIComponent(query)}`, {
				signal: this.searchController.signal
			});
			if (sequence === this.searchSequence && this.state.searchScope === 'account') {
				this.setState({ searchResults: result.items || [], searching: false });
			}
			return null;
		} catch (error) {
			if (error.name !== 'AbortError' && sequence === this.searchSequence) {
				this.setState({ searching: false, error: error.message });
				return error;
			}
			return null;
		}
	}

	async saveProfile(values) {
		this.setState({ busy: true, error: '', notice: '' });
		try {
			const profile = await api('/api/profile', { method: 'POST', json: values });
			setActiveProfile(profile.activeProfileId || '');
			closeFloatingWindows(this.props.workspaceScope, this.pageNode && this.pageNode.ownerDocument);
			resetWorkspace(this.props.workspaceScope);
			this.setState({ profile, busy: false, showSettings: false, addingAccount: false, path: '/', trashMode: false, selectedItems: [], previewFile: null, editorFile: null });
			this.loadDirectory('/');
			this.loadQuota();
			this.loadFeatureMetadata();
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	}

	connectWithLoginFlow = async ({ nextcloudUrl, label }) => {
		if (!nextcloudUrl) { this.setState({ error: this.t('nextcloudUrlRequired') }); return; }
		const popup = globalThis.open('', 'nextcloud-zimbra-login', 'popup=yes,width=760,height=820,resizable=yes,scrollbars=yes');
		if (popup) {
			popup.document.title = 'Nextcloud';
			popup.document.body.textContent = this.t('startingSecureLogin');
		}
		this.setState({ busy: true, loginFlowBusy: true, error: '' });
		try {
			const flow = await api('/api/login-flow/start', { method: 'POST', json: { nextcloudUrl } });
			if (!popup) throw new Error(this.t('allowLoginPopup'));
			popup.location.href = flow.login;
			const deadline = Date.now() + Math.min(1200, Number(flow.expiresIn || 1200)) * 1000;
			while (Date.now() < deadline) {
				if (popup.closed) throw new Error(this.t('secureLoginCancelled'));
				await new Promise(resolve => globalThis.setTimeout(resolve, 1800));
				const result = await api('/api/login-flow/poll', { method: 'POST', json: { ...flow, label } });
				if (result.pending) continue;
				const profile = result.profile;
				setActiveProfile(profile.activeProfileId || '');
				try { popup.close(); } catch (error) {}
				closeFloatingWindows(this.props.workspaceScope, this.pageNode && this.pageNode.ownerDocument);
				resetWorkspace(this.props.workspaceScope);
				this.setState({ profile, busy: false, loginFlowBusy: false, showSettings: false, addingAccount: false, path: '/', selectedItems: [] });
				this.loadDirectory('/'); this.loadQuota(); this.loadFeatureMetadata();
				return;
			}
			throw new Error(this.t('secureLoginExpired'));
		} catch (error) {
			try { if (popup && !popup.closed) popup.close(); } catch (ignored) {}
			this.setState({ busy: false, loginFlowBusy: false, error: error.message });
		}
	};

	switchAccount = async event => {
		const profileId = typeof event === 'string' ? event : String(event.target.value || '');
		if (!profileId || profileId === (this.state.profile && this.state.profile.activeProfileId)) return;
		closeFloatingWindows(this.props.workspaceScope, this.pageNode && this.pageNode.ownerDocument);
		this.setState({ busy: true, error: '', previewFile: null, editorFile: null, contextMenu: null });
		try {
			const profile = await api('/api/profile/select', { method: 'POST', json: { profileId } });
			setActiveProfile(profile.activeProfileId || profileId);
			resetWorkspace(this.props.workspaceScope);
			this.setState({ profile, busy: false, path: '/', search: '', searchResults: [], trashMode: false, selectedItems: [], quota: null });
			this.loadDirectory('/');
			this.loadQuota();
			this.loadFeatureMetadata();
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	};

	removeActiveAccount = async () => {
		const profile = this.state.profile;
		if (!profile || !profile.activeProfileId || profile.managed) return;
		const displayName = profile.label || profile.username || profile.nextcloudUrl;
		if (!globalThis.confirm(this.t('removeAccountConfirm', { name: displayName }))) return;
		this.setState({ busy: true, error: '' });
		try {
			const nextProfile = await api('/api/profile/delete', {
				method: 'POST', json: { profileId: profile.activeProfileId }
			});
			setActiveProfile(nextProfile.activeProfileId || '');
			closeFloatingWindows(this.props.workspaceScope, this.pageNode && this.pageNode.ownerDocument);
			resetWorkspace(this.props.workspaceScope);
			this.setState({
				profile: nextProfile, busy: false, showSettings: false, addingAccount: false,
				path: '/', items: [], search: '', searchResults: [], trashMode: false,
				selectedItems: [], quota: null, previewFile: null, editorFile: null
			}, () => {
				if (nextProfile.configured) { this.loadDirectory('/'); this.loadQuota(); this.loadFeatureMetadata(); }
			});
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	};

	openAddAccount = () => {
		if (!this.state.profile || !this.state.profile.canAddAccount) {
			this.setState({ error: this.t('maxAccountsReached') });
			return;
		}
		this.setState({ addingAccount: true, showSettings: true, error: '' });
	};

	activateManagedAccount = async () => {
		this.setState({ busy: true, error: '' });
		try {
			const activationResult = await api('/api/activate', { method: 'POST', json: {} });
			setActiveProfile(activationResult.profile.activeProfileId || '');
			closeFloatingWindows(this.props.workspaceScope, this.pageNode && this.pageNode.ownerDocument);
			resetWorkspace(this.props.workspaceScope);
			this.setState({
				profile: activationResult.profile,
				activationResult,
				busy: false,
				path: '/',
				search: '',
				previewFile: null,
				editorFile: null
			});
			this.loadDirectory('/');
			this.loadQuota();
			this.loadFeatureMetadata();
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	};

	openFile = file => {
		if (file.isDirectory) {
			this.navigateToDirectory(file.path);
			return;
		}
		if (previewKind(file)) {
			this.setState({ previewFile: file });
			return;
		}
		if (canOpenInOnlyOffice(file) && file.canEdit !== false) {
			this.setState({ editorFile: file });
			return;
		}
		this.downloadFile(file);
	};

	editFile = file => {
		if (file && canOpenInOnlyOffice(file) && file.canEdit !== false && !file.lockedByOther) this.setState({ editorFile: file });
	};

	downloadUrl = async (url, name) => {
		if (this.state.busy) return;
		this.setState({ busy: true, error: '', notice: '' });
		try {
			const blob = await fetchDownload(url);
			const objectUrl = URL.createObjectURL(blob);
			const anchor = document.createElement('a');
			anchor.href = objectUrl;
			anchor.download = name;
			anchor.style.display = 'none';
			document.body.appendChild(anchor);
			anchor.click();
			document.body.removeChild(anchor);
			globalThis.setTimeout(() => URL.revokeObjectURL(objectUrl), 30000);
			this.setState({ busy: false });
		} catch (error) {
			this.setState({ busy: false, error: error.message || this.t('downloadFailed') });
		}
	};

	downloadFile(file) {
		if (file.hideDownload || file.canDownload === false) { this.setState({ error: this.t('downloadNotAllowed') }); return; }
		this.downloadUrl(fileUrl(file.path, 'attachment'), file.name);
	}

	toggleFavorite = async file => {
		try {
			await api('/api/favorite', { method: 'POST', json: { path: file.path, favorite: !file.favorite } });
			const update = item => item.path === file.path ? { ...item, favorite: !file.favorite } : item;
			this.setState({ items: this.state.items.map(update), searchResults: this.state.searchResults.map(update), contextMenu: null });
			if (this.state.smartView === 'favorites' && file.favorite) this.loadSmartView('favorites');
		} catch (error) { this.setState({ error: error.message, contextMenu: null }); }
	};

	downloadSelectedArchive = async () => {
		const items = this.state.selectedItems;
		if (!items.length) return;
		const parents = new Set(items.map(item => parentPath(item.path)));
		if (parents.size !== 1) { this.setState({ error: this.t('zipSameFolderOnly') }); return; }
		await this.downloadUrl(archiveUrl(Array.from(parents)[0], items.map(item => item.name)), 'Cloud-files.zip');
	};

	downloadItem = async file => {
		if (!file.isDirectory) { this.downloadFile(file); return; }
		await this.downloadUrl(archiveUrl(parentPath(file.path), [file.name]), `${file.name}.zip`);
	};

	uploadFiles = event => {
		const files = Array.from((event.target && event.target.files) || event.files || []);
		if (event.target) event.target.value = '';
		this.queueUploads(files);
	};

	queueUploads = files => {
		if (!files.length) return;
		const basePath = this.state.path;
		const jobs = files.map((file, index) => {
			const relative = String(file.webkitRelativePath || file.relativePath || file.name).replace(/^\/+/, '');
			return {
				id: `${Date.now()}-${index}-${Math.random().toString(16).slice(2)}`,
				file,
				name: relative,
				destination: joinPath(basePath, relative),
				status: 'waiting', progress: 0, message: '', uploadId: ''
			};
		});
		this.setState({ uploads: this.state.uploads.concat(jobs), error: '', dragActive: false }, () => this.processUploadQueue());
	};

	updateUpload = (id, patch, callback) => this.setState(state => ({ uploads: state.uploads.map(job => job.id === id ? { ...job, ...patch } : job) }), callback);

	requestCollisionChoice = job => new Promise(resolve => {
		this.collisionResolver = resolve;
		this.setState({ collision: job });
	});

	resolveCollision = choice => {
		const resolve = this.collisionResolver;
		this.collisionResolver = null;
		this.setState({ collision: null });
		if (resolve) resolve(choice || 'cancel');
	};

	processUploadQueue = async () => {
		if (this.processingUploads) return;
		this.processingUploads = true;
		try {
			while (this.mounted) {
				const job = this.state.uploads.find(item => item.status === 'waiting');
				if (!job) break;
				await this.processUploadJob(job, 'ask');
			}
		} finally {
			this.processingUploads = false;
			if (this.mounted) { this.loadQuota(); this.loadDirectory(this.state.path); }
		}
	};

	processUploadJob = async (job, collisionPolicy) => {
		const controller = new AbortController();
		if (!this.uploadControllers) this.uploadControllers = new Map();
		this.uploadControllers.set(job.id, controller);
		this.updateUpload(job.id, { status: 'uploading', progress: 1, message: this.t('preparingUpload') });
		let uploadId = '';
		try {
			if (job.file.size === 0) {
				let empty;
				try {
					empty = await api('/api/upload/empty', { method: 'POST', json: {
						path: job.destination, collisionPolicy,
						createParents: Boolean(job.file.webkitRelativePath || job.file.relativePath)
					}, signal: controller.signal });
				} catch (error) {
					if (error.status !== 409 || collisionPolicy !== 'ask') throw error;
					const choice = await this.requestCollisionChoice(job);
					if (choice === 'cancel') { this.updateUpload(job.id, { status: 'cancelled', progress: 0, message: this.t('uploadCancelled') }); return; }
					return this.processUploadJob(job, choice);
				}
				this.updateUpload(job.id, { status: 'done', progress: 100, message: empty.skipped ? this.t('fileSkipped') : (empty.renamed ? this.t('uploadedWithNewName') : this.t('uploadComplete')) });
				return;
			}
			let started;
			try {
				started = await api('/api/upload/start', { method: 'POST', json: { path: job.destination, totalBytes: job.file.size, collisionPolicy, createParents: Boolean(job.file.webkitRelativePath || job.file.relativePath) }, signal: controller.signal });
			} catch (error) {
				if (error.status !== 409 || collisionPolicy !== 'ask') throw error;
				const choice = await this.requestCollisionChoice(job);
				if (choice === 'cancel') { this.updateUpload(job.id, { status: 'cancelled', progress: 0, message: this.t('uploadCancelled') }); return; }
				return this.processUploadJob(job, choice);
			}
			if (started.skipped) { this.updateUpload(job.id, { status: 'done', progress: 100, message: this.t('fileSkipped') }); return; }
			uploadId = started.uploadId;
			this.updateUpload(job.id, { uploadId, message: this.t('uploading') });
			const chunkBytes = Math.max(5 * 1024 * 1024, Number(started.chunkBytes || 8 * 1024 * 1024));
			const count = Math.ceil(job.file.size / chunkBytes);
			for (let index = 0; index < count; index += 1) {
				const chunk = job.file.slice(index * chunkBytes, Math.min(job.file.size, (index + 1) * chunkBytes));
				let lastError;
				for (let attempt = 1; attempt <= 3; attempt += 1) {
					try {
						await api(`/api/upload/chunk?uploadId=${encodeURIComponent(uploadId)}&index=${index + 1}&totalBytes=${job.file.size}`, {
							method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: chunk, signal: controller.signal
						});
						lastError = null; break;
					} catch (error) {
						if (error.name === 'AbortError') throw error;
						lastError = error;
						if (attempt < 3) await new Promise(resolve => globalThis.setTimeout(resolve, attempt * 700));
					}
				}
				if (lastError) throw lastError;
				this.updateUpload(job.id, { progress: Math.max(2, Math.round((index + 1) / count * 94)), message: this.t('uploadChunkProgress', { current: index + 1, total: count }) });
			}
			await api('/api/upload/finish', { method: 'POST', json: { uploadId, targetPath: started.targetPath, totalBytes: job.file.size, replace: Boolean(started.replace) }, signal: controller.signal });
			this.updateUpload(job.id, { status: 'done', progress: 100, message: started.renamed ? this.t('uploadedWithNewName') : this.t('uploadComplete') });
		} catch (error) {
			if (error.name === 'AbortError') this.updateUpload(job.id, { status: 'cancelled', message: this.t('uploadCancelled') });
			else this.updateUpload(job.id, { status: 'error', message: error.message });
			if (uploadId) {
				try { await api('/api/upload/cancel', { method: 'POST', json: { uploadId } }); } catch (ignored) {}
			}
		} finally { this.uploadControllers.delete(job.id); }
	};

	cancelUpload = job => {
		const controller = this.uploadControllers && this.uploadControllers.get(job.id);
		if (controller) controller.abort();
		else this.updateUpload(job.id, { status: 'cancelled', message: this.t('uploadCancelled') });
	};

	retryUpload = job => this.updateUpload(job.id, { status: 'waiting', progress: 0, message: '' }, () => this.processUploadQueue());

	clearCompletedUploads = () => this.setState({ uploads: this.state.uploads.filter(job => job.status === 'waiting' || job.status === 'uploading') });

	createItem = async ({ kind, name: rawName, templateId = '', collisionPolicy = 'ask' }) => {
		let name = rawName.replace(/[\\/]/g, '').trim();
		if (!name) return;
		if (kind !== 'folder' && !name.toLowerCase().endsWith(`.${kind}`)) name += `.${kind}`;
		this.setState({ busy: true, error: '' });
		try {
			await api('/api/create', {
				method: 'POST',
				json: { path: joinPath(this.state.path, name), kind, templateId, collisionPolicy }
			});
			this.setState({ busy: false, showNewItem: false });
			this.loadDirectory();
			this.loadQuota();
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	};

	renameItem = async name => {
		const file = this.state.renameFile;
		if (!file || !name || name === file.name) {
			this.setState({ renameFile: null });
			return;
		}
		this.setState({ busy: true, error: '' });
		try {
			await api('/api/move', {
				method: 'POST',
				json: { from: file.path, to: joinPath(parentPath(file.path), name) }
			});
			this.setState({ busy: false, renameFile: null });
			this.loadDirectory();
			this.loadQuota();
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	};

	deleteItem = async file => {
		if (!globalThis.confirm(this.t('moveToTrashConfirm', { name: file.name }))) return;
		this.setState({ busy: true, error: '' });
		try {
			await api('/api/delete', { method: 'POST', json: { path: file.path } });
			this.setState({ busy: false, selectedItems: this.state.selectedItems.filter(item => item.path !== file.path) });
			this.loadDirectory();
			this.loadQuota();
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	};

	openBulkAction = bulkAction => {
		if (!this.state.selectedItems.length) return;
		this.setState({ bulkAction, error: '', notice: '' });
	};

	deleteSelected = () => {
		const count = this.state.selectedItems.length;
		if (!count || !globalThis.confirm(this.tp(count, 'batchTrashConfirm'))) return;
		this.executeBulkAction('delete');
	};

	executeBulkAction = async (operation, destination = '', collisionPolicy = 'keep-both') => {
		const items = this.state.selectedItems.slice();
		if (!items.length) return;
		this.setState({ busy: true, error: '', notice: '' });
		try {
			const result = await api('/api/batch', {
				method: 'POST',
				json: {
					operation,
					paths: items.map(item => item.path),
						...(operation === 'delete' ? {} : { destination, collisionPolicy })
				}
			});
			const completed = Array.isArray(result.completed) ? result.completed : [];
			const failures = Array.isArray(result.failures) ? result.failures : [];
			if (operation !== 'copy' && completed.length) {
				const changed = new Set(completed);
				if (
					(this.state.previewFile && changed.has(this.state.previewFile.path)) ||
					(this.state.editorFile && changed.has(this.state.editorFile.path))
				) {
					closeFloatingWindows(this.props.workspaceScope, this.pageNode && this.pageNode.ownerDocument);
					this.setState({ previewFile: null, editorFile: null });
				}
			}
			this.setState({ selectedItems: [], bulkAction: null });
			const query = this.state.search.trim();
			let refreshError = null;
			if (this.state.searchScope === 'account' && query) refreshError = await this.performGlobalSearch(query);
			else refreshError = await this.loadDirectory(this.state.path);
			await this.loadQuota();
			const successKey = operation === 'copy' ? 'batchCopied' : (operation === 'move' ? 'batchMoved' : 'batchTrashed');
			const notice = completed.length ? this.tp(completed.length, successKey, { destination }) : '';
			let failureMessage = '';
			if (failures.length) {
				const details = failures.slice(0, 3).map(failure => `${failure.path} : ${failure.error}`).join(' · ');
				failureMessage = `${this.tp(failures.length, 'batchFailed')} ${details}`;
			}
			this.setState({
				busy: false,
				notice: refreshError ? '' : notice,
				error: refreshError ? refreshError.message : failureMessage
			});
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	};

	createShare = async options => {
		const file = this.state.shareFile;
		if (!file) return;
		this.setState({ busy: true, error: '', shareResult: null });
		try {
			const shareResult = await api('/api/share', {
				method: 'POST',
				json: { path: file.path, password: options.password, expireDate: options.expireDate }
			});
			this.setState({ busy: false, shareResult });
		} catch (error) {
			this.setState({ busy: false, error: error.message });
		}
	};

	setViewMode = viewMode => {
		savePreference('viewMode', viewMode);
		this.setState({ viewMode });
	};

	setSortField = sortField => {
		savePreference('sortField', sortField);
		this.setState({ sortField });
	};

	toggleSortDirection = () => {
		const sortDirection = this.state.sortDirection === 'asc' ? 'desc' : 'asc';
		savePreference('sortDirection', sortDirection);
		this.setState({ sortDirection });
	};

	toggleSelection = file => {
		if (!file) return;
		const alreadySelected = this.state.selectedItems.some(item => item.path === file.path);
		if (!alreadySelected && this.state.selectedItems.length >= MAX_BULK_ITEMS) {
			this.setState({ error: this.t('bulkLimit', { count: MAX_BULK_ITEMS }) });
			return;
		}
		const selectedItems = alreadySelected
			? this.state.selectedItems.filter(item => item.path !== file.path)
			: this.state.selectedItems.concat([file]);
		this.setState({ selectedItems, error: '', notice: '' });
	};

	toggleAllVisible = visibleItems => {
		const visiblePaths = new Set(visibleItems.map(file => file.path));
		const allVisibleSelected = visibleItems.length > 0 && visibleItems.every(file =>
			this.state.selectedItems.some(item => item.path === file.path)
		);
		let selectedItems = allVisibleSelected
			? this.state.selectedItems.filter(item => !visiblePaths.has(item.path))
			: this.state.selectedItems.concat(visibleItems.filter(file =>
				!this.state.selectedItems.some(item => item.path === file.path)
			));
		const limited = selectedItems.length > MAX_BULK_ITEMS;
		if (limited) selectedItems = selectedItems.slice(0, MAX_BULK_ITEMS);
		this.setState({
			selectedItems,
			error: limited ? this.t('bulkLimit', { count: MAX_BULK_ITEMS }) : '',
			notice: ''
		});
	};

	toggleBackground = () => {
		const backgroundEnabled = !this.state.backgroundEnabled;
		savePreference('backgroundEnabled', backgroundEnabled);
		this.setState({ backgroundEnabled });
	};

	nextBackground = () => {
		this.setState({
			backgroundEnabled: true,
			backgroundIndex: (this.state.backgroundIndex + 1) % BACKGROUNDS.length
		});
		savePreference('backgroundEnabled', true);
	};

	refreshView = () => {
		this.loadQuota();
		if (this.state.smartView === 'advanced') { this.runAdvancedSearch(); return; }
		if (this.state.smartView !== 'files') { this.loadSmartView(this.state.smartView); return; }
		const query = this.state.search.trim();
		if (this.state.searchScope === 'account' && query) this.performGlobalSearch(query);
		else this.loadDirectory();
	};

	renderActions(file) {
		const open = this.state.contextMenu && this.state.contextMenu.file.path === file.path;
		return (
			<div class={style.actionLauncher}>
				<button type="button" onClick={event => open ? this.closeContextMenu() : this.openContextMenu(event, file)} aria-expanded={Boolean(open)} aria-haspopup="menu" aria-label={this.t('actionsFor', { name: file.name })}>
					⋮ {this.t('actions')}
				</button>
				{open && this.renderContextMenu(file)}
			</div>
		);
	}

	renderContextMenu(file) {
		const canPreview = !file.isDirectory && Boolean(previewKind(file));
		const canEdit = !file.isDirectory && canOpenInOnlyOffice(file) && file.canEdit !== false && !file.lockedByOther;
		const canDownload = file.canDownload !== false && !file.hideDownload;
		const canCopy = file.canCopy !== false && file.canRead !== false;
		const canShare = file.canShare !== false && feature(this.state.capabilities, 'sharing', false);
		const primaryLabel = file.isDirectory ? this.t('open') : (canPreview ? this.t('view') : (canEdit ? this.t('editOnline') : this.t('download')));
		return (
			<div class={style.contextMenu} role="menu" onMouseDown={event => event.stopPropagation()} onClick={event => event.stopPropagation()}>
					<div class={style.contextTitle}><span>{iconFor(file)}</span><strong title={file.name}>{file.name}</strong></div>
					<button type="button" role="menuitem" onClick={() => { this.closeContextMenu(); this.openFile(file); }}>
						<span>{file.isDirectory ? '📂' : (canPreview ? '👁' : (canEdit ? '✎' : '↓'))}</span>{primaryLabel}
					</button>
					{canPreview && canEdit && <button type="button" role="menuitem" onClick={() => { this.closeContextMenu(); this.editFile(file); }}><span>✎</span>{this.t('editOnline')}</button>}
					{canDownload && <button type="button" role="menuitem" onClick={() => { this.closeContextMenu(); this.downloadItem(file); }}><span>↓</span>{file.isDirectory ? this.t('downloadAsZip') : this.t('download')}</button>}
					{file.canRead !== false && <button type="button" role="menuitem" onClick={() => this.toggleFavorite(file)}><span>{file.favorite ? '☆' : '★'}</span>{file.favorite ? this.t('removeFavorite') : this.t('addFavorite')}</button>}
					{canShare && <button type="button" role="menuitem" onClick={() => this.setState({ contextMenu: null, shareFile: file, shareResult: null, error: '' })}><span>🔗</span>{this.t('createReadOnlyLink')}</button>}
					<button type="button" role="menuitem" onClick={() => this.setState({ contextMenu: null, detailsFile: file })}><span>ⓘ</span>{this.t('details')}</button>
					{file.canMove !== false && <button type="button" role="menuitem" onClick={() => this.setState({ contextMenu: null, selectedItems: [file], bulkAction: 'move', error: '' })}><span>↪</span>{this.t('move')}</button>}
					{canCopy && <button type="button" role="menuitem" onClick={() => this.setState({ contextMenu: null, selectedItems: [file], bulkAction: 'copy', error: '' })}><span>⧉</span>{this.t('copy')}</button>}
					{file.canRename !== false && <button type="button" role="menuitem" onClick={() => this.setState({ contextMenu: null, renameFile: file, error: '' })}><span>✎</span>{this.t('rename')}</button>}
					<div class={style.contextSeparator} />
					{file.canDelete !== false && <button type="button" role="menuitem" class={style.contextDanger} onClick={() => { this.closeContextMenu(); this.deleteItem(file); }}><span>🗑</span>{this.t('moveToTrash')}</button>}
			</div>
		);
	}

	renderTrashContent() {
		const { trashItems, trashLoading, busy, error } = this.state;
		return (
			<div class={style.trashView}>
				<section class={style.workspaceBar}>
					<div class={style.locationBlock}>
						<strong class={style.trashTitle}>🗑 {this.t('deletedFiles')}</strong>
						<div class={style.counts}>{this.tp(trashItems.length, 'trashCount')}</div>
					</div>
					<div class={style.toolbarActions}>
						<button type="button" class={style.secondaryButton} onClick={this.loadTrash} disabled={trashLoading || busy}>↻ {this.t('refresh')}</button>
						<button type="button" class={style.secondaryButton} onClick={this.emptyTrash} disabled={!trashItems.length || busy}>{this.t('emptyForever')}</button>
						<button type="button" class={style.primaryButton} onClick={this.closeTrash}>{this.t('backToFiles')}</button>
					</div>
				</section>
				{error && <div class={style.error}>{error}</div>}
				{(trashLoading || busy) && <div class={style.progress}>{this.t('loadingDeletedFiles')}</div>}
				<div class={style.fileList}>
					{trashItems.map(item => (
						<article key={item.trashId} class={style.trashRow}>
							<span class={style.fileIcon}>{item.isDirectory ? '📁' : iconFor(item)}</span>
							<div class={style.trashName}>
								<strong>{item.name}</strong>
								<small>{this.t('originalLocation', { path: `/${item.trashOriginalLocation || this.t('unknown')}` })}</small>
							</div>
							<div class={style.fileDates}>
								<time>{this.t('deletedAt', { date: this.formatDate(item.trashDeletionTime) })}</time>
								{!item.isDirectory && <time>{this.t('size')} : {this.formatSize(item.size)}</time>}
							</div>
							<div class={style.trashActions}>
								<button type="button" onClick={() => this.restoreTrashItem(item)} disabled={busy}>{this.t('restore')}</button>
								<button type="button" class={style.dangerAction} onClick={() => this.deleteTrashItem(item)} disabled={busy}>{this.t('deleteForever')}</button>
							</div>
						</article>
					))}
				</div>
				{!trashLoading && !trashItems.length && (
					<div class={style.emptyState}><span>🗑</span><strong>{this.t('noDeletedFiles')}</strong><small>{this.t('trashEmpty')}</small></div>
				)}
			</div>
		);
	}

	renderBreadcrumbs() {
		const segments = this.state.path.split('/').filter(Boolean);
		let current = '';
		return (
			<nav class={style.breadcrumbs} aria-label={this.t('nextcloudPath')}>
				<button type="button" onClick={() => this.navigateToDirectory('/')}>{this.t('myFiles')}</button>
				{segments.map(segment => {
					current += `/${segment}`;
					const path = current;
					return [
						<span class={style.separator}>/</span>,
						<button type="button" onClick={() => this.navigateToDirectory(path)} title={segment}>{segment}</button>
					];
				})}
			</nav>
		);
	}

	render() {
		const language = this.language();
		const {
			profile, items, loading, busy, error, showSettings, showNewItem,
			renameFile, viewMode, backgroundEnabled,
			backgroundIndex, search, searchScope, searchResults, searching,
			quota, quotaLoading, sortField, sortDirection, selectedItems,
			trashMode, shareFile, shareResult, detailsFile, addingAccount,
			bulkAction, notice, capabilities, templates, smartView, hasMore, loadingMore,
			showAdvancedSearch, advancedFilters, uploads, dragActive, collision, showDiagnostics,
			showChat, talkOverview, talkBusy
		} = this.state;
		const query = search.trim().toLowerCase();
		const globalSearchActive = smartView === 'advanced' || (searchScope === 'account' && Boolean(query));
		const filteredItems = globalSearchActive
			? searchResults
			: (query ? items.filter(file => String(file.name || '').toLowerCase().includes(query)) : items);
		const visibleItems = sortFiles(filteredItems, sortField, sortDirection, language);
		const folderCount = items.filter(file => file.isDirectory).length;
		const fileCount = items.length - folderCount;
		const locationSummary = smartView !== 'files' && smartView !== 'advanced'
			? `${this.t(SMART_VIEW_LABELS[smartView] || 'smartFiles')} · ${this.tp(visibleItems.length, 'accountResults')}`
			: globalSearchActive
			? this.tp(visibleItems.length, 'accountResults')
			: `${this.tp(folderCount, 'folderCount')} · ${this.tp(fileCount, 'fileCount')}`;
		const remoteBackgroundsAllowed = Boolean(profile && profile.remoteBackgroundsAllowed);
		const backgroundStyle = backgroundEnabled && remoteBackgroundsAllowed
			? { backgroundImage: `linear-gradient(135deg, rgba(8, 23, 42, .74), rgba(8, 64, 96, .32)), url("${BACKGROUNDS[backgroundIndex]}")` }
			: (backgroundEnabled ? { backgroundImage: 'radial-gradient(circle at 12% 12%, rgba(26,139,190,.33), transparent 38%), linear-gradient(135deg, #102f4a, #0d6683 58%, #4a87a0)' } : {});
		const quotaUsed = quota && Number(quota.used) >= 0 ? Number(quota.used) : -1;
		const quotaTotal = quota && Number(quota.total) > 0 ? Number(quota.total) : -1;
		const quotaPercent = quotaTotal > 0 ? Math.min(100, Math.max(0, quotaUsed / quotaTotal * 100)) : 0;
		const selectedSize = selectedItems.reduce((sum, file) => sum + Number(file.size || 0), 0);
		const allVisibleSelected = visibleItems.length > 0 && visibleItems.every(file =>
			selectedItems.some(item => item.path === file.path)
		);
		const selectedCanMove = selectedItems.every(item => item.canMove !== false);
		const selectedCanCopy = selectedItems.every(item => item.canCopy !== false && item.canRead !== false);
		const selectedCanDelete = selectedItems.every(item => item.canDelete !== false);
		const selectedCanDownload = selectedItems.every(item => item.canDownload !== false && !item.hideDownload);
		const talkUnread = Math.max(0, Number(talkOverview && talkOverview.unread || 0));
		const settingsProfile = addingAccount ? {
			...profile,
			configured: false,
			activeProfileId: '',
			label: '',
			nextcloudUrl: '',
			username: '',
			passwordSet: false,
			managed: false,
			officeMode: 'global',
			officeProvider: profile.defaultOfficeProvider || profile.officeProvider || 'onlyoffice',
			officeUrl: '',
			officeSecurityMode: profile.defaultOfficeSecurityMode || 'jwt',
			officeJwtHeader: profile.defaultOfficeJwtHeader || 'Authorization',
			officeJwtSecretSet: false
		} : profile;

		if (!profile && loading) {
			return <div class={style.centerState}><div class={style.spinner} />{this.t('loadingCloudSpace')}</div>;
		}

		if (profile && profile.configured && showChat) {
			return <div ref={this.setPageNode} class={`${style.page} ${style.chatRoutePage}`}>
				<FullChatBoundary language={language} onClose={this.closeChat}>
					<Chat workspaceScope={this.props.workspaceScope} userLanguage={language} onClose={this.closeChat} onOverview={overview => {
						this.setState({ talkOverview: overview });
						notifyTalkNavigation({ scope: this.props.workspaceScope, enabled: true, unread: Math.max(0, Number(overview && overview.unread || 0)) });
					}} />
				</FullChatBoundary>
			</div>;
		}

		return (
			<div ref={this.setPageNode} class={`${style.page} ${backgroundEnabled ? style.withBackground : style.withoutBackground} ${dragActive ? style.dragActive : ''}`} style={backgroundStyle}
				onDragOver={event => { event.preventDefault(); if (!dragActive) this.setState({ dragActive: true }); }}
				onDragLeave={event => { if (event.target === event.currentTarget) this.setState({ dragActive: false }); }}
				onDrop={event => { event.preventDefault(); this.setState({ dragActive: false }); this.queueUploads(Array.from(event.dataTransfer.files || [])); }}>
				<header class={style.header}>
					<div class={style.brand}>
						<div class={style.brandIcon}>☁</div>
						<div>
								<h1>{this.t('myCloudSpace')}</h1>
							{profile && profile.configured && Array.isArray(profile.accounts) && (
								<div class={style.accountSwitcher}>
									<select value={profile.activeProfileId || ''} onChange={this.switchAccount} aria-label={this.t('switchCloudAccount')} disabled={busy}>
										{profile.accounts.map(account => (
											<option value={account.id} key={account.id}>{account.label || account.username} — {account.nextcloudUrl}</option>
										))}
									</select>
									<span>{this.t('accountsConnected', { count: profile.accounts.length })}</span>
								</div>
							)}
							{profile && profile.configured && <span class={style.account}>{profile.username} · {profile.nextcloudUrl}</span>}
							{profile && profile.configured && (
									<div class={style.quotaBlock} title={this.t('storageQuotaTitle')}>
										<div class={style.quotaLabel}>
											{quotaLoading && !quota ? this.t('loadingStorage') : (
												quota && quota.unlimited
													? this.t('usedUnlimited', { used: this.formatSize(quotaUsed) })
													: (quotaTotal > 0
														? this.t('usedOfTotal', { used: this.formatSize(quotaUsed), total: this.formatSize(quotaTotal) })
														: this.t('quotaUnavailable'))
										)}
									</div>
									{quotaTotal > 0 && (
										<div class={`${style.quotaTrack} ${quotaPercent >= 95 ? style.quotaCritical : (quotaPercent >= 80 ? style.quotaWarning : '')}`}>
											<span style={{ width: `${quotaPercent}%` }} />
										</div>
									)}
								</div>
							)}
						</div>
					</div>
					<div class={style.headerActions}>
						{profile && profile.configured && profile.canAddAccount && (
							<button type="button" class={`${style.glassButton} ${style.addAccountButton}`} onClick={this.openAddAccount}>＋ {this.t('addCloudAccount')}</button>
						)}
							{remoteBackgroundsAllowed && <button type="button" class={style.glassButton} onClick={this.nextBackground} title={this.t('changePhoto')}>◫ {this.t('background')}</button>}
							<button type="button" class={style.glassButton} onClick={this.toggleBackground}>{backgroundEnabled ? `◐ ${this.t('plainMode')}` : `◑ ${this.t('photoMode')}`}</button>
						{profile && profile.configured && (
							<button type="button" class={style.glassButton} onClick={trashMode ? this.closeTrash : this.openTrash}>
									{trashMode ? `☁ ${this.t('myFiles')}` : `🗑 ${this.t('deletedFiles')}`}
							</button>
						)}
						{profile && profile.configured && (
								<button type="button" class={style.glassButton} onClick={() => this.setState({ showSettings: true, addingAccount: false, error: '' })}>⚙ {this.t('editConnection')}</button>
						)}
					{profile && profile.configured && <button type="button" class={style.glassButton} onClick={() => this.setState({ showDiagnostics: true })}>✓ {this.t('diagnostics')}</button>}
					{profile && profile.configured && (!profile.talkEnabled ? (
						<button type="button" class={`${style.glassButton} ${style.chatEnableButton}`} disabled={talkBusy} onClick={() => this.setTalkEnabled(true)}>
							💬 {talkBusy ? this.t('checkingTalk') : this.t('enableChat')}
						</button>
					) : (
						<div class={style.chatHeaderControl}>
							<button type="button" class={style.glassButton} onClick={this.openChat}>
								💬 {this.t('chat')}{talkUnread > 0 && <span class={style.chatUnread} aria-label={this.t('unreadCount', { count: talkUnread })}>{talkUnread > 99 ? '99+' : talkUnread}</span>}
							</button>
							<button type="button" class={style.chatDisableButton} disabled={talkBusy} title={this.t('disableChat')} aria-label={this.t('disableChat')} onClick={() => this.setTalkEnabled(false)}>⏻</button>
						</div>
					))}
				</div>
			</header>

				{profile && !profile.configured && (
					<main class={style.setupContent}>
						{profile.accountMode === 'managed' ? (
							<ManagedActivation
							profile={settingsProfile}
							language={language}
							adding={addingAccount}
								saving={busy}
								error={error}
								onActivate={this.activateManagedAccount}
							/>
						) : (
							<Settings
								inline
								profile={profile}
									language={language}
								saving={busy}
								error={error}
								onSave={values => this.saveProfile(values)}
								onLoginFlow={this.connectWithLoginFlow}
							/>
						)}
					</main>
				)}

			{profile && profile.configured && (
				<main class={style.content}>
						{trashMode ? this.renderTrashContent() : (
						<div class={`${style.filesView} ${selectedItems.length > 0 ? style.filesViewWithSelection : ''}`}>
						<SmartNavigation active={smartView} capabilities={capabilities} language={language} onChange={this.loadSmartView} />
						<section class={style.workspaceBar}>
							<div class={style.locationBlock}>
								{this.renderBreadcrumbs()}
								<div class={style.counts}>{locationSummary}</div>
							</div>
							<div class={style.toolbarActions}>
								<button type="button" class={style.secondaryButton} onClick={this.refreshView} disabled={loading || busy || searching}>↻</button>
									<button type="button" class={style.secondaryButton} onClick={() => this.setState({ showNewItem: true })} disabled={busy || smartView !== 'files'}>＋ {this.t('newItem')}</button>
									<label class={`${style.primaryButton} ${busy ? style.disabled : ''}`}>
										↑ {this.t('uploadFiles')}
									<input type="file" multiple onChange={this.uploadFiles} disabled={busy || smartView !== 'files'} />
								</label>
								<label class={`${style.secondaryButton} ${busy || smartView !== 'files' ? style.disabled : ''}`}>
									📁 {this.t('uploadFolder')}
									<input type="file" multiple webkitdirectory="" directory="" onChange={this.uploadFiles} disabled={busy || smartView !== 'files'} />
								</label>
							</div>
						</section>

						<section class={style.filterBar}>
							<label class={style.searchBox}>
								<span>⌕</span>
								<input
									type="search"
									value={search}
									onInput={event => this.updateSearch(event.target.value)}
									placeholder={searchScope === 'account' ? this.t('searchWholeAccount') : this.t('searchCurrentFolder')}
								/>
							</label>
							<div class={style.searchScope} role="group" aria-label={this.t('searchScope')}>
								<button type="button" class={searchScope === 'folder' ? style.activeScope : ''} onClick={() => this.setSearchScope('folder')}>{this.t('currentFolder')}</button>
								<button type="button" class={searchScope === 'account' ? style.activeScope : ''} onClick={() => this.setSearchScope('account')}>{this.t('wholeAccount')}</button>
							</div>
							<div class={style.sortControls}>
								<label>
									<span class={style.srOnly}>{this.t('sortFiles')}</span>
									<select value={sortField} onChange={event => this.setSortField(event.target.value)}>
										<option value="name">{this.t('sortByName')}</option>
										<option value="created">{this.t('sortByCreation')}</option>
										<option value="modified">{this.t('sortByModified')}</option>
										<option value="size">{this.t('sortBySize')}</option>
									</select>
								</label>
								<button type="button" onClick={this.toggleSortDirection} title={sortDirection === 'asc' ? this.t('ascending') : this.t('descending')}>
									{sortDirection === 'asc' ? '↑' : '↓'}
								</button>
							</div>
							<div class={style.viewSwitch}>
								<button type="button" class={viewMode === 'grid' ? style.activeView : ''} onClick={() => this.setViewMode('grid')}>▦ {this.t('cards')}</button>
								<button type="button" class={viewMode === 'list' ? style.activeView : ''} onClick={() => this.setViewMode('list')}>☷ {this.t('list')}</button>
							</div>
							<button type="button" class={`${style.secondaryButton} ${showAdvancedSearch ? style.activeFilterButton : ''}`} onClick={() => this.setState({ showAdvancedSearch: !showAdvancedSearch })}>⚲ {this.t('advancedSearch')}</button>
							{visibleItems.length > 0 && (
								<button type="button" class={`${style.secondaryButton} ${style.selectVisibleButton}`} onClick={() => this.toggleAllVisible(visibleItems)}>
									{allVisibleSelected ? `☐ ${this.t('deselectVisibleItems')}` : `☑ ${this.t('selectVisibleItems')}`}
								</button>
							)}
						</section>
						{showAdvancedSearch && <AdvancedSearchPanel values={advancedFilters} onChange={this.updateAdvancedFilters} onSearch={this.runAdvancedSearch} onReset={this.resetAdvancedSearch} language={language} busy={searching} />}

						{selectedItems.length > 0 && (
							<section class={style.selectionBar}>
								<div>
									<strong>{this.tp(selectedItems.length, 'selectedItem')}</strong>
									<span>{this.t('selectedSize', { size: this.formatSize(selectedSize) })}</span>
								</div>
								<div>
									{selectedCanDownload && <button type="button" class={style.secondaryButton} onClick={this.downloadSelectedArchive} disabled={busy}>↓ {this.t('downloadZip')}</button>}
									<button type="button" class={style.secondaryButton} onClick={() => this.openBulkAction('move')} disabled={busy || !selectedCanMove}>↪ {this.t('moveSelected')}</button>
									<button type="button" class={style.secondaryButton} onClick={() => this.openBulkAction('copy')} disabled={busy || !selectedCanCopy}>⧉ {this.t('copySelected')}</button>
									<button type="button" class={style.selectionDangerButton} onClick={this.deleteSelected} disabled={busy || !selectedCanDelete}>🗑 {this.t('trashSelected')}</button>
									<button type="button" class={style.secondaryButton} onClick={() => this.setState({ selectedItems: [], error: '', notice: '' })}>{this.t('deselectAll')}</button>
								</div>
							</section>
						)}
						{notice && <div class={style.notice}>{notice}</div>}
						{error && <div class={style.error}>{error}</div>}
							{(loading || busy || searching) && <div class={style.progress}>{searching ? this.t('searchingAccount') : this.t('processing')}</div>}

						{viewMode === 'grid' ? (
							<div class={style.fileGrid}>
								{smartView === 'files' && !globalSearchActive && this.state.path !== '/' && (
									<button type="button" class={`${style.fileCard} ${style.parentCard}`} onClick={() => this.navigateToDirectory(parentPath(this.state.path))}>
										<span class={style.cardIcon}>↩</span><strong>{this.t('goUp')}</strong><small>{this.t('parentFolder')}</small>
									</button>
								)}
								{visibleItems.map(file => {
									const kind = previewKind(file);
									return (
										<article key={file.path} class={`${style.fileCard} ${selectedItems.some(item => item.path === file.path) ? style.selectedFile : ''} ${this.state.contextMenu && this.state.contextMenu.file.path === file.path ? style.menuOpen : ''}`} onContextMenu={event => this.openContextMenu(event, file)}>
											<label class={style.cardCheckbox} title={this.t('selectItem', { name: file.name })}>
												<input type="checkbox" checked={selectedItems.some(item => item.path === file.path)} onChange={() => this.toggleSelection(file)} />
												<span class={style.srOnly}>{this.t('selectItem', { name: file.name })}</span>
												</label>
											<button type="button" class={style.cardMain} onClick={() => this.openFile(file)} title={file.name}>
												<div class={style.cardVisual}>
											{kind === 'image' && !file.isDirectory
												? <LazyThumbnail file={file} />
														: <span class={style.cardIcon}>{iconFor(file)}</span>}
												</div>
												<strong>{file.name}</strong>
										<small>{globalSearchActive
											? `${parentPath(file.path)} · ${file.isDirectory ? this.t('folder') : this.formatSize(file.size)}`
											: (file.isDirectory ? this.t('folder') : this.t('createdModified', { size: this.formatSize(file.size), created: this.formatDate(file.created), modified: this.formatDate(file.modified) }))}</small>
											</button>
											{this.renderActions(file)}
										</article>
									);
								})}
							</div>
						) : (
							<div class={style.fileList}>
								{smartView === 'files' && !globalSearchActive && this.state.path !== '/' && (
									<button type="button" class={style.listParent} onClick={() => this.navigateToDirectory(parentPath(this.state.path))}>↩ {this.t('goUp')} — {this.t('parentFolder')}</button>
								)}
								{visibleItems.map(file => (
									<article key={file.path} class={`${style.listRow} ${selectedItems.some(item => item.path === file.path) ? style.selectedFile : ''} ${this.state.contextMenu && this.state.contextMenu.file.path === file.path ? style.menuOpen : ''}`} onContextMenu={event => this.openContextMenu(event, file)}>
										<input class={style.listCheckbox} type="checkbox" checked={selectedItems.some(item => item.path === file.path)} onChange={() => this.toggleSelection(file)} aria-label={this.t('selectItem', { name: file.name })} />
										<button type="button" class={style.fileName} onClick={() => this.openFile(file)}>
											<span class={style.fileIcon}>{iconFor(file)}</span>
									<span><strong>{file.name}</strong><small>{globalSearchActive ? parentPath(file.path) : (file.isDirectory ? this.t('folder') : this.formatSize(file.size))}</small></span>
									</button>
									<div class={style.fileDates}><time>{this.t('createdAt', { date: this.formatDate(file.created) })}</time><time>{this.t('modifiedAt', { date: this.formatDate(file.modified) })}</time></div>
										{this.renderActions(file)}
									</article>
								))}
							</div>
						)}

						{!loading && !searching && !visibleItems.length && (
							<div class={style.emptyState}>
								<span>☁</span>
									<strong>{query ? this.t('noResults') : this.t('folderEmpty')}</strong>
									<small>{query ? this.t('tryAnotherWord') : this.t('emptyFolderHelp')}</small>
							</div>
						)}
						{hasMore && smartView === 'files' && <div class={style.loadMore}><button type="button" class={style.secondaryButton} onClick={this.loadMore} disabled={loadingMore}>{loadingMore ? this.t('loading') : this.t('loadMore')}</button></div>}
						</div>
						)}
							{backgroundEnabled && remoteBackgroundsAllowed && <a class={style.unsplashCredit} href="https://unsplash.com" target="_blank" rel="noreferrer">{this.t('unsplashPhotos')}</a>}
					</main>
				)}
			{dragActive && <div class={style.dropOverlay}><div><span>↑</span><strong>{this.t('dropFilesHere')}</strong><small>{this.t('dropFilesHelp')}</small></div></div>}

				{showSettings && profile && (
					<Settings
						profile={settingsProfile}
						language={language}
						adding={addingAccount}
						saving={busy}
							error={error}
							onSave={values => this.saveProfile(values)}
							onLoginFlow={this.connectWithLoginFlow}
							onDelete={this.removeActiveAccount}
							onClose={() => this.setState({ showSettings: false, addingAccount: false, error: '' })}
					/>
				)}
				{showNewItem && <NewItem language={language} templates={templates} directory={this.state.path} onClose={() => this.setState({ showNewItem: false })} onCreate={this.createItem} />}
				{renameFile && <Rename language={language} file={renameFile} onClose={() => this.setState({ renameFile: null })} onRename={this.renameItem} />}
				{shareFile && <ShareLink
					file={shareFile}
					result={shareResult}
					language={language}
					saving={busy}
					error={error}
					onCreate={this.createShare}
					onClose={() => this.setState({ shareFile: null, shareResult: null, error: '' })}
				/>}
				{detailsFile && <Modal title={this.t('detailsTitle', { name: detailsFile.name })} wide onClose={() => this.setState({ detailsFile: null })} language={language}>
					<ItemDetails language={language} file={detailsFile} capabilities={capabilities} onChanged={this.refreshView} />
				</Modal>}
				{showDiagnostics && <Modal title={this.t('diagnostics')} wide onClose={() => this.setState({ showDiagnostics: false })} language={language}>
					<DiagnosticsPanel language={language} onClose={() => this.setState({ showDiagnostics: false })} />
				</Modal>}
				{collision && <Modal title={this.t('resolveConflict')} dismissible={false} onClose={() => this.resolveCollision('cancel')} language={language}>
					<CollisionDialog name={collision.name} language={language} onChoose={this.resolveCollision} onClose={() => this.resolveCollision('cancel')} />
				</Modal>}
				{bulkAction && selectedItems.length > 0 && (
					<FolderPicker
						action={bulkAction}
						items={selectedItems}
						startPath={searchScope === 'account' && search.trim() ? '/' : this.state.path}
						language={language}
						busy={busy}
						error={error}
						onClose={() => this.setState({ bulkAction: null, error: '' })}
						onConfirm={(destination, collisionPolicy) => this.executeBulkAction(bulkAction, destination, collisionPolicy)}
					/>
				)}
				{this.state.activationResult && (
					<ActivationCredentials
						result={this.state.activationResult}
						language={language}
						onClose={() => this.setState({ activationResult: null })}
					/>
				)}
				<UploadCenter jobs={uploads} language={language} onCancel={this.cancelUpload} onRetry={this.retryUpload} onClear={this.clearCompletedUploads} />
			</div>
		);
	}
}
