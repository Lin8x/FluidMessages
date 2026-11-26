# Check your download

Anyone can copy an app, change it, and share it again. Checking your download tells you it really came from FluidMessages and nobody changed it on the way.

## The official signing key

Every FluidMessages Android app is signed with this key. Its SHA-256 fingerprint is:

```
2D:09:80:EE:4C:11:BF:65:54:82:E7:EE:92:79:C6:E6
6B:56:63:6E:8E:18:C8:AC:06:3E:1F:08:78:5D:F5:BC
```

Without the colons, the way `apksigner` prints it:

```
2d0980ee4c11bf655482e7ee9279c6e66b56636e8e18c8ac063e1f08785df5bc
```

The package name is `org.fluidmessages`.

The certificate's name is "FluidMessages".

### The key changed in 0.45

Up to version 0.44, FluidMessages was signed with an older key:

```
AF:FA:5E:31:F7:19:41:1E:B6:90:81:A6:31:A1:5A:ED
94:DD:5C:D0:BE:16:EB:D2:BD:5A:A7:3A:E9:01:18:81
```

From 0.45 on, releases are signed with the new key above. The old key signed a handover to the new one (Android calls this key rotation), so phones that installed an older version accept the new one as a normal update and keep all their messages. Android checks that handover itself. To see it, run `apksigner lineage --in FluidMessages.apk --print-certs`: it lists the old key first, then the new one.

If you're on 0.44, FluidMessages' own update button doesn't know about the handover yet and says the release uses a different signing key. Download the new APK from the [releases page](https://github.com/Lin8x/FluidMessages/releases) and install it over your current app once. Your messages stay. Later updates work from inside the app again.

## On your phone: in FluidMessages

Open **Settings → About and updates**. Under **This app's signing key** you'll see your copy's fingerprint.

- **Matches the official FluidMessages key**: you're good.
- **This copy was signed by someone else**: don't use it. Uninstall it and get FluidMessages from the [official releases page](https://github.com/Lin8x/FluidMessages/releases).

If you got FluidMessages from F-Droid, F-Droid may have built it from the source code and signed it with F-Droid's own key. Then the page says **Signed by F-Droid** instead, and AppVerifier won't match the key above. That's expected.

A changed app could lie about itself, so for a stronger check use AppVerifier too.

## On your phone: with AppVerifier

[AppVerifier](https://github.com/soupslurpr/AppVerifier) is a free, open-source app that checks other apps' signing keys. You can get it from F-Droid, Accrescent, or its GitHub page.

1. Install FluidMessages.
2. Copy these two lines:

   ```
   org.fluidmessages
   2D:09:80:EE:4C:11:BF:65:54:82:E7:EE:92:79:C6:E6:6B:56:63:6E:8E:18:C8:AC:06:3E:1F:08:78:5D:F5:BC
   ```

3. Open AppVerifier and choose to verify from the clipboard (paste).
4. It should say the key matches. If it says it doesn't, uninstall that copy.

## On a computer: with apksigner

`apksigner` comes with the Android SDK, in `build-tools/<version>/`.

```sh
apksigner verify --print-certs FluidMessages.apk
```

Look for the line `Signer #1 certificate SHA-256 digest:`. It must show:

```
2d0980ee4c11bf655482e7ee9279c6e66b56636e8e18c8ac063e1f08785df5bc
```

If there is a different number, or more than one signer, don't install it.

## Desktop apps and the server: SHA-256 sums

Each release from 0.45 on has a file called `SHA256SUMS.txt` with a SHA-256 sum (a long number made from the file) for every download. GitHub also shows each file's SHA-256 on the release page. If even one byte of a file changes, its sum changes.

Download `SHA256SUMS.txt` from the same release as your file, then:

**Linux**, in the folder with both files:

```sh
sha256sum --check --ignore-missing SHA256SUMS.txt
```

It should say `OK` next to your file.

**Mac**, in Terminal:

```sh
shasum -a 256 FluidMessages-Web-macOS.dmg
```

**Windows**, in PowerShell:

```powershell
Get-FileHash .\FluidMessages-Web-Windows.exe -Algorithm SHA256
```

On Mac and Windows, compare the number it prints with the line for your file in `SHA256SUMS.txt`. Upper or lower case doesn't matter. Every character must match.

This works the same for the Android app (`.apk`) and the server (`.zip`).

The Windows and Mac apps aren't code-signed yet, so your computer may warn you the first time. A matching SHA-256 sum is how you know the file is the real one.

## Updates are protected too

Android itself only installs an update if it's signed with the same key as the app you already have. If someone sends you a changed FluidMessages, Android refuses to install it over yours. FluidMessages' own update check makes the same check before it offers an update.

That also means: if you first installed a copy signed by someone else, real updates won't install over it. Uninstall it first (make a backup in **Settings → Backups**), then install the official app.

## Found a fake?

Please tell us privately. See [SECURITY.md](../SECURITY.md).
