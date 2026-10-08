"""Print an APK's SHA-256, refusing (exit 1) any APK that carries game data."""
import hashlib
import sys
import zipfile

apk = sys.argv[1]
with zipfile.ZipFile(apk) as z:
    if any(n.startswith("assets/gamedata/GameData/") for n in z.namelist()):
        sys.exit("REFUSED: this APK contains game data; publish the plain CI build only")
h = hashlib.sha256()
with open(apk, "rb") as f:
    for chunk in iter(lambda: f.read(1 << 20), b""):
        h.update(chunk)
print(h.hexdigest())
