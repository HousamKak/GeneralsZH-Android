"""Print an APK's SHA-256 on stdout; list any bundled game archives on stderr."""
import hashlib
import sys
import zipfile

apk = sys.argv[1]
with zipfile.ZipFile(apk) as z:
    bundled = [n for n in z.namelist() if n.startswith("assets/gamedata/GameData/")]
if bundled:
    print("note: this APK bundles %d game data files, which will be public:" % len(bundled), file=sys.stderr)
    for name in bundled:
        print("  " + name[len("assets/gamedata/"):], file=sys.stderr)
h = hashlib.sha256()
with open(apk, "rb") as f:
    for chunk in iter(lambda: f.read(1 << 20), b""):
        h.update(chunk)
print(h.hexdigest())
