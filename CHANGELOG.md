# Changelog

What changed in each MailCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/MailCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## Unreleased

Changes on `main` that aren't in a release yet. They ship with the next version.

### New

- **Always load pictures, per sender.** The bar that says pictures from the internet are blocked has a second button, **Always from this sender**. Tap it and that sender's pictures load by themselves from then on; everyone else's stay blocked as before. Nobody is on the list until you add them. Settings › Reading › **Senders whose pictures always load** shows the list and removes senders, and a message's menu has **Stop always loading this sender's pictures**. The list is kept encrypted on the phone and isn't part of any backup.
- **Dark messages.** While MailCtrl is dark, HTML messages are shown dark too (light text on a dark page) instead of a white page in a dark app. Pictures keep their own colours. It's done with a style inside the locked-down viewer, so there are still no scripts. A message that looks wrong that way can be switched back from its menu (**Show original colours**), and Settings › Reading › **Dark messages** turns it off for all mail.
- **Recipient suggestions from addresses you've mailed.** While you type in To, Cc or Bcc, up to four addresses you have sent mail to before are offered under the field; tap one to fill it in, or its cross to forget it. The list only ever grows from messages you send from MailCtrl: there is still no contacts permission and the phone's contacts are never read. It's kept encrypted on the phone. Settings › Writing has **Suggest recipients** (on) and **Forget remembered addresses**.
- **Identities and aliases.** An account can have other addresses to send from (account settings › **Identities**), each with its own name and signature if you like. When writing, **From** lists every account's own address and its identities, and the signature follows the one you pick. A message that was sent to one of your identities is answered from that address by itself. Mail still goes out through the account's own server, which has to allow the address (Gmail: add it under "Send mail as" first; Proton through hydroxide: an address of your Proton account). The Autocrypt key is only attached when you send from the account's own address.
- **Encrypted subjects.** When you send an OpenPGP-encrypted message, the subject is now encrypted with it: the real subject travels inside the encrypted part (the "protected headers" format that Thunderbird and K-9 Mail use), and mail servers, and the copy in your Sent folder until it's opened, only show "..." as the subject. MailCtrl already showed the real subject of such messages once decrypted. It's on by default; Settings › OpenPGP › **Encrypt the subject too** turns it off, and the note under the Encrypt switch says which applies. Signed-only messages are unchanged. Recipients with a mail program that doesn't know this format see "..." as the subject.
- **Conversations.** In the inbox and in folders, a message and its answers now show as one row: the newest message, with the number of messages in the conversation next to the sender. It's unread while any message in it is. Tap it to see every message of the conversation (your own answers from the Sent folder are added when the phone has them) and tap one to open it; swiping the row or selecting it archives, deletes, flags or marks the whole conversation. Messages are put together by the Message-IDs they name (References and In-Reply-To), and by subject for an answer ("Re:", "Fwd:") when the server doesn't hand those out; two messages that merely share a subject are not joined, and accounts are never mixed. Lists already on the phone learn the Message-IDs only for mail that arrives from now on, so older messages are joined by subject. Settings › Message lists › **Conversations** turns it off. Search results stay one row per message.
- **Send later.** Under the text of a message you're writing, **Send later** picks a time: later today, this evening, tomorrow morning, next week, or a date and time of your own. The message is put together straight away (signed or encrypted then, attachments included) and waits on the phone, encrypted with the phone's secure hardware, until Android's WorkManager sends it at that time. The phone has to be on and online then; Android may run it a few minutes late while the phone sleeps. It isn't sealed with the app PIN, so it can go out while MailCtrl is locked; only when account passwords are sealed with the PIN (app lock on, notifications off) does it wait until you unlock. If sending fails, the message stays in the list with the reason and you get a notification; it's only tried again by itself when the server was never reached, so nothing is sent twice.
- **Undo send.** Settings › Writing › **Undo send** makes every message wait 5, 10, 20 or 30 seconds before it goes out (off by default). A bar at the bottom of the screen counts down and offers **Undo**, which takes the message back and opens it again with its text and attachments. When the time is up it's sent in the background, also if you've closed MailCtrl meanwhile.
- **Snooze.** A message's menu, and the menu of selected messages in a list, have **Snooze**: pick a time and the message disappears from the lists on this phone until then, when it shows again with a notification (as much as your Notification content setting shows). Nothing changes on the mail server, so other mail apps keep showing it. The list of snoozed messages is kept encrypted on the phone.
- **Scheduled and snoozed** (profile picture). Lists the messages waiting to be sent, each with **Send now** and **Cancel sending** (it becomes a draft again, without its attachments), and the snoozed messages, each with **Show again now**.


### Project

