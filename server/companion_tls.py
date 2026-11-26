"""FluidMessages Web's own certificate, so the phone can pair over real HTTPS without a domain name.

On first start the server makes a self-signed certificate and key in its data folder (companion-cert.pem and
companion-key.pem, the key readable only by this user) with the openssl command, which the Docker image ships and
macOS and Linux have. It then serves HTTPS on a second port (FLUID_TLS_PORT, by default FLUID_PORT + 1; 0 turns it
off). The phone gets the certificate's SHA-256 fingerprint from the pairing QR code (or it's typed in) and trusts
exactly that certificate, so no certificate authority is involved. Browsers keep using the http port or a reverse
proxy, as before. The key is never printed or logged.
"""
import hashlib
import os
import shutil
import socket
import ssl
import subprocess
import sys
from http.server import ThreadingHTTPServer
from pathlib import Path

CERT_NAME, KEY_NAME = "companion-cert.pem", "companion-key.pem"
# Ten years: the phone pins this exact certificate, so a new one only means pairing again.
DAYS = 3650


def tls_port(http_port, value=None):
    """The HTTPS port from FLUID_TLS_PORT: a number, 0 (or off) for none, or FLUID_PORT + 1 when it isn't set."""
    value = (os.environ.get("FLUID_TLS_PORT", "") if value is None else value).strip().lower()
    if value in ("0", "off", "no", "false"):
        return 0
    if not value:
        return http_port + 1 if http_port < 65535 else 0
    port = int(value)
    if not 0 <= port <= 65535:
        raise ValueError("FLUID_TLS_PORT must be a port number, or 0 to turn HTTPS off")
    return port


def system_env():
    """The environment for running the computer's own openssl. The packaged desktop apps (PyInstaller) put their bundled
    libraries first on the library path, and a newer system openssl can't start with those, so they're put back."""
    env = dict(os.environ)
    if getattr(sys, "frozen", False):
        for name in ("LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH"):
            original = env.pop(name + "_ORIG", None)
            if original:
                env[name] = original
            else:
                env.pop(name, None)
    return env


def ensure(folder, openssl=None):
    """The certificate and key paths in [folder], made on first use. None when openssl isn't installed."""
    folder = Path(folder)
    cert, key = folder / CERT_NAME, folder / KEY_NAME
    if cert.exists() and key.exists():
        os.chmod(key, 0o600)
        return cert, key
    tool = openssl or shutil.which("openssl")
    if not tool:
        return None
    folder.mkdir(parents=True, exist_ok=True)
    new_cert, new_key = cert.with_suffix(".new"), key.with_suffix(".new")
    for path in (new_cert, new_key):
        path.unlink(missing_ok=True)
    # The key file exists, readable only by this user, before openssl writes into it.
    os.close(os.open(new_key, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600))
    try:
        subprocess.run([tool, "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
                        "-keyout", str(new_key), "-out", str(new_cert), "-days", str(DAYS), "-subj", "/CN=FluidMessages Web",
                        "-addext", "basicConstraints=critical,CA:FALSE",
                        "-addext", "keyUsage=critical,digitalSignature",
                        "-addext", "extendedKeyUsage=serverAuth"],
                       check=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60,
                       env=system_env())
        os.chmod(new_key, 0o600)
        os.replace(new_key, key)
        os.replace(new_cert, cert)
    except (OSError, subprocess.SubprocessError):
        for path in (new_cert, new_key):
            path.unlink(missing_ok=True)
        return None
    return cert, key


def fingerprint(cert):
    """The certificate's SHA-256 fingerprint, as 64 hex digits in pairs: AB:CD:…"""
    der = ssl.PEM_cert_to_DER_cert(Path(cert).read_text())
    digest = hashlib.sha256(der).hexdigest().upper()
    return ":".join(digest[index:index + 2] for index in range(0, len(digest), 2))


class TlsServer(ThreadingHTTPServer):
    """HTTPS on its own port. The handshake runs in each request's own thread, so a slow client can't hold up others."""

    def __init__(self, address, handler, cert, key):
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.minimum_version = ssl.TLSVersion.TLSv1_2
        self.context.load_cert_chain(str(cert), str(key))
        super().__init__(address, handler)

    def get_request(self):
        sock, address = self.socket.accept()
        return self.context.wrap_socket(sock, server_side=True, do_handshake_on_connect=False), address

    def finish_request(self, request, client_address):
        request.settimeout(15)
        try:
            request.do_handshake()
        except (OSError, ssl.SSLError, socket.timeout):
            return
        super().finish_request(request, client_address)

    def handle_error(self, request, client_address):
        # Failed handshakes and dropped connections are routine on an open port and aren't logged; anything else is.
        if not isinstance(sys.exc_info()[1], (OSError, ssl.SSLError)):
            super().handle_error(request, client_address)
