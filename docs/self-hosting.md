# Self-hosting FluidMessages Web

FluidMessages Web is the web version of FluidMessages, like WhatsApp Web. Use it in a browser, as an installed app (a PWA), or as a desktop app. It runs on a server you control:

- **Messages from other services:** it keeps your Matrix, XMPP, Email, and IRC connections open, so your phone doesn't have to.
- **Texts:** it shows texts your phone syncs to it, and sends texts through your phone.
- **Without a phone:** it works on its own for everything except texts.

- [Docker Compose](#docker-compose)
- [Portainer](#portainer)
- [Signing in](#signing-in)
- [Desktop apps](#desktop-apps)
- [HTTPS and connecting your phone](#https-and-connecting-your-phone)
- [Using it](#using-it)
- [Install it as an app (PWA)](#install-it-as-an-app-pwa)

## Docker Compose

The server runs on x86-64 and ARM64 Docker hosts. Each release publishes a ready-made image, `ghcr.io/lin8x/fluidmessages-web`. From a copy of this repository:

```sh
docker compose up -d
docker compose logs fluidmessages
```

Open `http://localhost:8080` on the server (or your HTTPS address, below). The first visit asks for the **one-time setup code** from the log and lets you create your password. That's it: no token to make or type.

- **Build it yourself:** `docker compose up -d --build` builds from this checkout instead of downloading the image.
- **Your data:** the SQLite database, accounts, and files are in the `fluid-data` volume.
- **Updating:** run `docker compose pull`, then `docker compose up -d`.
- **Stopping:** `docker compose down` stops it. `docker compose down -v` also deletes your data.
- **Optional access token:** set `FLUID_TOKEN` in `.env` (for example `printf 'FLUID_TOKEN=%s\n' "$(openssl rand -hex 32)" > .env`) if you want a fixed token for your phone or scripts. Servers that already have one keep working as before, and the token also works for first-run setup.

Optional accounts and backup destinations can be set in `.env`. See the variables in [compose.yaml](../compose.yaml). Accounts set there show as read-only in the app.

## Portainer

1. In Portainer, open **Stacks → Add stack**.
2. Choose **Repository** and point it at this repository's `compose.yaml`. Or choose **Web editor** and paste [deploy/portainer-stack.yml](../deploy/portainer-stack.yml). No token is needed: the one-time setup code is in the container's log.
3. Select **Deploy the stack**.
4. Open the container's **Logs** for the one-time setup code, then open FluidMessages Web in your browser and create your password.

The image is published to the GitHub Container Registry with each release. If Portainer can't pull it, make sure the package's visibility is set to **Public** on GitHub, under the repository's **Packages**.

## Signing in

FluidMessages Web holds your messages and account passwords, so it always asks who you are, but you never have to copy a token around:

- **First visit:** you create a password. To make sure it's you and not a stranger who found the page, setup asks for the one-time setup code the server prints in its log (and keeps in its data volume as `setup-code`). The server's `FLUID_TOKEN`, if it has one, works there too. The code stops working as soon as a password exists.
- **After that:** sign in with your password. **Keep me signed in** keeps this browser signed in for 30 days; otherwise the sign-in ends when you close the browser, or after 12 hours without using it.
- **What's stored:** only a salted scrypt hash of your password (PBKDF2-SHA256 on Python builds without scrypt) and a SHA-256 of each sign-in. The browser gets an `HttpOnly`, `SameSite=Strict` session cookie, marked `Secure` over HTTPS, that page scripts can't read.
- **Guessing:** five wrong passwords or setup codes from one address make it wait 30 seconds, then longer, up to 15 minutes. Behind a reverse proxy on the same host or network, the address is the one your proxy puts last in `X-Forwarded-For`.
- **Other sites:** requests that change anything must come from FluidMessages Web's own page (the browser's `Sec-Fetch-Site` or `Origin`, plus a header other sites can't send), so a page elsewhere can't act for you.
- **Settings → Sign-in and security** changes your password (which signs out every other browser) or signs out other browsers.
- **Forgot your password?** Run `docker compose exec fluidmessages python main.py reset-password`. It removes the password and every browser sign-in and prints a new setup code. Messages and accounts stay. A server with `FLUID_TOKEN` can also sign in with **Use an access token instead** and set a new password in Settings.

Keep FluidMessages Web on HTTPS whenever it leaves your computer, so your password and messages are encrypted on the way.

## Desktop apps

Each release includes FluidMessages Web for **Windows** (`.exe`), **macOS** (`.dmg`), and **Linux** (`.AppImage`). Download them from [Releases](https://github.com/Lin8x/FluidMessages/releases/latest).

- **What it does:** runs FluidMessages Web on your computer, signs you in automatically, and opens it in its own window. A small window shows that it's running, with **Open** and **Quit**. It only listens on this computer, so there's no password to set.
- **Your data:** stays in a private folder for your user account.
- **Security warnings:** the apps aren't code-signed yet. On Windows, choose **More info → Run anyway**. On macOS, right-click the app and choose **Open**.

To run it from a copy of this repository instead:

- **Linux or macOS:**

  ```sh
  ./scripts/web-app
  ```

- **Windows:**

  ```powershell
  powershell -ExecutionPolicy Bypass -File scripts\web-app.ps1
  ```

These print an access token. On the first visit, enter it as the setup code (or choose **I have an access token**) and create a password.

## HTTPS and connecting your phone

The container is published on host loopback (`127.0.0.1:8080`). For a phone connection, put an HTTPS reverse proxy (Caddy, nginx, Traefik) in front of it, with a hostname whose certificate the phone trusts, and point your LAN DNS at the proxy. Both Android and your browser should use that HTTPS origin. Set the proxy to pass `X-Forwarded-Proto` and `X-Forwarded-For`, so sign-in cookies are marked `Secure` and guessing limits apply per visitor. If an older browser behind a proxy that rewrites the `Host` header can't save changes, set `FLUID_ORIGINS` to your public address, like `https://messages.example.org`.

1. In FluidMessages Web, open **Settings → Your phone → Connect your phone** and choose **Make a pairing token**. Copy it; it's shown once, and only a hash is kept. A server with `FLUID_TOKEN` can use that instead.
2. On Android, select **Settings → Web companion**. Tap **Set up companion** if the status is **Not set up**, then enter the HTTPS origin and the pairing token, check **Enable browser messaging**, and tap **Save connection**. Leave browser messaging off to pair for cloud backups alone.
3. **Make a new pairing token** replaces the old one, and **Remove pairing token** disconnects a phone that uses it.

## Using it

The inbox shows the newest 2,000 synced SMS messages along with Matrix, XMPP, Email, and IRC conversations.

- **Your phone's settings or the web app's own:** in **Settings → Your phone**, choose **My phone's settings (recommended)** to follow the phone's theme, colors (including each protocol's color), bubble shape, exact-time and tag preferences, and disappearing timers each time it syncs. Choose **This web app's own settings** to keep a separate look and timers here. Without a phone, the web app always uses its own. Blocked, archived, and pinned chats, and which protocols are on, always come from the phone.
- **Themes and appearance:** **Color theme** lists every named theme (Fluid, Midnight, Lavender, Evergreen, Tideline, Ember, Love, and Rosewater), each in light and dark, or matching your device. Picking a theme or **Reset to theme** restores its colors and bubble layout. **Customize colors**, **Message bubbles**, and **Inbox and times** fine-tune it. Colored bubbles always use white text, and bright colors are shaded so it stays readable.
- **Replying:** reply, react, forward, edit, or download an attachment from a message's **Actions** menu. Internet protocol replies and reactions use each protocol's native feature, and protocols turned off on the phone are read-only. **Enter** sends and **Shift+Enter** starts a new line (change it in **Settings → Messages, media, and links**).
- **The + menu:** **Money**, **Photo or file**, **Location**, **Contact**, **Poll**, **Event**, **Games**, **Dice and random**, **Send with effect**, and **GIF or sticker**. Each opens as its own page: the whole screen on a phone, a roomy panel on a computer, with **Back** at the top. Photos, files, and contact cards go through internet chats; to text a photo, send it from your phone.
- **Money:** pick how first (your usual method is one big button on top, then crypto, payment apps and banks, and your own), then fill in only what that method needs. **Settings → Payments** sets your usual method.
- **Calls:** **Call** on a phone-number conversation sends a call request to your phone, which shows a *Tap to call* notification that opens the dialer. Requests expire after two minutes. Android doesn't let apps carry carrier call audio, so the call itself happens on the phone. **Voice** and **Video** call Matrix chats from the browser, which needs FluidMessages Web opened over HTTPS or at `localhost`. To add a TURN relay besides the homeserver's, set `FLUID_ICE_SERVERS` to a JSON list of WebRTC ICE servers.
- **Send later:** choose a time under **Send later**. Texts wait for the phone, which uses the selected SIM on its next sync. Foreground sync checks every 20 seconds. Android background sync runs opportunistically with a 15-minute minimum interval and may be delayed by Doze or loss of network access. Internet messages send from FluidMessages Web at their time.
- **Without a phone:** **Settings → Accounts** adds Matrix, XMPP, Email, and IRC accounts, and **New chat** starts a conversation on any protocol. **Details** sets a chat's disappearing timer and invites or removes group members.

A queued job can be cancelled under **Scheduled** before the phone claims it. **Submitted** means the phone handed the message to Android's SMS API. Check the SMS thread for the sending result. **Claimed** can persist if sync was interrupted mid-send. Check the phone before scheduling a replacement. The server does not reclaim interrupted sends automatically, to avoid duplicate texts.

The server stores the synced SMS and scheduled messages in readable form so the browser can display them. Protect the server's data volume separately from encrypted Android backup files.

## Install it as an app (PWA)

FluidMessages Web installs as an app (a PWA) from Chrome, Edge, or Brave: use the **Install** button in the address bar. It has Calls, Messages, and Settings in a side rail, which turns into tabs at the bottom on a phone. The app caches only its own files, never your messages.

To try it on your own computer without Docker:

- **Linux or macOS:** run this. It starts on http://localhost:8080 and opens an app window.

  ```sh
  ./scripts/web-app
  ```

- **Windows:**

  ```powershell
  powershell -ExecutionPolicy Bypass -File scripts\web-app.ps1
  ```

Both print an access token, which you can enter as the setup code on the first visit, and keep your data in a private folder on the computer. `--full` (or `-Full` on Windows) also installs the optional XMPP and encrypted-Matrix support, and `--port` changes the port.
