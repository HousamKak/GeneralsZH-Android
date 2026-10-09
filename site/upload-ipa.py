"""Upload an iOS build (.ipa) to the site's R2 bucket.

Usage: upload-ipa.py <ipa> <version> [build] [--release]

build is the build number (CFBundleVersion, the Android versionCode). The app compares it, not
the version name, to tell whether a release is newer.

The IPA goes to R2 under ipa/ZH-Commander-<version>-<sha8>.ipa and is recorded as the newest
iOS build (builds/ios-latest.json, private). --release also adds it to the site's AltStore
source (/altstore.json), which is how AltStore/SideStore users install and update the app.
The upload token comes from GZH_UPLOAD_TOKEN or ~/.generalszh/license_admin_token; the site
from GZH_SITE_URL.
"""
import hashlib
import json
import os
import sys
import time

import zh_r2

DEFAULT_SITE = "https://zh-commander.housam-kak20.workers.dev"


def token():
    value = os.environ.get("GZH_UPLOAD_TOKEN")
    if value:
        return value.strip()
    with open(os.path.join(os.path.expanduser("~"), ".generalszh", "license_admin_token"), encoding="utf-8") as f:
        return f.read().strip()


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    ipa, version = sys.argv[1], sys.argv[2]
    release = "--release" in sys.argv[3:]
    numbers = [a for a in sys.argv[3:] if a.isdigit()]
    build_number = int(numbers[0]) if numbers else None
    site = os.environ.get("GZH_SITE_URL", DEFAULT_SITE).rstrip("/")
    tok = token()

    h = hashlib.sha256()
    with open(ipa, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    sha256 = h.hexdigest()
    size = os.path.getsize(ipa)
    key = "ipa/ZH-Commander-%s-%s.ipa" % (version, sha256[:8])

    zh_r2.upload_file(site, tok, ipa, key, size)
    build = {"key": key, "version": version, "size": size, "sha256": sha256,
             "published": time.strftime("%Y-%m-%d", time.gmtime())}
    if build_number is not None:
        build["build"] = build_number
    zh_r2.call("POST", "%s/admin/ios/record" % site, tok, json.dumps(build).encode())
    if release:
        zh_r2.call("POST", "%s/admin/ios/release" % site, tok, json.dumps(build).encode())
    print("%s %s (%d MB)" % ("released" if release else "uploaded", key, size >> 20))


if __name__ == "__main__":
    main()
