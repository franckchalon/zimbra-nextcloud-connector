# 3.2.0-beta.7

This seventh beta keeps the Classic workspaces successfully exercised on Zimbra FOSS 10.1.18 and completes the native **Attach > Cloud** picker fix. Live beta.6 testing confirmed that the native menu opens and Cloud files can be selected, but Zimbra Classic's native dialog let the list push the action footer below the browser viewport. Beta.7 gives the native picker an explicit pixel height derived from the current browser viewport, keeps the action footer fixed and makes only the file list scroll. Deployable Zimlet metadata is bumped to the Zimbra-compatible numeric version `3.2.4` to invalidate beta.6 resources.

This beta adds a Zimbra Classic UI shell while preserving the established Modern UI behavior from 3.1.23.

## Highlights

- Installer choice: **Modern only**, **Classic only**, or **Modern + Classic**.
- Unattended equivalents: `--ui=modern`, `--ui=classic`, `--ui=both` or `CLOUD_UI_MODE`.
- Separate Classic **Cloud** and **Chat** application tabs, backed by the same complete workspaces as Modern.
- Full Nextcloud Talk text workspace and global resizable Quick Chat in Classic.
- Classic compose integration through the native **Attach > Cloud** menu for Cloud attachments and read-only public links.
- Viewport-aware Classic picker sizing: the account controls and action footer remain visible while only the file tree scrolls.
- One shared Preact feature core for Modern and Classic instead of two diverging copies.
- Same encrypted profiles, WebDAV/OCS backend, file operations, media previews and ONLYOFFICE/Euro-Office editing.
- New `--backend-only` deployment mode for an additional mailbox node, with documented profile-storage and key-management limitations.
- Diagnostics now report selected UI mode, node role, Classic deployment and assignment parity.
- Reproducible Classic package, repair/uninstall support and automated Classic host/compose tests.
- Existing Modern Zimlet IDs are deliberately retained for safe upgrades; the newly introduced Classic package uses `fr_franckchalon_nextcloud_classic`.
- Composer links are restricted to HTTP(S), and attachment download has a Blob fallback for older Classic browsers.
- README now explains the project's use case, compares existing alternatives factually and documents current multi-server/multi-tenant boundaries.
- `configure.sh --ui=modern|classic|both` can change only the deployed web clients later, without rebuilding the Java extension or restarting mailboxd. Plain `configure.sh` also asks for the final UI mode after server settings.
- `install.sh --ui=...` remains intentionally non-interactive; omit that option to receive the installation prompt.

## Important beta warning

Classic Cloud, full Talk, Quick Chat and the native compose menu were manually exercised on a Zimbra FOSS 10.1.18 test server. The beta.7 footer/attachment completion, focus refresh and compact editor header still require a live retest, and the compatibility matrix remains too narrow for production certification. Install this release on a staging or recoverable test server first. Report exact sanitized versions and results so the matrix can be expanded.

This release is messaging-only for Talk. It does not provide audio/video calls or signaling.

## Multi-mailbox and multi-tenant scope

- `--backend-only` helps install the same Java backend on additional mailbox nodes. It does not by itself make local encrypted profile storage highly available.
- Personal mode supports up to three Nextcloud profiles per Zimbra account.
- Managed mode currently targets one administrator-selected Nextcloud.
- A secure domain/COS-to-several-Nextcloud mapping is not implemented in this beta and remains future work.

## Installation

```bash
cd /tmp
sha256sum -c zimbra-nextcloud-connector-v3.2.0-beta.7.zip.sha256
unzip zimbra-nextcloud-connector-v3.2.0-beta.7.zip
cd zimbra-nextcloud-connector-3.2.0-beta.7
./install.sh
./diagnose.sh
```

For a direct test of both interfaces:

```bash
./install.sh --ui=both
```

Expected diagnostic final line: `RESULT OK`.

After installation, fully close existing Zimbra sessions, sign in again, force-refresh once and follow the Classic test checklist in `TESTING.md`.

## Feedback

Open a GitHub issue with:

- connector version and selected UI mode;
- exact Zimbra version/edition and single- or multi-mailbox topology;
- Nextcloud, Talk and storage-backend versions;
- browser/OS and office connector/Document Server versions when relevant;
- sanitized `diagnose.sh`, reproduction steps, browser console/network errors and relevant mailbox log lines.

Never publish passwords, app passwords, JWT secrets, cookies, authorization headers, master keys, encrypted profiles or customer data.

License: BSD-3-Clause. Copyright 2026 Franck Chalon.
