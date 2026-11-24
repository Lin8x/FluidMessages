"""Builds the FluidMessages Web desktop app for the system this runs on, into dist/.

    python desktop/build.py --version 0.33.0

Windows makes FluidMessages-Web-<version>-Windows.exe, macOS makes FluidMessages-Web-<version>-macOS.dmg, and Linux
makes FluidMessages-Web-<version>-Linux-x86_64.AppImage (or a .tar.gz when appimagetool isn't available). Needs
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
SERVER_MODULES = ["main", "accounts", "cloud_backup", "link_fetch", "protocol_gateway", "irc_client", "matrix_e2ee", "xmpp_client"]


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
    for optional in ("slixmpp", "aiohttp", "nio"):
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
    elif system == "Darwin":
        target = ROOT / "dist" / f"FluidMessages-Web-{version}-macOS.dmg"
        bundle = work / "dist" / f"{NAME}.app"
        subprocess.run(["hdiutil", "create", "-volname", NAME, "-srcfolder", str(bundle), "-ov", "-format", "UDZO", str(target)], check=True)
        check = [str(bundle / "Contents/MacOS" / NAME), "--check"]
    else:
        target = linux(work, version)
        check = [str(work / "dist" / NAME), "--check"]
    # Every build proves it can start its server and serve the web app before it's released.
    subprocess.run(check, check=True, timeout=120)
    print("Built", target)


if __name__ == "__main__":
    main()
