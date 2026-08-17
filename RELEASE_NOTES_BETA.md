# 3.1.23 Public Beta 2

This beta adds native Nextcloud Talk messaging to the existing file-management and ONLYOFFICE/Euro-Office integration inside Zimbra Modern UI.

## Highlights

- Stable native navigation: Cloud is the connector's only top-bar `MenuItem`. Chat is intentionally absent from that bar and is opened through the compact floating launcher; `/modern/cloud` always opens files and `/modern/cloud/chat` remains the full Chat workspace.
- Up to three encrypted Nextcloud profiles per Zimbra user.
- File/folder browsing, search, sorting, uploads, bulk move/copy/delete and trash management.
- Image, audio and video previews in persistent movable/resizable windows.
- OOXML and OpenDocument creation and collaborative editing through Nextcloud's ONLYOFFICE or Euro-Office connector.
- Public read-only links, file details, versions and capability-aware advanced features.
- Paperclip → Cloud attachments and read-only link insertion in the Zimbra compose window.
- Eleven interface/admin languages.
- Read-only diagnostic, storage and profile-lifecycle scripts.
- Compact floating **💬** launcher available from Mail, Calendar, Contacts and Cloud. Its badge shows the aggregated unread count. It opens a quick-chat panel with current conversations, unread counters, recent messages, quick replies, GIF search/send/display, read markers and polling without leaving the current Zimbra view.
- Full-size Chat workspace remains available on `/modern/cloud/chat` from the quick panel and the Cloud header.
- Version 3.1.23 uses `window.parent` only for the floating launcher/panel. Only Cloud is registered through Zimbra's native navigation slot; no Chat top-bar node is registered, injected or mutated.
- Quick-chat refreshes are coalesced and backed off after a failure. The file workspace and full Chat route no longer perform duplicate Talk polling, read markers are sent only for newly observed messages, and interactive Talk requests use a bounded 8-second server timeout.
- Upstream 502/503/504 HTML pages are never rendered in the panel: users receive a localized temporary-unavailability message and a Retry action while Cloud files remain independent.
- The unsafe nested Chat control from 3.1.15 has been removed. Cloud is again a strict primitive-label `MenuItem`, preventing the internal Modern UI rendering failure that left the file workspace on its loading screen.
- Interactive Unsplash selection now accepts the displayed default on Enter and normalizes terminal whitespace and carriage returns before validating `1` or `2`.
- Creation of group/direct Talk conversations and deletion of messages through the official Talk API, with Nextcloud remaining responsible for permissions and time limits.
- Reliable selected-item and folder ZIP downloads: the server now uses Nextcloud's documented WebDAV archive query and the browser waits for the authenticated response before creating the local download.
- Defensive filtering of null Talk message entries so a malformed item cannot break the conversation rendering.
- Chat enable/disable control next to Diagnostics, persisted independently for each encrypted Nextcloud profile; enabling the selected account opens Chat immediately.
- The historical auxiliary Chat package is upgraded to an inert compatibility bundle so previously deployed versions are replaced cleanly without registering another Zimbra navigation slot.
- Automatic COS/account assignment mirroring: the Chat package is granted to every COS and explicit account assignment that already exposes Cloud instead of remaining limited to Zimbra's `default` COS. Installation now finds account-level differences with two grouped LDAP searches instead of starting one `zmprov` JVM per mailbox, with an automatic legacy fallback. The diagnostic keeps its exhaustive effective-value check and reports `modern_chat_cos` and `modern_chat_accounts` separately from deployment status.
- The floating launcher carries the global unread counter; the full Chat workspace retains its user-controlled local notification sound.
- Higher-contrast initial avatars in the conversation list and header.
- Stale message loads are cancelled when changing conversations, and a separate draft is restored for every conversation after navigating through Zimbra.
- Legacy Preact runtime rendering test for the conversation workspace, preventing the blank Chat screen caused by unsupported JSX fragments.
- Optional GIF picker through Nextcloud's `integration_giphy` app, with automatic search while typing, previews compatible with both `/apps/integration_giphy/` and `/index.php/apps/integration_giphy/`, validated CDN redirects and no Nextcloud credential forwarding.
- Infinite GIF browsing in both Chat interfaces: approaching the bottom loads the next Nextcloud cursor, preserves scroll position, removes duplicates and keeps existing results available if a later page must be retried.
- The floating Chat launcher is hidden while the full Chat workspace is active, including the internal Chat view served on `/modern/cloud`, and returns automatically on Files or other Zimbra views.
- Messaging only: no audio/video calls and no Talk signaling code.

## Beta warning

Use a staging server and a recoverable backup. This release is not yet certified for every Zimbra/Nextcloud/Talk/Document Server version, multi-mailbox topology, external storage backend or browser. Talk has automated API-contract coverage but still needs live multi-user validation before this release can be called stable.

## Manually exercised environment

- Zimbra: 10.1.20 GA 4893 on Ubuntu 18.04.6, single mailbox node.
- Zimbra Modern UI in a Chromium-family browser on Windows.
- One live HTTPS Nextcloud environment.
- Live document opening/editing with ONLYOFFICE and Euro-Office.
- **Before publishing, add the exact Nextcloud, connector, Document Server and browser versions here.**

See `TESTING.md` for the full verified/partial/not-tested matrix.

## Installation

```bash
cd /tmp
sha256sum -c zimbra-nextcloud-connector-v3.1.23.zip.sha256
unzip zimbra-nextcloud-connector-v3.1.23.zip
cd zimbra-nextcloud-connector-3.1.23
./install.sh
./diagnose.sh
```

Expected final diagnostic line: `RESULT OK`.

## Feedback

Please open a GitHub issue with exact sanitized versions, reproduction steps, `diagnose.sh` output and relevant server/browser errors. Never publish passwords, app passwords, JWT secrets, cookies, authorization headers, encrypted profiles or customer data.

License: BSD-3-Clause. Copyright 2026 Franck Chalon.
