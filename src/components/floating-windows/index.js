import { editorUrl, fileUrl } from '../../api';
import { previewKind } from '../../file-types';
import { translate } from '../../i18n';
import { updateStoredWindow } from '../../workspace-state';
import style from './style.less';

const HOST_ID = 'com-nextcloud-connector-floating-windows';
const callbacksByScope = new Map();
let windowManager;

function normalizedScope(scope) {
	return String(scope || 'default');
}

function normalizedMediaFiles(file, mediaFiles) {
	const files = Array.isArray(mediaFiles) ? mediaFiles.filter(item => item && item.path && item.name) : [];
	return files.some(item => item.path === file.path) ? files : [file, ...files];
}

function notify(scope, name, value) {
	const callbacks = callbacksByScope.get(normalizedScope(scope));
	if (callbacks && typeof callbacks[name] === 'function') callbacks[name](value);
}

function createNode(ownerDocument, tagName, className, text) {
	const node = ownerDocument.createElement(tagName);
	if (className) node.className = className;
	if (text !== undefined) node.textContent = text;
	return node;
}

function removeChildren(node) {
	while (node.firstChild) node.removeChild(node.firstChild);
}

function interactionShield(ownerDocument, cursor) {
	const shield = ownerDocument.createElement('div');
	Object.assign(shield.style, {
		position: 'fixed', inset: '0', zIndex: '2147483646', cursor,
		background: 'transparent', userSelect: 'none'
	});
	ownerDocument.body.appendChild(shield);
	return () => {
		if (shield.parentNode) shield.parentNode.removeChild(shield);
	};
}

class FloatingFrame {
	constructor(manager, { kind, title, language = 'fr', windowClass, minimumWidth = 420, minimumHeight = 280, onClose }) {
		this.manager = manager;
		this.kind = kind;
		this.ownerDocument = manager.ownerDocument;
		this.ownerWindow = manager.ownerWindow;
		this.minimumWidth = minimumWidth;
		this.minimumHeight = minimumHeight;
		this.onClose = onClose;

		this.node = createNode(this.ownerDocument, 'section', `${style.window} ${windowClass}`);
		this.node.setAttribute('role', 'dialog');
		this.header = createNode(this.ownerDocument, 'header', style.header);
		this.titleNode = createNode(this.ownerDocument, 'h2', '', title);
		this.titleNode.title = title;
		this.headerActions = createNode(this.ownerDocument, 'div', style.headerActions);
		this.closeButton = createNode(this.ownerDocument, 'button', style.closeButton, '×');
		this.closeButton.type = 'button';
		this.body = createNode(this.ownerDocument, 'div', style.body);

		this.headerActions.appendChild(this.closeButton);
		this.header.appendChild(this.titleNode);
		this.header.appendChild(this.headerActions);
		this.node.appendChild(this.header);
		this.node.appendChild(this.body);
		this.manager.layer.appendChild(this.node);

		this.resizeHandles = [];
		[
			['n', style.resizeHandleN], ['ne', style.resizeHandleNE], ['e', style.resizeHandleE], ['se', style.resizeHandleSE],
			['s', style.resizeHandleS], ['sw', style.resizeHandleSW], ['w', style.resizeHandleW], ['nw', style.resizeHandleNW]
		].forEach(([direction, className]) => {
			const handle = createNode(this.ownerDocument, 'span', `${style.resizeHandle} ${className}`);
			handle.setAttribute('aria-hidden', 'true');
			handle.addEventListener('mousedown', event => this.startResize(event, direction));
			handle.addEventListener('click', event => {
				event.preventDefault();
				event.stopPropagation();
			});
			this.node.appendChild(handle);
			this.resizeHandles.push({ direction, handle });
		});

		this.node.addEventListener('mousedown', () => this.manager.activate(this.kind));
		this.header.addEventListener('mousedown', event => this.startMove(event));
		this.closeButton.addEventListener('click', event => {
			event.preventDefault();
			event.stopPropagation();
			this.onClose();
		});
		this.boundConstrain = () => this.constrainToLayer();
		this.ownerWindow.addEventListener('resize', this.boundConstrain);
		this.setLanguage(language);
	}

