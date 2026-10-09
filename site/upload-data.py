"""Publish a folder of game data for activated apps to download.

Usage: upload-data.py <folder> <version> [--reuse] [--site URL]

Every file under <folder> is uploaded to R2 under data/<version>/<its path>, then
data/manifest.json is published listing each one's path, size and SHA-256. The app downloads
the files into its GameData folder, keeping the same layout (a base-game archive goes in a
ZH_Generals/ subfolder, as the engine expects). Unchanged files are not re-downloaded by apps
that already have them.

The upload token comes from ~/.generalszh/license_admin_token or GZH_UPLOAD_TOKEN.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time

import zh_r2

DEFAULT_SITE = "https://zh-commander.housam-kak20.workers.dev"
SEGMENT_RE = re.compile(r"^[A-Za-z0-9._ !-]{1,120}$")


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def token():
    value = os.environ.get("GZH_UPLOAD_TOKEN")
    if value:
        return value.strip()
    home = os.path.expanduser("~")
    with open(os.path.join(home, ".generalszh", "license_admin_token"), encoding="utf-8") as f:
        return f.read().strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folder")
    ap.add_argument("version", help="a name for this data release, e.g. 2026-10-08 or v2")
    ap.add_argument("--site", default=os.environ.get("GZH_SITE_URL", DEFAULT_SITE))
    ap.add_argument("--reuse", action="store_true",
                    help="keep the existing uploads of files that did not change since the last publish")
    a = ap.parse_args()
    if not re.match(r"^[A-Za-z0-9._-]{1,64}$", a.version):
        sys.exit("version may use letters, digits, '.', '_' and '-' only")
    site = a.site.rstrip("/")
    tok = token()

    files = []
    for d, _, names in os.walk(a.folder):
        for name in sorted(names):
            full = os.path.join(d, name)
            rel = os.path.relpath(full, a.folder).replace(os.sep, "/")
            if any(not SEGMENT_RE.match(seg) for seg in rel.split("/")) or rel.count("/") > 4:
                sys.exit("unsupported file name or nesting: %s" % rel)
            files.append((rel, full))
    if not files:
        sys.exit("no files in %s" % a.folder)
    # The game keeps its AI and match rules as loose files, not inside an archive: without
    # Data/Scripts/SkirmishScripts.scb the computer opponents do nothing at all (seen on a phone
    # on 10/10/2026, with a pack that had every .big but no Data/ folder).
    present = {rel.lower() for rel, _ in files}
    required = ["data/scripts/skirmishscripts.scb", "data/scripts/multiplayerscripts.scb", "data/scripts/scripts.ini"]
    missing = [r for r in required if r not in present]
    if missing:
        sys.exit("the game data is missing %s (copy the Data/Scripts folder of the game into %s)"
                 % (", ".join(missing), a.folder))

    # GeneralsX @tweak Codex 08/10/2026 Accept all archives; keep hashes for download integrity.
    print("==> Hashing %d files" % len(files))
    entries = []
    for rel, full in files:
        digest = sha256_of(full)
        entries.append({"path": rel, "size": os.path.getsize(full), "sha256": digest,
                        "key": "data/%s/%s" % (a.version, rel), "local": full})

    # --reuse: a file identical (same path, size and SHA-256) to one already published keeps that
    # upload; only new and changed files are sent. Apps skip unchanged files either way.
    if a.reuse:
        published = {f["path"]: f for f in zh_r2.call("GET", "%s/admin/data/manifest" % site, tok)["files"]}
        for e in entries:
            old = published.get(e["path"])
            if old and old["sha256"] == e["sha256"] and old["size"] == e["size"]:
                e["key"] = old["key"]
                e["reused"] = True
    todo = [e for e in entries if not e.get("reused")]
    total = sum(e["size"] for e in todo)
    print("==> Uploading %d files (%d MB) as data version %s; %d unchanged kept"
          % (len(todo), total >> 20, a.version, len(entries) - len(todo)))
    for i, e in enumerate(todo, 1):
        print("[%d/%d] %s" % (i, len(todo), e["path"]))
        zh_r2.upload_file(site, tok, e["local"], e["key"], e["size"])

    manifest = {"version": a.version, "published": time.strftime("%Y-%m-%d", time.gmtime()),
                "files": [{k: e[k] for k in ("path", "size", "sha256", "key")} for e in entries]}
    zh_r2.call("POST", "%s/admin/data/publish" % site, tok, json.dumps(manifest).encode())
    print("==> Game data %s published: activated apps download it on their next start" % a.version)


if __name__ == "__main__":
    main()
