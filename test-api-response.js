#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const babel = require('@babel/core');

const source = babel.transformSync(fs.readFileSync(path.join(__dirname, 'src/api.js'), 'utf8'), {
	filename: 'src/api.js',
	plugins: [require('@babel/plugin-transform-modules-commonjs')]
}).code;
const moduleValue = { exports: {} };
new Function('require', 'module', 'exports', source)(require, moduleValue, moduleValue.exports);

const response = (status, contentType, body, jsonError = false) => ({
	status,
	ok: status >= 200 && status < 300,
	headers: { get(name) { return String(name).toLowerCase() === 'content-type' ? contentType : ''; } },
	async text() { return body; },
	async json() {
		if (jsonError) throw new SyntaxError('invalid JSON');
		return JSON.parse(body);
	}
});

async function rejectedMessage(fakeResponse) {
	globalThis.fetch = async () => fakeResponse;
	try {
		await moduleValue.exports.api('/api/test');
		throw new Error('The request unexpectedly succeeded');
	} catch (error) {
		return error;
	}
}

(async () => {
	let error = await rejectedMessage(response(504, 'text/html; charset=UTF-8', '<html><body><h2>HTTP ERROR 504</h2></body></html>'));
	if (error.status !== 504 || error.message !== 'HTTP 504' || error.message.includes('<html>')) {
		throw new Error('Raw proxy HTML was exposed by the API helper');
	}

	error = await rejectedMessage(response(502, 'application/json', '<broken>', true));
	if (error.status !== 502 || error.message !== 'HTTP 502') {
		throw new Error('Malformed upstream JSON was not converted to a bounded HTTP error');
	}

	error = await rejectedMessage(response(400, 'text/plain', 'Requête refusée'));
	if (error.status !== 400 || error.message !== 'Requête refusée') {
		throw new Error('A safe plain-text API error was not preserved');
	}

	console.log('ApiResponseTest: OK (HTML 504 masqué, JSON invalide borné, texte sûr conservé)');
})().catch(error => {
	console.error(error.stack || error);
	process.exitCode = 1;
});
