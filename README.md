# zimbra-nextcloud-connector

> **Public beta — version 3.2.0-beta.7.** This release adds a Zimbra Classic UI shell and changes the installer. Test it on a staging mailbox server and keep a recoverable backup. Classic Cloud, Talk and the native **Attach > Cloud** menu were manually exercised on Zimbra FOSS 10.1.18; beta.7's fixed picker footer and attachment completion still require live validation before this beta can be called stable. See [TESTING.md](TESTING.md).

`zimbra-nextcloud-connector` is an independent community integration for Nextcloud files, Nextcloud Talk text messaging and ONLYOFFICE or Euro-Office document editing in the Zimbra **Modern and Classic** web clients.

Created and maintained by **Franck Chalon**. This project is not an official Zimbra, Nextcloud, ONLYOFFICE or Euro-Office product.

[Documentation française](README_FR.md) · [Test status](TESTING.md) · [Changelog](CHANGELOG.md) · [Security](SECURITY.md) · [Contributing](CONTRIBUTING.md)

## Why this project exists

Zimbra and community projects already provide useful Nextcloud integrations. This project is an additional option for deployments that want one installable server extension and a shared user experience across Zimbra Modern and Classic, with up to three user-selected Nextcloud profiles, file operations, document editing and Talk text messaging.

It is not presented as a replacement for the official Zimbra projects. Administrators should compare maintenance, compatibility and required features before choosing an implementation.

