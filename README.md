<p align="center">
  <img src="server/icons/icon-192.png" width="112" height="112" alt="FluidMessages icon">
</p>

<h1 align="center">FluidMessages</h1>

<p align="center">
  <strong>Your texts, calls, and chats in one private, beautiful app.</strong><br>
  Open source for Android, with a web version you can host yourself.
</p>

<p align="center">
  <a href="https://github.com/Lin8x/FluidMessages/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/Lin8x/FluidMessages?label=download&color=3bb85f"></a>
  <a href="LICENSE"><img alt="License: Apache-2.0" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Android 13 or newer" src="https://img.shields.io/badge/Android-13%2B-3ddc84">
  <a href="https://lin8x.github.io/FluidMessages/"><img alt="Website" src="https://img.shields.io/badge/website-lin8x.github.io-555"></a>
</p>

---

FluidMessages is a texting and calling app for Android. It puts regular texts (SMS and MMS) side by side with Matrix, XMPP, Email, and IRC in one inbox. It also has a Phone tab for calls over your phone number, Matrix, XMPP, SIP, or a simple meeting link. FluidMessages Web brings the same inbox to any browser or computer, from a server you run.

## Highlights

- **One inbox for every service:** texts, group texts, Matrix, XMPP, Email, and IRC, each with a clear tag. Replies, reactions, photos, files, voice messages, locations, and money requests work on every service.
- **Calls:** phone calls, end-to-end encrypted Matrix and XMPP calls, SIP, and meeting links, from a Phone tab laid out like the iPhone's. Calls can be recorded and saved to any folder you choose.
- **Privacy you can see:** a small lock on every chat shows who can read it. You also get:
  - app lock
  - chat locks
  - a private folder
  - decoy mode
  - disappearing messages
  - an emergency erase passcode
  - encrypted storage on your phone
- **Encrypted texting:** encrypt regular texts with someone who also uses FluidMessages.
- **More than text:** GIFs and stickers, polls, shareable calendar events, chess games, link previews for videos and songs, and money requests by Monero, Bitcoin, Lightning, PayPal, bank transfer, and more. Each one is plain text or a picture underneath, so it still works with people on other apps.
- **Backups you control:** encrypted backups to your phone, your own server (SFTP, WebDAV such as Nextcloud, FTPS, or FTP, on any port), or OneDrive, Google Drive, and S3, on a schedule or whenever you like.
- **Make it yours:** themes, wallpapers, bubble shapes, motion, and sounds, with a sample inbox to practice in.
- **Works everywhere:** the Android app, a web version you can install as an app (a PWA), and desktop apps for Windows, macOS, and Linux.
- **Yours to run:** no ads, no tracking, no account with us. Host FluidMessages Web with Docker, Docker Compose, or Portainer.

## Download

| Platform | Get it | Notes |
|---|---|---|
| **Android** 13 or newer | [`FluidMessages-<version>.apk`](https://github.com/Lin8x/FluidMessages/releases/latest) | Open the file on your phone to install. Updates are offered in the app. |
| **Windows** | [`FluidMessages-Web-<version>-Windows.exe`](https://github.com/Lin8x/FluidMessages/releases/latest) | FluidMessages Web as a desktop app. |
| **macOS** | [`FluidMessages-Web-<version>-macOS.dmg`](https://github.com/Lin8x/FluidMessages/releases/latest) | FluidMessages Web as a desktop app. |
| **Linux** | [`FluidMessages-Web-<version>-Linux-x86_64.AppImage`](https://github.com/Lin8x/FluidMessages/releases/latest) | Make it executable, then open it. |
| **Server** | `ghcr.io/lin8x/fluidmessages-web` | For Docker, Docker Compose, or Portainer. See [Self-hosting](docs/self-hosting.md). |

Everything is on the [Releases page](https://github.com/Lin8x/FluidMessages/releases/latest). The desktop apps aren't code-signed yet; see [Desktop apps](docs/self-hosting.md#desktop-apps) if your computer warns you.

## Get started on Android

1. Download the APK on your phone and open it. Android may ask you to allow installs from your browser or files app.
2. The setup guide walks you through colors, privacy, and the services you use. **Practice first** opens a sample inbox with pretend chats and calls.
3. In your inbox, tap **Set as default SMS app** to send and receive texts.
   - On Android 15, Android may block this for apps installed from a file. Go to **App info → ⋮ → Allow restricted settings**, then try again.
4. Optional: add Matrix, XMPP, Email, or IRC in **Settings → Chats and calls → Accounts**.

## FluidMessages Web

FluidMessages Web is the web version, like WhatsApp Web. It runs on your own server or computer. It keeps your other chat services connected, shows your synced texts, and sends texts through your phone.

**Quick start with Docker Compose:**

```sh
printf 'FLUID_TOKEN=%s\n' "$(openssl rand -hex 32)" > .env
docker compose up -d
```

Open http://localhost:8080 and sign in with the token from `.env`. To connect your phone, put FluidMessages Web behind HTTPS, then open **Settings → FluidMessages Web** on the phone.

The full guide is in **[Self-hosting](docs/self-hosting.md)**. It covers:

- Docker and Portainer
- HTTPS and pairing your phone
- the desktop apps
- installing it as an app (PWA)

## Documentation

- **[Features](docs/features.md):** everything the app can do, in detail.
- **[Self-hosting](docs/self-hosting.md):** Docker, Docker Compose, Portainer, HTTPS, the desktop apps, and the PWA.
- **[Development](docs/development.md):** building, testing on the emulator, local checks, and releasing.
- **[Contributing](CONTRIBUTING.md):** how to help.

## Privacy

FluidMessages has no ads, analytics, or accounts with us. Your messages stay on your phone and on the server you run.

- **Calls:** Matrix and XMPP calls are end-to-end encrypted.
- **Texts:** regular texts are only encrypted when both people use FluidMessages encryption.
- **Every chat:** the lock next to each chat's name shows how it's protected.

See [Privacy and security](docs/features.md#privacy-and-security) for the details.

## License

FluidMessages is open source under the [Apache License 2.0](LICENSE).
