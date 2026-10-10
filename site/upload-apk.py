"""Upload an APK of any size to the site's R2 bucket and make it the download.

Usage: upload-apk.py <apk> <version> <sha256> <site_url> <token> [--no-publish]

Uploads through the site Worker (zh_r2.py), records it as the newest build (what
bundle-game-data.sh fetches) and, unless --no-publish, makes it the site's download.
"""
import json
import os
import sys
import time

import zh_r2


def main():
    apk, version, sha256, site, token = sys.argv[1:6]
    publish = "--no-publish" not in sys.argv[6:]
    site = site.rstrip("/")
    size = os.path.getsize(apk)
    key = "apk/ZH-Commander-%s-%s.apk" % (version, sha256[:8])

    zh_r2.upload_file(site, token, apk, key, size)

    latest = {"key": key, "version": version, "size": size, "sha256": sha256,
              "published": time.strftime("%Y-%m-%d", time.gmtime())}
    # For the admin console's Releases tab, when CI provides them.
    if os.environ.get("GZH_BUILD", "").isdigit():
        latest["build"] = int(os.environ["GZH_BUILD"])
    if os.environ.get("GZH_MANDATORY") in ("true", "false"):
        latest["mandatory"] = os.environ["GZH_MANDATORY"] == "true"
    if os.environ.get("GZH_RUN_URL"):
        latest["run_url"] = os.environ["GZH_RUN_URL"]
    zh_r2.call("POST", "%s/admin/upload/record" % site, token, json.dumps(latest).encode())
    if publish:
        zh_r2.call("POST", "%s/admin/upload/publish" % site, token, json.dumps(latest).encode())
    print("%s %s (%d MB)" % ("published" if publish else "uploaded", key, size >> 20))
    if publish:
        zh_r2.prune(site, token)


if __name__ == "__main__":
    main()