	setLanguage(language = 'fr') {
		this.language = language;
		this.closeButton.setAttribute('aria-label', translate(language, 'closeLabel'));
		this.resizeHandles.forEach(({ direction, handle }) => {
			handle.title = direction === 'se' ? translate(language, 'resizeWindow') : '';
		});
	}

	setTitle(title) {
		this.titleNode.textContent = title;
		this.titleNode.title = title;
	}

	setZIndex(zIndex) {
		this.node.style.zIndex = String(zIndex);
	}

	addHeaderAction(node) {
		this.headerActions.insertBefore(node, this.closeButton);
	}

	layerBounds() {
		const bounds = this.manager.layer.getBoundingClientRect();
		return bounds && bounds.width ? bounds : {
			left: 0, top: 64, right: this.ownerWindow.innerWidth, bottom: this.ownerWindow.innerHeight,
			width: this.ownerWindow.innerWidth, height: Math.max(0, this.ownerWindow.innerHeight - 64)
		};
	}

	constrainToLayer() {
		if (!this.node || this.ownerDocument.fullscreenElement === this.node) return;
		const bounds = this.layerBounds();
		const rect = this.node.getBoundingClientRect();
		const availableWidth = Math.max(160, bounds.width - 16);
		const availableHeight = Math.max(160, bounds.height - 16);
		const width = Math.min(rect.width, availableWidth);
		const height = Math.min(rect.height, availableHeight);
		const left = Math.min(bounds.right - width - 8, Math.max(bounds.left + 8, rect.left));
		const top = Math.min(bounds.bottom - height - 8, Math.max(bounds.top + 8, rect.top));
		Object.assign(this.node.style, {
			position: 'fixed', left: `${left}px`, top: `${top}px`,
			width: `${width}px`, height: `${height}px`, transform: 'none'
		});
	}

	startResize(event, direction) {
		if (!this.node || event.button !== 0 || this.ownerDocument.fullscreenElement === this.node) return;
		this.manager.activate(this.kind);
		event.preventDefault();
		event.stopPropagation();
		const bounds = this.layerBounds();
		const start = this.node.getBoundingClientRect();
		const startX = event.clientX;
		const startY = event.clientY;
		const minimumWidth = Math.min(this.minimumWidth, Math.max(160, bounds.width - 16));
		const minimumHeight = Math.min(this.minimumHeight, Math.max(160, bounds.height - 16));
		const previousCursor = this.ownerDocument.body.style.cursor;
		const previousSelection = this.ownerDocument.body.style.userSelect;
		const removeShield = interactionShield(this.ownerDocument, `${direction}-resize`);

		Object.assign(this.node.style, {
			position: 'fixed', left: `${start.left}px`, top: `${start.top}px`,
			width: `${start.width}px`, height: `${start.height}px`, transform: 'none'
		});
		this.ownerDocument.body.style.cursor = `${direction}-resize`;
		this.ownerDocument.body.style.userSelect = 'none';

		const resizeWindow = moveEvent => {
			moveEvent.preventDefault();
			const dx = moveEvent.clientX - startX;
			const dy = moveEvent.clientY - startY;
			let left = start.left;
			let top = start.top;
			let width = start.width;
			let height = start.height;

			if (direction.includes('e')) width = Math.max(minimumWidth, Math.min(start.width + dx, bounds.right - 8 - start.left));
			if (direction.includes('w')) {
				width = Math.max(minimumWidth, Math.min(start.width - dx, start.right - bounds.left - 8));
				left = start.right - width;
			}
			if (direction.includes('s')) height = Math.max(minimumHeight, Math.min(start.height + dy, bounds.bottom - 8 - start.top));
			if (direction.includes('n')) {
				height = Math.max(minimumHeight, Math.min(start.height - dy, start.bottom - bounds.top - 8));
				top = start.bottom - height;
			}

			Object.assign(this.node.style, {
				left: `${Math.max(bounds.left + 8, left)}px`, top: `${Math.max(bounds.top + 8, top)}px`,
				width: `${width}px`, height: `${height}px`
			});
		};

		const stop = stopEvent => {
			if (stopEvent) {
				stopEvent.preventDefault();
				stopEvent.stopPropagation();
			}
			this.ownerDocument.removeEventListener('mousemove', resizeWindow, true);
			this.ownerDocument.removeEventListener('mouseup', stop, true);
			this.ownerWindow.removeEventListener('blur', stop);
			this.ownerDocument.body.style.cursor = previousCursor;
			this.ownerDocument.body.style.userSelect = previousSelection;
			removeShield();
		};

		this.ownerDocument.addEventListener('mousemove', resizeWindow, true);
		this.ownerDocument.addEventListener('mouseup', stop, true);
		this.ownerWindow.addEventListener('blur', stop);
	}

