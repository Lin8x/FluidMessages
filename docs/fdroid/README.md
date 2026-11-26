# Getting FluidMessages into F-Droid

F-Droid is an app store for free and open-source Android apps. It builds every app itself from the source code.

Everything F-Droid reads from this repository is ready:

- `fastlane/metadata/android/en-US/`: the name, short and full description, changelogs (named by versionCode), and the icon.
- `docs/fdroid/org.fluidmessages.yml`: a draft recipe for F-Droid's list of apps (fdroiddata).
- The `fdroid` Gradle flag: `./gradlew assembleRelease -Pfdroid=true` builds a copy that never checks GitHub for updates or downloads them. Settings → About and updates says "Updates come from F-Droid". Without the flag, the build is the same as before.

## What only you can do

These steps need your own accounts and your name on a merge request, so nobody else can do them for you.

1. **Make a GitLab account** at [gitlab.com](https://gitlab.com/users/sign_up). F-Droid lives on GitLab, not GitHub.
2. **Fork fdroiddata.** Open [gitlab.com/fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata) and choose **Fork**.
3. **Make a release with the flag in it.** Version 0.44.0 came out before the `fdroid` flag, so F-Droid can't use it. Release the next version as usual, then in the recipe change `versionName`, `versionCode`, `commit` (the tag, like `v0.45.0`), `CurrentVersion`, and `CurrentVersionCode` to match it. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` for it (500 characters at most).
4. **Choose who signs the app** (see below), and edit the recipe to match.
5. **Add the recipe.** In your fork, make a new branch called `org.fluidmessages` and add the file as `metadata/org.fluidmessages.yml`.
6. **Check it, if you can.** With [fdroidserver](https://f-droid.org/docs/Installing_the_Server_and_Repo_Tools/) installed, run these in your fork:

   ```sh
   fdroid readmeta
   fdroid rewritemeta org.fluidmessages
   fdroid lint org.fluidmessages
   fdroid build -v -l org.fluidmessages
   ```

   GitLab also runs these checks on your merge request.
7. **Open a merge request** from your branch to `fdroid/fdroiddata`, using the **App inclusion** template. F-Droid's volunteers review it and may ask questions there. Answer them from your account.
8. **Turn on private reports on GitHub.** SECURITY.md sends people to GitHub's private vulnerability report, and it's off right now. In GitHub, open the repository's **Settings → Security** (Code security) and turn on **Private vulnerability reporting**.

After it's accepted, F-Droid checks for new `vX.Y.Z` tags by itself (`UpdateCheckMode: Tags`) and builds them (`AutoUpdateMode: Version`). Each new version needs its changelog file in `fastlane/` before you tag it.

## Who signs the app

**Option A, simplest: F-Droid signs it.** Remove the `AllowedAPKSigningKeys` line and leave `Binaries` commented out. F-Droid builds and signs FluidMessages with its own key. It works right away, but the F-Droid copy and the GitHub copy have different keys. Android won't update one with the other, so people switching between them have to uninstall first (after a backup). The app's About page says "Signed by F-Droid" for this copy instead of a warning.

**Option B: F-Droid ships your own signed copy (reproducible builds).** F-Droid builds the app, checks that its build matches yours byte for byte, and then publishes your signed APK. People can then move between GitHub and F-Droid without uninstalling. For this you need, for every release:

1. A **release** build (not debug) made with the flag: `./gradlew assembleRelease -Pfdroid=true`, signed with your key using `apksigner`.
2. That file attached to the GitHub release as `FluidMessages-<version>-fdroid.apk`. Then uncomment the `Binaries:` line in the recipe and keep `AllowedAPKSigningKeys`.
3. The same build setup F-Droid uses: JDK 17, the same Android build tools, and a build from the clean tag.

The things below stand in the way of Option B today. Check them first.

## What could break reproducible builds

- **The releases are debug builds.** `scripts/release` publishes `app-debug.apk`, which is marked debuggable and signed with the Android SDK's automatic debug key (its certificate is named "Android Debug"). F-Droid builds the release variant, so it can never match a debug APK. A release `signingConfig` is needed. If you make a new release key, its fingerprint changes: Android won't update existing installs signed with the old key, so update `SigningKey.OFFICIAL`, `docs/verify.md`, and this recipe together, and plan how current users move over. Keeping the current key as the release key avoids that, but then keep that keystore file safe and backed up, because it lives on one computer.
- **The F-Droid flag changes the app.** `BuildConfig.FDROID` is compiled in, so the regular GitHub APK and the F-Droid APK are different files. Option B needs the separate `-fdroid.apk` above; the regular APK can't be used.
- **Baseline profile.** Release builds include `assets/dexopt/baseline.prof`. Some Android Gradle Plugin versions wrote it differently from build to build. AGP 8.9 should be stable, but compare two clean builds to be sure.
- **Build tools and paths.** Different JDK, Kotlin, or build-tools versions change the output. Build from a clean checkout of the tag. The Gradle files don't stamp dates, git hashes, or build paths into the app.
- **Dependency info block.** Android Gradle Plugin normally adds an encrypted list of libraries for Google Play to the APK signature. F-Droid rejects it, so `app/build.gradle` turns it off (`dependenciesInfo`).

## F-Droid's rules, checked

- **License:** Apache-2.0 (`LICENSE`).
- **No closed parts:** no Google Play Services, Firebase, or other closed libraries, and no prebuilt `.jar`, `.aar`, or `.so` files besides Gradle's own wrapper. All libraries come from Google's Maven (AndroidX) and Maven Central: Compose and AndroidX, PdfBox-Android, JavaMail for Android, Smack (with MiniDNS, jxmpp, PGPainless, and BouncyCastle), JSch, Apache Commons Net, and Kotlin.
- **Bundled JavaScript:** `app/src/main/assets/jssip.min.js` is JsSIP 3.11.1 (MIT), already minified. Reviewers sometimes ask for the source of minified files. Point them to [jssip.net](https://jssip.net) and the version in the file header.
- **No self-updates:** the `fdroid` flag turns off the GitHub update check and download.
- **Anti-features:** the recipe flags `NonFreeNet`, because optional GIF search uses GIPHY and Tenor. Reviewers decide the final list.
- **Screenshots:** none yet. See `fastlane/metadata/android/en-US/images/phoneScreenshots/README.md`.
