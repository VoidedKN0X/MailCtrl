# Changelog

What changed in each MailCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/MailCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

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
