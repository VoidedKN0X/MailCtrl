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

## Encryption at rest

- The message list (`data/cache/MailCache`, `mailcache.db`) seals each row's sender, recipients and subject together with AES-256-GCM (`data/crypto/SealedFile`, marker `MAILCTRL-REST1`) under the data key (`DataKeyStore`: 32 random bytes in `noBackupFilesDir/data.key`, wrapped through `KeystoreKeyWrapper` by the existing `KeystoreCrypto` key `mailctrl_data`; no user authentication, so background checks work). A sealed row has a BLOB in `subject` and NULL in `from_name`, `from_email`, `to_json`; a row with text in `subject` is an ordinary one and must keep being read (`Cursor.getType`). Ordinary rows are sealed in the background by `sealOrdinaryRows` (batches in transactions, then one `VACUUM`).
- No SQLCipher: the database file, account ids, folder names, uids, dates, sizes and flags stay readable because the SQL sorts and filters on them. Never use the sealed columns in `WHERE`, `ORDER BY`, `LIKE` or an index, and don't bump the database version for this (an older app would crash on a newer version number).
- It's a cache: a sealed row that can't be opened is deleted and refetched; no UI. Without a data key rows are written as ordinary text as before. Never replace an existing `data.key`. If the data key is ever used for something that can't be downloaded again, that needs the full treatment (block writes, keep the file, tell the user), as in RideCtrl.
- The data key is only fetched on background threads (inside `MailCache`'s `Dispatchers.IO` blocks); keep it off the main thread.
- Already encrypted and separate from this: passwords and drafts (`KeystoreCrypto`, plus the app lock's `LockVault`), kept messages (`mail/MessageStore`, own key in `messages.key`), OpenPGP keys. Not encrypted: the account list in the `accounts` preferences (read on its own thread at start; every accessor of `AccountStore` waits for that read, so it behaves as a synchronous read), settings, `notify_state`, and attachments in `cacheDir/attachments` (handed to other apps through the FileProvider, cleared at start).
- `SealedFileTest` covers the format and the key rules (`./gradlew :app:testDebugUnitTest`; CI doesn't run unit tests).

## Dependency verification

Every library and plugin download is checked against `gradle/verification-metadata.xml` (SHA-256 only, no PGP). When you add, remove or update a dependency or Gradle plugin, run the manual **Dependency verification metadata** workflow (`gh api -X POST repos/VoidedKN0X/MailCtrl/actions/workflows/verification-metadata.yml/dispatches -f ref=main`), take the file from its log (gzipped and base64-encoded between the BEGIN/END markers), check the diff (only Maven Central, Google's Maven and the Gradle plugin portal) and commit it in the same push. Keep the `<trusted-artifacts>` entries for `-sources.jar` and `-javadoc.jar`. Never turn verification off or to lenient in CI.