	startMove(event) {
		if (!this.node || event.button !== 0 || this.ownerDocument.fullscreenElement === this.node) return;
		if (event.target && event.target.closest && event.target.closest(`.${style.headerActions}`)) return;
		this.manager.activate(this.kind);
		event.preventDefault();
		event.stopPropagation();
		const bounds = this.layerBounds();
		const start = this.node.getBoundingClientRect();
		const startX = event.clientX;
		const startY = event.clientY;
		const previousCursor = this.ownerDocument.body.style.cursor;
		const previousSelection = this.ownerDocument.body.style.userSelect;
		const removeShield = interactionShield(this.ownerDocument, 'move');

		Object.assign(this.node.style, {
			position: 'fixed', left: `${start.left}px`, top: `${start.top}px`,
			width: `${start.width}px`, height: `${start.height}px`, transform: 'none'
		});
		this.ownerDocument.body.style.cursor = 'move';
		this.ownerDocument.body.style.userSelect = 'none';

		const moveWindow = moveEvent => {
			moveEvent.preventDefault();
			const maximumLeft = Math.max(bounds.left + 8, bounds.right - start.width - 8);
			const maximumTop = Math.max(bounds.top + 8, bounds.bottom - start.height - 8);
			const left = Math.min(maximumLeft, Math.max(bounds.left + 8, start.left + moveEvent.clientX - startX));
			const top = Math.min(maximumTop, Math.max(bounds.top + 8, start.top + moveEvent.clientY - startY));
			Object.assign(this.node.style, { left: `${left}px`, top: `${top}px` });
		};

		const stop = stopEvent => {
			if (stopEvent) {
				stopEvent.preventDefault();
				stopEvent.stopPropagation();
			}
			this.ownerDocument.removeEventListener('mousemove', moveWindow, true);
			this.ownerDocument.removeEventListener('mouseup', stop, true);
			this.ownerWindow.removeEventListener('blur', stop);
			this.ownerDocument.body.style.cursor = previousCursor;
			this.ownerDocument.body.style.userSelect = previousSelection;
			removeShield();
		};

		this.ownerDocument.addEventListener('mousemove', moveWindow, true);
		this.ownerDocument.addEventListener('mouseup', stop, true);
		this.ownerWindow.addEventListener('blur', stop);
	}

	destroy() {
		this.ownerWindow.removeEventListener('resize', this.boundConstrain);
		if (this.node && this.node.parentNode) this.node.parentNode.removeChild(this.node);
		this.node = null;
	}
}

class FloatingWindowManager {
	constructor(ownerDocument) {
		this.ownerDocument = ownerDocument;
		this.ownerWindow = ownerDocument.defaultView || globalThis;
		this.activeScope = '';
		this.preview = null;
		this.editor = null;
		this.sequence = 3;
		this.previewZ = 2;
		this.editorZ = 3;

		this.hostNode = ownerDocument.getElementById(HOST_ID);
		if (!this.hostNode) {
			this.hostNode = ownerDocument.createElement('div');
			this.hostNode.id = HOST_ID;
			this.hostNode.setAttribute('data-zimlet', 'com_nextcloud_connector');
			ownerDocument.body.appendChild(this.hostNode);
		} else {
			removeChildren(this.hostNode);
		}

		this.layer = createNode(ownerDocument, 'div', `${style.layer} ${style.layerInactive}`);
		this.layer.setAttribute('aria-hidden', 'true');
		this.hostNode.appendChild(this.layer);
		this.boundKeyDown = event => this.onKeyDown(event);
		this.boundFullscreenChange = () => this.onFullscreenChange();
		ownerDocument.addEventListener('keydown', this.boundKeyDown);
		ownerDocument.addEventListener('fullscreenchange', this.boundFullscreenChange);
	}

