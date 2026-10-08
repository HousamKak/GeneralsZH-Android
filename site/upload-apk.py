"""Upload an APK of any size to the site's R2 bucket and make it the download.

Usage: upload-apk.py <apk> <version> <sha256> <site_url> <token>

Sends the file in PART_SIZE pieces through the site Worker's /admin/upload/* (an R2 multipart
upload: a Worker request body is capped at 100 MB, R2 objects are not), then publishes it.
"""
import json
import os
import sys
import time
import urllib.request

PART_SIZE = 64 * 1024 * 1024  # R2 wants every part but the last the same size, at least 5 MiB


def call(method, url, token, body=None, content_type="application/json"):
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Content-Type", content_type)
    # Cloudflare turns away urllib's default user agent.
    req.add_header("User-Agent", "zh-commander-upload/1")
    with urllib.request.urlopen(req, timeout=600) as res:
        return json.loads(res.read().decode("utf-8"))


def main():
    apk, version, sha256, site, token = sys.argv[1:6]
    site = site.rstrip("/")
    size = os.path.getsize(apk)
    key = "apk/ZH-Commander-%s-%s.apk" % (version, sha256[:8])
    q = "key=" + urllib.parse.quote(key, safe="")

    upload_id = call("POST", "%s/admin/upload/start?%s" % (site, q), token, b"{}")["uploadId"]
    q += "&uploadId=" + urllib.parse.quote(upload_id, safe="")
    parts = []
    sent = 0
    started = time.time()
    try:
        with open(apk, "rb") as f:
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
                print("  %3d%%  %d / %d MB  (%.1f MB/s)" % (sent * 100 // size, sent >> 20, size >> 20, rate),
                      flush=True)
                number += 1
        call("POST", "%s/admin/upload/complete?%s" % (site, q), token, json.dumps({"parts": parts}).encode())
    except Exception:
        try:
            call("POST", "%s/admin/upload/abort?%s" % (site, q), token, b"{}")
        finally:
            raise

    latest = {"key": key, "version": version, "size": size, "sha256": sha256,
              "published": time.strftime("%Y-%m-%d", time.gmtime())}
    call("POST", "%s/admin/upload/publish" % site, token, json.dumps(latest).encode())
    print("published %s (%d MB)" % (key, size >> 20))


if __name__ == "__main__":
    import urllib.parse  # noqa: E402  (kept next to its only use in main)
    main()