- **Checksum list regenerated without the old entries.** `gradle/verification-metadata.xml` was regenerated on CI with the fixed workflow and committed exactly as printed (its SHA-256 matches the log). It now lists only the 444 components the build downloads today; the leftovers from earlier library and build-tool versions are gone.
- **The checksum list no longer collects old entries.** The hand-started workflow that regenerates `gradle/verification-metadata.xml` used to add the new checksums to the existing file, so entries for library versions that are no longer used stayed in it. It now clears the old entries first and writes only what the build uses, and prints the file's SHA-256 so the copy that gets committed can be checked against the log. Nothing in the app changes.
## v0.4.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published. It is the first release built with the new build tools (Android Gradle Plugin 9, stricter code shrinking), so update over your installed version and check that your data and settings are still there.

### Changed

- **A lighter start.** The list of your accounts (a small file, and decoding it) was read on the main thread before anything else could happen, at every start of MailCtrl, also the background ones for a mail check or a push. It is now read on a separate thread while the rest of the app starts. Everything that uses the list waits until it has been read, so MailCtrl can never mistake "not read yet" for "no accounts" and show the welcome screen.
- **Smoother message lists.** On every redraw of a list MailCtrl made a copy of the swiped-away messages and went through the whole list again to find the selected ones. Both are now only worked out when the list, the swiped-away messages or the selection actually change, and rows tell Android what kind of row they are so they are reused while scrolling. What you see is the same.
- **Long folders don't slow the lists down any more.** MailCtrl keeps the list of every message it has ever loaded for a folder, and until now a list on screen read all of it again (and, since 0.3.0, decrypted every entry) after any change to any folder of any account. A folder's list now reads its newest 200 messages at first, and 200 more of what's already on the phone each time you scroll to its end; once that has run out, older mail is fetched from the server as before ("scroll to load more" works the same, it just doesn't need the server for what the phone already has). New mail doesn't push older messages out of the list while you're in it. A list also only reads again when its own folder changed, not for changes elsewhere. "All inboxes" was already limited to the newest 600 and is unchanged, apart from reading again only when one of its inboxes changed. The unread count and the "mark all as read" button still count the whole folder.
- **Less reading behind the scenes.** After a refresh MailCtrl read a folder's whole list just to pick the few messages to download ahead, and "load more" read it all to find the oldest message. Both now ask the database for exactly that. The unread numbers on the account chips come from one query instead of one per account.
- **Lists stay responsive during a refresh.** The database behind the message lists now uses SQLite's write-ahead log, so a list on screen can be read while a refresh is writing instead of waiting for it. It is the same database with the same contents (and the same encryption of senders, recipients and subjects); SQLite keeps two extra files next to it (`mailcache.db-wal` and `-shm`), which are excluded from backups like the database itself. An older MailCtrl opens the database as before.
- **Newer build tools and libraries; nothing changes in how MailCtrl looks or works.** MailCtrl is now built with the current stable toolchain: Android Gradle Plugin 8.11.1 → 9.5.1, Gradle 8.14.3 → 9.9.2, Kotlin 2.2.0 → 2.4.21 (with its Compose and serialization compiler plugins) and Compose BOM 2025.07.00 → 2026.09.00 (Compose 1.12.1, Material 3 1.4.0). The libraries move to the versions the newest Ctrl apps use: activity-compose 1.10.1 → 1.13.0, core-ktx 1.16.0 → 1.18.0, lifecycle 2.9.1 → 2.10.0, navigation-compose 2.9.1 → 2.9.8, datastore-preferences 1.1.7 → 1.3.0, kotlinx-serialization-json 1.9.0 → 1.13.0 and kotlinx-coroutines-android 1.10.2 → 1.11.0. Every other library stays at the version it had. No library was added, and CI still checks that no Google Play services, Firebase, ML Kit, Play Core or Tink is in the app.
- **Compiled against the Android 17 SDK.** Compose 1.12 needs `compileSdk` 37, so that goes from 36 to 37. The Android versions MailCtrl runs on and targets are unchanged (`minSdk` 35, `targetSdk` 36), so Android treats the app exactly as before.
- **The app is shrunk by a newer R8.** Android Gradle Plugin 9 compiles Kotlin itself (the separate Kotlin Android plugin is gone from the build files) and brings the R8 version that understands Kotlin 2.4. MailCtrl's own keep rules are unchanged. The newer R8 has stricter defaults than the old one, so the release build needs a check on a phone before the next version is published.
- The checksums Gradle verifies every download against (`gradle/verification-metadata.xml`) were regenerated on CI for the new versions. Built and checked by CI only; not yet run on a phone.

### Fixed

- **Fixed before release: the app would have crashed at start with the new build tools.** MailCtrl uses WorkManager for its background work, and WorkManager stores its jobs in a small Room database that is created by name when the app starts. The shrinker (R8) in Android Gradle Plugin 9 is stricter than the old one and removed the constructor that needs, which makes the app stop right at launch (a known problem with Room 2.6.1 under Android Gradle Plugin 9). A keep rule for that constructor, the same one newer Room versions ship themselves, is now in `app/proguard-rules.pro`. No released version was affected: the new build tools have not been in any release. Built by CI only; not yet run on a phone.
- **"Mark as read" and "Delete" on a notification are more reliable on a slow connection.** Tapping one of them used to sign in to the mail server and do the work inside the few seconds Android gives a notification button; on a slow or unreachable server Android could stop MailCtrl first and the action was lost. The notification still disappears at once, and the work is now handed to a short background job (the same kind that checks for new mail), which starts right away and isn't cut off. Nothing else changes: if the server can't be reached the action isn't repeated later, as before. The job's details (account, folder and message number) are kept by Android's job queue in MailCtrl's private storage until it has run.