	destroy() {
		this.ownerDocument.removeEventListener('keydown', this.boundKeyDown);
		this.ownerDocument.removeEventListener('fullscreenchange', this.boundFullscreenChange);
		if (this.preview) this.preview.frame.destroy();
		if (this.editor) this.editor.frame.destroy();
		if (this.hostNode && this.hostNode.parentNode) this.hostNode.parentNode.removeChild(this.hostNode);
		this.preview = null;
		this.editor = null;
	}

	setRouteActive(scope, active) {
		const scopeId = normalizedScope(scope);
		this.activeScope = active ? scopeId : (this.activeScope === scopeId ? '' : this.activeScope);
		this.renderVisibility();
	}

	renderVisibility() {
		const previewVisible = Boolean(this.preview && this.preview.scope === this.activeScope);
		const editorVisible = Boolean(this.editor && this.editor.scope === this.activeScope);
		const visible = Boolean(this.activeScope && (previewVisible || editorVisible));
		this.layer.className = `${style.layer} ${visible ? '' : style.layerInactive}`;
		this.layer.setAttribute('aria-hidden', visible ? 'false' : 'true');
		if (this.preview) this.preview.frame.node.style.display = previewVisible ? '' : 'none';
		if (this.editor) this.editor.frame.node.style.display = editorVisible ? '' : 'none';
	}

	activate(kind) {
		this.sequence += 1;
		if (kind === 'preview') {
			this.previewZ = this.sequence;
			if (this.preview) this.preview.frame.setZIndex(this.previewZ);
		} else {
			this.editorZ = this.sequence;
			if (this.editor) this.editor.frame.setZIndex(this.editorZ);
		}
	}

	createPreviewFrame(data) {
		const frame = new FloatingFrame(this, {
			kind: 'preview', title: data.file.name, language: data.language,
			windowClass: style.mediaWindow, onClose: () => this.closePreview(true)
		});
		const fullscreenButton = createNode(this.ownerDocument, 'button', style.fullscreenButton);
		fullscreenButton.type = 'button';
		fullscreenButton.addEventListener('click', event => {
			event.preventDefault();
			event.stopPropagation();
			this.togglePreviewFullscreen();
		});
		frame.addHeaderAction(fullscreenButton);
		const shell = createNode(this.ownerDocument, 'div', style.previewShell);
		const content = createNode(this.ownerDocument, 'div', style.preview);
		shell.appendChild(content);
		frame.body.appendChild(shell);
		return { frame, fullscreenButton, shell, content, navigation: null };
	}

	openPreview(data) {
		if (!data || !data.file) return;
		const scope = normalizedScope(data.scope);
		const identity = `${scope}:${data.profileId || ''}:${data.file.path}`;
		const mediaFiles = normalizedMediaFiles(data.file, data.mediaFiles);
		const sameIdentity = Boolean(this.preview && this.preview.identity === identity);
		if (!this.preview) {
			const nodes = this.createPreviewFrame(data);
			this.preview = { ...data, ...nodes, scope, identity, mediaFiles };
		} else {
			this.preview = { ...this.preview, ...data, scope, identity, mediaFiles };
		}
		this.preview.frame.setLanguage(data.language || 'fr');
		if (!sameIdentity) this.renderPreviewMedia();
		this.updatePreviewChrome();
		updateStoredWindow(scope, 'previewFile', data.file);
		this.activate('preview');
		this.renderVisibility();
	}

