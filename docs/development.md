# Developing FluidMessages

## Local checks

Run `./scripts/test-local` for automated checks, or `./scripts/test-local --demo` to open the companion with sample conversations. See [CONTRIBUTING.md](CONTRIBUTING.md) for Android build and install checks.

## Android emulator

With JDK 17 and Android SDK Command-line Tools installed, run as your regular user:

```sh
./scripts/test-emulator.sh
```

The script uses the pinned Gradle wrapper, installs missing SDK packages after Android SDK licenses are accepted, and creates an API 35 test device if needed. It builds and installs the preview, checks the setup guide, opens the appearance editor, and sends a practice message in a sample thread. The emulator stays open after a pass. If Android rejects an update because the signing key differs, the script installs the selected APK on a separate test AVD and leaves the original emulator's app data intact. Use `--open` to launch for manual review, `--headless` for terminal-only checks, or `--apk PATH` to skip the build. Set `ANDROID_HOME` if the SDK is installed somewhere other than `~/Android/Sdk`. The first run downloads the emulator and system image.

## Building

Build with JDK 17, Android SDK 35, and the included Gradle wrapper:

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

To build the desktop app for the computer you're on, run these two commands:

```sh
pip install pyinstaller pillow
```

```sh
python desktop/build.py --version 0.0.0
```

GitHub Actions builds a debug APK for every push.

## Releasing

`./scripts/release` builds a fresh APK and runs the local checks. It then commits any changes, tags `v<version>`, pushes the branch and tag, and creates a GitHub release with these files:

- `FluidMessages-<version>.apk`: the Android app. **Check for updates** in the app picks it up.
- `FluidMessages-Server-<version>.zip`: the FluidMessages Web server, with Docker Compose and the `web-app` scripts, for hosting it yourself.
- FluidMessages Web desktop apps, which GitHub Actions builds on each system and adds to the same release a few minutes later:
  - `…-Windows.exe`
  - `…-macOS.dmg`
  - `…-Linux-x86_64.AppImage`

  Each one runs FluidMessages Web on the computer, signs in automatically, and opens it in its own window. To build one by hand, run `pip install pyinstaller pillow`, then `python desktop/build.py --version <version>`.

- `--bump` raises the patch version first.
- `--notes "..."` sets the release notes.
- `--dry-run` previews the steps without changing anything.

It needs the GitHub CLI signed in (`gh auth login`). APKs are signed with this computer's debug key, so keep releasing from the same machine, or phones can't install the update over the old version.

## Remaining release work

- Carrier MMS and RCS, including iPhone RCS interoperability and user-controlled transport fallback.
- Matrix group calls, XMPP and SIP calls in FluidMessages Web, and replacing the default phone app.
- Imported fonts, configurable gestures, and detailed motion/sound controls.
- Attachment backups, direct SFTP/SSH connectors, and external backup-format imports.
- Device-level blocking and local message scheduling.
- Physical-device testing on GrapheneOS with both SIMs, release signing, code-signed desktop apps, and F-Droid packaging and submission.

## Sample images

The built-in image generation tool created the fictional contact photos and chat attachments. These are the prompts used for the packaged assets.

- A square avatar photo of a fictional young East Asian American woman with a dark bob and a soft smile, candid natural daylight, simple muted eucalyptus green backdrop, realistic portrait photography with casual clothes, face centered and sized for a very small circular contact avatar. No letters, logo or watermark. [Maya's avatar](../app/src/main/res/drawable-nodpi/sample_maya.webp)
- A square avatar photo of a fictional young Black American man with short textured hair, round glasses, and a relaxed smile, candid natural daylight, quiet slate-blue backdrop, realistic portrait photography with casual clothes, centered face readable as a very small circular contact avatar. No letters, logo or watermark. [Jordan's avatar](../app/src/main/res/drawable-nodpi/sample_jordan.webp)

- An original realistic landscape photo sent as a chat attachment on a weekend walk. A quiet lakeside picnic spot viewed from a low wooden footbridge, soft late-afternoon sun, green shoreline, a few soft pink clouds reflected in clear blue water, natural smartphone photography, landscape 4:3 framing, no people, signs, logos, captions, or text. [Sample chat photo](../app/src/main/res/drawable-nodpi/sample_lakeside.webp)


- Original candid smartphone photo for a fictional message attachment. An open cardboard box on a café table holding two small glazed handmade ceramic cups, one cobalt-blue and one cream with subtle speckles, morning window light and a slightly imperfect casual crop. Photorealistic, quiet tones, landscape 4:3, no people, writing, logos, or captions. [Pottery photo](../app/src/main/res/drawable-nodpi/sample_pottery.webp)