### Security

- **The signing key is no longer available to the build.** Until now the release key and its passwords were present in the same GitHub Actions job that runs Gradle, so any build plugin or library could in principle have read them. The build job now has no secrets at all and produces an unsigned APK. A separate job, which checks out no code and runs no Gradle, signs it with `apksigner`, and then refuses to hand it on unless it is signed by exactly the certificate published in SECURITY.md and README.md and its contents are identical to what the build job made. Releases are signed with the same key and the same signature scheme (v2) as before, so updates install as usual. The app itself is unchanged by this. The release step of the new workflow has not run yet for MailCtrl: it runs for the first time with the next version.
- **The build checks that the published fingerprint and the enforced one are the same.** If `build.yml`, SECURITY.md and README.md ever disagree about the signing certificate, the build fails.

### Project

- **Builds no longer use a saved Gradle cache, and the Gradle plugin portal is no longer a plugin source.** Other Ctrl apps had builds fail with "Dependency verification failed ... from repository Gradle Central Plugin Repository", and keep failing until the caches were deleted by hand. The cause (found on DateCtrl): with a restored cache Gradle still fetches the plugin files from Maven Central on every build; when Maven Central can't be reached for a moment, Gradle takes the Kotlin plugin "marker" file from the Gradle plugin portal instead, the portal serves a different file under the same name, verification rightly refuses it, and the failed run then saved that state as the cache for every later run. Now every build and the code scan start from a clean Gradle home (a build takes a minute or two longer), and `settings.gradle.kts` lists only Google's Maven and Maven Central for plugins. Dependency verification itself is unchanged and as strict as before. Nothing in the app changed.

## v0.3.0 (2026-11-20)

**This release encrypts some of what the app stores on the phone. An older version cannot read it afterwards, so export or back up anything you need before you update (details below).** Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Security

