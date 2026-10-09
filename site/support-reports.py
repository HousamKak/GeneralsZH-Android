#!/usr/bin/env python3
"""List or download the support reports players sent from the game's SUPPORT button.

  python site/support-reports.py            newest reports: reference, date, platform, version, device
  python site/support-reports.py ABCD2345   download that report's zip into the current folder

The upload token comes from GZH_UPLOAD_TOKEN or ~/.generalszh/license_admin_token, as for the
upload scripts. Reports live in the site's R2 bucket under support/ (site/src/support.ts).
"""
import json
import os
import sys
import urllib.request

SITE = "https://zh-commander.housam-kak20.workers.dev"


def token():
    value = os.environ.get("GZH_UPLOAD_TOKEN")
    if value:
        return value.strip()
    with open(os.path.expanduser("~/.generalszh/license_admin_token")) as f:
        return f.read().strip()


def get(path):
    # The workers.dev address and a named client, as the upload scripts use (zh_r2.py): the
    # custom domain's bot protection turns Python's default client away.
    req = urllib.request.Request(SITE + path, headers={
        "Authorization": "Bearer " + token(), "User-Agent": "zh-commander-support/1"})
    context = None
    if sys.platform == "win32":
        import ssl
        import truststore  # the Windows certificate store; see site/requirements.txt
        context = truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    return urllib.request.urlopen(req, timeout=60, context=context)


def main():
    if len(sys.argv) > 1:
        ref = sys.argv[1].upper()
        name = f"zh-report-{ref}.zip"
        with get(f"/admin/support/{ref}") as r, open(name, "wb") as out:
            out.write(r.read())
        print(name)
        return
    with get("/admin/support") as r:
        reports = json.load(r)["reports"]
    if not reports:
        print("No reports yet.")
    for rep in reports:
        print(f"{rep.get('ref', '?'):9} {rep['uploaded'][:16]}  {rep.get('platform', ''):8} "
              f"{rep.get('version', ''):8} {rep.get('model', '')[:28]:28} {rep.get('device', '')[:12]}  "
              f"{rep['size'] // 1024} KB")


if __name__ == "__main__":
    main()
