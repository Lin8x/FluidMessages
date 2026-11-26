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

Step-by-step command-line instructions, starting from a fresh computer, are in [building.md](building.md). In short, build with JDK 17, Android SDK 35, and the included Gradle wrapper:

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

`./scripts/release` builds a fresh APK and runs the local checks. It then commits any changes, tags `v<version>`, pushes the branch and tag, moves `main` up to the release, and creates a GitHub release with these files:

- `FluidMessages-<version>.apk`: the Android app. **Check for updates** in the app picks it up.
- `FluidMessages-<version>.aab`: the app bundle for app stores like Google Play and the Galaxy Store. It's a store build (`-Pstore=true`): no update checks or update permission, since the store updates it. Signed with the release key by `scripts/sign-aab`; on Google Play that's the upload key.
- `FluidMessages-Server-<version>.zip`: the FluidMessages Web server, with Docker Compose and the `web-app` scripts, for hosting it yourself.
- FluidMessages Web desktop apps, which GitHub Actions builds on each system and adds to the same release a few minutes later:
  - `…-Windows.exe`
  - `…-Windows.msix`: for the Microsoft Store, unsigned (the Store signs it). Its identity must match Partner Center → Product identity: set the repository variables `MSIX_IDENTITY_NAME`, `MSIX_PUBLISHER` and `MSIX_PUBLISHER_DISPLAY_NAME` (GitHub → Settings → Secrets and variables → Actions → Variables).
  - `…-macOS.dmg`
  - `…-Linux-x86_64.AppImage`
  - `…-Linux-amd64.deb` and `…-Linux-x86_64.rpm`: the same Linux program as a package, for Debian and Ubuntu (22.04 or Debian 12 and newer) and for Fedora (36 and newer) or openSUSE. `desktop/build.py` makes them when `dpkg-deb` and `rpmbuild` are installed. The program is never stripped, since that breaks it.
  - `…-Linux-amd64.snap`: the same program as a Snap (`snap/snapcraft.yaml`). It's also sent to the Snap Store's stable channel once the `SNAPCRAFT_STORE_CREDENTIALS` repository secret is set (see [Linux app stores](#linux-app-stores)).

  Each one runs FluidMessages Web on the computer, signs in automatically, and opens it in its own window. To build one by hand, run `pip install pyinstaller pillow`, then `python desktop/build.py --version <version>`.

After the desktop apps are attached, the script points the Homebrew tap ([Lin8x/homebrew-tap](https://github.com/Lin8x/homebrew-tap)) at the new Mac app with `scripts/update-homebrew-tap`, and each release is added to the Linux AppStream metadata (`desktop/linux/`, used for Flathub). To test the desktop builds from a branch without touching a release, run the desktop workflow by hand with **ref** set to the branch and **upload** turned off; the apps are kept as the run's artifacts.

- `--bump` raises the patch version first.
- `--notes "..."` sets the release notes.
- `--dry-run` previews the steps without changing anything.
- `--no-wait` skips waiting for the desktop apps. Normally the script waits until GitHub Actions has attached the Windows, macOS, and Linux apps, retries a failed build once, and stops with an error if any of the desktop files is still missing (the Snap isn't required).
- `--no-main` leaves `main` where it is. Otherwise, releasing from another branch fast-forwards `main` to it, so the GitHub page, README, and website show the latest release. It never force-pushes: if `main` has commits your branch doesn't, it's left alone with a warning, and the release still goes out.

It needs the GitHub CLI signed in (`gh auth login`). APKs are signed with the FluidMessages release key (`scripts/sign-apk`, made once by `scripts/make-release-key`), so only a computer with those key files can release.

## Linux app stores

These need accounts that only the maintainer can create, so each is set up once by hand.

**Snap Store.** The release workflow builds the Snap every time; it only needs permission to publish it.

1. Create an account at <https://snapcraft.io> and register the name: `snapcraft register fluidmessages-web` (after `sudo snap install snapcraft --classic` and `snapcraft login`).
2. Make a publishing login for GitHub Actions: `snapcraft export-login --snaps=fluidmessages-web --acls=package_access,package_push,package_release -`.
3. Save its output as the repository secret `SNAPCRAFT_STORE_CREDENTIALS` (GitHub → Settings → Secrets and variables → Actions → Secrets).

The next release then goes to the stable channel. The first upload is checked by the Store's automated review. The Snap is strictly confined, with only the network, desktop and display permissions.

**Fedora COPR.** COPR builds an RPM from source for each Fedora version, from `deploy/fedora/fluidmessages-web.spec` and `.copr/Makefile` (the version comes from the latest tag). It uses Fedora's own Python libraries, so OMEMO encryption for XMPP is off there; the `.rpm` on each release has everything.

1. Sign in at <https://copr.fedorainfracloud.org> with a Fedora account, and create a project named `fluidmessages` with the Fedora releases (and EPEL, if you like) to build for.
2. In the project, choose **Packages → New package → SCM**: clone URL `https://github.com/Lin8x/FluidMessages`, committish `main`, build method **make srpm**, and turn on **Auto-rebuild**.
3. Under **Settings → Integrations**, copy the GitHub webhook URL and add it in GitHub → Settings → Webhooks (content type `application/json`, just the push event). Every push to `main` then rebuilds it.

Users install it with `sudo dnf copr enable lin8x/fluidmessages` and then `sudo dnf install fluidmessages-web`. To test the package locally, run `make -f .copr/Makefile srpm outdir=/tmp/srpm` in a Fedora container, then `rpmbuild --rebuild` the result.

**Debian and Ubuntu.** There's no APT repository yet: people install the `.deb` from the release, and update by installing the next one. An official Debian package needs a Debian Developer to sponsor it and every Python library packaged in Debian.

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
