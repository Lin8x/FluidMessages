# Self-hosting FluidMessages Web

FluidMessages Web is FluidMessages as an app for your computer. Use it in a browser, as an installed app (a PWA), or as a desktop app. It runs on a computer or server you control:

- **On its own:** Matrix, XMPP, Email, and IRC chats, calls, themes, effects, polls, games, and everything else in the app, with no phone needed. It keeps those connections open even when the page is closed.
- **With your phone, if you like:** FluidMessages for Android adds your texts, and can share its theme and timers. Connect it any time from **Settings → Phone and other devices**.

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

Open `http://localhost:8080` on the same computer. It opens straight into a short welcome (pick a look, add your chat accounts, and optionally connect your phone), with no password or code: the default Compose file publishes the port on `127.0.0.1` only and sets `FLUID_LOCAL_ONLY=1`, so only this computer can reach it.

- **Running Docker on another machine** (a home server or VPS)? Delete the `FLUID_LOCAL_ONLY` line from `compose.yaml` before you publish the port anywhere but `127.0.0.1`, then put HTTPS in front of it (below). Opened from another device before any password exists, it asks for the **one-time setup code** from `docker compose logs fluidmessages` and lets you choose a password.

- **Build it yourself:** `docker compose up -d --build` builds from this checkout instead of downloading the image.
- **Your data:** the SQLite database, accounts, and files are in the `fluid-data` volume.
- **Updating:** run `docker compose pull`, then `docker compose up -d`.
- **Stopping:** `docker compose down` stops it. `docker compose down -v` also deletes your data.
- **Optional access token:** set `FLUID_TOKEN` in `.env` (for example `printf 'FLUID_TOKEN=%s\n' "$(openssl rand -hex 32)" > .env`) if you want a fixed token for your phone or scripts. Servers that already have one keep working as before, and the token also works for first-run setup.

Optional accounts and backup destinations can be set in `.env`. See the variables in [compose.yaml](../compose.yaml). Accounts set there show as read-only in the app.

For an email account, `FLUID_EMAIL_IMAP_PORT` and `FLUID_EMAIL_SMTP_PORT` are optional and default to 993 and 465. Both use TLS from the start on those ports. IMAP on 143 and SMTP on any other port (such as 587) upgrade with STARTTLS, and the connection stops if the server doesn't offer it. FluidMessages never sends mail or passwords in plain text. Ports must be from 1 to 65535. The same **IMAP port** and **SMTP port** fields are in the email account form on the phone and on FluidMessages Web.

## Portainer

1. In Portainer, open **Stacks → Add stack**.
2. Choose **Repository** and point it at this repository's `compose.yaml`. Or choose **Web editor** and paste [deploy/portainer-stack.yml](../deploy/portainer-stack.yml).
3. If you'll open FluidMessages from a different computer than the Docker host, delete the `FLUID_LOCAL_ONLY` line and publish the port as you need it.
4. Select **Deploy the stack**, then open FluidMessages Web. On the Docker host it opens right away; from another computer, enter the one-time setup code from the container's **Logs** and choose a password.

The image is published to the GitHub Container Registry with each release. If Portainer can't pull it, make sure the package's visibility is set to **Public** on GitHub, under the repository's **Packages**.

## Signing in

FluidMessages Web holds your messages and account passwords, so it lets in only you, without making you copy tokens around.

