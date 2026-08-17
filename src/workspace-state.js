const WORKSPACE_STATE_KEY = 'com_nextcloud_connector.workspace.v1';

export function workspaceStateKey(scope) {
	return `${WORKSPACE_STATE_KEY}.${encodeURIComponent(String(scope || 'default'))}`;
}

function safeStoredFile(value) {
	if (!value || typeof value !== 'object') return null;
	if (typeof value.path !== 'string' || typeof value.name !== 'string') return null;
	return value;
}

export function restoredWorkspace(scope) {
	try {
		const value = JSON.parse(globalThis.sessionStorage.getItem(workspaceStateKey(scope)) || '{}');
		return {
			path: typeof value.path === 'string' && value.path.startsWith('/') ? value.path : '/',
			search: typeof value.search === 'string' ? value.search.slice(0, 500) : '',
			previewFile: safeStoredFile(value.previewFile),
			editorFile: safeStoredFile(value.editorFile)
		};
	} catch (error) {
		return { path: '/', search: '', previewFile: null, editorFile: null };
	}
}

export function saveWorkspace(state, scope) {
	try {
		globalThis.sessionStorage.setItem(workspaceStateKey(scope), JSON.stringify({
			path: state.path,
			search: state.search,
			previewFile: state.previewFile,
			editorFile: state.editorFile
		}));
	} catch (error) {}
}

export function updateStoredWindow(scope, property, file) {
	if (property !== 'previewFile' && property !== 'editorFile') return;
	const workspace = restoredWorkspace(scope);
	workspace[property] = safeStoredFile(file);
	saveWorkspace(workspace, scope);
}

export function resetWorkspace(scope) {
	try { globalThis.sessionStorage.removeItem(workspaceStateKey(scope)); }
	catch (error) {}
}
