# Publishing the first community beta

This is the maintainer checklist for `zimbra-nextcloud-connector` 3.1.23 Public Beta 2.

## 1. Complete the compatibility record

Before publishing, record exact versions in the release notes:

```bash
su - zimbra -c 'zmcontrol -v'
curl -fsS https://NEXTCLOUD_HOST/status.php
```

Record the browser, Nextcloud office connector and ONLYOFFICE/Euro-Office Document Server versions manually. Remove hostnames, account names and infrastructure details that should not be public.

## 2. Run the release checks

From a clean source tree:

```bash
npm ci
npm audit --omit=dev
./build-release.sh
```

Then verify the generated bundle:

```bash
cd dist
sha256sum -c zimbra-nextcloud-connector-v3.1.23.zip.sha256
unzip -t zimbra-nextcloud-connector-v3.1.23.zip
```

Search for accidental private data before the first commit:

```bash
rg -n -i 'password|app[_-]?password|jwt[_-]?secret|authorization|cookie|PRIVATE_COMPANY_NAME|PRIVATE_DOMAIN|customer|client' . --glob '!node_modules/**' --glob '!dist/**' --glob '!build/**' --glob '!pkg/**'
```

Replace `PRIVATE_COMPANY_NAME` and `PRIVATE_DOMAIN` with every former private identifier before running the command. This search deliberately reports legitimate source-code identifiers and test placeholders. Review every result; never blindly delete security-related code.

Also inspect the archive file list and confirm it contains no `node_modules`, `.git`, production `.properties`, encrypted profile, log, browser export, screenshot with customer data or editor backup file.

Run a full `npm audit` as an informational build-tool review. The official Zimlet CLI currently brings known development-only advisories documented in `SECURITY.md`; do not claim that the complete development tree is clean, and do not apply a forced dependency rewrite without rebuilding and retesting everything.

## 3. Create the GitHub repository

Create a public repository under the personal account **Franck Chalon**:

- repository name: `zimbra-nextcloud-connector`;
- description: `Nextcloud file management, Talk chat and ONLYOFFICE/Euro-Office editing for Zimbra Modern UI`;
- license: BSD-3-Clause (already included);
- suggested topics: `zimbra`, `zimlet`, `nextcloud`, `onlyoffice`, `euro-office`, `preact`, `webdav`, `java`;
- enable Issues and Discussions;
- enable private vulnerability reporting in **Settings → Security → Advanced Security**;
- enable secret scanning/push protection when available.

Do not initialize the remote with another README or license if the local source is going to be pushed.

Initialize and push from a private working copy, not directly from `/tmp` on the production Zimbra server:

```bash
git init
git add .
git status
git diff --cached --check
git commit -m 'Initial public beta of zimbra-nextcloud-connector'
git branch -M main
git remote add origin https://github.com/YOUR_GITHUB_ACCOUNT/zimbra-nextcloud-connector.git
git push -u origin main
```

Review `git status` and `git diff --cached` before committing. Never use `git add -f` to bypass exclusions for a configuration or secret.

## 4. Create the beta tag and GitHub release

Use a beta tag without changing the internal numeric Zimlet version:

```bash
git tag -a v3.1.23-beta.2 -m '3.1.23 Public Beta 2'
git push origin v3.1.23-beta.2
```

On GitHub, open **Releases → Draft a new release**:

- tag: `v3.1.23-beta.2`;
- title: `3.1.23 Public Beta 2`;
- check **Set as a pre-release**;
- paste the summary from `RELEASE_NOTES_BETA.md` and fill the missing exact versions;
- attach only:
  - `zimbra-nextcloud-connector-v3.1.23.zip`;
  - `zimbra-nextcloud-connector-v3.1.23.zip.sha256`;
- save as draft, verify both downloads and checksum, then publish.

Do not replace an asset silently after publication. If bytes change, publish `beta.2` with a new tag and release notes.

## 5. Publish to the Zimbra community

After the GitHub pre-release is public:

1. Create an account and sign in to the Zeta Alliance Zimlet Gallery.
2. Submit the project as a Modern UI / Photos and Files Zimlet. If the account does not expose a submission button, use the Zeta Alliance contact channel and provide the GitHub release URL.
3. Mark it clearly as **Beta**, list only the compatibility actually tested, use the GitHub release as the download/source link and include the BSD-3-Clause license.
4. Create a post on the Zimbra Community forum with a short feature list, tested matrix, limitations, installation link and request for sanitized compatibility reports.
5. Link back to GitHub Issues; do not split bug tracking across several private message systems.

GitHub is used internationally and should remain the canonical source. Zeta Alliance is the Zimbra-specific discovery channel; the forum is useful for announcements and feedback.

## 6. Information every bug report must include

- connector version and GitHub release/tag;
- Zimbra version/edition and single- or multi-mailbox topology;
- Nextcloud version and storage backend if relevant;
- ONLYOFFICE/Euro-Office connector and Document Server versions;
- browser/OS;
- personal or managed account mode and number of Cloud profiles;
- exact reproduction steps, expected and actual results;
- sanitized `./diagnose.sh`, connector log lines and browser console/network error.

Never request or accept production secrets in a public issue.

## 7. Maintenance after publication

- Triage issues and label confirmed compatibility reports.
- Maintain a compatibility table instead of broad unsupported claims.
- Publish fixes as a new tag and immutable release asset.
- Update `CHANGELOG.md`, `TESTING.md`, version strings and checksum together.
- Repeat the complete build/test/install cycle after every Zimbra, Nextcloud or office API change.
- Keep the first public releases marked beta until the exit criteria in `TESTING.md` are met.
