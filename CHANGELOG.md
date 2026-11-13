# Changelog

What changed in each MailCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/MailCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

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
