# FluidMessages

FluidMessages is an open-source Android SMS preview and a self-hosted browser companion. It uses an original interface with rounded chat bubbles, blue SMS, purple RCS previews, and a sample inbox.

## Try it locally

Run `./scripts/test-local` for automated checks, or `./scripts/test-local --demo` to open the companion with sample conversations. See [CONTRIBUTING.md](CONTRIBUTING.md) for Android build and install checks.

## Android emulator

With JDK 17 and Android SDK Command-line Tools installed, run as your regular user:

```sh
./scripts/test-emulator.sh
```

The script uses the pinned Gradle wrapper, installs missing SDK packages after Android SDK licenses are accepted, and creates an API 35 test device if needed. It builds and installs the preview, checks the setup guide, opens the appearance editor, and sends a practice message in a sample thread. The emulator stays open after a pass. If an in-place update rejects the local debug key, the launcher retries the packaged APK for the same version when one exists. It preserves app data and reports which APK installed. Use `--open` to launch for manual review, `--headless` for terminal-only checks, or `--apk PATH` to skip the build. Set `ANDROID_HOME` if the SDK is installed somewhere other than `~/Android/Sdk`. The first run downloads the emulator and system image.

## Android

Target device: Pixel 7 with GrapheneOS and dual SIM. The app targets Android 15 and requires Android 13 or newer. It uses the Android SMS role and does not require Google Play Services.

Build with JDK 17, Android SDK 35, and the included Gradle 8.11.1 wrapper:

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds a downloadable debug APK for every push. Debug builds are for testing and need a consistent signing key for in-place updates. An official direct-download release and F-Droid submission still need release signing and publication.

After installation, choose **Set as default SMS app** to load the system inbox and send or receive SMS. **SIM** in the composer selects a subscription. Long-press a conversation for pinning, archiving, or hiding it from the regular inbox. Hiding changes the local inbox and does not block carrier delivery. Notification previews omit message text.

