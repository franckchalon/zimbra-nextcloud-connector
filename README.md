# zimbra-nextcloud-connector

> **Public beta — version 3.1.23.** Test on a staging Zimbra server and keep a recoverable backup before production use. See [TESTING.md](TESTING.md) for the exact validation status.

`zimbra-nextcloud-connector` is an independent community Zimlet that brings Nextcloud file management, collaborative document editing and Nextcloud Talk messaging into the Zimbra Modern UI.

Created and maintained by **Franck Chalon**. This project is not an official Zimbra, Nextcloud, ONLYOFFICE or Euro-Office product.

[Documentation française](README_FR.md) · [Test status](TESTING.md) · [Changelog](CHANGELOG.md) · [Security](SECURITY.md) · [Contributing](CONTRIBUTING.md)

## Main features

- Browse up to three Nextcloud accounts from the **Cloud** tab.
- Manual app-password login or Nextcloud Login Flow v2.
- Optional administrator-managed Nextcloud account provisioning.
- Encrypted server-side profile storage, available from every user device.
- Grid/list views, breadcrumbs, sorting, folder/account search, favorites and smart views.
- Upload files and folders, chunked uploads, progress, cancel/retry and collision handling.
- Create folders and OOXML/OpenDocument files: `.docx`, `.xlsx`, `.pptx`, `.odt`, `.ods`, `.odp`.
- Rename, copy, move, multi-select, delete to trash, restore and permanently delete.
- Quota display, details, permissions, locks, checksums, tags, shares, versions, comments and activity when exposed by Nextcloud.
- Create read-only public links, optionally protected by a password and expiration date.
- Preview images, audio and video in persistent movable/resizable windows with next/previous navigation and media fullscreen.
- Edit documents through the matching Nextcloud **ONLYOFFICE** or **Euro-Office** connector; office settings may be overridden per Cloud account.
- Keep Zimbra Mail, Calendar and other navigation available while an editor or media preview remains open.
- Add Cloud files as email attachments or insert read-only links through **Paperclip → Cloud**.
- Floating **💬 Chat** button in Mail, Calendar, Contacts and Cloud. It opens a compact panel with current conversations, unread counts, recent messages, quick replies and read markers without leaving the current Zimbra view. The ↗ command opens the full workspace on `/modern/cloud/chat`. No Chat tab is injected into Zimbra's top bar, and the native Cloud entry is never hidden, resized or duplicated.
- Per-Nextcloud-profile enable/disable control next to Diagnostics, encrypted server-side and checked against the currently selected Talk account before activation.
- Only explicitly enabled Cloud accounts appear in Chat; enabling one profile never enables the other connected profiles.
- Conversations from up to three enabled Cloud accounts, creation of group or direct conversations, message deletion according to Nextcloud Talk permissions, global/per-conversation unread badges, text messages, replies, reactions and Cloud-file sharing.
- A gentle unread animation and a locally generated Web Audio chime for newly received unread messages. Sound is enabled by default, can be disabled from Chat, contains no third-party audio asset and follows browser autoplay restrictions.
- A separate browser-session draft is preserved for every account/conversation while navigating between Zimbra tabs.
- Optional GIF picker through the Nextcloud `integration_giphy` app, with debounced search, cursor-based infinite scrolling, validated Giphy CDN redirects and no Nextcloud credential forwarding.
- Chat only: no audio/video calls and no signaling API are included; the Talk high-performance backend is not required by this Zimlet's messaging feature.
- Optional Unsplash backgrounds; disabled by default for privacy.
- French, US English, Spanish (Spain/Argentina), Italian, German, Portuguese (Portugal/Brazil), Hindi, Malay and Russian.

Collabora, Talk audio/video calls and the Zimbra Classic UI are not supported.

## Requirements