- **The list of your messages is now encrypted on the phone.** MailCtrl keeps a list of your messages (who each one is from and to, and its subject) on the phone so it opens instantly. Until now that list was stored as ordinary text in MailCtrl's private storage (`mailcache.db`), while the messages themselves, your drafts and your passwords were already encrypted. Now the sender, the recipients and the subject of every listed message are encrypted together (AES-256-GCM) with a new random key, the "data key". That key is stored in `data.key` in MailCtrl's private storage, itself encrypted by the same key in the phone's secure hardware (Android Keystore) that already protects your passwords; it needs no fingerprint or PIN, so background mail checks keep working while the phone or MailCtrl is locked. Someone who copies MailCtrl's files off the phone can no longer read who you write with or what about from the list. It doesn't protect against something that can already run as MailCtrl on an unlocked phone: the key is in memory while the app runs. It works the same with or without the app lock.
- **What is deliberately not encrypted, and why.** The list is a database, and MailCtrl sorts and filters it by account, folder, message number, date and read/flagged state, so those stay readable: a copy of the files still shows your folder names, how many messages each has, their dates and sizes, and which are read, flagged or have attachments. The database file as a whole isn't encrypted (that would need an extra library, SQLCipher, which the Ctrl apps don't take on). Your account settings (address, server names, sender name, signature; never the password, which was already encrypted) are read the moment the app starts, before any key could be loaded off the main thread, so they stay as they were. Attachments you open or share are written as ordinary files in the cache for the other app to read, and are removed the next time MailCtrl starts, as before. Settings and the notification bookkeeping (the number of the newest message seen per account) stay as they were. Drafts, kept messages, passwords and OpenPGP keys were already encrypted and are untouched.
- **Your existing list is converted by itself, in the background.** The first time the list is used after the update, MailCtrl encrypts the entries that are still ordinary text, 200 at a time. Each entry is encrypted, decrypted again to check it, and only then written, and each batch is saved as a whole or not at all, so being interrupted (the app closed, the battery empty) just leaves the rest for the next start. Until an entry is converted it's read as before, so the list looks the same throughout. When everything is converted the database file is rewritten once, so the old text doesn't stay behind in its unused parts. Storage chips can still hold old copies until they reuse that space; that's outside what an app can control.
- **If the key is ever lost, nothing of yours is lost.** The list is only a copy of what's on your mail servers. If an encrypted entry can't be opened (the key in the secure hardware is gone, for example after the phone's Keystore was reset), that entry is removed from the list and downloaded again with the next refresh; older pages come back with "Load more". There is no message to tap and nothing to restore. While no key can be had, MailCtrl keeps the list as ordinary text, exactly as before this change; an existing `data.key` is never replaced by a new one.
- **Going back to an older version.** A version of MailCtrl from before this change can't read the encrypted entries and would fail to show the list. Android doesn't install an older version over a newer one anyway; to go back you'd uninstall first, which removes the list, and the older version downloads it again. There's nothing to export first: your mail stays on your servers.

## v0.2.4 (2026-11-20)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Security

- **The mail library is updated to the version with the fix for CVE-2025-7962.** MailCtrl sends and reads mail with JavaMail for Android (`com.sun.mail:android-mail` and `android-activation`), which moves from 1.6.7 to 1.6.8. The older version could be tricked into sending extra SMTP commands when a line break was hidden in a message's addressing; 1.6.8 closes that in the library itself, on top of MailCtrl's own check below. Nothing else about sending or reading mail changes. The checksums Gradle verifies every download against (`gradle/verification-metadata.xml`) were regenerated on CI for the new version.
- **Line breaks and other hidden control characters are refused in everything that becomes a mail header.** MailCtrl now refuses to send when a recipient's address or name, your own sender name or address, the subject or an attachment's name contains a line break or another control character, and tells you why. Such characters could come in through a `mailto:` link, another app's share, or a crafted message you reply to, and would let whoever wrote them add headers or recipients of their own to your message.
- **Text arriving from outside is cleaned first, so you aren't stopped by something you can't see.** A subject handed over by a `mailto:` link or a share, the subject of a message you reply to or forward, and the names filled into To and Cc when you reply have any control character replaced by a space. The hidden Message-ID and References of a reply are put on one clean line.
- **A message that crashes the HTML viewer no longer closes the app.** When Android's web engine crashes on a message or is stopped for using too much memory (a huge or broken HTML mail), Android used to close MailCtrl, and again every time that message was opened. MailCtrl now shows "This message couldn't be displayed." with a Retry button in place of the message, and everything else keeps working.

### Changed

- **Less work for the phone's secure hardware.** The key that protects saved passwords and drafts is looked up once per app start instead of on every read and write. The key itself still never leaves the secure hardware; if the remembered handle stops working, MailCtrl looks the key up again and retries.

## v0.2.3 (2026-11-20)

MailCtrl now loads mail the way Thunderbird does. Thunderbird downloads messages ahead in batches while it refreshes and keeps them on the phone, so opening one needs no connection at all, while MailCtrl fetched each message when you tapped it, with three or four separate requests, and forgot it when the app closed or locked. Every request waits for the network and the server, which is slow over a VPN and with hydroxide (Proton).

### New

- **Messages are downloaded ahead and kept on the phone.** After each refresh, the newest 50 messages of the folder you're looking at come down in the background, many at once in a single request (in batches of up to 2 MB). Messages over 256 KB are skipped, so big attachments stay on the server until you open them. Opening one of these messages is then instant, with no waiting for the server and even without a connection. Messages you open yourself are kept too, up to 1 MB each. "Load more" downloads the older page ahead as well.
- **New mail is ready before you tap the notification.** When a background check finds new mail, those messages are downloaded straight away, so tapping the notification opens the message at once.
- **"Keep messages on this phone" switch** (Settings › Reading, on by default). The copies are encrypted (AES-256-GCM) with a key that is itself protected by the phone's secure hardware and never leaves the phone. They take up to 300 MB in all, and the ones you haven't opened in the longest time are removed first. A message deleted or moved on the server (or from another device) disappears from the phone with the next refresh, and removing an account removes its copies. Turning the switch off removes every copy at once; then only the few newest unread inbox messages are kept in memory, as before.

### Changed

- **Opening a message the phone doesn't have yet takes one request instead of three or four.** The message and its read/flagged state now come in one go. It's also no longer held back until the server has marked it read: it shows straight away, and the read mark goes to the server in the background (if that fails, the message shows as unread again after the next refresh).
- **Refreshing a folder takes fewer requests.** Checking what changed (new messages, removed ones, read and flagged elsewhere) is now one request instead of two. The folder also stays open between refreshes instead of being opened and closed every time, which saved another two. On a quick connection you won't notice, but on mobile data, over a VPN or with hydroxide it's the difference between a refresh that's done at once and one that visibly loads.
- **Downloading ahead is much quicker.** It used to fetch each message on its own, with two requests per message. It now fetches up to 40 messages in one request.
- **Attachments of messages kept on the phone open without downloading them again,** as long as the message was small enough to be kept.

### Privacy

- Until now, messages were only ever kept in memory. With "Keep messages on this phone" on, recent messages are saved on the phone, encrypted as described above, in MailCtrl's private storage, which isn't included in backups. The list of your messages (sender, subject, date) was already kept on the phone. Decrypted OpenPGP messages are still never written to storage. Turn the switch off for the old behaviour.

## v0.2.2 (2026-11-20)

### Changed

- **Signing in is quicker after the first time.** Every new connection to your mail server used to start a brand-new secure session, with the full back-and-forth that takes. MailCtrl now resumes the secure session it already made with that server, which skips most of that exchange. It's kept per server and per set of certificates you trusted, so a certificate you trusted for one server is never reused for another.
- **Less data over the network.** When your mail server offers compression (Gmail does), MailCtrl turns it on, so message lists and messages arrive compressed. This helps most on slow mobile data and over a VPN.
- **MailCtrl signs in before you need it.** As soon as you open MailCtrl, it signs in to your accounts in the background, so the first folder or message you tap doesn't have to wait for a sign-in. After switching networks (Wi-Fi to mobile data, or the other way round) while MailCtrl is open, it drops the old connections and signs in again straight away, instead of waiting for the old ones to time out.

### New

- **Test connection speed.** In an account's settings (tap the profile picture, then the account), "Test connection speed" measures each step with your mail server on a fresh connection: finding the server, signing in, one round trip, opening the inbox, listing the newest 25 messages and downloading the newest one. It also shows whether compression is on. When something is slow it tells you where: a slow round trip means the network (for example a VPN that goes through a relay instead of connecting directly), while a quick round trip with slow listing or downloading means the server itself (for Proton through hydroxide, which fetches and decrypts every message from Proton when it's asked for). The test changes nothing and marks nothing as read.

