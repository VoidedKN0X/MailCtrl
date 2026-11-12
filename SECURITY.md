# Security policy

Thank you for helping keep MailCtrl and its users safe.

## Reporting a problem

**Please don't report security problems in a public issue.** Report them privately instead:

1. Go to the [Security tab](https://github.com/VoidedKN0X/MailCtrl/security) of this repo.
2. Click **Report a vulnerability** and describe what you found, how to reproduce it, and which version of MailCtrl you used.

Only the maintainer can see the report. I'll reply as soon as I can, work on a fix, and credit you in the changelog if you'd like.

## Supported versions

Only the latest release gets security fixes. Please update before reporting, and check the [changelog](CHANGELOG.md) to see whether it's already fixed.

## What counts

MailCtrl has no server of its own: it only talks to the mail servers of the accounts you add. So these count:

- Your mail passwords, app passwords or messages going anywhere other than your own mail servers, or to them without encryption
- MailCtrl accepting a server certificate it shouldn't, or loading anything from the internet when you open a message (such as tracking pixels or remote images you didn't allow)
- Ways around the app lock, such as messages, drafts or notifications showing while MailCtrl is locked, or protected screens showing up in screenshots when they shouldn't
- Another app on the phone being able to make MailCtrl send mail or do something else you didn't ask for (for example through "Share to MailCtrl", mailto: links or its push receiver), or to read its data
- Weaknesses in the app lock PIN
- Anything in a received message that lets someone run code or read files they shouldn't

Problems in Gmail, Proton Mail, hydroxide or other mail servers themselves should go to those projects.

## How the app protects you

- Passwords are encrypted with a key in the phone's secure hardware. With the app lock on and notifications off, they're also sealed with your app PIN, so a locked MailCtrl can't read them. Unsent drafts are sealed with the PIN whenever the app lock is on.
- Certificates are checked like a browser does: the phone must trust them and they must name the server. A self-signed certificate is only accepted after you've compared and trusted its exact fingerprint. STARTTLS is required when chosen, never optional.
- HTML messages are shown without scripts, forms or frames, and nothing is loaded from the internet unless you tap to load pictures. Links ask before they open in your browser.
- Other apps can only fill in a new message (a mailto: link or "Share to MailCtrl"); nothing is sent until you tap Send, and another app can't make MailCtrl attach its own private files.
- Nothing is backed up or copied to a new phone, and there's no analytics, crash reporting or tracking of any kind.

## How releases are protected

Every release is built from this repo by GitHub Actions and signed with the same release key. Its SHA-256 certificate fingerprint is:

```
E8:0F:B6:89:51:93:44:E1:DB:C9:67:55:55:E4:92:6D:8F:78:1A:D9:CC:48:0C:B9:25:83:5B:4D:F4:9F:50:65
```

The [CtrlF-Droid repo](https://voidedkn0x.github.io/CtrlF-Droid/) only accepts APKs signed with this key.
