"""Fetches link previews and pictures for FluidMessages Web, so the browser never contacts other sites directly.

Only public HTTPS addresses are allowed: every hop is resolved first and refused if it points at a private,
loopback, link-local, or otherwise reserved address, and the connection goes to the address that was checked.
"""
import html
import http.client
import ipaddress
import re
import socket
import ssl
import time
from urllib.parse import urljoin, urlsplit

_CACHE = {}
_CACHE_SECONDS = 3600


class Refused(Exception):
    pass


def _public_address(host, port):
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except socket.gaierror as error:
        raise Refused("unknown host") from error
    for family, _, _, _, address in infos:
        ip = ipaddress.ip_address(address[0])
        if not ip.is_global or ip.is_multicast:
            raise Refused("not a public address")
    family, _, _, _, address = infos[0]
    return family, address


class _Pinned(http.client.HTTPSConnection):
    """An HTTPS connection to an address that was already checked, with the hostname kept for the certificate."""

    def __init__(self, host, port, family, address, timeout):
        super().__init__(host, port, timeout=timeout, context=ssl.create_default_context())
        self._family, self._address = family, address

    def connect(self):
        raw = socket.socket(self._family, socket.SOCK_STREAM)
        raw.settimeout(self.timeout)
        raw.connect(self._address)
        self.sock = self._context.wrap_socket(raw, server_hostname=self.host)


def fetch(url, limit, accept):
    """Returns (final url, content type, body bytes). Follows up to four redirects, each one checked again."""
    for _ in range(5):
        parts = urlsplit(url)
        if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
            raise Refused("only https links")
        port = parts.port or 443
        family, address = _public_address(parts.hostname, port)
        connection = _Pinned(parts.hostname, port, family, address, 10)
        path = (parts.path or "/") + ("?" + parts.query if parts.query else "")
        try:
            connection.request("GET", path, headers={"User-Agent": "Mozilla/5.0 (compatible; FluidMessages link preview)",
                                                     "Accept": accept, "Accept-Encoding": "identity"})
            response = connection.getresponse()
            if response.status in (301, 302, 303, 307, 308):
                location = response.getheader("Location")
                if not location:
                    raise Refused("redirect without a location")
                url = urljoin(url, location)
                continue
            if response.status != 200:
                raise Refused("status %d" % response.status)
            length = response.getheader("Content-Length")
            if length and length.isdigit() and int(length) > limit:
                raise Refused("too large")
            body = response.read(limit + 1)
            if len(body) > limit:
                raise Refused("too large")
            return url, (response.getheader("Content-Type") or "").split(";")[0].strip().lower(), body
        finally:
            connection.close()
    raise Refused("too many redirects")


def _meta(page, *names):
    for name in names:
        for tag in re.findall(r"<meta[^>]+>", page, re.I):
            if re.search(r"""(?:property|name)\s*=\s*["']%s["']""" % re.escape(name), tag, re.I):
                content = re.search(r"""content\s*=\s*["']([^"']*)["']""", tag, re.I)
                if content and content.group(1).strip():
                    return html.unescape(content.group(1).strip())
    return ""


def preview(url):
    """Title, description, picture, and site name from a page's Open Graph tags, cached for an hour."""
    cached = _CACHE.get(url)
    if cached and time.time() - cached[0] < _CACHE_SECONDS:
        return cached[1]
    result = None
    try:
        final, kind, body = fetch(url, 512 * 1024, "text/html")
        if "html" in kind:
            page = body.decode("utf-8", "replace")
            title = _meta(page, "og:title", "twitter:title")
            if not title:
                match = re.search(r"<title[^>]*>([^<]{1,300})</title>", page, re.I)
                title = html.unescape(match.group(1).strip()) if match else ""
            image = _meta(page, "og:image:secure_url", "og:image", "twitter:image")
            image = urljoin(final, image) if image else ""
            if title:
                result = {"url": url, "title": title[:200], "description": _meta(page, "og:description", "description")[:300],
                          "image": image if image.startswith("https://") else "",
                          "site": _meta(page, "og:site_name") or (urlsplit(final).hostname or "").removeprefix("www.")}
    except (Refused, OSError, http.client.HTTPException, ValueError):
        result = None
    if len(_CACHE) > 500:
        _CACHE.clear()
    _CACHE[url] = (time.time(), result)
    return result


def picture(url):
    """A picture or GIF from a public HTTPS address, at most 15 MB. Returns (content type, bytes) or None."""
    try:
        _, kind, body = fetch(url, 15 * 1024 * 1024, "image/*")
    except (Refused, OSError, http.client.HTTPException, ValueError):
        return None
    if not kind.startswith("image/") or kind == "image/svg+xml":
        return None
    return kind, body


def gif_search(provider, query, key, stickers):
    """Searches Tenor or GIPHY with the person's own API key. Only those two services' own addresses are used."""
    import json
    from urllib.parse import quote
    q, k = quote(query.strip()), quote(key.strip())
    if provider == "tenor":
        media = "tinygif_transparent,gif_transparent" if stickers else "tinygif,gif"
        path = ("/v2/featured?" if not query.strip() else "/v2/search?q=%s&" % q)
        url = "https://tenor.googleapis.com%skey=%s&client_key=fluidmessages&limit=30&contentfilter=medium&media_filter=%s%s" % (
            path, k, media, "&searchfilter=sticker" if stickers else "")
    elif provider == "giphy":
        kind = "stickers" if stickers else "gifs"
        path = ("/v1/%s/trending?" % kind) if not query.strip() else ("/v1/%s/search?q=%s&" % (kind, q))
        url = "https://api.giphy.com%sapi_key=%s&limit=30&rating=pg-13" % (path, k)
    else:
        return []
    try:
        _, _, body = fetch(url, 2_000_000, "application/json")
        data = json.loads(body)
    except (Refused, OSError, http.client.HTTPException, ValueError):
        return []
    results = []
    if provider == "tenor":
        for item in data.get("results", []):
            formats = item.get("media_formats", {})
            small = formats.get("tinygif_transparent" if stickers else "tinygif") or {}
            full = formats.get("gif_transparent" if stickers else "gif") or small
            if small.get("url") and full.get("url"):
                results.append({"preview": small["url"], "send": full["url"]})
    else:
        for item in data.get("data", []):
            images = item.get("images", {})
            small = images.get("fixed_width_small") or images.get("fixed_width") or {}
            full = images.get("downsized") or images.get("original") or small
            if small.get("url") and full.get("url"):
                results.append({"preview": small["url"], "send": full["url"].split("?")[0]})
    return [item for item in results if item["preview"].startswith("https://") and item["send"].startswith("https://")]
