"""Publish a folder of game data for activated apps to download.

Usage: upload-data.py <folder> <version> [--reuse] [--check] [--site URL]

Every file under <folder> is uploaded to R2 under data/<version>/<its path>, then
data/manifest.json is published listing each one's path, size and SHA-256. The app downloads
the files into its GameData folder, keeping the same layout (a base-game archive goes in a
ZH_Generals/ subfolder, as the engine expects). Unchanged files are not re-downloaded by apps
that already have them, and a file a new version no longer lists is removed from phones.

Optional packs (mods a player turns on and off in the game's ZH COMMANDER screen, MODS) live in
<folder>/_packs/<id>/, laid out like the game folder, with a pack.json:

  {"title": {"en": "HD unit textures", "ar": "..."},
   "description": {"en": "...", "ar": "..."},
   "gameplay": false}

"gameplay": true is required for a pack that changes how the game plays (INI files or scripts,
loose or inside an archive): every player in an online match needs the same of those, so the
game warns before going online with one on. The upload refuses a pack that changes them without
saying so.

--reuse   keep the existing uploads of files that did not change since the last publish
--check   inspect only: list what would be published, which archives override which, and stop

Archive order: the game mounts archives by name, and the alphabetically first one (ignoring
case) wins when two contain the same file; a loose file wins over any archive. That is why mods
are named "!Something.big" or "100_Something.big". The check lists every file a new or changed
archive overrides, or loses to, so a mod that silently does nothing (or too much) shows up here.

The upload token comes from ~/.generalszh/license_admin_token or GZH_UPLOAD_TOKEN.
"""
import argparse
import hashlib
import json
import os
import re
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import zh_r2  # noqa: E402, next to this script

DEFAULT_SITE = "https://zh-commander.housam-kak20.workers.dev"
SEGMENT_RE = re.compile(r"^[A-Za-z0-9._ !-]{1,120}$")
PACK_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")
PACKS_DIR = "_packs"
REQUIRED = ["data/scripts/skirmishscripts.scb", "data/scripts/multiplayerscripts.scb", "data/scripts/scripts.ini"]
# What makes a pack "gameplay": the simulation reads these, so players online must match.
GAMEPLAY_RE = re.compile(r"^data/(ini|scripts)/|\.(ini|scb)$")


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


def big_entries(path):
    """The file names inside a .big archive (BIGF / BIG4), normalized like the engine: lower case, '/'."""
    with open(path, "rb") as f:
        head = f.read(16)
        if len(head) < 16 or head[:4] not in (b"BIGF", b"BIG4"):
            return []
        count, header_end = struct.unpack(">II", head[8:16])
        table = f.read(max(0, header_end - 16))
    names, pos = [], 0
    for _ in range(count):
        if pos + 8 > len(table):
            break
        end = table.find(b"\0", pos + 8)
        if end < 0:
            break
        names.append(table[pos + 8:end].decode("latin-1").replace("\\", "/").lower())
        pos = end + 1
    return names


