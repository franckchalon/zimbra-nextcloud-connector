# Contributing

Thank you for improving the Cloud Zimlet.

1. Open an issue describing the Zimbra, Nextcloud, office-provider and browser versions involved.
2. Create a focused branch and do not commit credentials, production URLs, encrypted profiles, configuration files or customer data.
3. Keep functional selectors, route slugs, API field names and configuration keys language-neutral. Visible text belongs in `src/i18n.js` or `installer/i18n.sh`.
4. Update all eleven locale variants when adding a visible message. Native-speaker review is especially welcome.
5. Run `./build-release.sh`, which builds/packages the Zimlet and executes the frontend, floating-window, compose, installer and Java suites.
6. Explain any mailboxd restart, migration or security impact in the pull request.

New integrations must be capability-gated, preserve Nextcloud permissions, remain independent of translated Zimbra labels and degrade cleanly when the remote app is absent. Add bounded parsing and request-size checks to every new server route. A green local suite is necessary but does not replace an end-to-end staging test with the declared Zimbra, Nextcloud and office-provider versions.

Compatibility reports should use the matrix in `TESTING.md` and include exact logs and reproduction steps, but secrets and authorization headers must be removed.

Use the Node/npm versions declared by the pinned official `@zimbra/zimlet-cli` package when possible. Its transitive dependency tree currently has development-only npm advisories with no upstream-compatible automatic fix; see `SECURITY.md`. Build in an isolated environment and never run the watch/development server on an untrusted network. The deployable ZIP contains neither this toolchain nor `node_modules`.
