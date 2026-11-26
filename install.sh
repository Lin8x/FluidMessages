#!/usr/bin/env bash
# Installs (or updates) FluidMessages Web, the computer app, on Linux or macOS:
#
#   curl -fsSL https://raw.githubusercontent.com/Lin8x/FluidMessages/main/install.sh | bash
#
# To remove it:
#
#   curl -fsSL https://raw.githubusercontent.com/Lin8x/FluidMessages/main/install.sh | bash -s -- --uninstall
#
# Linux (x86_64): puts the AppImage in ~/.local/share/fluidmessages-web, a `fluidmessages-web` command in
#   ~/.local/bin, and FluidMessages Web in your app menu. No sudo needed.
# macOS (Apple silicon): installs FluidMessages Web.app into /Applications (or ~/Applications).
# Windows: use install.ps1 instead.
#
# Every download is checked against the release's SHA256SUMS.txt before anything is installed. Your messages and
# settings are kept on uninstall; the end of the uninstall says where they are.
set -euo pipefail

REPO="https://github.com/Lin8x/FluidMessages"
LATEST="$REPO/releases/latest/download"
APP="FluidMessages Web"
ID="io.github.Lin8x.FluidMessages"

say() { printf '%s\n' "$*"; }
fail() { printf 'FluidMessages install: %s\n' "$*" >&2; exit 1; }
command -v curl >/dev/null || fail "curl is needed."

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fetch() {  # fetch NAME: downloads a release file into $TMP and checks its SHA-256.
  local name="$1"
  say "Downloading $name…"
  curl -fL --progress-bar -o "$TMP/$name" "$LATEST/$name" || fail "Couldn't download $name."
  [[ -f "$TMP/SHA256SUMS.txt" ]] || curl -fsSL -o "$TMP/SHA256SUMS.txt" "$LATEST/SHA256SUMS.txt" || fail "Couldn't download SHA256SUMS.txt."
  local want have
  want=$(awk -v n="$name" '$2 == n { print $1 }' "$TMP/SHA256SUMS.txt")
  [[ -n "$want" ]] || fail "$name isn't listed in SHA256SUMS.txt."
  if command -v sha256sum >/dev/null; then have=$(sha256sum "$TMP/$name" | awk '{print $1}')
  else have=$(shasum -a 256 "$TMP/$name" | awk '{print $1}'); fi
  [[ "$have" == "$want" ]] || fail "$name doesn't match its SHA-256 in SHA256SUMS.txt, so nothing was installed."
  say "Checked: $name matches the release's SHA-256."
}

UNINSTALL=0
for arg in "$@"; do
  case "$arg" in
    --uninstall) UNINSTALL=1 ;;
    -h|--help) sed -n '2,17p' "$0" 2>/dev/null || true; exit 0 ;;
    *) fail "Unknown option: $arg" ;;
  esac
done

linux() {
  local dir="${XDG_DATA_HOME:-$HOME/.local/share}" bin="$HOME/.local/bin"
  local home="$dir/fluidmessages-web" desktop="$dir/applications/$ID.desktop" icon="$dir/icons/hicolor/512x512/apps/$ID.png"
  if ((UNINSTALL)); then
    rm -f "$home/FluidMessages-Web.AppImage" "$bin/fluidmessages-web" "$desktop" "$icon"
    rmdir "$home" 2>/dev/null || true
    command -v update-desktop-database >/dev/null && update-desktop-database "$dir/applications" 2>/dev/null || true
    say "Removed $APP. Your messages and settings are still in $dir/$APP; delete that folder to remove them too."
    return
  fi
  [[ "$(uname -m)" == x86_64 ]] || fail "The Linux app is only built for x86_64 so far. You can run it from source: $REPO/blob/main/docs/building.md"
  fetch FluidMessages-Web-Linux-x86_64.AppImage
  mkdir -p "$home" "$bin" "$(dirname "$desktop")" "$(dirname "$icon")"
  install -m 755 "$TMP/FluidMessages-Web-Linux-x86_64.AppImage" "$home/FluidMessages-Web.AppImage"
  curl -fsSL -o "$icon" "https://raw.githubusercontent.com/Lin8x/FluidMessages/main/server/icons/icon-512.png" || true
  # AppImages need FUSE 2. Without it, the app unpacks itself each time it starts instead, which also works.
  local run="$home/FluidMessages-Web.AppImage"
  if ! { ldconfig -p 2>/dev/null | grep -q 'libfuse\.so\.2'; }; then run="env APPIMAGE_EXTRACT_AND_RUN=1 $run"; fi
  printf '#!/bin/sh\nexec %s "$@"\n' "$run" > "$bin/fluidmessages-web"
  chmod 755 "$bin/fluidmessages-web"
  cat > "$desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$APP
GenericName=Messaging
Comment=Your phone's texts, calls, and chats on your computer
Exec=$bin/fluidmessages-web
Icon=$ID
Categories=Network;Chat;InstantMessaging;
Keywords=SMS;text;messages;chat;Matrix;XMPP;IRC;RCS;
StartupNotify=true
Terminal=false
EOF
  command -v update-desktop-database >/dev/null && update-desktop-database "$dir/applications" 2>/dev/null || true
  say ""
  say "Installed $APP. Open it from your app menu, or run: fluidmessages-web"
  case ":$PATH:" in *":$bin:"*) ;; *) say "($bin isn't on your PATH yet, so use the app menu, or run $bin/fluidmessages-web)" ;; esac
  say "To update later, run the same command again."
}

mac() {
  local target="/Applications"
  [[ -w "$target" ]] || target="$HOME/Applications"
  if ((UNINSTALL)); then
    rm -rf "/Applications/$APP.app" "$HOME/Applications/$APP.app"
    say "Removed $APP. Your messages and settings are still in ~/Library/Application Support/$APP; delete that folder to remove them too."
    return
  fi
  [[ "$(uname -m)" == arm64 ]] || fail "The Mac app is only built for Apple silicon (M1 and newer) so far. On an Intel Mac, run it from source: $REPO/blob/main/docs/building.md"
  fetch FluidMessages-Web-macOS.dmg
  local mount="$TMP/mount"
  mkdir -p "$mount" "$target"
  hdiutil attach -quiet -nobrowse -readonly -mountpoint "$mount" "$TMP/FluidMessages-Web-macOS.dmg" || fail "Couldn't open the disk image."
  pkill -x "$APP" 2>/dev/null || true
  rm -rf "$target/$APP.app"
  cp -R "$mount/$APP.app" "$target/" || { hdiutil detach -quiet "$mount"; fail "Couldn't copy the app to $target."; }
  hdiutil detach -quiet "$mount" || true
  # The app isn't notarized by Apple yet. Its download was checked against the release above, so the quarantine flag
  # that would stop it from opening is removed.
  xattr -dr com.apple.quarantine "$target/$APP.app" 2>/dev/null || true
  say ""
  say "Installed $APP in $target. Open it from Launchpad or Spotlight."
  say "To update later, run the same command again."
}

case "$(uname -s)" in
  Linux) linux ;;
  Darwin) mac ;;
  *) fail "This installer is for Linux and macOS. On Windows, run in PowerShell: irm https://raw.githubusercontent.com/Lin8x/FluidMessages/main/install.ps1 | iex" ;;
esac
