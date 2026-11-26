"""Builds the FluidMessages Web desktop app for the system this runs on, into dist/.

    python desktop/build.py --version 0.33.0

Windows makes FluidMessages-Web-<version>-Windows.exe and, for the Microsoft Store, FluidMessages-Web-<version>-Windows.msix
(unsigned: the Store signs it), macOS makes FluidMessages-Web-<version>-macOS.dmg, and Linux
makes FluidMessages-Web-<version>-Linux-x86_64.AppImage (or a .tar.gz when appimagetool isn't available), plus a .deb
and an .rpm when dpkg-deb and rpmbuild are installed. Needs
PyInstaller and Pillow: pip install pyinstaller pillow. GitHub Actions runs this on each system for every release.
"""
import argparse
import os
import platform
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NAME = "FluidMessages Web"
SERVER_MODULES = ["main", "auth", "accounts", "cloud_backup", "companion_tls", "link_fetch", "protocol_gateway", "irc_client", "matrix_e2ee", "xmpp_client",
                  "xmpp_omemo", "omemo_legacy", "omemo_trust"]


def icons(work):
    from PIL import Image
    image = Image.open(ROOT / "server/icons/icon-512.png").convert("RGBA")
    image.save(work / "icon.ico", sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    image.save(work / "icon.icns")
    image.resize((256, 256)).save(work / "icon.png")


def pyinstaller(work, system):
    data = f"{ROOT / 'server'}{os.pathsep}server"
    command = [sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--name", NAME,
               "--distpath", str(work / "dist"), "--workpath", str(work / "build"), "--specpath", str(work),
               "--add-data", data, "--paths", str(ROOT / "server")]
    for module in SERVER_MODULES:
        command += ["--hidden-import", module]
    for optional in ("slixmpp", "aiohttp", "nio", "omemo", "doubleratchet", "x3dh", "xeddsa"):
        try:
            __import__(optional)
            command += ["--collect-all", optional]
        except ImportError:
            pass
    if system == "Windows":
        command += ["--onefile", "--windowed", "--icon", str(work / "icon.ico")]
    elif system == "Darwin":
        command += ["--windowed", "--icon", str(work / "icon.icns"), "--osx-bundle-identifier", "org.fluidmessages.web"]
    else:
        command += ["--onefile"]
    subprocess.run(command + [str(ROOT / "desktop/launcher.py")], check=True)


def linux(work, version):
    binary = work / "dist" / NAME
    app = work / "AppDir"
    shutil.rmtree(app, ignore_errors=True)
    (app / "usr/bin").mkdir(parents=True)
    shutil.copy2(binary, app / "usr/bin/fluidmessages-web")
    shutil.copy2(work / "icon.png", app / "fluidmessages-web.png")
    (app / "fluidmessages-web.desktop").write_text(
        "[Desktop Entry]\nType=Application\nName=FluidMessages Web\nComment=Your texts, calls, and chats from the web\n"
        "Exec=fluidmessages-web\nIcon=fluidmessages-web\nCategories=Network;Chat;\nTerminal=false\n")
    run = app / "AppRun"
    run.write_text('#!/bin/sh\nexec "$(dirname "$(readlink -f "$0")")/usr/bin/fluidmessages-web" "$@"\n')
    run.chmod(0o755)
    tool = shutil.which("appimagetool") or os.environ.get("APPIMAGETOOL")
    out = ROOT / "dist"
    if tool:
        target = out / f"FluidMessages-Web-{version}-Linux-x86_64.AppImage"
        subprocess.run([tool, "--appimage-extract-and-run", str(app), str(target)], check=True, env={**os.environ, "ARCH": "x86_64"})
        return target
    target = out / f"FluidMessages-Web-{version}-Linux-x86_64.tar.gz"
    with tarfile.open(target, "w:gz") as archive:
        archive.add(binary, f"FluidMessages-Web-{version}/fluidmessages-web")
        archive.add(app / "fluidmessages-web.desktop", f"FluidMessages-Web-{version}/fluidmessages-web.desktop")
        archive.add(work / "icon.png", f"FluidMessages-Web-{version}/fluidmessages-web.png")
    return target


LINUX_ID = "io.github.Lin8x.FluidMessages"
SUMMARY = "Your phone's texts, calls, and chats on your computer"


def linux_root(work):
    """The files the .deb, .rpm and Snap install, laid out from / (also what snap/snapcraft.yaml packs)."""
    root = work / "pkg-root"
    shutil.rmtree(root, ignore_errors=True)
    files = {
        "usr/bin/fluidmessages-web": work / "dist" / NAME,
        f"usr/share/applications/{LINUX_ID}.desktop": ROOT / f"desktop/linux/{LINUX_ID}.desktop",
        f"usr/share/metainfo/{LINUX_ID}.metainfo.xml": ROOT / f"desktop/linux/{LINUX_ID}.metainfo.xml",
        f"usr/share/icons/hicolor/512x512/apps/{LINUX_ID}.png": ROOT / "server/icons/icon-512.png",
    }
    for path, source in files.items():
        (root / path).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, root / path)
        (root / path).chmod(0o755 if path.startswith("usr/bin/") else 0o644)
    for folder in [root, *(p for p in root.rglob("*") if p.is_dir())]:
        folder.chmod(0o755)
    return root