## v0.2.1 (2026-11-20)

### Changed

- **The app icon matches the other Ctrl apps.** MailCtrl's icon was drawn in a darker set of greys than the rest of the series: a near-black background, a darker keycap and a front bar you could hardly see. It now uses the same background, keycap and bar colours as the other Ctrl apps, so the icons look like one family side by side. The purple symbol and the themed (monochrome) icon are unchanged.

## Project updates (2026-11-19)

### Project

- **The weekly code scan no longer fails at random.** CodeQL has to watch the app being compiled, but its build could take the compiled code from Gradle's build cache instead, and then the scan stopped with "CodeQL detected code written in Java/Kotlin but this run didn't build any of it" (and GitHub mailed a failed run). The scan's build now runs with `--no-build-cache`, so the code is always compiled. No app changes, so no new version.

## v0.2.0 (2026-11-13)

MailCtrl now does OpenPGP itself: encrypted and signed mail, with your own keys, no extra app needed. And "All inboxes" shows every account's mail together, each message tagged with its account's colour.

### New

- **Encrypted mail (OpenPGP), built in.** Read and send encrypted and signed mail without OpenKeychain or any other app. It works with GnuPG, Thunderbird, Proton and other OpenPGP programs (PGP/MIME, and the older inline style for reading).
- **Your own keys.** Make a key under your profile picture › **OpenPGP keys** (Curve25519, or RSA 4096 for very old software), with an expiry date if you like, or import one you already have (a public or secret key file, armored or binary). Every key shows its fingerprint and when it expires. Secret keys never leave the phone: they're encrypted with its secure hardware, and still protected by their own passphrase inside that.
- **Passphrase remembered for a while.** After you type a key's passphrase, MailCtrl remembers it (in memory only) for 5 minutes by default; choose from "Don't remember" to "Until MailCtrl locks or closes" in Settings › OpenPGP. It's always forgotten when the app lock locks.
- **Reading encrypted mail.** Encrypted messages are decrypted on the phone, attachments included; tap **Unlock** to enter your passphrase when it isn't remembered. Messages encrypted without an integrity check are refused, so nobody can tamper with them unnoticed.
- **Signatures you can trust at a glance.** A signed message says **Signed by** who signed it, **Signed with an unknown key** when you don't have the signer's key yet, or **Bad signature** in red when it was changed after signing. It also warns when the key belongs to a different address than the sender's, or has expired.
- **Writing encrypted mail.** **Encrypt** and **Sign** switches under the subject, for each message. MailCtrl tells you which recipients have no key, and never sends a message unencrypted when you asked for encryption. Everything you encrypt is encrypted to your own key too, so you can still read it in Sent. Answering an encrypted message starts with encryption on.
- **Your contacts' keys.** Import them from a file, from a key attached to a message (the key button next to the attachment), or from the sender's **Autocrypt** header (MailCtrl offers it; nothing is imported without your tap). Each key shows its fingerprint to compare with the person.
- **Look up a key (optional).** When someone's key is missing, **Look up** asks their own mail domain for it (Web Key Directory). It only happens when you tap it, and MailCtrl tells you which domain it will contact first. You can turn the button off in Settings.
- **Send your key with Autocrypt (optional).** Turn it on per account, and every message you send carries your public key, so people with Autocrypt-capable apps can write back encrypted.
- **All inboxes.** The Inbox tab shows every account's inbox together, newest first, each message tagged with its account's name and colour. Each chip shows its unread count. Actions (read, flag, archive, delete, move) always happen on the message's own account.
- **Account colours, and which accounts are in All inboxes.** In an account's settings, pick its colour and whether it's part of All inboxes. The unread counts follow that choice, and with several accounts each account's notifications carry its colour as well as its name.
- **All inboxes in the Folders tab,** with its unread count, one tap away.

### Changed

- The "Combined inbox" setting is now called **All inboxes**.

## v0.1.9 (2026-11-13)

### Security

