#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const AdmZip = require('adm-zip');
const { zimletVersion } = require('./package.json');

const root = __dirname;
const buildDir = path.join(root, 'build-chat');
const packageDir = path.join(root, 'pkg-chat');
const zimletName = 'com_nextcloud_connector_chat';
const descriptions = {
	en_US: ['Chat', 'Nextcloud Talk chat navigation for zimbra-nextcloud-connector.'],
	fr_FR: ['Chat', 'Navigation du chat Nextcloud Talk pour zimbra-nextcloud-connector.'],
	es_ES: ['Chat', 'Navegación del chat Nextcloud Talk para zimbra-nextcloud-connector.'],
	es_AR: ['Chat', 'Navegación del chat Nextcloud Talk para zimbra-nextcloud-connector.'],
	it_IT: ['Chat', 'Navigazione della chat Nextcloud Talk per zimbra-nextcloud-connector.'],
	de_DE: ['Chat', 'Nextcloud-Talk-Chatnavigation für zimbra-nextcloud-connector.'],
	pt_PT: ['Chat', 'Navegação do chat Nextcloud Talk para zimbra-nextcloud-connector.'],
	pt_BR: ['Chat', 'Navegação do chat Nextcloud Talk para zimbra-nextcloud-connector.'],
	hi_IN: ['चैट', 'zimbra-nextcloud-connector के लिए Nextcloud Talk चैट नेविगेशन।'],
	ms_MY: ['Sembang', 'Navigasi sembang Nextcloud Talk untuk zimbra-nextcloud-connector.'],
	ru_RU: ['Чат', 'Навигация чата Nextcloud Talk для zimbra-nextcloud-connector.']
};

function unicodeProperties(value) {
	let result = '';
	for (const character of String(value)) {
		const code = character.codePointAt(0);
		if (code >= 0x20 && code <= 0x7e) result += character;
		else if (code <= 0xffff) result += `\\u${code.toString(16).padStart(4, '0')}`;
		else {
			const offset = code - 0x10000;
			const high = 0xd800 + (offset >> 10);
			const low = 0xdc00 + (offset & 0x3ff);
			result += `\\u${high.toString(16)}\\u${low.toString(16)}`;
		}
	}
	return result;
}

if (!fs.existsSync(path.join(buildDir, 'index.js'))) {
	throw new Error('build-chat/index.js is missing; run npm run build:chat first');
}
fs.mkdirSync(packageDir, { recursive: true });

const [label, description] = descriptions.en_US;
const resources = fs.readdirSync(buildDir)
	.filter(file => file !== `${zimletName}.xml` && !file.endsWith('.properties'))
	.sort()
	.map(file => file.endsWith('.js') ? `\t<include>${file}</include>` : `\t<resource>${file}</resource>`);
const descriptor = [
	`<zimlet name="${zimletName}" version="${zimletVersion}" description="${description}" label="${label}" zimbraXZimletCompatibleSemVer=">=0.0.1">`,
	...resources,
	'</zimlet>'
].join('\n');
fs.writeFileSync(path.join(buildDir, `${zimletName}.xml`), descriptor);

for (const [locale, values] of Object.entries(descriptions)) {
	const suffix = locale === 'en_US' ? '' : `_${locale}`;
	const properties = `\nlabel = ${unicodeProperties(values[0])}\ndescription = ${unicodeProperties(values[1])}\n`;
	fs.writeFileSync(path.join(buildDir, `${zimletName}${suffix}.properties`), properties);
}

const archive = new AdmZip();
archive.addLocalFolder(buildDir, '');
const destination = path.join(packageDir, `${zimletName}.zip`);
archive.writeZip(destination);
console.log(destination);
