import { createElement, Component } from 'preact';

import { api, versionUrl } from '../../api';
import { iconFor } from '../../file-types';
import { localeFor, translate } from '../../i18n';
import style from './style.less';

function t(language, key, variables) {
	return translate(language, key, variables);
}

function formatSize(value, language) {
	const size = Number(value || 0);
	if (!Number.isFinite(size) || size < 0) return '—';
	const units = language === 'fr' ? ['o', 'Ko', 'Mo', 'Go', 'To'] : ['B', 'KB', 'MB', 'GB', 'TB'];
	let amount = size;
	let index = 0;
	while (amount >= 1024 && index < units.length - 1) { amount /= 1024; index += 1; }
	return `${amount >= 10 || index === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[index]}`;
}

function formatDate(value, language) {
	if (!value || Date.parse(value) < 86400000) return '—';
	try {
		return new Intl.DateTimeFormat(localeFor(language), { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
	} catch (error) { return String(value); }
}

function feature(capabilities, key, fallback = true) {
	const features = capabilities && capabilities.features;
	return features && Object.prototype.hasOwnProperty.call(features, key) ? Boolean(features[key]) : fallback;
}

export function CapabilityBadge({ ok, children }) {
	return <span class={`${style.capabilityBadge} ${ok ? style.capabilityAvailable : style.capabilityUnavailable}`}>{ok ? '✓' : '–'} {children}</span>;
}

export function SmartNavigation({ active, capabilities, onChange, language }) {
	const views = [
		['files', '☁', 'smartFiles', true],
		['favorites', '★', 'smartFavorites', feature(capabilities, 'favorites')],
		['recent', '◷', 'smartRecent', feature(capabilities, 'search')],
		['sharedByMe', '↗', 'smartSharedByMe', feature(capabilities, 'sharing', false)],
		['sharedWithMe', '↙', 'smartSharedWithMe', feature(capabilities, 'sharing', false)],
		['publicLinks', '🔗', 'smartPublicLinks', feature(capabilities, 'publicLinks', false)]
	];
	return (
		<nav class={style.smartNavigation} aria-label={t(language, 'smartViews')}>
			{views.filter(view => view[3]).map(view => (
				<button type="button" key={view[0]} class={active === view[0] ? style.activeSmartView : ''} onClick={() => onChange(view[0])}>
					<span>{view[1]}</span>{t(language, view[2])}
				</button>
			))}
		</nav>
	);
}

export function AdvancedSearchPanel({ values, onChange, onSearch, onReset, language, busy }) {
	return (
		<form class={style.advancedSearchPanel} onSubmit={event => { event.preventDefault(); onSearch(); }}>
			<div class={style.advancedSearchGrid}>
				<label><span>{t(language, 'keywords')}</span><input type="search" value={values.query} onInput={event => onChange({ query: event.target.value })} /></label>
				<label><span>{t(language, 'fileCategory')}</span><select value={values.category} onChange={event => onChange({ category: event.target.value })}>
					<option value="all">{t(language, 'allTypes')}</option><option value="office">{t(language, 'officeDocuments')}</option>
					<option value="image">{t(language, 'images')}</option><option value="video">{t(language, 'videos')}</option>
					<option value="audio">{t(language, 'audioFiles')}</option><option value="text">{t(language, 'textFiles')}</option>
				</select></label>
				<label><span>{t(language, 'modifiedAfter')}</span><input type="date" value={values.modifiedAfter} onInput={event => onChange({ modifiedAfter: event.target.value })} /></label>
				<label><span>{t(language, 'minimumSizeMb')}</span><input type="number" min="0" step="1" value={values.minimumSizeMb} onInput={event => onChange({ minimumSizeMb: event.target.value })} /></label>
				<label><span>{t(language, 'maximumSizeMb')}</span><input type="number" min="0" step="1" value={values.maximumSizeMb} onInput={event => onChange({ maximumSizeMb: event.target.value })} /></label>
			</div>
			<div class={style.advancedSearchActions}>
				<button type="button" class={style.secondaryButton} onClick={onReset}>{t(language, 'resetFilters')}</button>
				<button type="submit" class={style.primaryButton} disabled={busy}>{busy ? t(language, 'searchingAccount') : t(language, 'runAdvancedSearch')}</button>
			</div>
		</form>
	);
}

export function CollisionDialog({ name, onChoose, onClose, language }) {
	return (
		<div class={style.collisionPanel}>
			<div class={style.collisionIcon}>⇄</div>
			<h3>{t(language, 'fileAlreadyExists')}</h3>
			<p>{t(language, 'collisionHelp', { name })}</p>
			<div class={style.collisionChoices}>
				<button type="button" class={style.dangerButton} onClick={() => onChoose('replace')}>{t(language, 'replaceExisting')}</button>
				<button type="button" class={style.primaryButton} onClick={() => onChoose('keep-both')}>{t(language, 'keepBoth')}</button>
				<button type="button" class={style.secondaryButton} onClick={() => onChoose('skip')}>{t(language, 'skipFile')}</button>
				<button type="button" class={style.secondaryButton} onClick={onClose}>{t(language, 'cancel')}</button>
			</div>
		</div>
	);
}

export function UploadCenter({ jobs, onCancel, onRetry, onClear, language }) {
	if (!jobs.length) return null;
	const active = jobs.some(job => job.status === 'uploading' || job.status === 'waiting');
	return (
		<aside class={style.uploadCenter} aria-live="polite">
			<header><div><strong>↑ {t(language, 'transfers')}</strong><small>{t(language, 'transferSummary', { count: jobs.length })}</small></div>
				<button type="button" onClick={onClear} disabled={active} aria-label={t(language, 'clearCompleted')}>×</button></header>
			<div class={style.uploadJobs}>
				{jobs.map(job => (
					<div class={style.uploadJob} key={job.id}>
						<div class={style.uploadJobTitle}><span>{job.status === 'done' ? '✓' : (job.status === 'error' ? '!' : '↑')}</span><strong title={job.name}>{job.name}</strong><small>{job.progress || 0}%</small></div>
						<div class={style.uploadTrack}><span style={{ width: `${job.progress || 0}%` }} /></div>
						<div class={style.uploadJobFooter}><span>{job.message || t(language, `uploadStatus_${job.status}`)}</span>
							{(job.status === 'uploading' || job.status === 'waiting') && <button type="button" onClick={() => onCancel(job)}>{t(language, 'cancel')}</button>}
							{job.status === 'error' && <button type="button" onClick={() => onRetry(job)}>{t(language, 'retry')}</button>}
						</div>
					</div>
				))}
			</div>
		</aside>
	);
}

export class DiagnosticsPanel extends Component {
	state = { loading: true, result: null, error: '' };
	componentDidMount() { this.load(); }
	load = async () => {
		this.setState({ loading: true, error: '' });
		try { this.setState({ loading: false, result: await api('/api/diagnostics') }); }
		catch (error) { this.setState({ loading: false, error: error.message }); }
	};
	render() {
		const { language } = this.props;
		const { loading, result, error } = this.state;
		return <div class={style.diagnosticsPanel}>
			{loading && <div class={style.progress}>{t(language, 'runningDiagnostics')}</div>}
			{error && <div class={style.error}>{error}</div>}
			{result && <div>
				<div class={`${style.diagnosticSummary} ${result.status === 'ok' ? style.diagnosticOk : style.diagnosticWarning}`}>
					<strong>{result.status === 'ok' ? t(language, 'diagnosticsHealthy') : t(language, 'diagnosticsDegraded')}</strong>
					<span>{t(language, 'connectorVersion', { version: result.connectorVersion })}</span>
				</div>
				<div class={style.diagnosticChecks}>{(result.checks || []).map(check => <div key={check.name}><span>{check.ok ? '✓' : '!'}</span><strong>{t(language, `diagnostic_${check.name}`)}</strong><small>{check.detail}</small></div>)}</div>
				<div class={style.capabilityList}>{result.nextcloud && Object.keys(result.nextcloud.features || {}).map(key => <CapabilityBadge key={key} ok={result.nextcloud.features[key]}>{t(language, `capability_${key}`)}</CapabilityBadge>)}</div>
			</div>}
			<div class={style.formActions}><button type="button" class={style.secondaryButton} onClick={this.load}>{t(language, 'runAgain')}</button><button type="button" class={style.primaryButton} onClick={this.props.onClose}>{t(language, 'close')}</button></div>
		</div>;
	}
}

export class ItemDetails extends Component {
	constructor(props) {
		super(props);
		this.state = { tab: 'info', items: [], loading: false, error: '', message: '', shareType: 3, shareWith: '', permissions: 1, password: '', expireDate: '' };
	}

	setTab = tab => this.setState({ tab, items: [], error: '' }, () => { if (tab !== 'info') this.loadTab(); });

	loadTab = async () => {
		const { file } = this.props;
		const route = this.state.tab === 'shares' ? `/api/shares?path=${encodeURIComponent(file.path)}`
			: this.state.tab === 'versions' ? `/api/versions?fileId=${encodeURIComponent(file.fileId || '')}`
				: this.state.tab === 'comments' ? `/api/comments?fileId=${encodeURIComponent(file.fileId || '')}`
					: `/api/activity?fileId=${encodeURIComponent(file.fileId || '')}`;
		this.setState({ loading: true, error: '' });
		try { const result = await api(route); this.setState({ items: result.items || [], loading: false }); }
		catch (error) { this.setState({ loading: false, error: error.message }); }
	};

	createShare = async event => {
		event.preventDefault();
		const { file } = this.props;
		this.setState({ loading: true, error: '' });
		try {
			await api('/api/share/create', { method: 'POST', json: {
				path: file.path, shareType: Number(this.state.shareType), shareWith: this.state.shareWith,
				permissions: Number(this.state.shareType) === 3 ? 1 : Number(this.state.permissions),
				password: this.state.password, expireDate: this.state.expireDate
			} });
			this.setState({ shareWith: '', password: '', expireDate: '', message: t(this.props.language, 'shareCreated') });
			this.loadTab();
		} catch (error) { this.setState({ loading: false, error: error.message }); }
	};

	deleteShare = async share => {
		if (!globalThis.confirm(t(this.props.language, 'revokeShareConfirm'))) return;
		this.setState({ loading: true, error: '' });
		try { await api('/api/share/delete', { method: 'POST', json: { id: share.id } }); this.loadTab(); }
		catch (error) { this.setState({ loading: false, error: error.message }); }
	};

	restoreVersion = async version => {
		if (!globalThis.confirm(t(this.props.language, 'restoreVersionConfirm'))) return;
		this.setState({ loading: true, error: '' });
		try {
			await api('/api/version/restore', { method: 'POST', json: { fileId: this.props.file.fileId, versionId: version.versionId } });
			this.setState({ message: t(this.props.language, 'versionRestored') }); this.loadTab();
			if (this.props.onChanged) this.props.onChanged();
		} catch (error) { this.setState({ loading: false, error: error.message }); }
	};

	addComment = async event => {
		event.preventDefault();
		const input = event.currentTarget.elements.comment;
		const message = String(input.value || '').trim();
		if (!message) return;
		this.setState({ loading: true, error: '' });
		try { await api('/api/comment/add', { method: 'POST', json: { fileId: this.props.file.fileId, message } }); input.value = ''; this.loadTab(); }
		catch (error) { this.setState({ loading: false, error: error.message }); }
	};

	deleteComment = async comment => {
		if (!globalThis.confirm(t(this.props.language, 'deleteCommentConfirm'))) return;
		try { await api('/api/comment/delete', { method: 'POST', json: { fileId: this.props.file.fileId, commentId: comment.id } }); this.loadTab(); }
		catch (error) { this.setState({ error: error.message }); }
	};

	renderInfo() {
		const { file, language } = this.props;
		const permissions = [
			['canRead', 'permissionRead'], ['canWrite', 'permissionWrite'], ['canCreateFile', 'permissionCreate'],
			['canRename', 'permissionRename'], ['canMove', 'permissionMove'], ['canDelete', 'permissionDelete'], ['canShare', 'permissionShare']
		];
		return <div class={style.detailsInfo}>
			<div class={style.detailsHero}><span>{iconFor(file)}</span><div><strong>{file.name}</strong><small>{file.ownerDisplayName || file.ownerId || 'Nextcloud'}</small></div></div>
			<dl class={style.detailsList}>
				<div><dt>{t(language, 'cloudPath')}</dt><dd>{file.path}</dd></div>
				<div><dt>{t(language, 'size')}</dt><dd>{file.isDirectory ? '—' : formatSize(file.size, language)}</dd></div>
				<div><dt>{t(language, 'creation')}</dt><dd>{formatDate(file.created, language)}</dd></div>
				<div><dt>{t(language, 'lastModified')}</dt><dd>{formatDate(file.modified, language)}</dd></div>
				<div><dt>{t(language, 'mimeType')}</dt><dd>{file.mimeType || t(language, 'unknown')}</dd></div>
				{file.checksums && file.checksums.length > 0 && <div><dt>{t(language, 'checksums')}</dt><dd>{file.checksums.join(' · ')}</dd></div>}
				{file.tags && file.tags.length > 0 && <div><dt>{t(language, 'tags')}</dt><dd>{file.tags.join(', ')}</dd></div>}
				{file.locked && <div><dt>{t(language, 'lock')}</dt><dd>{file.lockOwnerDisplayName || file.lockOwner || t(language, 'locked')}</dd></div>}
			</dl>
			<div class={style.permissionGrid}>{permissions.map(item => <CapabilityBadge key={item[0]} ok={file[item[0]] !== false}>{t(language, item[1])}</CapabilityBadge>)}</div>
		</div>;
	}

	renderShares() {
		const { file, language } = this.props;
		const { items, shareType, shareWith, permissions, password, expireDate } = this.state;
		return <div class={style.detailsTabContent}>
			<form class={style.shareComposer} onSubmit={this.createShare}>
				<div class={style.formRow}><label><span>{t(language, 'shareType')}</span><select value={shareType} onChange={event => this.setState({ shareType: Number(event.target.value) })}>
					<option value="3">{t(language, 'publicReadOnlyLink')}</option><option value="0">{t(language, 'nextcloudUser')}</option>
					<option value="1">{t(language, 'nextcloudGroup')}</option><option value="4">{t(language, 'emailShare')}</option>
					<option value="6">{t(language, 'federatedShare')}</option><option value="7">{t(language, 'circleShare')}</option>
				</select></label>
				{Number(shareType) !== 3 && <label><span>{t(language, 'shareRecipient')}</span><input value={shareWith} required onInput={event => this.setState({ shareWith: event.target.value })} /></label>}</div>
				{Number(shareType) !== 3 && <label><span>{t(language, 'permissions')}</span><select value={permissions} onChange={event => this.setState({ permissions: Number(event.target.value) })}>
					<option value="1">{t(language, 'readOnly')}</option><option value="3">{t(language, 'readAndEdit')}</option>
					{file.isDirectory && <option value="15">{t(language, 'readEditCreateDelete')}</option>}
					<option value="31">{t(language, 'fullSharePermissions')}</option>
				</select></label>}
				{Number(shareType) === 3 && <div class={style.readOnlyNotice}>🔒 {t(language, 'publicLinksAlwaysReadOnly')}</div>}
				<div class={style.formRow}><label><span>{t(language, 'linkPassword')}</span><input type="password" value={password} onInput={event => this.setState({ password: event.target.value })} /></label>
				<label><span>{t(language, 'expirationDate')}</span><input type="date" value={expireDate} min={new Date().toISOString().slice(0, 10)} onInput={event => this.setState({ expireDate: event.target.value })} /></label></div>
				<button type="submit" class={style.primaryButton}>{t(language, 'createShare')}</button>
			</form>
			<div class={style.detailRows}>{items.map(share => <article key={share.id}>
				<div><strong>{share.url ? t(language, 'publicLink') : (share.shareWithDisplayName || share.shareWith)}</strong><small>{share.url || t(language, 'sharePermissionsValue', { value: share.permissions })}</small></div>
				{share.url && <button type="button" onClick={() => globalThis.navigator.clipboard && globalThis.navigator.clipboard.writeText(share.url)}>{t(language, 'copyLink')}</button>}
				<button type="button" class={style.inlineDanger} onClick={() => this.deleteShare(share)}>{t(language, 'revoke')}</button>
			</article>)}</div>
			{!items.length && <div class={style.inlineEmpty}>{t(language, 'noShares')}</div>}
		</div>;
	}

	renderVersions() {
		const { file, language } = this.props;
		return <div class={style.detailsTabContent}><div class={style.detailRows}>{this.state.items.map(version => <article key={version.versionId}>
			<div><strong>{formatDate(version.timestamp || version.modified, language)}</strong><small>{formatSize(version.size, language)}{version.author ? ` · ${version.author}` : ''}</small></div>
			<a class={style.inlineButton} href={versionUrl(file.fileId, version.versionId, file.name)}>{t(language, 'download')}</a>
			<button type="button" onClick={() => this.restoreVersion(version)}>{t(language, 'restore')}</button>
		</article>)}</div>{!this.state.items.length && <div class={style.inlineEmpty}>{t(language, 'noVersions')}</div>}</div>;
	}

	renderComments() {
		const { language } = this.props;
		return <div class={style.detailsTabContent}><form class={style.commentComposer} onSubmit={this.addComment}><textarea name="comment" maxlength="4000" required placeholder={t(language, 'addCommentPlaceholder')} /><button type="submit" class={style.primaryButton}>{t(language, 'publishComment')}</button></form>
			<div class={style.commentList}>{this.state.items.map(comment => <article key={comment.id}><div><strong>{comment.actorDisplayName || comment.actorId}</strong><time>{formatDate(comment.created, language)}</time></div><p>{comment.message}</p><button type="button" onClick={() => this.deleteComment(comment)}>{t(language, 'delete')}</button></article>)}</div>
			{!this.state.items.length && <div class={style.inlineEmpty}>{t(language, 'noComments')}</div>}</div>;
	}

	renderActivity() {
		const { language } = this.props;
		return <div class={style.detailsTabContent}><div class={style.activityList}>{this.state.items.map((item, index) => <article key={item.id || index}><span>•</span><div><strong>{item.subject || item.message}</strong><small>{item.user}{item.datetime ? ` · ${formatDate(item.datetime, language)}` : ''}</small></div></article>)}</div>
			{!this.state.items.length && <div class={style.inlineEmpty}>{t(language, 'noActivity')}</div>}</div>;
	}

	render() {
		const { file, language, capabilities } = this.props;
		const { tab, loading, error, message } = this.state;
		const tabs = [['info', 'details'], ['shares', 'sharing'], ['versions', 'versions'], ['comments', 'comments'], ['activity', 'activity']]
			.filter(item => item[0] === 'info' || (item[0] === 'shares' ? file.canShare !== false && feature(capabilities, 'sharing', false) : (!file.isDirectory && feature(capabilities, item[0], item[0] !== 'activity'))));
		return <div class={style.detailsWorkspace}>
			<nav class={style.detailsTabs}>{tabs.map(item => <button type="button" key={item[0]} class={tab === item[0] ? style.activeDetailsTab : ''} onClick={() => this.setTab(item[0])}>{t(language, item[1])}</button>)}</nav>
			{error && <div class={style.error}>{error}</div>}{message && <div class={style.notice}>{message}</div>}{loading && <div class={style.progress}>{t(language, 'loading')}</div>}
			{tab === 'info' && this.renderInfo()}{tab === 'shares' && this.renderShares()}{tab === 'versions' && this.renderVersions()}{tab === 'comments' && this.renderComments()}{tab === 'activity' && this.renderActivity()}
		</div>;
	}
}

export { feature };
