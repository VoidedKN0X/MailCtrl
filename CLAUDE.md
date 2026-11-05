# MailCtrl

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "MailCtrl X.Y.Z". Never add a build number (`-build.N`, "(build N)").
- Use the same version for every change that ships together; README, licence and CI changes don't need a bump.

## Changelog

Every new version needs a detailed entry at the top of `CHANGELOG.md`, under a heading for that version (match the existing headings). CI refuses to release without one, and the entry becomes the release notes on GitHub, in Obtainium and in the CtrlF-Droid repo.

- Write for people using the app, not for developers: what's new, what changed, what was fixed.
- Cover every user-visible change in the version, one bullet each, with enough detail to understand it without opening the app.
- Group with `### New`, `### Changed`, `### Fixed` and `### Security` when there's more than a couple of items.

## Building

The sandbox Claude works in can't download the Android SDK or Gradle dependencies, so the app is compiled by GitHub Actions on every push to `main`. After pushing, check the run (`gh run list`, `gh run view --log-failed`) and fix any compile errors in a follow-up push.

## Dependency verification

Every library and plugin download is checked against `gradle/verification-metadata.xml` (SHA-256 only, no PGP). When you add, remove or update a dependency or Gradle plugin, run the manual **Dependency verification metadata** workflow (`gh api -X POST repos/VoidedKN0X/MailCtrl/actions/workflows/verification-metadata.yml/dispatches -f ref=main`), take the file from its log (gzipped and base64-encoded between the BEGIN/END markers), check the diff (only Maven Central, Google's Maven and the Gradle plugin portal) and commit it in the same push. Keep the `<trusted-artifacts>` entries for `-sources.jar` and `-javadoc.jar`. Never turn verification off or to lenient in CI.
