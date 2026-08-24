let nextBridgeId = 1;
const bridges = [];

function messageIdentity(value) {
	if (!value || typeof value !== 'object') return '';
	return String(value.id || value.cid || value.draftId || value.messageId || '');
}

function readMessage(source) {
	if (!source || typeof source.getMessage !== 'function') return null;
	try { return source.getMessage() || null; } catch (error) { return null; }
}

export function registerComposeBridge(props) {
	const bridge = { id: nextBridgeId++, props: props || {}, updatedAt: Date.now() };
	bridges.push(bridge);
	return bridge;
}

export function updateComposeBridge(bridge, props) {
	if (!bridge) return;
	bridge.props = props || {};
	bridge.updatedAt = Date.now();
}

export function unregisterComposeBridge(bridge) {
	const index = bridges.indexOf(bridge);
	if (index >= 0) bridges.splice(index, 1);
}

export function resolveComposeBridge(editor) {
	const available = bridges.filter(bridge => bridge.props && typeof bridge.props.insertAtCaret === 'function');
	if (!available.length) return null;
	const editorMessageId = messageIdentity(readMessage(editor));
	let best = null;
	let bestScore = -1;
	available.forEach(bridge => {
		const props = bridge.props;
		let score = bridge.id;
		if (props.editor === editor || props.composeEditor === editor) score += 1000000;
		const bridgeMessageId = messageIdentity(readMessage(props));
		if (editorMessageId && bridgeMessageId === editorMessageId) score += 500000;
		if (score > bestScore) { best = bridge; bestScore = score; }
	});
	return best;
}

function plainTextMode(props) {
	if (!props) return false;
	try {
		return typeof props.isPlainText === 'function' ? Boolean(props.isPlainText()) : Boolean(props.isPlainText);
	} catch (error) { return false; }
}

export function insertComposeContent(bridge, editor, html, text) {
	if (bridge && bridge.props && typeof bridge.props.insertAtCaret === 'function') {
		bridge.props.insertAtCaret(plainTextMode(bridge.props) ? text : html, false);
		return true;
	}

	// Compatibility fallback for Zimbra builds exposing an editor insertion
	// method on the attachment slot. The documented insertAtCaret bridge above
	// remains the primary path.
	const candidates = [
		editor,
		editor && editor.editor,
		editor && typeof editor.getEditor === 'function' ? editor.getEditor() : null
	].filter(Boolean);
	for (const candidate of candidates) {
		for (const method of ['insertContent', 'insertHTML', 'insertHtml']) {
			if (typeof candidate[method] === 'function') {
				candidate[method](html);
				return true;
			}
		}
	}
	return false;
}

export function buildReadOnlyLinkContent(links, intro, readOnlyLabel) {
	const escape = value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
		.replace(/>/g, '&gt;').replace(/"/g, '&quot;');
	// Never place an arbitrary scheme returned by a remote service in a mail
	// composer. Nextcloud public shares are HTTP(S); anything else is rejected.
	const safeLinks = (Array.isArray(links) ? links : []).filter(link =>
		link && /^https?:\/\/[^\s]+$/i.test(String(link.url || ''))
	);
	return {
		html: `<p>${escape(intro)}</p><ul>${safeLinks.map(link => `<li><a href="${escape(link.url)}">${escape(link.name)}</a> — ${escape(readOnlyLabel)}</li>`).join('')}</ul>`,
		text: `${intro}\n${safeLinks.map(link => `${link.name} (${readOnlyLabel}) : ${link.url}`).join('\n')}`
	};
}

export function resetComposeBridgesForTests() {
	bridges.splice(0, bridges.length);
	nextBridgeId = 1;
}
