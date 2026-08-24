# Publishing 3.2.0-beta.7

This maintainer checklist keeps source, generated artifacts and compatibility claims separate.

## 1. Complete the compatibility record

Before publishing, record sanitized exact versions:

```bash
su - zimbra -c 'zmcontrol -v'
curl -fsS https://NEXTCLOUD_HOST/status.php
```

Also record the Zimbra client tested (Modern/Classic), browser/OS, single- or multi-mailbox topology, Nextcloud Talk version, storage backend, office connector and Document Server. Never publish private hostnames, account names or secrets.

Classic must be labelled **automated-only / needs real-server validation** until the checklist in `TESTING.md` has been performed.

## 2. Build from a clean source checkout

```bash
npm ci
npm audit --omit=dev
./build-release.sh
git diff --check
git status --short
```

The last command must show only intentional source changes before commit. Generated output is ignored and must not be added to Git.

Verify the release:

```bash
cd dist
sha256sum -c zimbra-nextcloud-connector-v3.2.0-beta.7.zip.sha256
unzip -t zimbra-nextcloud-connector-v3.2.0-beta.7.zip
unzip -l zimbra-nextcloud-connector-v3.2.0-beta.7.zip
```

Confirm the archive contains the Modern packages, `frontend/fr_franckchalon_nextcloud_classic.zip`, Java source/build inputs, installer scripts and internal `SHA256SUMS`, but no `.git`, `node_modules`, production properties, encrypted profiles, master keys, logs, browser exports or customer screenshots.

Review possible sensitive strings without blindly deleting legitimate security code:

```bash
rg -n -i 'password|app[_-]?password|jwt[_-]?secret|authorization|cookie|PRIVATE_COMPANY_NAME|PRIVATE_DOMAIN|customer|client' . \
  --glob '!node_modules/**' --glob '!dist/**' --glob '!build/**' --glob '!classic-build/**' \
  --glob '!pkg/**' --glob '!pkg-classic/**'
```

Run full `npm audit` as an informational build-tool review. Do not claim the development dependency tree is vulnerability-free when only `npm audit --omit=dev` is clean; see `SECURITY.md`.

## 3. Pull request workflow

Use a focused branch rather than committing directly to `main`:

```bash
git switch main
git pull --ff-only
git switch -c feat/classic-ui-3.2.0-beta.7
git add --all
git diff --cached --check
git diff --cached --stat
git commit -m 'feat: add shared Zimbra Classic UI support'
git push -u origin feat/classic-ui-3.2.0-beta.7
```

Open a pull request, describe testing and limitations, wait for CI, review **Files changed**, and merge only after the checks pass. Do not close multi-server or multi-tenant issues unless their stated scope is genuinely complete.

## 4. Tag and GitHub pre-release

After the PR is merged and `main` is clean:

```bash
git switch main
git pull --ff-only
git tag -a v3.2.0-beta.7 -m '3.2.0 beta 7: fix Classic compose picker sizing'
git push origin v3.2.0-beta.7
```

Create a GitHub release:

- tag: `v3.2.0-beta.7`;
- title: `3.2.0-beta.7 — Classic UI public beta`;
- check **Set as a pre-release**;
- paste `RELEASE_NOTES_BETA.md` and fill in the exact tested versions;
- attach only:
  - `zimbra-nextcloud-connector-v3.2.0-beta.7.zip`;
  - `zimbra-nextcloud-connector-v3.2.0-beta.7.zip.sha256`.

Download both assets again and verify their checksum before announcing the release. Never replace an asset silently after publication; publish a new beta tag if any byte changes.

## 5. Community announcement

- Keep GitHub as the canonical source/download/issue tracker.
- Mark the Zeta Alliance and Zimbra Community entries clearly as **Beta** and **Modern + Classic**.
- Describe Talk accurately as **text messaging only**, not calls.
- List only tested Zimbra/Nextcloud versions and link the public compatibility matrix.
- Invite sanitized Classic and multi-mailbox reports.

## 6. Issue responses

When replying to feature requests:

- thank the reporter and link the implementing PR/release;
- distinguish implemented, partially implemented and deliberately deferred scope;
- avoid closing an issue merely because an installer switch exists;
- explain compatibility decisions such as retaining deployed Modern IDs;
- ask for exact test versions and reproducible evidence.

## 7. Maintenance

- Update version strings, `CHANGELOG.md`, `TESTING.md`, release notes and checksum together.
- Publish generated ZIP/JAR files only as GitHub Release assets, never as tracked source.
- Keep branch protection and CI enabled on `main`.
- Repeat a clean build and staged installation after Zimbra, Nextcloud or office API changes.
- Keep Classic marked beta until the exit criteria in `TESTING.md` are satisfied.
