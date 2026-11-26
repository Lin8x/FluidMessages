# Self-hosting FluidMessages Web

FluidMessages Web is FluidMessages as an app for your computer. Use it in a browser, as an installed app (a PWA), or as a desktop app. It runs on a computer or server you control:

- **On its own:** Matrix, XMPP, Email, and IRC chats, calls, themes, effects, polls, games, and everything else in the app, with no phone needed. It keeps those connections open even when the page is closed.
- **With your phone, if you like:** FluidMessages for Android adds your texts, and can share its theme and timers. Connect it any time from **Settings → Phone and other devices**.

- [Docker Compose](#docker-compose)
- [Portainer](#portainer)
- [Signing in](#signing-in)
- [Desktop apps](#desktop-apps)
- [HTTPS and connecting your phone](#https-and-connecting-your-phone)
- [Reach it from outside your home](#reach-it-from-outside-your-home)
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
- **Forgot your password?** Open FluidMessages on the computer it runs on and set a new one in Settings. Or run `docker compose exec fluidmessages python main.py reset-password`: it removes the password, two-step sign-in, and every browser sign-in and prints a new setup code. Messages and accounts stay.

### Two-step sign-in

Two-step sign-in: after your password, type a code from an authenticator app on your phone (like Aegis or 2FAS). Then a stolen password alone can't get in.

- **On by default.** Whenever a password is set (the first-visit setup page, **Set up a password** in Settings, or the first step of **Connect your phone**), setup goes straight on to two-step sign-in: scan the QR code (or type the key shown in groups of four), type the 6-digit code from the app, then save the 10 recovery codes (Copy or Download). **Skip for now** is always there; if you skip, **Settings → Phone and other devices** shows "Two-step sign-in is off" with **Turn on**.
- **Installs from before this** keep signing in with just the password. They see the same notice in Settings and a one-time offer after the next password sign-in. Nothing is forced and nobody is locked out.
- **Signing in:** password first, then the code on the same page. **Remember this browser for 30 days** skips the code in that browser (an `HttpOnly`, `SameSite=Strict` cookie, like the sign-in cookie). Signing in on this computer without a password, and the phone's pairing token or `FLUID_TOKEN`, never ask for a code.
- **Recovery codes** each work once, in place of a code from the app. **Settings → Two-step sign-in → Make new recovery codes** (asks for a code) replaces them and shows how many are left. **Turn off** asks for your password and a code.
- **How it works:** standard TOTP (RFC 6238: 6 digits, 30-second steps, SHA-1), allowing one step early or late for a phone clock that's a little off. A code can't be used twice. Five wrong codes from one address make it wait, like wrong passwords.
- **What's stored:** the app's secret is encrypted with a random key kept in a private file in the data folder, `auth-secret.key`, so a copy of the database alone can't make codes. Back up that file with the database; without it, codes from the app stop working but recovery codes still do. Recovery codes are stored only as hashes.

**Locked out?** (lost your phone and your recovery codes)

- On the computer FluidMessages Web runs on, open it (it opens there without a password, unless you turned that off) and go to **Settings → Phone and other devices → Two-step sign-in → Turn off**. No password or code is needed there, by the same "this computer" rule as above.
- Or run `docker compose exec fluidmessages python main.py reset-two-step` on the server (`python server/main.py reset-two-step` without Docker). It turns two-step sign-in off and forgets remembered browsers. Then sign in with just your password and turn it on again.

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

Your phone connects to FluidMessages Web over your network. In FluidMessages Web, **Settings → Phone and other devices → Connect your phone** shows a QR code. On the phone, open **Settings → FluidMessages Web** and tap **Scan QR code**. That's all: the code holds the address, the link code, and the certificate fingerprint (below). Under the QR code are the same details to type in instead, like `https://192.168.1.20:8081`, each with a Copy button.

### Real HTTPS without a domain name

On its first start, FluidMessages Web makes its own certificate and key and keeps them in its data folder (`companion-cert.pem`, and `companion-key.pem`, which only its own user can read and which is never shown or logged). It then answers over HTTPS on a second port, so your phone's connection is encrypted even on shared Wi-Fi, with no domain name or certificate authority:

- **The port** is `FLUID_PORT` + 1, so `8081` by default. Set `FLUID_TLS_PORT` to choose another, or to `0` to turn it off. The `http://` port (`8080`) stays exactly as it was, for your browser.
- **The certificate fingerprint** is a long code (64 letters and numbers in pairs, like `AB:CD:…`) that belongs to this one certificate. The QR code carries it, and the server prints it when it starts. Your phone saves it next to the address and then trusts only that certificate, at that address, for FluidMessages Web. So no warning shows: the connection is private. Nothing else in the app is affected.
- **If the certificate ever changes** (you erased the data folder or reinstalled), the phone stops connecting and says so plainly in **Settings → FluidMessages Web**. It never falls back to a weaker check. Scan a new QR code to pair again. If you didn't change anything, someone may be trying to listen in.
- **Typing it in:** the phone's **Certificate fingerprint** field is optional. Leave it empty for an address with a real certificate (your own domain, below), which uses the phone's normal checks.
- **Needs the `openssl` command**, which the Docker image includes and macOS and Linux have. Without it, the server says HTTPS for phones is off and everything else works as before.
- **Docker:** publish `8081` the same way as `8080` (`compose.yaml` has both). **Firewalls:** allow `8081` too.

Browsers keep using `http://localhost:8080`, or your own `https://` address through a reverse proxy, as before.

### What the address means

- **The IP address** (like `192.168.1.20`) is this computer’s number on your home network. Your router hands one to every device on your Wi-Fi.
- **The port** (the number after the colon, like `8081`) is which “door” on that computer FluidMessages answers on. Browsers use `8080` (`FLUID_PORT`); phones use the HTTPS port, `8081` (`FLUID_TLS_PORT`).
- **http:// or https://** says whether the connection is encrypted. `https://` is. `http://` is not: anyone on the same Wi-Fi could read your messages and the link code. That’s fine for testing at home. For everyday use, use an `https://` address (below).

### Finding them

FluidMessages Web finds them for you and shows them when you connect your phone. To look them up yourself:

- **Windows:** open Command Prompt and run `ipconfig`. Look for **IPv4 Address** under your Wi-Fi or Ethernet adapter.
- **Mac:** System Settings → Wi-Fi → **Details** next to your network. Or run `ipconfig getifaddr en0` in Terminal.
- **Linux:** run `hostname -I` or `ip -4 addr`.

Home network addresses usually start with `192.168.`, `10.`, or `172.16.` to `172.31.`.

### On the same Wi-Fi

1. Let other devices reach FluidMessages. By default it only answers this computer.
   - **From the source:** start it with `FLUID_BIND=0.0.0.0`, like `FLUID_BIND=0.0.0.0 ./scripts/web-app` (on Windows, `$env:FLUID_BIND='0.0.0.0'` before `scripts\web-app.ps1`).
   - **The desktop app** only answers this computer. To connect a phone, run it from the source or with Docker instead.
   - **Docker Compose:** delete the `FLUID_LOCAL_ONLY` line and change the ports to `"8080:8080"` and `"8081:8081"` in `compose.yaml`, then run `docker compose up -d` again.
   - If your computer has a firewall, allow the ports (for example `sudo ufw allow 8080/tcp` and `sudo ufw allow 8081/tcp`, or allow Python when Windows asks).
2. Set a password in **Settings → Phone and other devices**, since other devices can now reach it.
3. Put your phone on the same Wi-Fi and scan the QR code. Or type the address (like `https://192.168.1.20:8081`), the link code, and the certificate fingerprint. An `http://` address (like `http://192.168.1.20:8080`) still works for testing, with a warning that it isn't encrypted.

Your router may give the computer a new IP address later. If the phone stops connecting, check the address again, or ask your router to always give this computer the same one (often called a “DHCP reservation”).

### Reach it from outside your home

Pick one. The first is the simplest.

**A private network, like Tailscale (simplest).** Install [Tailscale](https://tailscale.com) (or a similar private network such as ZeroTier or WireGuard) on the computer and on your phone, and sign in to the same account on both. Your phone can then reach the computer from anywhere, as if it were on your Wi-Fi, and nothing is opened to the internet. Use the computer’s Tailscale address (it starts with `100.`; FluidMessages Web lists it too, with the port, like `http://100.x.y.z:8080`) or its Tailscale name. Tailscale encrypts the traffic between your devices; for an `https://` address on top, see `tailscale serve`.

**A domain name with HTTPS.** Point a name you own (like `messages.example.net`) at your server, and put a reverse proxy in front of FluidMessages that gets a certificate for it. With [Caddy](https://caddyserver.com), this whole `Caddyfile` does it:

```
messages.example.net {
    reverse_proxy 127.0.0.1:8080
}
```

Caddy gets and renews the certificate by itself. On a home connection, your router must forward ports 80 and 443 to the computer running Caddy (see the risks below). Then use `https://messages.example.net` on your phone and in your browser.

- **The proxy must send `X-Forwarded-For`** (Caddy and Traefik do; in nginx add `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` and `proxy_set_header X-Forwarded-Proto $scheme;`). That’s how FluidMessages knows a request came through the proxy rather than from this computer, and it also marks cookies `Secure` and keeps guessing limits per visitor.
- **Docker:** if anything other than this computer’s own browser reaches the container (a proxy container on the same Docker network, or a port published beyond `127.0.0.1`), delete `FLUID_LOCAL_ONLY` from `compose.yaml`.
- If an older browser behind a proxy that rewrites the `Host` header can’t save changes, set `FLUID_ORIGINS` to your public address, like `https://messages.example.org`.

**Port forwarding (be careful).** Your router can send a port from the internet straight to FluidMessages. This works, but anyone on the internet can then find it and try to sign in. Without HTTPS, your password, link code, and messages also cross the internet unencrypted, where others can read them. If you do it, only forward to an HTTPS proxy (as above), use a strong password, and keep FluidMessages up to date. Never forward the plain `http://` port.

### Connecting, step by step

1. In FluidMessages Web, open **Settings → Phone and other devices** and choose **Connect your phone**. Without a password yet, step 1 sets one (your phone’s browser and other devices sign in with it). Then it shows the QR code, with the address, port, pairing token, and certificate fingerprint below it. The token, and so the QR code, is shown only once, when it's made; only a hash is kept. A server with `FLUID_TOKEN` can use that instead (type it in).
2. On Android, select **Settings → FluidMessages Web** and tap **Scan QR code** (also in **Set up step by step**, on its **Enter it here** step). The phone asks for the camera only then; the camera reads the code on the phone and nothing is recorded. It tries the code's addresses in order and saves the first one that answers. To type instead, enter the address, the pairing token, and optionally the certificate fingerprint, check **Enable browser messaging**, and tap **Save connection**. An address typed without `http://` or `https://` uses `https://`. Leave browser messaging off to pair for cloud backups alone.
3. **Make a new pairing token** replaces the old one, and **Disconnect phone** removes the token a phone uses.

Over `http://` or `https://`, every request still needs a signed-in browser, the phone’s pairing token (sent in a header, never in the address), or this computer’s own browser. Wrong passwords and wrong tokens make that address wait before trying again.

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

## Tor

**Settings → Privacy and security → Connect through Tor** sends the server's own IRC, Matrix, and email connections, and link previews, through a Tor SOCKS proxy. It's off by default and shows the same warning as the phone. The proxy is `127.0.0.1:9050` unless you change it on that page or set `FLUID_TOR_PROXY` (for example `tor:9050` for a Tor container in the same Compose project). The page says whether the proxy answers, and how to install Tor if it doesn't (`sudo apt install tor` on Debian or Ubuntu, `brew install tor` on macOS).

Server names go to Tor unresolved, so the server doesn't look them up itself. While Tor is on but the proxy doesn't answer, connections wait with a message instead of going direct. XMPP waits, and encrypted Matrix rooms can't be used, while Tor is on, because their libraries open their own connections. Cloud backups (rclone) and servers on your own network connect directly.

Each chat's details have **Connect through Tor** too, for the connection the server makes for it: one IRC network, Matrix account, or mailbox, so it also applies to that account's other chats. A connection can use Tor while the setting is off, or skip it while it's on; the choice is saved with the setting and the connection reconnects. XMPP chats, encrypted Matrix rooms, and servers on your own network show the switch turned off with the reason. Chats going through Tor show an onion beside their plug in the inbox and the chat's header. The phone sees the same and can change it from its own chat details (`POST /api/tor/connection` with `account` and `on`).

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