def walk(root, skip_packs):
    out = []
    for d, dirs, names in os.walk(root):
        if skip_packs and os.path.abspath(d) == os.path.abspath(root) and PACKS_DIR in dirs:
            dirs.remove(PACKS_DIR)
        for name in sorted(names):
            full = os.path.join(d, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if rel == "pack.json":
                continue
            if any(not SEGMENT_RE.match(seg) for seg in rel.split("/")) or rel.count("/") > 4:
                sys.exit("unsupported file name or nesting: %s" % rel)
            out.append((rel, full))
    return out


def read_packs(folder):
    packs = []
    root = os.path.join(folder, PACKS_DIR)
    if not os.path.isdir(root):
        return packs
    for pack_id in sorted(os.listdir(root)):
        pdir = os.path.join(root, pack_id)
        if not os.path.isdir(pdir):
            continue
        if not PACK_ID_RE.match(pack_id):
            sys.exit("pack folder %s: use lower case letters, digits and '-'" % pack_id)
        try:
            with open(os.path.join(pdir, "pack.json"), encoding="utf-8") as f:
                meta = json.load(f)
        except (OSError, ValueError) as e:
            sys.exit("pack %s needs a valid pack.json (%s)" % (pack_id, e))
        for field in ("title", "description"):
            v = meta.get(field)
            if not isinstance(v, dict) or not v.get("en") or not v.get("ar"):
                sys.exit("pack %s: pack.json needs %s.en and %s.ar" % (pack_id, field, field))
        files = walk(pdir, skip_packs=False)
        if not files:
            sys.exit("pack %s has no files" % pack_id)
        packs.append({"id": pack_id, "meta": meta, "files": files})
    return packs


def archive_order_report(entries, published_paths):
    """Which files a new or changed archive overrides or loses to, by the engine's rule."""
    archives = [e for e in entries if e["path"].lower().endswith(".big")]
    owners = {}
    for e in archives:
        for name in e["inner"]:
            owners.setdefault(name, []).append(e)
    loose = {e["path"].lower() for e in entries if not e["path"].lower().endswith(".big")}
    rank = lambda e: os.path.basename(e["path"]).lower()  # noqa: E731
    lines = []
    for e in sorted(archives, key=rank):
        if e["path"] in published_paths:
            continue  # unchanged since the last publish: its overlaps are not news
        beats, loses, shadowed = {}, {}, 0
        for name in e["inner"]:
            if name in loose:
                shadowed += 1
            for other in owners[name]:
                if other is e:
                    continue
                (beats if rank(e) < rank(other) else loses).setdefault(other["path"], []).append(name)
        for other, names in sorted(beats.items()):
            lines.append("  %s overrides %d file(s) of %s, e.g. %s" % (e["path"], len(names), other, names[0]))
        for other, names in sorted(loses.items()):
            lines.append("  %s LOSES %d file(s) to %s (named earlier), e.g. %s" % (e["path"], len(names), other, names[0]))
        if shadowed:
            lines.append("  %s: %d file(s) are replaced by loose files of the same path" % (e["path"], shadowed))
    return lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folder")
    ap.add_argument("version", help="a name for this data release, e.g. 2026-10-08 or v2")
    ap.add_argument("--site", default=os.environ.get("GZH_SITE_URL", DEFAULT_SITE))
    ap.add_argument("--reuse", action="store_true",
                    help="keep the existing uploads of files that did not change since the last publish")
    ap.add_argument("--check", action="store_true", help="inspect only, upload nothing")
    a = ap.parse_args()
    if not re.match(r"^[A-Za-z0-9._-]{1,64}$", a.version):
        sys.exit("version may use letters, digits, '.', '_' and '-' only")
    site = a.site.rstrip("/")
    tok = token()

    base = walk(a.folder, skip_packs=True)
    if not base:
        sys.exit("no files in %s" % a.folder)
    # The game keeps its AI and match rules as loose files, not inside an archive: without
    # Data/Scripts/SkirmishScripts.scb the computer opponents do nothing at all (seen on a phone
    # on 10/10/2026, with a pack that had every .big but no Data/ folder).
    present = {rel.lower() for rel, _ in base}
    missing = [r for r in REQUIRED if r not in present]
    if missing:
        sys.exit("the game data is missing %s (copy the Data/Scripts folder of the game into %s)"
                 % (", ".join(missing), a.folder))
    packs = read_packs(a.folder)

    print("==> Hashing %d files%s" % (len(base) + sum(len(p["files"]) for p in packs),
                                      " in base and %d pack(s)" % len(packs) if packs else ""))

    def entry(rel, full, key_prefix):
        e = {"path": rel, "size": os.path.getsize(full), "sha256": sha256_of(full),
             "key": "data/%s/%s%s" % (a.version, key_prefix, rel), "local": full}
        e["inner"] = big_entries(full) if rel.lower().endswith(".big") else []
        return e

    entries = [entry(rel, full, "") for rel, full in base]
    seen = {e["path"].lower(): "base" for e in entries}
    for p in packs:
        p["entries"] = [entry(rel, full, "%s/%s/" % (PACKS_DIR, p["id"])) for rel, full in p["files"]]
        for e in p["entries"]:
            if e["path"].lower() in seen:
                sys.exit("%s is in both %s and pack %s: one path, one owner" % (e["path"], seen[e["path"].lower()], p["id"]))
            seen[e["path"].lower()] = "pack " + p["id"]
        # A pack that changes what the simulation reads must say so (see the docstring).
        touches = [e["path"] for e in p["entries"] if GAMEPLAY_RE.search(e["path"].lower())]
        touches += ["%s: %s" % (e["path"], n) for e in p["entries"] for n in e["inner"] if GAMEPLAY_RE.search(n)]
        p["gameplay"] = bool(p["meta"].get("gameplay"))
        if touches and not p["gameplay"]:
            sys.exit("pack %s changes gameplay files (%s); set \"gameplay\": true in its pack.json"
                     % (p["id"], touches[0]))

    published = {}
    if a.reuse or a.check:
        try:
            current = zh_r2.call("GET", "%s/admin/data/manifest" % site, tok)
            for f in current["files"]:
                published[f["path"]] = f
            for pk in current.get("packs", []):
                for f in pk.get("files", []):
                    published["%s/%s/%s" % (PACKS_DIR, pk["id"], f["path"])] = f
        except Exception as e:  # nothing published yet
            print("  (no published data to compare with: %s)" % e)

    def same_as_published(e, prefix):
        old = published.get(prefix + e["path"])
        return old if old and old["sha256"] == e["sha256"] and old["size"] == e["size"] else None

    unchanged = set()
    for e in entries:
        if same_as_published(e, ""):
            unchanged.add(e["path"])
    all_entries = entries + [e for p in packs for e in p["entries"]]
    report = archive_order_report(all_entries, unchanged)
    print("==> Archive order (the alphabetically first archive wins; loose files win over archives)")
    print("\n".join(report) if report else "  no new or changed archive overlaps another")

    if a.reuse:
        for e in entries:
            old = same_as_published(e, "")
            if old:
                e["key"], e["reused"] = old["key"], True
        for p in packs:
            for e in p["entries"]:
                old = same_as_published(e, "%s/%s/" % (PACKS_DIR, p["id"]))
                if old:
                    e["key"], e["reused"] = old["key"], True

    todo = [e for e in all_entries if not e.get("reused")]
    total = sum(e["size"] for e in todo)
    print("==> %d file(s) to upload (%d MB) as data version %s; %d unchanged kept"
          % (len(todo), total >> 20, a.version, len(all_entries) - len(todo)))
    for p in packs:
        print("  pack %s: %d file(s), %d MB%s" % (p["id"], len(p["entries"]),
              sum(e["size"] for e in p["entries"]) >> 20, ", gameplay" if p["gameplay"] else ""))
    if a.check:
        print("==> --check: nothing uploaded")
        return

    for i, e in enumerate(todo, 1):
        print("[%d/%d] %s" % (i, len(todo), e["key"]))
        zh_r2.upload_file(site, tok, e["local"], e["key"], e["size"])

    fields = ("path", "size", "sha256", "key")
    manifest = {
        "version": a.version,
        "published": time.strftime("%Y-%m-%d", time.gmtime()),
        # Base files only here: app versions older than packs read just this list.
        "files": [{k: e[k] for k in fields} for e in entries],
        "packs": [{
            "id": p["id"],
            "title": p["meta"]["title"],
            "description": p["meta"]["description"],
            "gameplay": p["gameplay"],
            "size": sum(e["size"] for e in p["entries"]),
            "files": [{k: e[k] for k in fields} for e in p["entries"]],
        } for p in packs],
    }
    zh_r2.call("POST", "%s/admin/data/publish" % site, tok, json.dumps(manifest, ensure_ascii=False).encode("utf-8"))
    print("==> Game data %s published: activated apps are offered it on their next start" % a.version)


if __name__ == "__main__":
    main()