	renderPreviewMedia() {
		if (!this.preview) return;
		const { file, profileId = '', content } = this.preview;
		removeChildren(content);
		const kind = previewKind(file);
		const src = fileUrl(file.path, 'inline', profileId);
		let media;
		if (kind === 'image') {
			media = createNode(this.ownerDocument, 'img');
			media.src = src;
			media.alt = file.name;
		} else if (kind === 'video') {
			media = createNode(this.ownerDocument, 'video');
			media.src = src;
			media.controls = true;
			media.autoplay = true;
		} else if (kind === 'audio') {
			const audioPreview = createNode(this.ownerDocument, 'div', style.audioPreview);
			audioPreview.appendChild(createNode(this.ownerDocument, 'span', '', '🎵'));
			audioPreview.appendChild(createNode(this.ownerDocument, 'strong', '', file.name));
			media = createNode(this.ownerDocument, 'audio');
			media.src = src;
			media.controls = true;
			media.autoplay = true;
			audioPreview.appendChild(media);
			content.appendChild(audioPreview);
			this.preview.mediaNode = media;
			return;
		} else if (kind === 'pdf' || kind === 'text') {
			media = createNode(this.ownerDocument, 'iframe');
			media.src = src;
			media.title = file.name;
		}
		if (media) content.appendChild(media);
		this.preview.mediaNode = media || null;
	}

	updatePreviewChrome() {
		if (!this.preview) return;
		const { file, mediaFiles, frame, shell, language = 'fr' } = this.preview;
		const index = mediaFiles.findIndex(item => item.path === file.path);
		const hasGallery = index >= 0 && mediaFiles.length > 1;
		frame.setTitle(hasGallery ? `${file.name} — ${index + 1}/${mediaFiles.length}` : file.name);
		if (this.preview.navigation && this.preview.navigation.parentNode) {
			this.preview.navigation.parentNode.removeChild(this.preview.navigation);
		}
		this.preview.navigation = null;
		if (hasGallery) {
			const navigation = createNode(this.ownerDocument, 'div', style.previewNavigation);
			const previous = createNode(this.ownerDocument, 'button', '', '‹');
			previous.type = 'button';
			previous.disabled = index === 0;
			previous.setAttribute('aria-label', translate(language, 'previousFile'));
			previous.addEventListener('click', () => this.navigatePreview(-1));
			const position = createNode(this.ownerDocument, 'span', '', `${index + 1} / ${mediaFiles.length}`);
			const next = createNode(this.ownerDocument, 'button', '', '›');
			next.type = 'button';
			next.disabled = index === mediaFiles.length - 1;
			next.setAttribute('aria-label', translate(language, 'nextFile'));
			next.addEventListener('click', () => this.navigatePreview(1));
			navigation.appendChild(previous);
			navigation.appendChild(position);
			navigation.appendChild(next);
			shell.appendChild(navigation);
			this.preview.navigation = navigation;
		}
		this.onFullscreenChange();
	}

	togglePreviewFullscreen() {
		if (!this.preview) return;
		try {
			if (this.ownerDocument.fullscreenElement) {
				const result = this.ownerDocument.exitFullscreen();
				if (result && typeof result.catch === 'function') result.catch(() => {});
			} else if (this.preview.frame.node.requestFullscreen) {
				const result = this.preview.frame.node.requestFullscreen();
				if (result && typeof result.catch === 'function') result.catch(() => {});
			}
		} catch (error) {}
	}

	onFullscreenChange() {
		if (!this.preview) return;
		const fullscreen = this.ownerDocument.fullscreenElement === this.preview.frame.node;
		this.preview.fullscreenButton.textContent = translate(this.preview.language || 'fr', fullscreen ? 'exitFullscreen' : 'fullscreen');
	}

	onKeyDown(event) {
		if (!this.preview || this.preview.scope !== this.activeScope) return;
		if (event.key === 'ArrowLeft') this.navigatePreview(-1);
		else if (event.key === 'ArrowRight') this.navigatePreview(1);
		else if (event.key === 'Escape' && !this.ownerDocument.fullscreenElement) this.closePreview(true);
	}

	navigatePreview(offset) {
		if (!this.preview) return;
		const index = this.preview.mediaFiles.findIndex(item => item.path === this.preview.file.path);
		const target = index + offset;
		if (index < 0 || target < 0 || target >= this.preview.mediaFiles.length) return;
		const file = this.preview.mediaFiles[target];
		this.preview.file = file;
		this.preview.identity = `${this.preview.scope}:${this.preview.profileId || ''}:${file.path}`;
		this.renderPreviewMedia();
		this.updatePreviewChrome();
		updateStoredWindow(this.preview.scope, 'previewFile', file);
		notify(this.preview.scope, 'onPreviewNavigate', file);
	}

