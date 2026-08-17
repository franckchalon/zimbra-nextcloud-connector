#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

class FakeEventTarget {
	constructor() {
		this.listeners = new Map();
	}

	addEventListener(type, listener) {
		if (!this.listeners.has(type)) this.listeners.set(type, new Set());
		this.listeners.get(type).add(listener);
	}

	removeEventListener(type, listener) {
		if (this.listeners.has(type)) this.listeners.get(type).delete(listener);
	}

	dispatch(type, event = {}) {
		event.type = type;
		if (!event.preventDefault) event.preventDefault = () => {};
		if (!event.stopPropagation) event.stopPropagation = () => {};
		for (const listener of this.listeners.get(type) || []) listener(event);
	}
}

class FakeElement extends FakeEventTarget {
	constructor(ownerDocument, tagName) {
		super();
		this.ownerDocument = ownerDocument;
		this.tagName = String(tagName).toUpperCase();
		this.children = [];
		this.parentNode = null;
		this.style = {};
		this.attributes = {};
		this.className = '';
		this.textContent = '';
		this.id = '';
	}

	get firstChild() {
		return this.children[0] || null;
	}

	appendChild(child) {
		if (child.parentNode) child.parentNode.removeChild(child);
		child.parentNode = this;
		this.children.push(child);
		return child;
	}

	insertBefore(child, reference) {
		if (child.parentNode) child.parentNode.removeChild(child);
		const index = this.children.indexOf(reference);
		child.parentNode = this;
		if (index < 0) this.children.push(child);
		else this.children.splice(index, 0, child);
		return child;
	}

	removeChild(child) {
		const index = this.children.indexOf(child);
		if (index >= 0) this.children.splice(index, 1);
		child.parentNode = null;
		return child;
	}

	setAttribute(name, value) {
		this.attributes[name] = String(value);
	}

	getAttribute(name) {
		return this.attributes[name];
	}

	closest(selector) {
		if (!selector || selector[0] !== '.') return null;
		const className = selector.slice(1);
		let current = this;
		while (current) {
			if (String(current.className || '').split(/\s+/).includes(className)) return current;
			current = current.parentNode;
		}
		return null;
	}

	getBoundingClientRect() {
		if (String(this.className).includes('layer')) {
			return { left: 0, top: 64, right: 1280, bottom: 800, width: 1280, height: 736 };
		}
		const left = Number.parseFloat(this.style.left) || 100;
		const top = Number.parseFloat(this.style.top) || 90;
		const width = Number.parseFloat(this.style.width) || 900;
		const height = Number.parseFloat(this.style.height) || 600;
		return { left, top, right: left + width, bottom: top + height, width, height };
	}

	requestFullscreen() {
		this.ownerDocument.fullscreenElement = this;
		this.ownerDocument.dispatch('fullscreenchange', { target: this.ownerDocument });
		return Promise.resolve();
	}
}

class FakeWindow extends FakeEventTarget {
	constructor() {
		super();
		this.innerWidth = 1280;
		this.innerHeight = 800;
	}
}

class FakeDocument extends FakeEventTarget {
	constructor() {
		super();
		this.defaultView = new FakeWindow();
		this.body = new FakeElement(this, 'body');
		this.fullscreenElement = null;
	}

	createElement(tagName) {
		return new FakeElement(this, tagName);
	}

	getElementById(id) {
		return findNode(this.body, node => node.id === id);
	}

	exitFullscreen() {
		this.fullscreenElement = null;
		this.dispatch('fullscreenchange', { target: this });
		return Promise.resolve();
	}
}

function findNode(root, predicate) {
	if (predicate(root)) return root;
	for (const child of root.children || []) {
		const result = findNode(child, predicate);
		if (result) return result;
	}
	return null;
}

function findTag(root, tagName) {
	return findNode(root, node => node.tagName === String(tagName).toUpperCase());
}

function assert(condition, message) {
	if (!condition) throw new Error(message);
}

const projectDir = __dirname;
let source = fs.readFileSync(path.join(projectDir, 'src/components/floating-windows/index.js'), 'utf8');
source = source
	.replace("import { editorUrl, fileUrl } from '../../api';", 'const { editorUrl, fileUrl } = globalThis.__deps;')
	.replace("import { previewKind } from '../../file-types';", 'const { previewKind } = globalThis.__deps;')
	.replace("import { translate } from '../../i18n';", 'const { translate } = globalThis.__deps;')
	.replace("import { updateStoredWindow } from '../../workspace-state';", 'const { updateStoredWindow } = globalThis.__deps;')
	.replace("import style from './style.less';", 'const style = new Proxy({}, { get: (_, key) => String(key) });')
	.replace(/export function /g, 'function ');
