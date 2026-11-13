# MailCtrl

**Your email, private and Google-free.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/MailCtrl?label=download)](https://github.com/VoidedKN0X/MailCtrl/releases/latest) ![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) ![Telemetry-free](https://img.shields.io/badge/telemetry-none-brightgreen) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

MailCtrl is an email app for Android, in the spirit of Thunderbird: several accounts, folders, search and attachments, talking straight to your own mail servers over IMAP and SMTP. No MailCtrl server sits in between, nothing is tracked, and nothing comes from Google.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/MailCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a> <a href="https://voidedkn0x.github.io/CtrlF-Droid/"><img src=".github/assets/badge_ctrlf-droid.png" alt="Get it from our F-Droid repo" height="80"></a>

## Features

- **Several accounts**, each with its own name, signature and notifications
- **Combined inbox** for every account together, or one account at a time
- **All your folders**, with unread counts; Sent, Drafts, Archive, Junk and Trash are found by themselves
- **Read mail safely**: HTML messages are shown without scripts, and pictures from the internet stay blocked until you tap **Load** (see [Reading mail](#reading-mail))
- **Write, reply, reply all and forward**, with Cc and Bcc, attachments from any app, and the original attachments when forwarding
- **Drafts** kept on the phone, encrypted, so a message you started is never lost
- **Attachments**: open them in another app, share them or save them wherever you like
- **Archive, delete, flag, mark read or unread and move** messages; select several at once with a long press
- **Swipe actions** you choose for left and right, with **Undo** for archive and delete
- **Search on the server**: in the inbox, Sent, Archive or every folder, so it finds all your mail, not just what's on the phone
- **New-mail notifications** with **Mark as read** and **Delete**, checked in the background and **instantly with ntfy** (see [Notifications](#notifications))
- **Any IMAP provider**: known addresses only need a password; anything else takes a server address
- **Proton Mail** through hydroxide or Proton Mail Bridge on your own server (see [Proton Mail](#proton-mail))
- **Self-signed certificates** you choose to trust, matched by their exact fingerprint
- **mailto: links** and **Share to MailCtrl** from any app
- **App lock** with its own PIN and optional fingerprint unlock (see [App lock](#app-lock))
- **Hide in screenshots** and **Hide in recent apps**, two separate switches, on their own or with the app lock
- The **Ctrl / Signal** look shared by every Ctrl app: high-contrast **dark and light** modes that follow your phone's setting

## Screenshots

Coming soon.

## Requirements

- Android 15 or newer, on a 64-bit ARM phone (nearly every phone from the last several years)
- A mail account with IMAP and SMTP access
- **Google-free and de-Googled:** no Google Play services, Firebase or other Google services, and the app never contacts Google. It works the same on de-Googled phones (GrapheneOS, CalyxOS, /e/OS, LineageOS without Google apps).

## Install

1. Download the latest `.apk` file from the [Releases page](https://github.com/VoidedKN0X/MailCtrl/releases/latest).
2. Open it on your phone. Android may ask you to allow installing apps from your browser or file manager.
3. Updates install over the old version. Your settings are kept.

**Automatic updates:** on your phone, tap one of the buttons below.

- **Obtainium:** install [Obtainium](https://github.com/ImranR98/Obtainium) first. The button adds MailCtrl and installs every new release for you.
- **F-Droid:** adds the [CtrlF-Droid repo](https://voidedkn0x.github.io/CtrlF-Droid/) to F-Droid, Droid-ify or Neo Store, with every Ctrl app and automatic updates.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/MailCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a> <a href="https://voidedkn0x.github.io/CtrlF-Droid/"><img src=".github/assets/badge_ctrlf-droid.png" alt="Get it from our F-Droid repo" height="80"></a>

If the Obtainium button doesn't open the app, add `https://github.com/VoidedKN0X/MailCtrl` in Obtainium by hand.

## Getting started

1. Open MailCtrl, type your address and password, and tap **Sign in**. MailCtrl checks both servers before saving anything.
2. For addresses it knows, that's all. For others it guesses the servers (`imap.` and `smtp.` plus your domain); if that doesn't work, it shows the server settings to check or fill in.
3. Add more accounts under your profile picture › **Add account**, or at the bottom of the Folders tab.

Settings, notifications and your accounts are behind the profile picture at the top right.

### App passwords

Most big providers don't let mail apps in with your normal password once two-step sign-in is on. Make an **app password** in your account's security settings and use that in MailCtrl instead. MailCtrl only signs in with a password over IMAP and SMTP; there's no sign-in through a web page, so nothing from the provider's own sign-in system ends up in the app.

- **Gmail:** turn on 2-Step Verification, then create an app password at myaccount.google.com › Security › App passwords (the **Make an app password** button under the password field opens that page). MailCtrl fills in the servers by itself.
- **Yahoo, iCloud, Fastmail, GMX and others:** the same idea, under their own security settings. For Yahoo and iCloud the button opens their page too.

### Proton Mail

Proton Mail has no public way for mail apps to read your mailbox, so MailCtrl reads it through a small server that translates Proton into normal IMAP and SMTP, running on your own machine:

- **[hydroxide](https://github.com/emersion/hydroxide)** (open source, works with free Proton accounts), or
- **Proton Mail Bridge** (Proton's own, needs a paid plan; it runs headless in Docker, for example `shenxn/protonmail-bridge`).

Then:

1. Run hydroxide or Bridge on your server and sign it in to Proton. It gives you a **bridge password**.
2. Make that server reachable from your phone over a **VPN** (Tailscale or WireGuard), not by opening ports to the internet.
3. In MailCtrl, type your Proton address. MailCtrl asks for the server's address (its LAN or VPN address) and fills in ports **1143** (IMAP) and **1025** (SMTP).
4. Use the **bridge password**, not your Proton password.
5. hydroxide has no encryption of its own: leave security on **None** (your VPN encrypts the connection). Bridge uses STARTTLS with its own certificate: choose **STARTTLS**, and MailCtrl asks whether to trust Bridge's certificate.

**Put sent mail in Sent** is off for Proton, because Proton files sent mail itself.

## Reading mail

- **HTML messages** are shown in a locked-down viewer: no JavaScript, no frames, no forms, no access to files on the phone, and a strict content policy on top. Links never open by themselves: tapping one shows where it really goes and asks before opening it in your browser.
- **Pictures from the internet** are blocked, because loading them tells the sender when and where you opened the message (that's how tracking pixels work). Tap **Load** to show them for that message, or choose **Always load** in Settings. Pictures sent inside the message always show.
- **Plain-text messages** show as text, with links and addresses you can tap (they ask first too).
- **Unsubscribe**: when a newsletter offers it, it's in the message's menu.
- MailCtrl keeps a **list of your messages** on the phone (sender, subject, date, flags) so it opens instantly, and refreshes it by downloading only what changed. The messages themselves are fetched from your server when you open them; the few you opened lately (and the newest unread ones, read ahead) are kept **in memory only** so they open again instantly, and forgotten when MailCtrl locks or closes.

## Notifications

MailCtrl checks your inboxes itself, by default every 15 minutes, and notifies you about new mail. There's no push service from Google or anyone else.

For **instant notifications**, MailCtrl can be woken through the **[ntfy](https://ntfy.sh) app** (UnifiedPush):

1. Install ntfy on your phone (from F-Droid) and set its Battery to Unrestricted.
2. In MailCtrl, open your profile picture › **Notifications** and turn on **Instant with ntfy**. Copy the **push address** it shows.
3. Have something on your server watch your mailbox and post to that address when mail arrives. For example, **[goimapnotify](https://gitlab.com/shackra/goimapnotify)** next to hydroxide or Bridge:

   ```yaml
   configurations:
     - host: 127.0.0.1
       port: 1143
       tls: false
       username: you@proton.me
       password: your-bridge-password
       boxes:
         - mailbox: INBOX
           onNewMail: 'curl -s -d wake https://ntfy.example.org/upYourTopic'
   ```

   (Check goimapnotify's own documentation for the exact format of your version.)

A wake-up carries no mail at all: MailCtrl ignores what's in it and checks your servers itself, so the push address can't be used to show you fake mail. Background checks keep running as a fallback.

**Notification content** (under Settings) chooses what notifications show: sender and subject, the sender only, or just "New mail". Until you pick one yourself, notifications show the sender and subject, or just "New mail" while the [app lock](#app-lock) is on, so a locked MailCtrl doesn't show your mail in the notification shade. Once you pick one, your choice is used whether the app lock is on or off. The lock screen only shows "New mail" unless Android is set to show everything there.

Tapping a notification opens that message. Other apps can't use this to open a message: only MailCtrl's own notifications can, and anything else just opens the inbox.

## App lock

Turn it on under your profile picture › **Settings > Privacy and security > App lock**. It's off until you turn it on.

- **Its own app PIN** of at least 6 digits, separate from your phone's screen lock. Very obvious PINs such as 111111 or 123456 are refused.
- **Unlock with fingerprint** (optional) only accepts strong biometrics, such as the fingerprint sensor, checked by the phone's secure hardware. The PIN is still needed after the phone restarts and at least every 72 hours, and adding a new fingerprint to the phone turns fingerprint unlock off until you turn it on again.
- **Drafts are encrypted with the PIN while MailCtrl is locked.** The key only exists in memory while the app is unlocked and is wiped the moment it locks. The PIN itself is never stored: it's stretched with 600,000 rounds of PBKDF2 and mixed with a key held in the phone's secure hardware.
- **Passwords:** with notifications **off**, your account passwords are sealed with the PIN too, so nothing can sign in to your mail while MailCtrl is locked. With notifications **on**, background checks need them while the app is locked, so they're encrypted with the phone's secure hardware only (as without the app lock). Settings shows which applies.
- **Wrong PINs:** after 5 tries you wait 30 seconds, doubling up to 15 minutes. Restarting the app or the phone, or changing the clock, doesn't skip the wait.
- **Notifications** just say "New mail" while the app lock is on, unless you chose a **Notification content** yourself (see [Notifications](#notifications)).
- **Lock again:** as soon as you leave the app (the default), after 1, 5 or 15 minutes or 1 hour away, or only when the app restarts. Picking files to attach, sharing and opening an attachment don't count as leaving, unless you stay away for more than 5 minutes.
- **Forgot your PIN?** It can't be recovered. The lock screen lets you reset the app lock after confirming it's you with your phone's own screen lock; drafts encrypted with the old PIN are deleted, and accounts whose password was sealed with it ask for it again. Your mail stays on your servers.
- **With the app lock on**, the recent-apps screen always shows a blank card.

Two more switches in the same section work with or without the app lock, and are both off until you turn them on:

- **Hide in screenshots**: screenshots and screen recordings show a blank screen (and so does the recent-apps view).
- **Hide in recent apps**: the recent-apps view shows a blank card instead of MailCtrl. Screenshots still work.

The lock, PIN and sign-in screens are always kept out of screenshots and recordings, whatever these switches say.

**Nothing can be drawn over the lock screen or PIN pad.** While the lock screen, a PIN screen or a confirmation (delete a message, discard a draft, empty a folder, remove an account, trust a certificate, **Forgot PIN?**) is showing, other apps' floating windows such as chat heads are hidden, so they can't cover a button to trick you into tapping it. Apps that aren't real accessibility tools also can't read the lock and PIN screens; screen readers such as TalkBack still work. Floating windows show again as soon as those screens close.

## Check a download

Every release is built from this repo by [the build workflow](.github/workflows/build.yml) on GitHub Actions and signed with the same release key. Android refuses an update signed with a different key, and the CtrlF-Droid repo only accepts APKs signed with this one. Its SHA-256 certificate fingerprint is:

```
E8:0F:B6:89:51:93:44:E1:DB:C9:67:55:55:E4:92:6D:8F:78:1A:D9:CC:48:0C:B9:25:83:5B:4D:F4:9F:50:65
```

Advanced users can compare it with [AppVerifier](https://github.com/soupslurpr/AppVerifier).

- The APK's SHA-256 checksum is at the bottom of each release's notes. Compare it with `sha256sum MailCtrl-vX.Y.Z.apk` (or `Get-FileHash` on Windows).
- Each release also has a signed build provenance attestation. With the [GitHub CLI](https://cli.github.com/), `gh attestation verify MailCtrl-vX.Y.Z.apk -R VoidedKN0X/MailCtrl` proves the APK was built by this repo's build workflow from the tagged commit, not uploaded by hand.

## Permissions

Every permission MailCtrl has, and what it's for:

| Permission | What MailCtrl uses it for |
| --- | --- |
| Internet (`INTERNET`) | Connecting to your own mail servers (IMAP and SMTP), and to your own ntfy address when you tap **Send a test wake-up** |
| Network state (`ACCESS_NETWORK_STATE`) | Only running background mail checks when the phone is online |
| Notifications (`POST_NOTIFICATIONS`) | New-mail notifications; asked for when you turn notifications on |
| Run at startup (`RECEIVE_BOOT_COMPLETED`) | Setting the background mail checks up again after the phone restarts (through Android's WorkManager) |
| Prevent phone from sleeping (`WAKE_LOCK`) | Keeping the phone awake for the few seconds a mail check takes (added by WorkManager) |
| Ignore battery optimisations (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | The **Battery** row under profile picture › **Notifications**: when you tap it, Android asks whether MailCtrl may run unrestricted, so checks aren't delayed while the phone sleeps. Nothing changes unless you tap it and say yes |
| Biometrics (`USE_BIOMETRIC`) | Optional fingerprint unlock for the app lock |
| Hide other apps' floating windows (`HIDE_OVERLAY_WINDOWS`) | Hide other apps' floating windows over the PIN screen, the lock screen and confirmations, so nothing can be drawn over them to trick a tap |

Android also lists a "dynamic receiver" permission (`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) that AndroidX adds to every app so only MailCtrl itself can send it internal broadcasts; it gives the app no access to anything. MailCtrl has no contacts, location, camera, microphone, storage or phone permissions: attachments come in through Android's file picker or "Share to MailCtrl".

## Privacy

**Telemetry-free.** MailCtrl has no analytics, crash reporting, usage stats or tracking of any kind.

- **Only your own servers.** MailCtrl connects to the IMAP and SMTP servers of your accounts and nothing else (and, when you test it, your own ntfy address). There's no MailCtrl server, and no online look-up of server settings: known providers are a list inside the app.
- **Passwords** are encrypted with a key in the phone's secure hardware (its separate security chip, StrongBox, when the phone has one) and only ever sent to your own mail servers. They're never shown again after you save them.
- **Gmail** goes through Google's standard mail servers only (imap.gmail.com and smtp.gmail.com) with an app password: no Google app, library or sign-in service is built in.
- **Encrypted connections** (SSL/TLS or STARTTLS) are checked like a browser does. A server whose certificate the phone doesn't trust is refused unless you trust that exact certificate yourself. STARTTLS is required, never optional: a server that suddenly stops offering it is refused rather than used unencrypted. "None" is only for servers on your own network or over a VPN.
- **No tracking pixels:** pictures from the internet stay blocked unless you load them, and the message viewer never looks links up online.
- **The phone's name stays private:** MailCtrl greets mail servers with a neutral address instead of your phone's name, makes Message-IDs without the app's name in them, and adds no "sent with" header.
- **On the phone:** your accounts, settings, drafts (encrypted) and the list of your messages. Downloaded attachments are cleared when MailCtrl starts again. Messages you opened lately are kept in memory only, never written to storage, and forgotten when MailCtrl locks or closes.
- **Links:** the **Make an app password** button opens your provider's own page (myaccount.google.com, login.yahoo.com or account.apple.com) in your browser, the **Install ntfy** button (when ntfy is missing) opens ntfy's page on f-droid.org, and the Support, Report a bug, Source code and Licence rows open GitHub or gnu.org. Only when you tap them; MailCtrl itself never contacts these sites.
- **Message viewer:** HTML mail is shown in a locked-down web view with no scripts, forms or frames and no online link checks or usage reports. Links in a message ask before they open in your browser.
- **No automatic backups:** Android doesn't back up MailCtrl's data or move it to a new phone. Your mail stays on your servers; on a new phone you just sign in again.

## Build from source

You need [Android Studio](https://developer.android.com/studio), or JDK 17 and the Android SDK.

1. Clone the repo:
   ```bash
   git clone https://github.com/VoidedKN0X/MailCtrl.git
   cd MailCtrl
   ```
2. Build the app:
   ```bash
   ./gradlew assembleDebug
   ```
   On Windows, use `gradlew.bat assembleDebug`.
3. The APK is in `app/build/outputs/apk/debug/`. Copy it to your phone and install it.

Or open the folder in Android Studio and press **Run** with your phone connected.

Every library and Gradle plugin download is checked against the SHA-256 checksums in `gradle/verification-metadata.xml`; the build stops if anything doesn't match. When a dependency or plugin changes, run the **Dependency verification metadata** workflow (Actions tab), check the diff of the file it prints, and commit the new file in the same push. On Windows or macOS, Android's `aapt2` tool is a different download that isn't listed, so add `--dependency-verification=lenient` to local builds there.

## Support the project

MailCtrl is free and open source. If it's useful to you, tap **Support MailCtrl** on the You page (tap your profile picture) to sponsor development on [GitHub Sponsors](https://github.com/sponsors/VoidedKN0X). Found a problem or have an idea? Tap **Report a bug** in the same place, or [open an issue](https://github.com/VoidedKN0X/MailCtrl/issues). Starring the repo helps too.

Found a security problem? Please don't open a public issue; see [SECURITY.md](SECURITY.md).

## The Ctrl series

Native Android apps for self-hosted services and everyday tools, all with the same Material You design.

| App | What it's for |
| --- | --- |
| [AdCtrl](https://github.com/VoidedKN0X/AdCtrl) | Control your AdGuard Home DNS server |
| [ArrCtrl](https://github.com/VoidedKN0X/ArrCtrl) | Radarr, Sonarr, Prowlarr, Bazarr, qBittorrent, autobrr and Cleanuparr in one app |
| [BeamCtrl](https://github.com/VoidedKN0X/BeamCtrl) | TV remote for phones with an IR blaster |
| [CallCtrl](https://github.com/VoidedKN0X/CallCtrl) | Phone app: dialer, call history and in-call screen |
| [CameraCtrl](https://github.com/VoidedKN0X/CameraCtrl) | Private camera with RAW (DNG) on every lens |
| [ChatCtrl](https://github.com/VoidedKN0X/ChatCtrl) | Matrix chat client for matrix.org and self-hosted Synapse |
| [ClockCtrl](https://github.com/VoidedKN0X/ClockCtrl) | Alarms, timers, stopwatch and world clock |
| [ContactCtrl](https://github.com/VoidedKN0X/ContactCtrl) | Contacts: browse, edit, import and export |
| [CraftyCtrl](https://github.com/VoidedKN0X/CraftyCtrl) | Manage Minecraft servers on Crafty Controller |
| [DateCtrl](https://github.com/VoidedKN0X/DateCtrl) | Calendar for the calendars on your phone and your accounts |
| [FilesCtrl](https://github.com/VoidedKN0X/FilesCtrl) | File manager with NFS, SMB, SFTP, FTP and WebDAV |
| [FrigateCtrl](https://github.com/VoidedKN0X/FrigateCtrl) | Watch and review your Frigate NVR cameras |
| [GalleryCtrl](https://github.com/VoidedKN0X/GalleryCtrl) | Private, offline gallery with an encrypted vault |
| [ImmichCtrl](https://github.com/VoidedKN0X/ImmichCtrl) | Photos and backup for your Immich server |
| [JellyCtrl](https://github.com/VoidedKN0X/JellyCtrl) | Movies, shows, music and Live TV from Jellyfin |
| [LijnCtrl](https://github.com/VoidedKN0X/LijnCtrl) | Live De Lijn bus and tram departures |
| [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl) | Private, offline photo and video editor |
| **MailCtrl** (this app) | Email for Gmail and Proton Mail |
| [MapsCtrl](https://github.com/VoidedKN0X/MapsCtrl) | Private, offline maps and navigation |
| [MemoCtrl](https://github.com/VoidedKN0X/MemoCtrl) | Notes on your Memos server |
| [NaviCtrl](https://github.com/VoidedKN0X/NaviCtrl) | Music player for Navidrome servers |
| [PaperCtrl](https://github.com/VoidedKN0X/PaperCtrl) | Scan and browse documents on Paperless-ngx |
| [PrintCtrl](https://github.com/VoidedKN0X/PrintCtrl) | Bambu Lab printers through Bambuddy, and Spoolman spools |
| [ProxCtrl](https://github.com/VoidedKN0X/ProxCtrl) | Manage your Proxmox VE server |
| **RecorderCtrl** (this app) | Private, offline voice recorder |
| [RideCtrl](https://github.com/VoidedKN0X/RideCtrl) | Offline maintenance, fuel and mod log for your vehicles |
| [SeerrCtrl](https://github.com/VoidedKN0X/SeerrCtrl) | Request movies and shows on Seerr / Jellyseerr |
| [ShutterCtrl](https://github.com/VoidedKN0X/ShutterCtrl) | Remote control and photo transfer for Canon cameras |
| [SoundCtrl](https://github.com/VoidedKN0X/SoundCtrl) | JBL headphones and speakers, without the account |
| [SumCtrl](https://github.com/VoidedKN0X/SumCtrl) | Offline calculator, unit converter and work tools |
| [SynapseCtrl](https://github.com/VoidedKN0X/SynapseCtrl) | Administer your Matrix Synapse server |
| [TalkCtrl](https://github.com/VoidedKN0X/TalkCtrl) | Cardo intercom settings, radio and updates |
| [TapoCtrl](https://github.com/VoidedKN0X/TapoCtrl) | Tapo plugs and bulbs on your home network |
| [TextCtrl](https://github.com/VoidedKN0X/TextCtrl) | Private SMS and MMS messaging |
| [TrueCtrl](https://github.com/VoidedKN0X/TrueCtrl) | Monitor and manage your TrueNAS server |
| [WakeCtrl](https://github.com/VoidedKN0X/WakeCtrl) | Keep the screen awake, per app or while charging |
| [WrtCtrl](https://github.com/VoidedKN0X/WrtCtrl) | Manage your OpenWrt router |

## License

MailCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

MailCtrl uses JavaMail for Android (Eclipse Distribution License 1.0 / GPLv2 with Classpath Exception). The Space Grotesk and JetBrains Mono fonts are licensed under the SIL Open Font License 1.1; see `FONT-LICENSE-SpaceGrotesk.txt` and `FONT-LICENSE-JetBrainsMono.txt`.
