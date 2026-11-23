# Self-hosting FluidMessages Web

FluidMessages Web is the web version of FluidMessages, like WhatsApp Web. Use it in a browser, as an installed app (a PWA), or as a desktop app. It runs on a server you control:

- **Messages from other services:** it keeps your Matrix, XMPP, Email, and IRC connections open, so your phone doesn't have to.
- **Texts:** it shows texts your phone syncs to it, and sends texts through your phone.
- **Without a phone:** it works on its own for everything except texts.

- [Docker Compose](#docker-compose)
- [Portainer](#portainer)
- [Desktop apps](#desktop-apps)
- [HTTPS and connecting your phone](#https-and-connecting-your-phone)
- [Using it](#using-it)
- [Install it as an app (PWA)](#install-it-as-an-app-pwa)

## Docker Compose

The server runs on x86-64 and ARM64 Docker hosts. Each release publishes a ready-made image, `ghcr.io/lin8x/fluidmessages-web`. From a copy of this repository:

```sh
umask 077
printf 'FLUID_TOKEN=%s\n' "$(openssl rand -hex 32)" > .env
docker compose up -d
```

- **Access token:** `FLUID_TOKEN` is your sign-in for the web app and for pairing your phone. Keep it secret.
- **Build it yourself:** `docker compose up -d --build` builds from this checkout instead of downloading the image.
- **Your data:** the SQLite database, accounts, and files are in the `fluid-data` volume.
- **Updating:** run `docker compose pull`, then `docker compose up -d`.
- **Stopping:** `docker compose down` stops it. `docker compose down -v` also deletes your data.

Optional accounts and backup destinations can be set in `.env`. See the variables in [compose.yaml](../compose.yaml). Accounts set there show as read-only in the app.

## Portainer

1. In Portainer, open **Stacks → Add stack**.
2. Choose **Web editor** and paste [deploy/portainer-stack.yml](../deploy/portainer-stack.yml). Or choose **Repository** and point it at this repository's `compose.yaml`.
3. Under **Environment variables**, add `FLUID_TOKEN` with a long random value. One way to make one is `openssl rand -hex 32`.
4. Select **Deploy the stack**.

The image is published to the GitHub Container Registry with each release. If Portainer can't pull it, make sure the package's visibility is set to **Public** on GitHub, under the repository's **Packages**.

## Desktop apps

Each release includes FluidMessages Web for **Windows** (`.exe`), **macOS** (`.dmg`), and **Linux** (`.AppImage`). Download them from [Releases](https://github.com/Lin8x/FluidMessages/releases/latest).

- **What it does:** runs FluidMessages Web on your computer, signs you in automatically, and opens it in its own window. A small window shows that it's running, with **Open** and **Quit**.
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

## HTTPS and connecting your phone


The stack works from the Git checkout on x86-64 and ARM64 Docker hosts. Set a fresh token before starting:

```sh
umask 077
printf 'FLUID_TOKEN=%s\n' "$(openssl rand -hex 32)" > .env
docker compose up -d --build
```

For Portainer, deploy the Compose file as a Git-backed stack from this checkout and set the same `FLUID_TOKEN` environment variable in the stack settings. The app builds from the included Dockerfile and stores its SQLite database in the `fluid-data` volume.

The container is published on host loopback. For a phone connection, put an HTTPS reverse proxy in front of `127.0.0.1:8080` on the host and use a hostname with a certificate trusted by the phone. Configure LAN DNS to point the hostname at the proxy. Both Android and your browser should use that HTTPS origin. A local browser on the host can open `http://localhost:8080` to check installation.

1. On Android, select **Settings → Web companion**. Tap **Set up companion** if the status is **Not set up**, then enter the HTTPS origin and pairing token, check **Enable browser messaging**, and tap **Save connection**. Leave browser messaging off to pair for cloud backups alone.
2. On your Mac or other computer, open the same origin in a browser and enter the pairing token. The inbox shows the newest 2,000 synced SMS messages along with Matrix, XMPP, Email, and IRC conversations, and follows Android’s global palette (including each protocol's color), bubble dimensions, inbox folders, protocol tags, and exact-time preference. Reply, react, forward, or download an attachment from a message’s **Actions** menu. Internet protocol replies and reactions use each protocol's native feature, and protocols turned off on the phone are read-only.
3. **Call** on a phone-number conversation sends a call request to your phone, which shows a *Tap to call* notification that opens the dialer. Requests expire after two minutes. Android doesn't let apps carry carrier call audio, so the call itself happens on the phone.
4. Choose a recipient and send time. The connected phone uses the selected SIM to submit due texts on its next sync. Foreground sync checks every 20 seconds. Android background sync runs opportunistically with a 15-minute minimum interval and may be delayed by Doze or loss of network access.

A queued job can be cancelled before the phone claims it. **Submitted** means the phone handed the message to Android's SMS API. Check the SMS thread for the sending result. **Claimed** can persist if sync was interrupted mid-send. Check the phone before scheduling a replacement. The server does not reclaim interrupted sends automatically, to avoid duplicate texts.

The companion also works without a phone. **Settings** in the browser adds Matrix, XMPP, Email, and IRC accounts, and **New chat** starts a conversation on any protocol. Texts still go through the phone. **Use my phone's settings** lets the phone's disappearing timers replace the companion's on each sync. **Details** sets a chat's disappearing timer and invites or removes group members. **Voice** and **Video** call Matrix chats from the browser, which needs the companion opened over HTTPS or at `localhost`. To add a TURN relay besides the homeserver's, set `FLUID_ICE_SERVERS` to a JSON list of WebRTC ICE servers.

The companion uses the same token for reading the synced inbox and scheduling sends. The server stores the synced SMS and scheduled messages in readable form so the browser can display them. Protect the server's data volume separately from encrypted Android backup files.

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

Both print the access token and keep your data in a private folder on the computer. `--full` (or `-Full` on Windows) also installs the optional XMPP and encrypted-Matrix support, and `--port` changes the port.