- **On this computer:** it opens like any app, with no password. Only a request that really comes from the computer FluidMessages runs on counts: a loopback connection (or, with `FLUID_LOCAL_ONLY=1`, the Docker gateway), addressed to `localhost`, sent by FluidMessages' own page (its `Origin`), over HTTP/1.1, and carrying no reverse-proxy headers (`X-Forwarded-For`, `Forwarded`, `X-Real-IP`, `Via`, and the like). The `localhost` check also stops other websites from reaching it through DNS rebinding. This computer gets a session cookie like any browser. Turn it off in **Settings → Phone and other devices → This computer**.
- **Other devices** (a laptop, your phone's browser, anything through HTTPS) sign in with a password. You set it the moment you need it: **Settings → Phone and other devices → Other devices → Set up a password**, or as the first step of **Connect your phone**. Until then, only this computer can open it.
- **First visit from another device, before any password exists:** it asks for the one-time setup code the server prints in its log (and keeps in its data volume as `setup-code`), or the server's `FLUID_TOKEN`, so a stranger who finds the page can't claim it. The code stops working once a password exists.
- **Installs from before this:** a server that already has a password keeps asking for it everywhere, until you turn on **Open without a password on this computer** in Settings.
- **Keep me signed in** keeps a browser signed in for 30 days; otherwise the sign-in ends when you close the browser, or after 12 hours without using it.
- **What's stored:** only a salted scrypt hash of your password (PBKDF2-SHA256 on Python builds without scrypt) and a SHA-256 of each sign-in. Browsers get an `HttpOnly`, `SameSite=Strict` session cookie, marked `Secure` over HTTPS, that page scripts can't read.
- **Guessing:** five wrong passwords or setup codes from one address make it wait 30 seconds, then longer, up to 15 minutes. Behind a reverse proxy on the same host or network, the address is the one your proxy puts last in `X-Forwarded-For`.
- **Other sites:** requests that change anything must come from FluidMessages Web's own page (the browser's `Sec-Fetch-Site` or `Origin`, plus a header other sites can't send), so a page elsewhere can't act for you.
- **Settings → Phone and other devices** changes your password (which signs out every other browser), signs out other browsers, and connects or disconnects your phone.
- **Forgot your password?** Open FluidMessages on the computer it runs on and set a new one in Settings. Or run `docker compose exec fluidmessages python main.py reset-password`: it removes the password and every browser sign-in and prints a new setup code. Messages and accounts stay.

Keep FluidMessages Web on HTTPS whenever it leaves your computer, so your password and messages are encrypted on the way.

## Desktop apps

Each release includes FluidMessages Web for **Windows** (`.exe`), **macOS** (`.dmg`), and **Linux** (`.AppImage`). Download them from [Releases](https://github.com/Lin8x/FluidMessages/releases/latest).

- **What it does:** runs FluidMessages Web on your computer and opens it in its own window, straight into the welcome on first run. A small window shows that it's running, with **Open** and **Quit**. It only listens on this computer, so there's no password to set.
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

These open `http://localhost:8080` in an app window, with no password on this computer.

## HTTPS and connecting your phone

The container is published on host loopback (`127.0.0.1:8080`). For a phone connection, put an HTTPS reverse proxy (Caddy, nginx, Traefik) in front of it, with a hostname whose certificate the phone trusts, and point your LAN DNS at the proxy. Both Android and your browser should use that HTTPS origin.

- **The proxy must send `X-Forwarded-For`** (Caddy and Traefik do; in nginx add `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` and `proxy_set_header X-Forwarded-Proto $scheme;`). That's how FluidMessages knows a request came through the proxy rather than from this computer, and it also marks cookies `Secure` and keeps guessing limits per visitor.
- **Docker:** if anything other than this computer's own browser reaches the container (a proxy container on the same Docker network, or a port published beyond `127.0.0.1`), delete `FLUID_LOCAL_ONLY` from `compose.yaml`.
- If an older browser behind a proxy that rewrites the `Host` header can't save changes, set `FLUID_ORIGINS` to your public address, like `https://messages.example.org`.

1. In FluidMessages Web, open **Settings → Phone and other devices** and choose **Connect your phone**. Without a password yet, step 1 sets one (your phone's browser and other devices sign in with it). Then copy the pairing token; it's shown once, and only a hash is kept. A server with `FLUID_TOKEN` can use that instead.
2. On Android, select **Settings → Web companion**. Tap **Set up companion** if the status is **Not set up**, then enter the HTTPS origin and the pairing token, check **Enable browser messaging**, and tap **Save connection**. Leave browser messaging off to pair for cloud backups alone.
3. **Make a new pairing token** replaces the old one, and **Disconnect phone** removes the token a phone uses.

## Using it

The inbox shows the newest 2,000 synced SMS messages along with Matrix, XMPP, Email, and IRC conversations.

- **Your phone's settings or the web app's own:** once a phone has synced, **Settings → Phone and other devices** offers **My phone's (recommended)** to follow the phone's theme, colors (including each protocol's color), bubble shape, exact-time and tag preferences, and disappearing timers each time it syncs, or **This web app's own** to keep a separate look and timers here. Without a phone, the web app always uses its own. Blocked, archived, and pinned chats, and which protocols are on, always come from the phone; the Inbox, Archived, and Blocked filter appears once a phone has synced.
- **Fun and effects:** **Settings → Fun and effects** turns message effects, reaction animations, games, dice and random picks, GIFs and stickers, and link previews on or off for every browser on the server. Off, games and dice show as plain text and their entries leave the **+** menu. Animations also stay off when your device asks for reduced motion. These are the web app's own and never follow the phone.
- **Themes and appearance:** **Color theme** lists every named theme (Fluid, Midnight, Lavender, Evergreen, Tideline, Ember, Love, and Rosewater), each in light and dark, or matching your device. Picking a theme or **Reset to theme** restores its colors and bubble layout. **Customize colors**, **Message bubbles**, and **Inbox and times** fine-tune it. Colored bubbles always use white text, and bright colors are shaded so it stays readable.
- **Replying:** the **⋯** beside a message (shown on hover or keyboard focus) reacts, replies, forwards, edits, copies, or downloads. A new reaction, yours or someone else's, pops up from the bubble. Internet protocol replies and reactions use each protocol's native feature, and protocols turned off on the phone are read-only. **Enter** sends and **Shift+Enter** starts a new line (change it in **Settings → Messages, media, and links**).
- **The + menu:** **Share** (Photo or file, Location, Contact, Money), **Create** (Poll, Event), and **Fun** (Effect, GIF or sticker, Games, Dice and random). Each opens as its own page: the whole screen on a phone, a roomy panel on a computer, with **Back** at the top. Photos, files, and contact cards go through internet chats, so they're offered there; to text a photo, send it from your phone.
- **Effects:** pick **Effect** before or after typing. It waits above the message box as a chip (like "🎉 Sending with Confetti"): tap it to switch, **×** to drop it, and your next message goes with it.
- **Pictures and videos:** photos, GIFs, and stickers open full screen; videos play in the chat. Each has a **Download** button, in the chat and in the full-screen viewer, saving with the file's own name.
- **Money:** pick how first (your usual method is one big button on top, then crypto, payment apps and banks, and your own), then fill in only what that method needs. **Settings → Payments** sets your usual method.
- **Calls:** **Call** on a phone-number conversation sends a call request to your phone, which shows a *Tap to call* notification that opens the dialer. Requests expire after two minutes. Android doesn't let apps carry carrier call audio, so the call itself happens on the phone. **Voice** and **Video** call Matrix chats from the browser, which needs FluidMessages Web opened over HTTPS or at `localhost`. To add a TURN relay besides the homeserver's, set `FLUID_ICE_SERVERS` to a JSON list of WebRTC ICE servers.
- **Send later:** choose a time under **Send later**. Texts wait for the phone, which uses the selected SIM on its next sync. Foreground sync checks every 20 seconds. Android background sync runs opportunistically with a 15-minute minimum interval and may be delayed by Doze or loss of network access. Internet messages send from FluidMessages Web at their time.
- **Without a phone:** **Settings → Chat accounts** (also offered in the welcome) adds Matrix, XMPP, Email, and IRC accounts, and **New chat** starts a conversation on any of them. **Chat details** (ⓘ) sets a chat's disappearing timer and invites or removes group members.

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

Both open it without a password on this computer and keep your data in a private folder there. `--full` (or `-Full` on Windows) also installs the optional XMPP and encrypted-Matrix support, and `--port` changes the port.