- **Passwords and drafts can use the phone's security chip.** On a new install, the key that encrypts your passwords and drafts is made in the phone's separate security chip (StrongBox) when it has one, and in its regular secure hardware otherwise. If you already use MailCtrl, your existing key stays where it is and nothing changes for you.
- **Wake-ups from ntfy are checked more strictly.** The secret that proves a wake-up really comes from ntfy is now compared in a way that gives nothing away about it, however many fake wake-ups another app sends.
- **The build tool download is checked.** The exact Gradle version used to build MailCtrl is now verified against its published checksum before it's used.

### Performance

- **Starts a little lighter.** The message cache, drafts and the mail connection are only opened when they're first needed, instead of all at once when MailCtrl starts (for example for a background check).

## v0.1.8 (2026-11-12)

### Changed

- **Slightly smaller download.** The bundled JetBrains Mono font (numbers, readouts and labels) no longer carries the Cyrillic, Vietnamese and maths-alphabet letters MailCtrl never shows itself, which takes about 0.1 MB off the download and 0.2 MB off the installed app. Everything looks the same; if a name or a log line shown in that font uses one of those scripts, those letters are now drawn with your phone's own font.

## v0.1.7 (2026-11-12)

MailCtrl is much faster: lists refresh in a fraction of the time, messages open quicker, and switching tabs no longer waits for the server.

### Changed

- **Stays signed in between actions.** MailCtrl used to sign in to your mail server again after 45 seconds without use, so almost everything you did started with a new secure connection and sign-in. The connection is now kept for 5 minutes, so most actions go straight to the server.
- **Refreshing only downloads what changed.** A refresh used to download the details of up to 200 messages again every time. It now asks only for new messages, plus which of the messages already on the phone were read, flagged, answered or removed. An unchanged inbox refreshes almost instantly, even on mobile data.
- **Opening a message never waits for a refresh.** Each account now has a second connection just for what you do yourself, so a refresh running in the background no longer holds up opening, flagging or moving a message.
- **Messages open in one go.** A message (up to about 900 KB, which is most mail) is now downloaded whole in a single request instead of part by part, including the pictures inside it. Its attachments then open without downloading again.
- **Subject and sender show straight away** when you open a message, while the rest loads.
- **Opening a message again is instant.** The messages you opened lately are kept in memory (never on storage) and forgotten as soon as MailCtrl locks or closes.
- **The newest unread messages are read ahead.** After the inbox refreshes, up to 5 small unread messages are downloaded in the background so they open instantly. They stay unread, and nothing from the internet is loaded for them.
- **Switching tabs doesn't refresh again.** Coming back to the Inbox or a folder within 30 seconds of the last refresh (2 minutes for the Folders tab) shows what's there without asking the server. Pull down to refresh at any time.
- **Folders tab loads faster.** Unread and total counts now take one request per folder instead of two, and Gmail's All Mail (slow to count) is no longer counted.
- **Staying in the same folder is quicker.** The folder you're working in stays open between actions, so the next message, flag or move in it needs one request fewer.
- **The message viewer is ready sooner.** Its engine is started in the background shortly after MailCtrl opens, so the first message you open doesn't wait for it.

### Fixed

- **No more long hangs after switching networks.** When the phone moves between Wi-Fi and mobile data, or your VPN goes up or down, MailCtrl now drops its old connections at once instead of waiting up to a minute for them to time out.
- **Shorter waits on a dead connection.** A server that stops answering now times out after 30 seconds instead of 60.

## v0.1.6 (2026-11-05)

MailCtrl now needs Android 15 or newer.

### Changed

- **Needs Android 15 or newer.** Android 13 no longer gets security updates and Android 14's end early 2027, so MailCtrl now supports Android 15, 16 and 17 only. On older phones the version you have keeps working, but it won't get updates.

## v0.1.5 (2026-11-05)

Floating windows from other apps can no longer cover the lock screen, PIN screens or confirmations, and files shared to MailCtrl must really belong to the app that shares them.

### Security

- **No floating windows over the lock screen, PIN screens and confirmations.** While the lock screen, choosing or changing your PIN, turning the lock off, **Forgot PIN?**, or a confirmation is showing, other apps' floating windows, such as chat heads and screen overlays, are hidden. A malicious app can then no longer lay something over a button to trick you into tapping it. This covers deleting a message, discarding a draft, emptying a folder, removing an account and trusting a server's certificate. Floating windows show again as soon as that screen closes; reading and writing mail are never affected. This uses a new permission, "Hide other apps' floating windows", which Android grants at install without asking.
- **Lock and PIN screens are hidden from apps that misuse accessibility** (Android 14 and newer). Only real accessibility tools, such as TalkBack and other screen readers, can read the lock screen and PIN pad; other apps with accessibility access can't see what's on them. Screen readers work exactly as before.
- **Shared files must belong to the app that shares them** (Android 15 and newer). When another app shares a file to MailCtrl, Android now checks that the sharing app can read that file itself, so no app can make MailCtrl attach something it isn't allowed to see. Sharing from your gallery, file manager and other apps works as before.
- **Stricter handling of requests from other apps** (Android 16 and newer). Other apps can only open MailCtrl the way it expects: the home-screen icon and its **Write** shortcut, mailto: links, sharing and ntfy's wake-ups keep working, and anything else is refused by Android.