def linux_deb(work, root, version):
    """A .deb for Debian and Ubuntu (22.04 and newer, or Debian 12 and newer: the app needs glibc 2.35)."""
    package = work / "deb"
    shutil.rmtree(package, ignore_errors=True)
    shutil.copytree(root, package, symlinks=True)
    size = sum(f.stat().st_size for f in package.rglob("*") if f.is_file()) // 1024
    (package / "DEBIAN").mkdir()
    (package / "DEBIAN/control").write_text(
        f"Package: fluidmessages-web\nVersion: {version}\nArchitecture: amd64\nMaintainer: Lin8x <Lin8x@users.noreply.github.com>\n"
        f"Installed-Size: {size}\nDepends: libc6 (>= 2.35)\nRecommends: openssl, xdg-utils\nSection: net\nPriority: optional\n"
        f"Homepage: https://github.com/Lin8x/FluidMessages\nDescription: {SUMMARY}\n"
        " FluidMessages Web runs on your computer and shows your Android phone's texts, calls, and chats in an app\n"
        " window. Your phone pairs with it over your own network; nothing goes through a cloud service.\n")
    target = ROOT / "dist" / f"FluidMessages-Web-{version}-Linux-amd64.deb"
    subprocess.run(["dpkg-deb", "--root-owner-group", "-Zxz", "--build", str(package), str(target)], check=True)
    return target


def linux_rpm(work, root, version):
    """An .rpm for Fedora, openSUSE and others (the app needs glibc 2.35, so Fedora 36 and newer)."""
    top = work / "rpm"
    shutil.rmtree(top, ignore_errors=True)
    (top / "SPECS").mkdir(parents=True)
    listed = sorted("/" + str(p.relative_to(root)) for p in root.rglob("*") if p.is_file())
    # The app is a PyInstaller program that unpacks itself: stripping or rewriting it would break it, so rpm's
    # post-install processing is off, and so is automatic dependency scanning (it has its own libraries inside).
    (top / "SPECS/fluidmessages-web.spec").write_text(f"""%global __os_install_post %{{nil}}
%global debug_package %{{nil}}
%global _build_id_links none
Name: fluidmessages-web
Version: {version.replace('-', '_')}
Release: 1
Summary: {SUMMARY}
License: Apache-2.0
URL: https://github.com/Lin8x/FluidMessages
ExclusiveArch: x86_64
AutoReqProv: no
Requires: glibc >= 2.35
Recommends: openssl
Recommends: xdg-utils

%description
FluidMessages Web runs on your computer and shows your Android phone's texts, calls, and chats in an app window.
Your phone pairs with it over your own network; nothing goes through a cloud service.

%install
cp -a {root}/. %{{buildroot}}/

%files
""" + "\n".join(listed) + "\n")
    subprocess.run(["rpmbuild", "-bb", "--target", "x86_64", "--define", f"_topdir {top}",
                    str(top / "SPECS/fluidmessages-web.spec")], check=True)
    built = next((top / "RPMS/x86_64").glob("fluidmessages-web-*.rpm"))
    target = ROOT / "dist" / f"FluidMessages-Web-{version}-Linux-x86_64.rpm"
    shutil.copy2(built, target)
    return target


def linux_packages(work, version):
    root = linux_root(work)
    built = []
    for tool, make in (("dpkg-deb", linux_deb), ("rpmbuild", linux_rpm)):
        if shutil.which(tool):
            built.append(make(work, root, version))
        else:
            print(f"Skipped the {make.__name__.removeprefix('linux_')} package: {tool} isn't installed.")
    return built


# The Microsoft Store package's identity. After reserving the app name in Partner Center (Product identity), set these
# as GitHub repository variables so they match, or the Store rejects the upload.
MSIX_NAME = os.environ.get("MSIX_IDENTITY_NAME") or "Lin8x.FluidMessagesWeb"
MSIX_PUBLISHER = os.environ.get("MSIX_PUBLISHER") or "CN=Lin8x"
MSIX_PUBLISHER_NAME = os.environ.get("MSIX_PUBLISHER_DISPLAY_NAME") or "Lin8x"
MSIX_EXE = "FluidMessagesWeb.exe"