Open the appearance editor from **Settings → Themes and appearance**, including before SMS setup. The Fluid palette uses blue for outgoing SMS, system orange for the RCS preview and green for the automated outgoing color. Incoming bubbles use system gray in light and dark mode. You can override the incoming color separately. **Color theme** lists the built-in themes and saved palettes. Individual message colors are in **Customize colors**. Colored message bubbles use white text. Bright chromatic fills are shaded for contrast. Neutral bubbles choose black or white based on brightness. The swatches use the light, dark, and increased-contrast RGB values from the [Apple Human Interface Guidelines color table](https://developer.apple.com/design/human-interface-guidelines/color#Specifications). Choose a coordinated theme such as Midnight, Lavender, Evergreen, Tideline, Ember, Love, or Rosewater. Each theme has a light and dark palette. Visual color controls, custom hex, and named swatches are available. Switching themes applies the selected palette, wallpaper and default layout. **Reset to theme** reapplies the current one. Mode, sound, haptic and motion preferences remain separate. Selecting Love applies its palette and matching wallpaper. The appearance page keeps presets and the live preview at the top. Expand **Customize colors**, **Wallpaper**, **Inbox and toolbar**, **Message bubbles**, or **Motion and sound** as needed. Use **Save custom palette** to keep a named version of a preset and your color overrides. Search can sit at the top or in a bottom dock. Toolbar finish and opacity, inbox density, and bubble tails are also adjustable. The optional green SMS and blue RCS preset can also be enabled during setup. First launch opens setup for appearance, motion, chimes, backups, privacy, and the LAN companion. Replay it or open the permission-free sample inbox from Settings. Under **Motion and sound**, set the outgoing sound and receive chime to Silent, a Fluid chime, the Android default, or a tone from the device picker. Choose a haptic pattern and preview the feedback. Device tones may need reselection after restoring on another phone. The animation setting controls the sample preview, conversation entrance, and new outgoing bubble animation. App lock uses your device credential or strong biometric and hides the app from screenshots while enabled.

### Replies, reactions, and conversation appearance

Enable **Settings → Messaging → Show exact message times** for inbox date-and-time labels and a time under each bubble. Received SMS use the sender timestamp when Android supplies it. Other messages use the stored timestamp. You can always tap an individual bubble to reveal its time.

Swipe a bubble right to quote it. Long-press for a reaction strip, Reply, Forward, Copy, Select text, Share, Message details, and Delete for me. Forward opens an editable draft for the recipient you choose. It sends readable SMS such as `Forwarded “Coffee at ten?” by “Maya Chen”`; FluidMessages renders that text as a forwarded card. Replies send the quote and reply body as readable SMS. FluidMessages shows compatible reply text as a quote card, and tapping a matched quote jumps to the original message. A thumbs-up sends text such as `Liked “Coffee at ten?”`. The **+** in the reaction strip accepts an emoji from your keyboard or clipboard. FluidMessages folds compatible standard and custom emoji reactions onto the matching earlier message. Unmatched reactions remain visible as text. React to incoming messages or your own outgoing messages. A reaction to your own message adds `(my message)` to the SMS so FluidMessages can distinguish the two cases. Matching uses the quote and sender, so repeated identical texts may match the latest one. Try both sides of a direct chat or open **Weekend Plans**, the sample group. Use **Send as** above the composer to choose yourself or a fictional group member.

Tap a conversation’s name or avatar to open its details. Use **Sender color** to mark a sender as automated or use the standard SMS color. Automatic uses short codes and alphanumeric sender IDs as signs of automation. This setting affects outgoing color and the conversation label. Incoming messages use the separate incoming color. **Edit contact** opens the Android Contacts editor. Calling, muting, pinning, and archiving are available for real SMS chats. **Theme and wallpaper** opens a live preview with built-in wallpapers, photo selection, and visual color controls. Selections save immediately for the conversation. Global wallpapers and visual colors are available in **Themes and appearance**. Selecting Love applies its palette and wallpaper together.

### Backups

Open **Settings → Backups** to export or restore a file, schedule a local folder, or choose a cloud destination. Select one or more categories in each backup or schedule: SMS, global theme and layout, sounds and effects, per-chat themes, conversation settings, and wallpaper photos. The wallpaper photo category embeds images for a restore on another device. Message attachments and group chats require MMS or RCS support. Backups combine the selected categories in one compact archive. Encryption is enabled by default and uses AES-256-GCM with a salted PBKDF2-HMAC-SHA256 key. Restore previews the archive and merges SMS into the Android provider with duplicate checking. Local and cloud schedules are independent, use a 15-minute to 7-day interval set with separate Hours and Minutes fields, and run when Android allows background work. To update a local schedule’s contents or interval, open **Local folder schedule**. The current folder stays selected, and an existing password can be kept. Scheduled archive passwords are stored using Android Keystore.

#### Back up through an Android storage app

**Export a file** and **Restore from a file** open Android's file picker. You can use a cloud app that makes its files available through the picker. **Share to a storage app** hands an archive to an app you select in the share menu. **Local folder schedule** also works with a storage app that offers a writable folder via the picker. These options work without pairing a companion. [Android storage access documentation](https://developer.android.com/training/data-storage/shared/documents-files)

#### Phone or companion per cloud destination

Open a provider in **Settings → Backups**, then choose **Phone** or **Companion** in **Backup method**. Phone lets you save or restore through Android’s file picker, hand off a backup to a storage app, or schedule backups to a writable folder. Select a cloud folder if the provider exposes one, or choose a device folder synced by a storage app. Provider credentials and sync for this route are handled by the selected Android app. Phone and companion schedules are separate and stay configured when you switch the selector.

#### OneDrive, Google Drive, and S3 through the companion

Cloud destinations use your paired HTTPS companion. The companion runs rclone and stores the provider credentials in its private data volume. Configure each provider you want from your companion host:

```sh
docker compose up -d --build
docker compose exec fluidmessages rclone config
```

Create rclone remotes for [OneDrive](https://rclone.org/onedrive/), [Google Drive](https://rclone.org/drive/), and [S3](https://rclone.org/s3/) as needed. Enter the remote names and backup locations in your Compose environment. For example:

```dotenv
FLUID_BACKUP_ONEDRIVE=onedrive:FluidMessages
FLUID_BACKUP_DRIVE=drive:FluidMessages
FLUID_BACKUP_S3=s3:your-bucket/FluidMessages
```

Run `docker compose up -d` again. If you use a reverse proxy, allow roughly 65 MiB request bodies and 200-second upstream reads for backup transfers. On Android, open **Settings → Backups** and choose a provider. **Back up now** lets you select categories and upload a one-time backup with no schedule. **Backup schedule** sets a recurring interval and categories for that provider, and **Run saved schedule now** uses those saved settings. Cloud schedules are tied to the paired companion address. Update them explicitly after changing servers. **Back up all cloud schedules** runs the configured phone folder and companion schedules. **Restore newest backup** finds and downloads the most recent FluidMessages archive for that provider and asks for confirmation before importing it. Re-pair a new phone with the same HTTPS companion and enter the archive password to restore there.


This preview does not download MMS. Keep that in mind before choosing it as your primary messaging app.

## LAN web companion

The stack works from the Git checkout on x86-64 and ARM64 Docker hosts. Set a fresh token before starting:

```sh
umask 077
printf 'FLUID_TOKEN=%s\n' "$(openssl rand -hex 32)" > .env
docker compose up -d --build
```

For Portainer, deploy the Compose file as a Git-backed stack from this checkout and set the same `FLUID_TOKEN` environment variable in the stack settings. The app builds from the included Dockerfile and stores its SQLite database in the `fluid-data` volume.

The container is published on host loopback. For a phone connection, put an HTTPS reverse proxy in front of `127.0.0.1:8080` on the host and use a hostname with a certificate trusted by the phone. Configure LAN DNS to point the hostname at the proxy. Both Android and your browser should use that HTTPS origin. A local browser on the host can open `http://localhost:8080` to check installation.

1. On Android, select **Settings → Web companion**. Tap **Set up companion** if the status is **Not set up**, then enter the HTTPS origin and pairing token, check **Enable browser messaging**, and tap **Save connection**. Leave browser messaging off to pair for cloud backups alone.
2. On your Mac or other computer, open the same origin in a browser and enter the pairing token. The inbox shows the newest 2,000 synced SMS messages.
3. Choose a recipient and send time. The connected phone uses the selected SIM to submit due texts on its next sync. Foreground sync checks every 20 seconds. Android background sync runs opportunistically with a 15-minute minimum interval and may be delayed by Doze or loss of network access.

A queued job can be cancelled before the phone claims it. **Submitted** means the phone handed the message to Android's SMS API. Check the SMS thread for the sending result. **Claimed** can persist if sync was interrupted mid-send. Check the phone before scheduling a replacement. The server does not reclaim interrupted sends automatically, to avoid duplicate texts.

The companion uses the same token for reading the synced inbox and scheduling sends. The server stores the synced SMS and scheduled messages in readable form so the browser can display them. Protect the server's data volume separately from encrypted Android backup files.

## Remaining release work

- Carrier MMS and RCS, including iPhone RCS interoperability and user-controlled transport fallback.
- Imported fonts, configurable gestures, and detailed motion/sound controls.
- Attachment backups, direct SFTP/SSH connectors, and external backup-format imports.
- More inbox and composer features, including a contact recipient picker, device-level blocking, and local message scheduling.
- Physical-device testing on GrapheneOS with both SIMs, direct-download release signing, and F-Droid packaging and submission.

### Sample portraits

The built-in image generation tool created the two fictional contact photos. These are the prompts used for the packaged assets.

- A square avatar photo of a fictional young East Asian American woman with a dark bob and a soft smile, candid natural daylight, simple muted eucalyptus green backdrop, realistic portrait photography with casual clothes, face centered and sized for a very small circular contact avatar. No letters, logo or watermark. [Maya's avatar](app/src/main/res/drawable-nodpi/sample_maya.webp)
- A square avatar photo of a fictional young Black American man with short textured hair, round glasses, and a relaxed smile, candid natural daylight, quiet slate-blue backdrop, realistic portrait photography with casual clothes, centered face readable as a very small circular contact avatar. No letters, logo or watermark. [Jordan's avatar](app/src/main/res/drawable-nodpi/sample_jordan.webp)

Apache-2.0. See [LICENSE](LICENSE).
