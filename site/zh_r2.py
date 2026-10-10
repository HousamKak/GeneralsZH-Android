"""Upload files of any size to the site's R2 bucket through the site Worker's /admin/upload/*.

Shared by upload-apk.py and upload-data.py. A Worker request body is capped at 100 MB and R2
objects are not, so each file goes up as an R2 multipart upload in PART_SIZE pieces.

Windows requires Python 3.10+ and truststore: python -m pip install -r site/requirements.txt
"""
from functools import lru_cache
import json
import ssl
import sys
import time
import urllib.parse
import urllib.request

PART_SIZE = 64 * 1024 * 1024  # R2 wants every part but the last the same size, at least 5 MiB


# GeneralsX @bugfix Codex 08/10/2026 Use native Windows certificate-chain validation.
@lru_cache(maxsize=1)
def https_context():
    if sys.platform == "win32":
        # OpenSSL can select an expired intermediate from Windows' certificate cache.
        # CryptoAPI builds the chain using the native store and refreshes intermediates.
        try:
            import truststore
        except ImportError as exc:
            raise SystemExit(
                "Windows uploads need Python 3.10+ and truststore. Install with:\n"
                '  "%s" -m pip install -r site/requirements.txt' % sys.executable
            ) from exc
        return truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    return ssl.create_default_context()


def call(method, url, token, body=None, content_type="application/json"):
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Content-Type", content_type)
    # Cloudflare turns away urllib's default user agent.
    req.add_header("User-Agent", "zh-commander-upload/1")
    with urllib.request.urlopen(req, timeout=600, context=https_context()) as res:
        return json.loads(res.read().decode("utf-8"))


# GeneralsX @feature ZH Commander 10/10/2026 Prune old APKs and IPAs after a release.
def prune(site, token):
    """Delete builds older than the newest 2 per platform (site/src/prune.ts). Never fails a release."""
    try:
        r = call("POST", "%s/admin/prune" % site, token, b"{}")
        print("pruned %d old builds (%d MB)" % (len(r["deleted"]), r["bytes_freed"] >> 20))
    except Exception as exc:  # noqa: BLE001 - a failed prune only leaves files for the next one
        print("prune skipped: %s" % exc)


def upload_file(site, token, path, key, size, label=""):
    """Upload one local file to R2 under key, printing progress. Aborts the upload on failure."""
    q = "key=" + urllib.parse.quote(key, safe="")
    upload_id = call("POST", "%s/admin/upload/start?%s" % (site, q), token, b"{}")["uploadId"]
    q += "&uploadId=" + urllib.parse.quote(upload_id, safe="")
    parts = []
    sent = 0
    started = time.time()
    try:
        with open(path, "rb") as f:
            number = 1
            while True:
                chunk = f.read(PART_SIZE)
                if not chunk:
                    break
                part = call("PUT", "%s/admin/upload/part?%s&part=%d" % (site, q, number), token,
                            chunk, "application/octet-stream")
                parts.append({"partNumber": part["partNumber"], "etag": part["etag"]})
                sent += len(chunk)
                rate = sent / max(time.time() - started, 0.001) / 1048576
                print("  %s%3d%%  %d / %d MB  (%.1f MB/s)" % (label, sent * 100 // max(size, 1), sent >> 20,
                                                            size >> 20, rate), flush=True)
                number += 1
        call("POST", "%s/admin/upload/complete?%s" % (site, q), token, json.dumps({"parts": parts}).encode())
    except Exception:
        try:
            call("POST", "%s/admin/upload/abort?%s" % (site, q), token, b"{}")
        finally:
            raise
