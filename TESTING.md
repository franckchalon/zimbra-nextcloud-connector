# Public beta test status — 3.2.0-beta.7

This matrix separates automated verification from real-server validation. “Verified” never means certified for every Zimbra, Nextcloud or browser version.

## Automated release gate

`./build-release.sh` must succeed from a clean checkout. It performs, at minimum:

- pinned npm installation and Modern production packaging;
- standalone Classic UI production build and Classic Zimlet packaging;
- shared file-picker, compose-link, API-response, Chat-rendering, navigation, floating-window and GIF tests;
- Classic host/runtime tests for application mounting, Quick Chat and compose attachments/links;
- installer, UI-mode, COS/account-assignment, backend-only, reconfiguration and safety tests;
- Java compilation against test stubs and server unit tests;
- internal archive checksums, final ZIP creation and SHA-256 output.

The CI workflow runs the same release build on Ubuntu and rejects tracked build output or missing release artifacts.

## Compatibility matrix

| Area | Status | Evidence and remaining work |
| --- | --- | --- |
| Modern Cloud navigation and files | Manually exercised + automated | Previous 3.1.23 behavior retained; shared components and release tests pass. Re-test this beta after the new Classic packaging/installer changes |
| Modern full Talk and Quick Chat | Manually exercised + automated | Text conversations, creation, replies, reactions, deletion, GIFs, unread state and bounded error handling are covered; no call/signaling code |
| Modern compose attachments/links | Automated + prior manual use | Shared picker now feeds the Modern compose bridge; re-test attachment limits and HTML/plain-text signatures |
| Classic Cloud/Chat tabs and workspaces | Manually exercised + automated | Both workspaces were displayed and opened on Zimbra FOSS 10.1.18. Beta.7 keeps the same bootstrap and adds profile refresh when the tab becomes active again |
| Classic full Talk and Quick Chat | Manually exercised + automated | Beta.4 full Talk and the resizable Quick Chat were exercised with a live Talk-enabled profile. A second non-Talk profile is ignored by the Talk workspace as designed |
| Classic compose attachments/links | Native menu manually exercised; beta.7 completion retest required | Beta.6 live testing confirmed **Cloud** in Zimbra's native **Attach** menu and file selection, but the footer was pushed below the viewport. Beta.7 computes an explicit native-dialog height, keeps the footer visible and sends selected files through Zimbra's native attachment API. Verify one/multiple attachments, link, draft, send and received message on the test server |
| Files, search, uploads and mutations | Automated + previous Modern manual use | Validate WebDAV behavior against exact Nextcloud/storage versions, including external storage and large chunked uploads |
| ZIP download | Automated contract + previous fix | Selected items are constrained to one folder and the authenticated response is downloaded as a Blob. Re-test file, folder and mixed selection in both clients |
| ONLYOFFICE / Euro-Office | Previously exercised in Modern | Shared App is used in Classic. Beta.7 retains beta.6's compact connector title bar; retest both editors with exact connector/Document Server versions |
| Installer: Modern / Classic / both | Automated | Argument, environment and interactive-mode paths are tested; requires one real install/upgrade/uninstall cycle for each relevant server family |
| COS and explicit-account assignment parity | Automated | Diagnostic verifies companion packages follow Modern Cloud where a Modern assignment source exists |
| Additional mailbox `--backend-only` | Automated installer path only | Not a HA certification. Test mailbox routing/moves, local profile storage and key distribution in an actual multi-mailbox lab |
| Personal multiple Nextcloud profiles | Implemented | Up to three profiles per Zimbra account. Test heterogeneous Nextcloud versions/capabilities |
| Managed multi-tenant domain/COS mapping | Not implemented | Secure mapping/secret design remains future work; do not report issue #8 as completed |
| Talk audio/video/signaling | Deliberately excluded | The suites reject call/signaling scope; the connector provides messaging only |
| Mobile browsers | Not certified | Responsive behavior is best effort; no mobile support claim |

## Known manually exercised environment

- Zimbra Modern: 10.1.20 GA 4893 on Ubuntu 18.04.6, single mailbox node.
- Zimbra Classic: 10.1.18 GA 4200001 on Ubuntu 22.04.5. Cloud and Chat displayed; files, full Talk and Quick Chat loaded against live profiles; **Attach > Cloud** opened and selected files in beta.6. Beta.7 footer/attachment completion, tab-return refresh and compact editor header remain to be retested.
- Browser: Chromium-family client on Windows.
- One live HTTPS Nextcloud environment.
- Live ONLYOFFICE and Euro-Office document opening/editing.

Before publishing this beta, record the exact Nextcloud, Talk, office connector, Document Server, Zimbra Classic and browser versions used by the new test server.

## Required Classic beta test

On a disposable or recoverable Zimbra FOSS test server:

1. run `./install.sh --ui=classic`, then `./diagnose.sh`;
2. sign into Classic after fully closing prior Zimbra sessions;
3. confirm that the separate Cloud and Chat tabs are visible, then open Cloud and connect one profile;
4. browse/search/upload/download/rename/copy/move/trash/restore files;
5. create and edit at least one supported office document;
6. attach one and several Cloud files in HTML and plain-text messages;
7. insert a read-only public link before a signature and send it;
8. open Quick Chat, switch conversations, reply, create a conversation and delete an allowed message;
9. open the full Chat workspace and confirm the Quick Chat panel does not duplicate it;
10. install again with `--ui=both`, verify both clients, then test upgrade and uninstall behavior.

Capture sanitized `diagnose.sh` output, browser console/network errors and relevant `/opt/zimbra/log/mailbox.log` lines. Never attach credentials, cookies, authorization headers, profile files or master keys.

## Beta exit criteria

The Classic feature should remain beta until all of the following are true:

- successful real-server installation on at least two declared Zimbra FOSS/Classic versions or an explicitly narrowed supported matrix;
- file, compose, Talk and office smoke tests pass in Classic;
- Modern regression test passes on the established environment;
- one upgrade from 3.1.23 and one clean uninstall are verified;
- any compatibility limitation is documented in the README and release notes;
- no open confirmed blocker involving data loss, credential exposure or inability to access Zimbra Mail.
