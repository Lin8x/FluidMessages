"""FluidMessages Web as a desktop app.

Starts FluidMessages Web on this computer, signs you in automatically, and opens it in its own app window (a
Chrome, Edge, or Brave app window when one is installed, otherwise your default browser). A small window shows
that it's running, with Open and Quit. Your data stays in a private folder for your user account.
"""
import os
import platform
import runpy
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser
from pathlib import Path

APP = "FluidMessages Web"


def server_folder():
    # Packaged apps unpack the server next to the launcher; from a checkout it's ../server.
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    return base / "server"


def data_folder():
    system = platform.system()
    if system == "Windows":
        root = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    elif system == "Darwin":
        root = Path.home() / "Library" / "Application Support"
    else:
        root = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    folder = root / APP
    folder.mkdir(parents=True, exist_ok=True)
    if system != "Windows":
        folder.chmod(0o700)
    return folder


def token_for(folder):
    path = folder / "token"
    if not path.exists() or not path.read_text().strip():
        path.write_text(secrets.token_hex(32))
        if platform.system() != "Windows":
            path.chmod(0o600)
    return path.read_text().strip()


def answering(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/manifest.webmanifest", timeout=1) as response:
            return b"FluidMessages" in response.read(2048)
    except OSError:
        return False


def choose_port():
    # The same port every time, so the installed app keeps its address; another one only if it's taken.
    for port in (8484, 8485, 8486, 8487):
        if answering(port):
            return port, True
        with socket.socket() as probe:
            if probe.connect_ex(("127.0.0.1", port)) != 0:
                return port, False
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1], False


def browser():
    system = platform.system()
    if system == "Windows":
        roots = [os.environ.get(name, "") for name in ("PROGRAMFILES(X86)", "PROGRAMFILES", "LOCALAPPDATA")]
        candidates = [Path(root) / path for root in roots if root for path in (
            "Microsoft/Edge/Application/msedge.exe", "Google/Chrome/Application/chrome.exe",
            "BraveSoftware/Brave-Browser/Application/brave.exe")]
    elif system == "Darwin":
        candidates = [Path("/Applications") / name for name in (
            "Google Chrome.app/Contents/MacOS/Google Chrome", "Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "Brave Browser.app/Contents/MacOS/Brave Browser")]
    else:
        candidates = [Path(found) for name in ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
                                                "microsoft-edge", "brave-browser") if (found := shutil.which(name))]
    return next((str(path) for path in candidates if path.exists()), None)


def open_window(url):
    app = browser()
    if app:
        subprocess.Popen([app, "--app=" + url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        webbrowser.open(url)


def check():
    """For build machines: start the packaged server, make sure it answers with the web app, and stop."""
    import tempfile
    folder = Path(tempfile.mkdtemp(prefix="fluid-check-"))
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    server = server_folder()
    os.environ.update(FLUID_TOKEN=secrets.token_hex(32), FLUID_DB=str(folder / "fluid.sqlite"), FLUID_BIND="127.0.0.1",
                      FLUID_PORT=str(port), FLUID_FILES=str(folder / "files"))
    sys.path.insert(0, str(server))
    threading.Thread(target=runpy.run_path, args=(str(server / "main.py"),), kwargs={"run_name": "__main__"}, daemon=True).start()
    for _ in range(150):
        if answering(port):
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=2) as page:
                ok = b"FluidMessages" in page.read()
            print("FluidMessages Web check:", "ok" if ok else "page missing")
            sys.exit(0 if ok else 1)
        time.sleep(0.1)
    print("FluidMessages Web check: the server didn't start")
    sys.exit(1)


def main():
    if "--check" in sys.argv:
        check()
    folder = data_folder()
    token = token_for(folder)
    port, running = choose_port()
    # The token goes in the address's # part, which never leaves the browser; the page signs in and removes it.
    url = f"http://localhost:{port}/#token={token}"
    if running:
        open_window(url)
        return
    server = server_folder()
    os.environ.update(FLUID_TOKEN=token, FLUID_DB=str(folder / "fluid.sqlite"), FLUID_BIND="127.0.0.1",
                      FLUID_PORT=str(port), FLUID_FILES=str(folder / "protocol-files"))
    sys.path.insert(0, str(server))
    thread = threading.Thread(target=runpy.run_path, args=(str(server / "main.py"),), kwargs={"run_name": "__main__"},
                              daemon=True)
    thread.start()
    for _ in range(100):
        if answering(port):
            break
        time.sleep(0.1)
    open_window(url)
    try:
        import tkinter
    except ImportError:
        print(f"{APP} is running at http://localhost:{port}. Close this window or press Ctrl+C to stop.")
        thread.join()
        return
    # A small window says it's running; closing it stops FluidMessages Web.
    window = tkinter.Tk()
    window.title(APP)
    window.resizable(False, False)
    tkinter.Label(window, text=f"{APP} is running on this computer.", padx=24, pady=(14)).pack()
    tkinter.Label(window, text="Close this window to stop it.", fg="#666666", padx=24).pack()
    buttons = tkinter.Frame(window, pady=14)
    buttons.pack()
    tkinter.Button(buttons, text="Open FluidMessages", command=lambda: open_window(url), padx=12).pack(side="left", padx=6)
    tkinter.Button(buttons, text="Quit", command=window.destroy, padx=12).pack(side="left", padx=6)
    window.mainloop()


if __name__ == "__main__":
    main()