def msix_manifest(version):
    from xml.sax.saxutils import quoteattr
    return f"""<?xml version="1.0" encoding="utf-8"?>
<Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
  IgnorableNamespaces="uap rescap">
  <Identity Name={quoteattr(MSIX_NAME)} Publisher={quoteattr(MSIX_PUBLISHER)} Version="{version}.0" ProcessorArchitecture="x64"/>
  <Properties>
    <DisplayName>{NAME}</DisplayName>
    <PublisherDisplayName>{MSIX_PUBLISHER_NAME}</PublisherDisplayName>
    <Logo>Assets\\StoreLogo.png</Logo>
  </Properties>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.26100.0"/>
  </Dependencies>
  <Resources>
    <Resource Language="en-us"/>
  </Resources>
  <Applications>
    <Application Id="FluidMessagesWeb" Executable="{MSIX_EXE}" EntryPoint="Windows.FullTrustApplication">
      <uap:VisualElements DisplayName="{NAME}" Description="Your phone's texts, calls, and chats on your computer"
        BackgroundColor="transparent" Square150x150Logo="Assets\\Square150x150Logo.png" Square44x44Logo="Assets\\Square44x44Logo.png">
        <uap:DefaultTile Wide310x150Logo="Assets\\Wide310x150Logo.png"/>
      </uap:VisualElements>
    </Application>
  </Applications>
  <Capabilities>
    <rescap:Capability Name="runFullTrust"/>
  </Capabilities>
</Package>
"""


def msix_layout(work, exe, version):
    """The folder makeappx packs: the app, its manifest, and the tile and Store logos."""
    from PIL import Image
    layout = work / "msix"
    shutil.rmtree(layout, ignore_errors=True)
    (layout / "Assets").mkdir(parents=True)
    shutil.copy2(exe, layout / MSIX_EXE)
    icon = Image.open(ROOT / "server/icons/icon-512.png").convert("RGBA")
    for name, size in (("StoreLogo", 50), ("Square44x44Logo", 44), ("Square150x150Logo", 150)):
        icon.resize((size, size), Image.LANCZOS).save(layout / "Assets" / f"{name}.png")
    wide = Image.new("RGBA", (310, 150), (0, 0, 0, 0))
    wide.paste(icon.resize((120, 120), Image.LANCZOS), (95, 15))
    wide.save(layout / "Assets" / "Wide310x150Logo.png")
    (layout / "AppxManifest.xml").write_text(msix_manifest(version), encoding="utf-8")
    return layout


def makeappx():
    found = shutil.which("makeappx")
    if found:
        return found
    kits = Path(os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)")) / "Windows Kits" / "10" / "bin"
    tools = sorted(kits.glob("10.*/x64/makeappx.exe"))
    if not tools:
        raise SystemExit("makeappx.exe wasn't found. Install the Windows SDK.")
    return str(tools[-1])


def windows_msix(work, exe, version):
    layout = msix_layout(work, exe, version)
    target = ROOT / "dist" / f"FluidMessages-Web-{version}-Windows.msix"
    subprocess.run([makeappx(), "pack", "/d", str(layout), "/p", str(target), "/o"], check=True)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", required=True)
    version = parser.parse_args().version
    system = platform.system()
    work = ROOT / "build" / "desktop"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    (ROOT / "dist").mkdir(exist_ok=True)
    icons(work)
    pyinstaller(work, system)
    if system == "Windows":
        target = ROOT / "dist" / f"FluidMessages-Web-{version}-Windows.exe"
        shutil.copy2(work / "dist" / f"{NAME}.exe", target)
        check = [str(target), "--check"]
        print("Built", windows_msix(work, target, version))
    elif system == "Darwin":
        target = ROOT / "dist" / f"FluidMessages-Web-{version}-macOS.dmg"
        bundle = work / "dist" / f"{NAME}.app"
        subprocess.run(["hdiutil", "create", "-volname", NAME, "-srcfolder", str(bundle), "-ov", "-format", "UDZO", str(target)], check=True)
        check = [str(bundle / "Contents/MacOS" / NAME), "--check"]
    else:
        target = linux(work, version)
        for package in linux_packages(work, version):
            print("Built", package)
        check = [str(work / "dist" / NAME), "--check"]
    # Every build proves it can start its server and serve the web app before it's released.
    subprocess.run(check, check=True, timeout=120)
    print("Built", target)


if __name__ == "__main__":
    main()