### Project

- **Every library and Gradle plugin download is checked.** The build now refuses any library or plugin whose SHA-256 checksum doesn't match the list in the repo, so a changed or swapped download can't end up in the app.
- README: a new **Check a download** section (the signing-key fingerprint, the SHA-256 in the release notes and `gh attestation verify`), the new permission in the permissions table, and how to update the checksum list when a dependency changes.

## Project updates (2026-11-05)

### Project

- **Releases can be checked against this repo.** Each release now carries a signed build provenance attestation (made by GitHub with Sigstore) that ties the APK to this repo's build workflow and the tagged commit, and the release notes list the APK's SHA-256 checksum.
- **Tighter build workflows.** Every workflow starts with no permissions and each job gets only what it needs; only the release job can write, and only to publish the release and its attestation.
- **New security checks on GitHub:** zizmor checks the workflows for security problems on every workflow change, and CodeQL scans the app's code every week and on every app change (results in the Security tab; it never blocks a release).
- **New manual workflow** that records the SHA-256 of every library and Gradle plugin the build downloads, so the build can refuse anything that has been changed or swapped. No app changes, so no new version.

## v0.1.4 (2026-11-05)

Other apps can no longer open your messages through MailCtrl, and with the app lock on, notifications no longer show your mail unless you want them to.

### Security

- **Only MailCtrl's own notifications can open a message.** Tapping a new-mail notification opens that message. Before, any other app on the phone could ask MailCtrl to open a message the same way, by account, folder and message number. MailCtrl's notifications now carry a secret that's made once on your phone, and a request to open a message without it just opens the inbox. Notifications open their message as before, and the **Compose** shortcut on the app icon, mailto: links and sharing to MailCtrl work as before.

### Changed