- A Zimbra server with the Modern UI and root access to the mailbox node.
- A reachable HTTPS Nextcloud server with WebDAV and OCS APIs.
- For Chat, the Nextcloud Talk (`spreed`) app must be enabled. `integration_giphy` is optional.
- A Nextcloud app password is recommended for manual connections.
- For editing, the corresponding Nextcloud ONLYOFFICE or Euro-Office connector and Document Server must already be installed and configured with matching URL/JWT settings.
- Network routes must allow Zimbra to reach Nextcloud and the office connector APIs; the Document Server must reach Nextcloud callbacks and file URLs.

The current beta has been manually exercised on **Zimbra 10.1.20 GA 4893 / Ubuntu 18.04.6** with one live Nextcloud environment and both ONLYOFFICE and Euro-Office. This is not a general compatibility claim. Exact server-version reports from other environments are welcome.

## Installation

Copy the release ZIP to the Zimbra mailbox server, then run as `root`:

```bash
cd /tmp
sha256sum -c zimbra-nextcloud-connector-v3.1.23.zip.sha256
unzip zimbra-nextcloud-connector-v3.1.23.zip
cd zimbra-nextcloud-connector-3.1.23
./install.sh
./diagnose.sh
```

The installer:

1. asks for the administrative language and privacy/account/office choices;
2. builds the Java extension against the exact Zimbra libraries on that server;
3. installs and verifies the extension;
4. deploys the paired Cloud and Chat Modern packages and mirrors Cloud's COS/account assignments to Chat, using grouped LDAP account searches with a compatibility fallback;
5. preserves the existing configuration and encrypted user profiles during upgrades.

After installation, close all Zimbra tabs, open a new browser session and sign in. Only the native **Cloud** entry should appear in Zimbra's top bar. The floating **💬 Chat** button opens quick chat; ↗ and the Cloud-header Chat button open the full workspace. `/modern/cloud` always opens Files, and a temporary Talk failure produces a concise Retry state instead of exposing the proxy's HTML error page. The first click or key press unlocks notification audio as required by browsers. The first user connection should use Nextcloud Login Flow or an app password.

## Operational checks

Run the read-only diagnostic after installation, after an upgrade and after reproducing a problem:

```bash
cd /tmp/zimbra-nextcloud-connector-3.1.23
./diagnose.sh
```

Expected final line:

```text
RESULT OK
```

Watch connector log events while testing:

```bash
tail -n 0 -F /opt/zimbra/log/mailbox.log | grep --line-buffered -iE 'NextcloudConnector|nextcloud-connector|Erreur Nextcloud Connector|fr\.franckchalon\.zimbra\.nextcloud'
```

These checks show known server-side failures; they cannot prove the absence of every bug. Browser console/network errors and an end-to-end staging test are also required before production use.

## Data and security

- Stored Nextcloud/office secrets are encrypted with AES-GCM and are not returned by the profile API.
- The configuration is installed as `/opt/zimbra/conf/nextcloud-zimlet.properties` with `zimbra:zimbra` ownership and mode `0600`.
- Cloud previews and ordinary downloads are streamed and are not kept as a file cache in Zimbra.
- A file attached to a draft/message becomes Zimbra mailbox data and counts against Zimbra quotas.
- Public links created by this Zimlet default to read-only.
- Private/loopback Nextcloud targets are blocked by default to reduce SSRF risk.
- Unsplash is off by default because enabling it makes user browsers contact a third party.

Do not commit or publish production configuration, encrypted profiles, credentials, JWT secrets, cookies, logs containing tokens or customer data.

## Build from source

```bash
npm ci
./build-release.sh
```

The release build runs frontend, installer and Java tests before producing the bundle and checksum under `dist/`. Build-time npm dependencies are not shipped in the deployable ZIP.

## Community release

GitHub should remain the source of truth for code, issues, checksums and releases. Mark this release as a **pre-release** named `3.1.23 Public Beta 2`, then link that release from the Zeta Alliance Zimlet Gallery and the Zimbra Community forum. Follow [PUBLISHING.md](PUBLISHING.md) before publishing.

## License

BSD-3-Clause — Copyright 2026 Franck Chalon.
