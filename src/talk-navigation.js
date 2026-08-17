export const TALK_NAVIGATION_EVENT = 'com_nextcloud_connector:talk-navigation';
export const CLOUD_VIEW_EVENT = 'com_nextcloud_connector:cloud-view';
export const TALK_SOUND_EVENT = 'com_nextcloud_connector:talk-sound';

function viewStorageKey(scope) {
	return `com_nextcloud_connector:cloud-view:${String(scope || 'default').slice(0, 160)}`;
}

function soundStorageKey(scope) {
	return `com_nextcloud_connector:talk-sound:${String(scope || 'default').slice(0, 160)}`;
}

function emit(name, detail) {
	const target = globalThis.window || globalThis;
	if (!target || typeof target.dispatchEvent !== 'function') return;
	let event;
	if (typeof globalThis.CustomEvent === 'function') {
		event = new globalThis.CustomEvent(name, { detail });
	} else if (globalThis.document && typeof globalThis.document.createEvent === 'function') {
		event = globalThis.document.createEvent('CustomEvent');
		event.initCustomEvent(name, false, false, detail);
	}
	if (event) target.dispatchEvent(event);
}

export function currentCloudView(scope) {
	try {
		return globalThis.sessionStorage && globalThis.sessionStorage.getItem(viewStorageKey(scope)) === 'chat'
			? 'chat' : 'files';
	} catch (error) {
		return 'files';
	}
}

export function setCloudView(scope, view) {
	const normalized = view === 'chat' ? 'chat' : 'files';
	try {
		if (globalThis.sessionStorage) globalThis.sessionStorage.setItem(viewStorageKey(scope), normalized);
	} catch (error) {}
	const detail = { scope: String(scope || 'default'), view: normalized };
	emit(CLOUD_VIEW_EVENT, detail);
}

export function isTalkSoundEnabled(scope) {
	try {
		const stored = globalThis.localStorage && globalThis.localStorage.getItem(soundStorageKey(scope));
		return stored !== 'false';
	} catch (error) {
		return true;
	}
}

export function setTalkSoundEnabled(scope, enabled) {
	const normalized = Boolean(enabled);
	try {
		if (globalThis.localStorage) globalThis.localStorage.setItem(soundStorageKey(scope), normalized ? 'true' : 'false');
	} catch (error) {}
	emit(TALK_SOUND_EVENT, { scope: String(scope || 'default'), enabled: normalized });
}

export function notifyTalkNavigation(detail = {}) {
	emit(TALK_NAVIGATION_EVENT, detail);
}