| Project | Main scope | Zimbra clients | Talk in the Zimbra client | Notes |
| --- | --- | --- | --- | --- |
| This project | Files, compose integration, media previews, office editing and Talk text chat | Modern + Classic (Classic is beta) | Text conversations, replies, reactions, GIFs and permitted message deletion; no calls | Independent community project; up to three personal profiles |
| [Zimbra Nextcloud extension](https://github.com/Zimbra/zm-nextcloud-extension) + [Modern Zimlet](https://github.com/Zimbra/zimbra-zimlet-nextcloud) + [Classic package announcement](https://blog.zimbra.com/2023/08/introducing-new-nextcloud-zimlet-for-classic-ui/) | Official Zimbra file integration: compose attachments/links and saving messages or attachments to Nextcloud | Modern + Classic packages; consult Zimbra for the exact supported matrix and availability of the Classic source | The separate [official Talk Zimlet](https://github.com/Zimbra/zimbra-zimlet-nextcloud-talk) creates Talk meetings from Calendar appointments | Recommended baseline when official support and upstream packages are the priority |
| [btactic/zimbra-drive](https://github.com/btactic/zimbra-drive) | Nextcloud/ownCloud drive integration | Consult its release documentation | Different scope | Community alternative with a different architecture |
| [btactic/owncloud-zimlet](https://github.com/btactic/owncloud-zimlet) | ownCloud/Nextcloud-related Zimlet | Consult its release documentation | Different scope | Community alternative |

The table intentionally avoids unsupported “better than” claims. Feature sets and supported versions evolve; verify the linked projects and test the selected solution in your own environment.

## Features

- Up to three Nextcloud accounts per Zimbra user, connected with an app password or Nextcloud Login Flow v2.
- Optional managed-account mode for one administrator-selected Nextcloud service.
- AES-GCM encrypted server-side profile storage, shared by the user's Zimbra sessions.
- Files, favorites, recent files, shares and public links; breadcrumbs, search, sorting and grid/list views.
- File/folder uploads, chunking, collision handling, download, ZIP download, copy, move, rename, trash, restore and permanent deletion.
- OOXML/OpenDocument creation and collaborative editing through the matching Nextcloud ONLYOFFICE or Euro-Office connector.
- Capability-aware details, versions, comments, activity, tags, locks, shares and read-only public links.
- Image, audio and video previews in movable/resizable windows.
- Cloud attachments and read-only public-link insertion in Zimbra compose windows.
- Nextcloud Talk text messaging: conversation creation, unread counters, drafts, replies, reactions, file sharing, permitted deletion and optional `integration_giphy` browsing.
- Compact Quick Chat plus a full Chat workspace. Audio/video calls and Talk signaling are deliberately excluded.
- French, US English, Spanish (Spain/Argentina), Italian, German, Portuguese (Portugal/Brazil), Hindi, Malay and Russian.

Modern and Classic shells use the same Preact file, picker and Talk components. Fixes therefore do not have to be implemented twice. Client-specific code is limited to navigation, compose attachment bridging and mounting into the Zimbra host.

### Modern UI

- Native Cloud route at `/modern/cloud` and full Chat at `/modern/cloud/chat`.
- Compact floating Chat launcher in other Modern views.
- Cloud picker in the Modern compose attachment menu.

The existing IDs `com_nextcloud_connector` and `com_nextcloud_connector_chat` are retained for upgrade and COS-assignment compatibility. Renaming an already deployed Modern Zimlet would require an explicit migration and is not hidden inside this beta.

### Classic UI

- Dedicated **Cloud** and **Chat** application tabs provided by the reverse-domain ID `fr_franckchalon_nextcloud_classic`.
- The same complete Cloud files and full Talk workspaces as Modern.
- Global resizable Quick Chat panel with unread badge.
- **Cloud** entry in the composer's native **Attach** menu for attachments and read-only public links.

Classic support is new in this beta. Automated tests verify packaging, host mounting, navigation, the compose bridge and Quick Chat lifecycle. Real-server compatibility reports are required before the support claim is broadened.

## Requirements

- A Zimbra mailbox server with root access and either the Modern UI, Classic UI, or both.
- A reachable HTTPS Nextcloud server with WebDAV and OCS APIs.
- Nextcloud Talk (`spreed`) for Chat; `integration_giphy` is optional.
- A Nextcloud app password is recommended for manual connections.
- For editing, a configured Nextcloud ONLYOFFICE or Euro-Office connector and matching Document Server.
- Network routes allowing Zimbra to reach Nextcloud and office APIs, and the Document Server to reach required callbacks/file URLs.

The previous Modern release was manually exercised on **Zimbra 10.1.20 GA 4893 / Ubuntu 18.04.6** with a live Nextcloud and both office providers. Classic was manually exercised on **Zimbra FOSS 10.1.18 GA 4200001 / Ubuntu 22.04.5** for Cloud, Talk, Quick Chat and opening the native **Attach > Cloud** picker. Beta.7's fixed picker footer and attachment completion, tab-return profile refresh and compact editor header are not yet certified. Exact compatibility reports are welcome.

Not supported: Collabora, Talk audio/video calls or signaling, public unencrypted HTTP targets, and mobile browsers as a certified environment.

## Installation

Copy the ZIP and checksum from the same GitHub pre-release to the mailbox server, then run as `root`:

```bash
cd /tmp
sha256sum -c zimbra-nextcloud-connector-v3.2.0-beta.7.zip.sha256
unzip zimbra-nextcloud-connector-v3.2.0-beta.7.zip
cd zimbra-nextcloud-connector-3.2.0-beta.7
./install.sh
./diagnose.sh
```

The interactive installer asks which clients to deploy:

1. Modern only;
2. Classic only;
3. Modern and Classic (default for a mixed server).

For unattended installation, use one of:

```bash
./install.sh --ui=modern
./install.sh --ui=classic
./install.sh --ui=both
CLOUD_UI_MODE=both ./install.sh
```

Passing `--ui=...` already supplies the selection, so the installer deliberately does not ask the same question again. To add or change web interfaces later without rebuilding the extension or restarting `mailboxd`:

```bash
./configure.sh --ui=modern   # Modern only
./configure.sh --ui=classic  # Classic only
./configure.sh --ui=both     # keep/install Modern and Classic
```

Without options, `./configure.sh` updates server settings and then asks for the desired interfaces. The selected mode is the desired final state; use `both` to add Classic while keeping Modern.

The installer compiles the Java extension against the exact Zimbra libraries, preserves existing configuration and encrypted profiles during upgrades, deploys only the selected clients, and mirrors existing Modern Cloud COS/explicit-account assignments to the companion Chat and Classic packages when applicable.

After installation, close every Zimbra tab, start a new browser session, sign in to the selected client and force-refresh once. Use Login Flow or an app password for the first Nextcloud connection.

### Multi-mailbox installation

`--backend-only` installs/restarts only the Java extension on an additional mailbox node and does not change LDAP Zimlet deployment:

```bash
./install.sh --backend-only
```

This is deployment assistance, not a blanket HA certification. Today encrypted profile files and the master key are local to the mailbox filesystem. Each user's requests must reach a mailbox that has the extension, configuration and that user's profile storage, or those files must be migrated/shared through an administrator-designed secure strategy. Test mailbox moves, proxy routing and failover before production. Do not copy different master keys over existing encrypted profiles.

### Multiple Nextcloud servers and tenants

- **Personal mode:** each Zimbra user may connect up to three arbitrary permitted Nextcloud URLs. This already supports users who need more than one server.
- **Managed mode:** one administrator-defined Nextcloud service is currently enforced.
- **Not yet implemented:** a centrally managed domain/COS-to-Nextcloud mapping for several tenants. A safe design needs an allowlisted mapping, secret references rather than service passwords in LDAP, deterministic provisioning and explicit migration/audit behavior. This remains future work rather than an unsafe implicit fallback.

## Diagnostics

Run after installation, upgrades and reproduced failures:

```bash
cd /tmp/zimbra-nextcloud-connector-3.2.0-beta.7
./diagnose.sh
```

Expected final line: `RESULT OK`.

Follow connector events while testing:

```bash
tail -n 0 -F /opt/zimbra/log/mailbox.log | grep --line-buffered -iE 'NextcloudConnector|nextcloud-connector|fr\.franckchalon\.zimbra\.nextcloud'
```

The diagnostic is read-only. Browser console/network output and an end-to-end staging test are still required.

## Data and security

- Nextcloud and office secrets are encrypted with AES-GCM and are not returned by the profile API.
- `/opt/zimbra/conf/nextcloud-zimlet.properties` is installed as `zimbra:zimbra` mode `0600`.
- Ordinary previews/downloads are streamed and not retained as a Zimbra file cache.
- A Cloud file attached to a message becomes Zimbra mailbox data and counts against Zimbra quotas.
- Public links created by the connector default to read-only.
- Private/loopback Nextcloud targets are blocked by default to reduce SSRF risk.
- Unsplash is disabled by default because enabling it contacts a third party from the browser.

Never publish production configuration, master keys, encrypted profiles, credentials, JWT secrets, cookies, authorization headers or customer logs/data.

## Build from source

```bash
npm ci
npm audit --omit=dev
./build-release.sh
```

The release build compiles and packages Modern, Classic and Java components, runs automated suites, then creates the ZIP and checksum under `dist/`. Generated ZIP/JAR artifacts and `node_modules` are excluded from Git source history.

## Community release

GitHub is the source of truth for source, issues, tags and immutable release assets. Publish this build as a **pre-release** named `3.2.0-beta.7`, attach the ZIP and matching checksum, and follow [PUBLISHING.md](PUBLISHING.md).

## License

BSD-3-Clause — Copyright 2026 Franck Chalon.
