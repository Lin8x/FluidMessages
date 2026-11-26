# Building FluidMessages from source

These steps build the Android app, and optionally FluidMessages Web, from the command line. They work on Linux and
macOS. On Windows, use the same steps in WSL, or run `gradlew.bat` in place of `./gradlew`.

## Android app

### 1. Install the tools

- **JDK 17 or newer.** For example `sudo apt install openjdk-17-jdk` (Debian and Ubuntu),
  `sudo dnf install java-17-openjdk-devel` (Fedora) or `brew install openjdk@17` (macOS). Check with `java -version`.
- **Android SDK command-line tools.** Download "Command line tools only" from
  <https://developer.android.com/studio#command-tools> and unpack it so that this path exists:
  `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager`. Android Studio is not needed.

Then install the SDK packages the app builds against, and accept their licenses:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
```

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
```

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

No NDK is needed. Gradle itself is downloaded by the included wrapper (`./gradlew`, Gradle 8.11.1), and all
libraries come from Google's Maven repository and Maven Central.

### 2. Get the source

```sh
git clone https://github.com/Lin8x/FluidMessages.git
```

```sh
cd FluidMessages
```

To build a specific release, check out its tag, for example `git checkout v0.47.1`.

If your SDK is somewhere other than `~/Android/Sdk`, either keep `ANDROID_HOME` set as above or create a file named
`local.properties` in the checkout containing `sdk.dir=/path/to/your/Sdk`.

### 3. Build

**A debug APK you can install right away** (signed with your computer's own debug key):

```sh
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install app/build/outputs/apk/debug/app-debug.apk`, or copy it to the phone and open it.

**A release APK** (unsigned, so you sign it with your own key):

```sh
./gradlew assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release-unsigned.apk`.

**The F-Droid variant**, which leaves updates to the app store and uses the Orbot app for Tor instead of the built-in
Tor (this is how F-Droid and other app stores build it):

```sh
./gradlew assembleRelease -Pfdroid=true
```

The first build downloads Gradle and the libraries, which takes a few minutes.

### 4. Sign a release APK

Android only installs signed APKs. To sign the release APK with a key of your own (`keytool` comes with the JDK):

```sh
keytool -genkeypair -keystore my.jks -alias me -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Me"
```

```sh
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
```

```sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks my.jks --ks-key-alias me --out FluidMessages.apk aligned.apk
```

An APK you sign yourself won't install over the official release (or the other way around) because the signatures
differ. Uninstall one before installing the other, after backing up your messages in the app.

Official releases are signed with the FluidMessages release key by `scripts/release` and `scripts/sign-apk`. To check
that an APK is official, see [verify.md](verify.md).

### 5. Run the tests (optional)

```sh
./gradlew testDebugUnitTest
```

## FluidMessages Web (optional)

FluidMessages Web is plain Python and JavaScript, so it runs from source without a build step. It needs a recent Python 3
(the Docker image uses 3.13) and OpenSSL (for the certificate phones pair with).

```sh
python3 -m venv .venv
```

```sh
.venv/bin/pip install "slixmpp==1.17.0" "aiohttp>=3.10" "matrix-nio[e2e]==0.26.0" "omemo==2.1.0" "doubleratchet==1.3.0" "x3dh==1.3.0" "xeddsa==1.2.0"
```

```sh
.venv/bin/python server/main.py
```

Then open <http://localhost:8080> in a browser on the same computer. The Python packages are optional: without them, the XMPP, Matrix encryption and OMEMO
features are turned off. To run it in Docker instead, see [self-hosting.md](self-hosting.md).

To build the desktop app (Windows `.exe`, macOS `.dmg` or Linux `.AppImage`) for the computer you're on:

```sh
.venv/bin/pip install pyinstaller pillow
```

```sh
.venv/bin/python desktop/build.py --version 0.0.0
```

The result is in `dist/`.
