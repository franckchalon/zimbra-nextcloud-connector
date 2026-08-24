#!/usr/bin/env node
'use strict';

const fs = require('fs');
const vm = require('vm');
const path = require('path');

const root = __dirname;
let source = fs.readFileSync(path.join(root, 'src/components/cloud-attacher/compose-bridge.js'), 'utf8')
	.replace(/\bexport\s+/g, '');
source += '\nglobalThis.__bridge = { registerComposeBridge, updateComposeBridge, unregisterComposeBridge, resolveComposeBridge, insertComposeContent, buildReadOnlyLinkContent, resetComposeBridgesForTests };';
const context = { Date };
vm.runInNewContext(source, context);
const bridge = context.__bridge;

function check(condition, message) {
	if (!condition) throw new Error(message);
}

bridge.resetComposeBridgesForTests();
let inserted = null;
const first = bridge.registerComposeBridge({
	getMessage: () => ({ id: 'draft-1' }),
	insertAtCaret: (content, preserveSelection) => { inserted = { content, preserveSelection }; },
	isPlainText: false
});
const second = bridge.registerComposeBridge({
	getMessage: () => ({ id: 'draft-2' }),
	insertAtCaret: () => { throw new Error('Wrong composer'); },
	isPlainText: false
});

check(bridge.resolveComposeBridge({ getMessage: () => ({ id: 'draft-1' }) }) === first,
	'The insertion bridge must match the active draft');
check(bridge.insertComposeContent(first, null, '<p>Cloud</p>', 'Cloud'),
	'The official insertAtCaret API must be used');
check(inserted.content === '<p>Cloud</p>' && inserted.preserveSelection === false,
	'HTML must be inserted through Zimbra insertAtCaret');

bridge.updateComposeBridge(first, {
	getMessage: () => ({ id: 'draft-1' }),
	insertAtCaret: content => { inserted = { content }; },
	isPlainText: () => true
});
bridge.insertComposeContent(first, null, '<p>HTML</p>', 'Plain text');
check(inserted.content === 'Plain text', 'Plain-text composers must receive plain links');

let legacyHtml = '';
check(bridge.insertComposeContent(null, { insertHTML: value => { legacyHtml = value; } }, '<b>Legacy</b>', 'Legacy'),
	'Older editor insertion APIs must remain a compatibility fallback');
check(legacyHtml === '<b>Legacy</b>', 'The compatibility fallback must receive HTML');
check(!bridge.insertComposeContent(null, {}, '<p>None</p>', 'None'),
	'Missing insertion APIs must return a controlled manual fallback');

const content = bridge.buildReadOnlyLinkContent([
	{ name: 'Budget <2026>.xlsx', url: 'https://cloud.example/s/a&b' }
], 'Cloud links:', 'read only');
check(content.html.includes('Budget &lt;2026&gt;.xlsx') && content.html.includes('a&amp;b'),
	'Generated HTML must escape file names and URLs');
check(content.text.includes('Budget <2026>.xlsx (read only) : https://cloud.example/s/a&b'),
	'The manual fallback must preserve readable plain text');
const unsafe = bridge.buildReadOnlyLinkContent([
	{ name: 'Unsafe', url: 'javascript:alert(1)' }
], 'Cloud links:', 'read only');
check(!unsafe.html.includes('javascript:') && !unsafe.text.includes('javascript:'),
	'Generated links must reject non-HTTP(S) URL schemes');

bridge.unregisterComposeBridge(first);
bridge.unregisterComposeBridge(second);
check(bridge.resolveComposeBridge({}) === null, 'Unmounted composers must leave no stale bridge');

console.log('ComposeLinksTest: OK (official insertAtCaret, plain text, legacy and manual fallbacks)');
