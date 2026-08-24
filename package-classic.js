'use strict';

const fs = require('fs');
const path = require('path');
const AdmZip = require('adm-zip');
const packageJson = require('./package.json');

const zimletId = 'fr_franckchalon_nextcloud_classic';
const root = __dirname;
const classicDir = path.join(root, 'classic');
const runtimePath = path.join(root, 'classic-build', `${zimletId}_app.js`);
const outputDir = path.join(root, 'pkg-classic');
const outputPath = path.join(outputDir, `${zimletId}.zip`);

if (!fs.existsSync(runtimePath)) {
	throw new Error(`Classic runtime missing: ${runtimePath}. Run npm run build:classic first.`);
}

const descriptor = `<?xml version="1.0" encoding="UTF-8"?>
<zimlet name="${zimletId}"
        version="${packageJson.zimletVersion}"
        target="main compose-window view-window"
        label="Nextcloud"
        description="Nextcloud files, Talk chat and collaborative document editing for Zimbra Classic UI">
    <include>${zimletId}.js</include>
    <resource>${zimletId}_app.js</resource>
    <includeCSS>${zimletId}.css</includeCSS>
    <zimletPanelItem label="Nextcloud" icon="frNextcloudCloudIcon" />
    <handlerObject>${zimletId}_HandlerObject</handlerObject>
</zimlet>
`;

fs.mkdirSync(outputDir, { recursive: true });
const zip = new AdmZip();
zip.addFile(`${zimletId}.xml`, Buffer.from(descriptor, 'utf8'));
zip.addLocalFile(runtimePath, '', `${zimletId}_app.js`);
[
	`${zimletId}.js`,
	`${zimletId}.css`,
	`${zimletId}.properties`,
	`${zimletId}_fr.properties`,
	'nextcloud-classic.svg',
	'nextcloud-classic-chat.svg'
].forEach(name => zip.addLocalFile(path.join(classicDir, name), '', name));
zip.writeZip(outputPath);
process.stdout.write(`${outputPath}\n`);