source += '\nglobalThis.__floatingWindows = { initializeFloatingWindows, attachFloatingWindowCallbacks, setCloudRouteActive, openFloatingPreview, openFloatingEditor, closeFloatingWindows };';

const document = new FakeDocument();
const storedWindows = [];
const navigatedFiles = [];
const context = {
	console,
	document,
	Map,
	Proxy,
	Promise,
	Object,
	String,
	Boolean,
	Math,
	Number,
	Array,
	globalThis: null,
	__deps: {
		editorUrl: (filePath, profileId) => `editor://${profileId}${filePath}`,
		fileUrl: (filePath, disposition, profileId) => `file://${profileId}${filePath}?${disposition}`,
		previewKind: file => file.kind,
		translate: (language, key, variables) => variables && variables.name ? `${key}:${variables.name}` : key,
		updateStoredWindow: (scope, property, file) => storedWindows.push({ scope, property, file })
	}
};
context.globalThis = context;
vm.runInNewContext(source, context, { filename: 'floating-windows/index.js' });

const api = context.__floatingWindows;
const scope = 'account-1';
const image = { path: '/photo.jpg', name: 'photo.jpg', kind: 'image' };
const video = { path: '/video.mp4', name: 'video.mp4', kind: 'video' };
const audio = { path: '/music.mp3', name: 'music.mp3', kind: 'audio' };
const office = { path: '/report.odt', name: 'report.odt' };

api.initializeFloatingWindows(document);
api.attachFloatingWindowCallbacks(scope, { onPreviewNavigate: file => navigatedFiles.push(file) });
api.setCloudRouteActive(scope, true, document);
api.openFloatingPreview({ scope, profileId: 'cloud-1', language: 'fr', file: image, mediaFiles: [image, video, audio] }, document);

const host = document.getElementById('com-nextcloud-connector-floating-windows');
assert(host, 'Le conteneur persistant n’a pas été créé.');
const layer = host.firstChild;
assert(layer.getAttribute('aria-hidden') === 'false', 'La fenêtre ouverte reste masquée.');
const originalImage = findTag(host, 'img');
assert(originalImage && originalImage.src.includes('/photo.jpg'), 'Le clic sur une image ne crée pas son aperçu.');

api.setCloudRouteActive(scope, false, document);
assert(layer.getAttribute('aria-hidden') === 'true', 'La fenêtre ne se masque pas en quittant Cloud.');
assert(findTag(host, 'img') === originalImage, 'L’image a été démontée en quittant Cloud.');
api.setCloudRouteActive(scope, true, document);
api.openFloatingPreview({ scope, profileId: 'cloud-1', language: 'fr', file: image, mediaFiles: [image, video, audio] }, document);
assert(findTag(host, 'img') === originalImage, 'L’aperçu a été recréé au retour dans Cloud.');

document.dispatch('keydown', { key: 'ArrowRight' });
assert(findTag(host, 'video'), 'Le passage de l’image à la vidéo ne fonctionne pas.');
document.dispatch('keydown', { key: 'ArrowRight' });
assert(findTag(host, 'audio'), 'Le passage de la vidéo à la musique ne fonctionne pas.');
assert(navigatedFiles.length === 2, 'La navigation multimédia n’est pas synchronisée.');

api.openFloatingEditor({ scope, profileId: 'cloud-1', language: 'fr', officeLabel: 'Euro-Office', file: office }, document);
const originalEditor = findNode(host, node => String(node.className).includes('editorFrame'));
assert(originalEditor && originalEditor.src === 'editor://cloud-1/report.odt', 'Le clic sur un document ne crée pas l’éditeur.');
api.setCloudRouteActive(scope, false, document);
api.setCloudRouteActive(scope, true, document);
api.openFloatingEditor({ scope, profileId: 'cloud-1', language: 'fr', officeLabel: 'Euro-Office', file: office }, document);
assert(findNode(host, node => String(node.className).includes('editorFrame')) === originalEditor, 'L’iframe bureautique a été recréée après navigation.');

assert(storedWindows.some(item => item.property === 'previewFile' && item.file), 'L’aperçu n’est pas mémorisé.');
assert(storedWindows.some(item => item.property === 'editorFile' && item.file), 'L’éditeur n’est pas mémorisé.');
api.closeFloatingWindows(scope, document);

console.log('FloatingWindowsRuntimeTest: OK (image, vidéo, audio, document et persistance de navigation)');