- **With the app lock on, notifications just say "New mail" by default.** Before, new-mail notifications showed the sender and subject even while MailCtrl was locked, so anyone holding the unlocked phone could read who wrote to you and about what without the PIN. Now, if you never picked a **Notification content** yourself, notifications only say "New mail" while the app lock is on (and still show the sender and subject when it's off). If you did pick one under **Settings > Privacy and security > Notification content**, MailCtrl keeps using your choice. If you had picked "Sender only" or "Just New mail" before this update, that choice is kept; if you want sender and subject while the app lock is on, pick **Sender and subject** there.

## v0.1.3 (2026-11-05)

An unused permission is gone, release builds no longer keep library debug logging, and there's now a security policy and a full permission list.

### Privacy

- **The foreground-service permission is gone.** Android's background work library added it, but MailCtrl's mail checks never run as a foreground service, so Android no longer lists it for MailCtrl.
- **No debug logging in release builds.** Debug, verbose and info messages from the libraries MailCtrl uses are now stripped from the app, so nothing about your accounts or mail can end up in the phone's system log. MailCtrl's own code already logged nothing.
- **One less entry point for other apps.** A receiver the Android build tools add for developer tooling (the profile installer) is removed, as in the other Ctrl apps. Only a connected computer could reach it, so nothing changes for you.
- The **Ignore battery optimisations** permission stays: it's only used by the **Battery** row under **Notifications**, which asks Android to let MailCtrl run unrestricted when you tap it. The README now explains it.

### Project

- New **SECURITY.md**: how to report a security problem privately, what counts, how MailCtrl protects you, and the fingerprint of the key every release is signed with.
- The build now checks that the Gradle wrapper is an official Gradle release, and fails if any Google Play services, Firebase, ML Kit, Play Core or Tink library ever ends up in the app.
- `.gitignore` also covers signing-key property files, `.p12` keys, `local.properties` in any folder and `google-services.json`, so they can't be committed by accident.
- README: a new **Permissions** section with every permission and what it's for, the links the app opens and the message viewer's protections under Privacy, a pointer to SECURITY.md, and the Ctrl series list now has all 28 apps with their current names (ElementCtrl is now ChatCtrl).

## v0.1.2 (2026-10-27)

A shortcut to make an app password while adding an account.

### New

- **Make an app password.** When you add a Gmail, Yahoo or iCloud address, a **Make an app password** button appears under the password field. It opens that provider's app-password page in your browser, so you can make one and paste it straight back into MailCtrl. MailCtrl itself never visits the page; it only opens when you tap the button.

## v0.1.1 (2026-10-27)

Clearer sign-in errors, and Gmail app passwords work however you paste them.

### Fixed

- **Gmail app passwords pasted with spaces.** The app password is shown in four groups ("abcd efgh ijkl mnop"); the spaces are now dropped when signing in, so it works pasted either way.

### Changed

- **Sign-in errors say what the server said.** When a server refuses the password, MailCtrl now shows its own explanation under the error, so you can see whether the password is wrong or something else is needed.
- **"Needs an app password"** is shown when the server says your normal password can't be used here, with what to do: turn on two-step sign-in, make an app password and paste that instead.
- **"Confirm in a web browser"** is shown when the provider blocked the sign-in until you approve it on the web.

## v0.1.0 (2026-10-27)

The first release: an email app in the spirit of Thunderbird, talking straight to your own IMAP and SMTP servers, private and Google-free.

### New

- **Accounts.** Add as many mail accounts as you like. A known address only needs its password (or app password); for other addresses MailCtrl guesses the servers from your domain and, if that doesn't work, shows the server settings (address, port, SSL/TLS, STARTTLS or none, username) to check. Both servers are signed in to before anything is saved.
- **Proton Mail through hydroxide or Proton Mail Bridge.** A Proton address asks for the address of your own hydroxide or Bridge server and fills in its ports (1143 and 1025). Use it over a VPN; "Put sent mail in Sent" is off, because Proton files sent mail itself.
- **Trust a self-signed certificate.** When a server's certificate isn't trusted by the phone (Bridge's, or a private authority's), MailCtrl shows its fingerprint, who it's for and until when it's valid, and lets you trust exactly that certificate for the account.
- **Inbox tab.** Every account's inbox together, newest first, with a small label showing which account each message is for, or one account at a time with the chips at the top. Pull down to refresh; older mail loads as you scroll. Mark everything as read with one button.
- **Folders tab.** Every account with all its folders and their unread counts, nested as on the server. Sent, Drafts, Archive (or All Mail), Junk and Trash are recognised by themselves and shown first. Trash and Junk can be emptied.
- **Search tab.** Searches on the server, in the subject, sender, recipients and text: in the inbox, Sent, Archive or every folder of an account.
- **Reading messages.** Subject, sender, date and (tap the sender) every recipient, then the message. HTML messages show in a locked-down viewer with no scripts; plain-text messages show as text with tappable links and addresses. Every link asks first and shows where it really goes.
- **Pictures from the internet blocked by default.** Tap **Load** to show them for one message, or choose **Always load** in Settings. Pictures sent inside the message always show.
- **Attachments.** Open them in another app, share them, or save them anywhere with Android's file picker.
- **Write, reply, reply all and forward.** Choose which account to write from, add Cc and Bcc, attach files from any app, and keep the original attachments when forwarding. Replies quote the original and thread properly in other mail apps; the original is marked as answered once sent.
- **Signatures**, per account, added to new messages, replies and forwards.
- **Sending in the background.** Leaving the screen doesn't stop a message from going out. If sending fails, you're told why (also with a notification) and the message is kept as a draft.
- **Drafts on this phone.** Leaving a message you started keeps it as a draft, encrypted on the phone. Open drafts from the Folders tab or your profile picture. (Attachments aren't kept in drafts.)
- **Organise mail.** Archive, delete (to Trash), flag, mark read or unread, move to any folder or to Junk, from an open message or for several at once after a long press in a list.
- **Swipe actions.** Choose what swiping a message left or right does: archive, delete, read or unread, flag, or nothing. Archive and Delete show an **Undo** bar for a few seconds.
- **Unsubscribe** from newsletters that offer it, from the message's menu.
- **New-mail notifications**, one per message and grouped per account, with **Mark as read** and **Delete**. MailCtrl checks your inboxes itself every 15, 30 or 60 minutes or every 3 hours; per account they can be turned off.
- **Instant notifications with ntfy.** Turn on **Instant with ntfy** and give its push address to whatever watches your mailbox (for example goimapnotify next to hydroxide); MailCtrl is woken the moment mail arrives and checks straight away. A test button sends a wake-up to check it works.
- **Notification content**: sender and subject, the sender only, or just "New mail".
- **mailto: links and Share to MailCtrl** from any app, with recipients, subject, text and files filled in. A **Write** shortcut on the launcher icon.
- **App lock** with its own PIN and optional fingerprint unlock, shared with every Ctrl app, plus **Hide in screenshots** and **Hide in recent apps** switches.
- The **Ctrl / Signal** look in **dark and light**, following your phone's setting, with a side rail on tablets.

### Security

- Passwords are encrypted with a key in the phone's secure hardware. With the app lock on and notifications off they're also sealed with the app PIN; drafts are always sealed with the PIN while the app lock is on.
- Encrypted connections are checked like a browser does (trusted certificate, made out to the server's name); STARTTLS is required when chosen, never silently skipped.
- The message viewer runs no JavaScript, opens no frames, forms or files, never navigates by itself, never loads anything from the internet unless you allow pictures, and never looks links up online.
- MailCtrl greets mail servers with a neutral address instead of the phone's name, only signs in with plain password methods (never NTLM, which would leak the phone's name), and makes Message-IDs without the app's name in them.
- The sign-in and server screens are always kept out of screenshots, and passwords are never shown again after saving.

### Privacy

- Telemetry-free: no analytics, crash reporting or tracking. MailCtrl only connects to your own mail servers (and your own ntfy address when you test it).
- No automatic backups of MailCtrl's data; your mail stays on your servers.
