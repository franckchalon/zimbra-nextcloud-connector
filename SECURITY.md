# Security policy

Version 3.1.23 is a public beta. It has automated security-oriented tests but has not received an independent security audit or penetration test.

Please do not disclose a suspected vulnerability in a public issue before a fix is available. Use GitHub **Private vulnerability reporting** on the public repository. If that feature is unavailable, open a public issue containing no technical vulnerability detail and ask the maintainer for a private contact channel.

Include the affected version, impact, reproduction steps and a minimal sanitized trace. Never send production passwords, app passwords, JWT secrets, cookies, authorization headers or encrypted profile files.

| Release | Security status |
| --- | --- |
| 3.1.23 Public Beta | Receives best-effort security fixes; not production-certified |
| Earlier/unreleased builds | Unsupported |

Administrators should use HTTPS, JWT for every global or per-profile office provider, a dedicated least-privilege Nextcloud service account in managed mode, and Nextcloud app passwords. Per-profile office secrets are stored inside the AES-GCM encrypted profile and are never returned by the profile API. Public links created by this Zimlet are read-only by default.

Version 3 adds Nextcloud Login Flow v2, strict same-origin validation for its login and polling endpoints, verified remote app-password revocation, bounded JSON/XML/binary responses, request throttling and inter-process locks around encrypted profile writes. The manual credential form remains available for compatibility; prefer Login Flow or a dedicated app password over a primary account password.

Remote photo backgrounds are disabled by default (`ui.remote_backgrounds=false`). Enabling them causes the browser to contact Unsplash and should therefore be an explicit privacy decision. Normal browsing, previews and chunk uploads are streamed or proxied and are not retained as a Cloud cache on Zimbra. Files deliberately attached to an email are copied into Zimbra and count against the mailbox/message limits.

For multiple mailbox nodes, use storage that provides reliable POSIX-style atomic moves and file locks, mount it at the same path on every node, then set `storage.shared=true`. Test failover before production. The flag documents the topology; it cannot make an unsuitable network filesystem safe.

## Build-tool advisory status

The release archive contains no `node_modules` directory and has no npm runtime dependency. On 2026-08-10, `npm audit --omit=dev` reports **0 vulnerabilities** for the deployable product. Building the Modern UI does, however, use the official `@zimbra/zimlet-cli` development tool. The full development-tree audit currently reports 12 transitive advisories (4 moderate, 7 high and 1 critical), including packages used for archive handling and the development server. Several have no upstream-compatible fix through the pinned CLI.

These development packages do not execute inside Zimbra after installation and are not included in the release ZIP. Contributors must nevertheless build in an isolated disposable environment, process only trusted project inputs, never expose the watch/development server to an untrusted network and track the upstream CLI for updates. Do not run `npm audit fix --force` and publish its output without a complete source review, rebuild and compatibility test.