	closePreview(shouldNotify) {
		if (!this.preview) return;
		const scope = this.preview.scope;
		if (this.ownerDocument.fullscreenElement === this.preview.frame.node && this.ownerDocument.exitFullscreen) {
			try {
				const result = this.ownerDocument.exitFullscreen();
				if (result && typeof result.catch === 'function') result.catch(() => {});
			} catch (error) {}
		}
		this.preview.frame.destroy();
		this.preview = null;
		updateStoredWindow(scope, 'previewFile', null);
		this.renderVisibility();
		if (shouldNotify) notify(scope, 'onPreviewClose');
	}

	openEditor(data) {
		if (!data || !data.file) return;
		const scope = normalizedScope(data.scope);
		const identity = `${scope}:${data.profileId || ''}:${data.file.path}`;
		const sameIdentity = Boolean(this.editor && this.editor.identity === identity);
		if (!this.editor) {
			const title = `${data.officeLabel || translate(data.language || 'fr', 'onlineEditor')} — ${data.file.name}`;
			const frame = new FloatingFrame(this, {
				kind: 'editor', title, language: data.language, windowClass: style.editorWindow,
				minimumWidth: 560, minimumHeight: 420, onClose: () => this.closeEditor(true)
			});
			const iframe = createNode(this.ownerDocument, 'iframe', style.editorFrame);
			frame.body.appendChild(iframe);
			this.editor = { ...data, scope, identity, frame, iframe };
		} else {
			this.editor = { ...this.editor, ...data, scope, identity };
		}
		const { file, language = 'fr', officeLabel, profileId = '', frame, iframe } = this.editor;
		frame.setLanguage(language);
		frame.setTitle(`${officeLabel || translate(language, 'onlineEditor')} — ${file.name}`);
		iframe.title = translate(language, 'editFile', { name: file.name });
		if (!sameIdentity) iframe.src = editorUrl(file.path, profileId);
		updateStoredWindow(scope, 'editorFile', file);
		this.activate('editor');
		this.renderVisibility();
	}

	closeEditor(shouldNotify) {
		if (!this.editor) return;
		const scope = this.editor.scope;
		this.editor.frame.destroy();
		this.editor = null;
		updateStoredWindow(scope, 'editorFile', null);
		this.renderVisibility();
		if (shouldNotify) notify(scope, 'onEditorClose');
	}

	closeAll(scope) {
		const scopeId = normalizedScope(scope);
		if (this.preview && this.preview.scope === scopeId) this.closePreview(false);
		else updateStoredWindow(scopeId, 'previewFile', null);
		if (this.editor && this.editor.scope === scopeId) this.closeEditor(false);
		else updateStoredWindow(scopeId, 'editorFile', null);
	}
}

function ensureManager(ownerDocument = globalThis.document) {
	const documentRef = ownerDocument && ownerDocument.body ? ownerDocument : globalThis.document;
	if (!documentRef || !documentRef.body) return null;
	if (windowManager && windowManager.ownerDocument === documentRef) return windowManager;
	if (windowManager) windowManager.destroy();
	windowManager = new FloatingWindowManager(documentRef);
	return windowManager;
}

function withManager(action, ownerDocument) {
	const manager = ensureManager(ownerDocument);
	if (manager) action(manager);
}

export function initializeFloatingWindows(ownerDocument) {
	ensureManager(ownerDocument);
}

export function attachFloatingWindowCallbacks(scope, callbacks) {
	callbacksByScope.set(normalizedScope(scope), callbacks || {});
}

export function detachFloatingWindowCallbacks(scope) {
	callbacksByScope.delete(normalizedScope(scope));
}

export function setCloudRouteActive(scope, active, ownerDocument) {
	withManager(manager => manager.setRouteActive(scope, active), ownerDocument);
}

export function openFloatingPreview(data, ownerDocument) {
	withManager(manager => manager.openPreview(data), ownerDocument);
}

export function openFloatingEditor(data, ownerDocument) {
	withManager(manager => manager.openEditor(data), ownerDocument);
}

export function closeFloatingWindows(scope, ownerDocument) {
	withManager(manager => manager.closeAll(scope), ownerDocument);
}
