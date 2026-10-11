#!/usr/bin/env python3
"""Add a player's own Zero Hour .big archives to a built APK, for personal sideloading.

The launcher copies assets/gamedata/** into the app's external files dir on first launch, and
both GeneralsZHActivity and SDL3Main.cpp fall back to <external>/GameData as the game folder, so
an APK carrying the archives there starts straight into the game. Base Generals archives go in
GameData/ZH_Generals, one of the folders loadBaseGeneralsAssetsForZH() probes.

The result contains EA's copyrighted game data: never commit, upload or share it.
bundle-game-data.sh wraps this with zipalign and apksigner.

Usage: bundle-game-data.py <in.apk> <out-unsigned.apk> <zero_hour_dir> <base_generals_dir>
"""
import hashlib
import os
import sys
import zipfile

# Classic (non-ZIP64) ZIP fields are unsigned 32-bit, so the real ceiling is 4 GiB; Python's
# zipfile switches to ZIP64 at 2 GiB. apksigner does not accept ZIP64 APKs.
zipfile.ZIP64_LIMIT = (1 << 32) - 1

# Mods and community add-ons, not retail data: they change the INI checksum online play compares.
SKIP_PREFIXES = ("!hotkeys", "340_controlbarpro", "customcontent")

# GeneralsX @bugfix ZH Commander 11/10/2026 The retail INI archive of Zero Hour 1.04. A local edit
# (one on the dev PC sets 150000 starting money) changes the INI checksum, and then no PC lobby on
# GeneralsOnline takes the phone. Same list as site/upload-data.py.
RETAIL_INI_SHA256 = {
    "inizh.big": "1a6d41a7a2cb31e67ad2f868aca9264ad069c275e0074f8a0d970a336071e9a0",
}


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def retail_archives(folder):
    names = []
    for name in sorted(os.listdir(folder)):
        lower = name.lower()
        if not lower.endswith(".big"):
            continue
        if lower.startswith(SKIP_PREFIXES):
            print(f"  skip (mod) {name}")
            continue
        names.append(name)
    return names


def main():
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    src, dst, zh_dir, base_dir = sys.argv[1:5]
    for required, folder in (("INIZH.big", zh_dir), ("INI.big", base_dir)):
        if not os.path.isfile(os.path.join(folder, required)):
            sys.exit(f"{required} not found in {folder}")
    for name, want in RETAIL_INI_SHA256.items():
        path = os.path.join(zh_dir, "INIZH.big") if name == "inizh.big" else None
        if path and sha256_of(path) != want:
            sys.exit(f"{path} is not the retail archive (sha256 {want} expected): it would break online play")

    total = 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", allowZip64=False) as zout:
        for info in zin.infolist():
            # The old v1 signature no longer matches; apksigner writes a fresh one.
            if info.filename.startswith("META-INF/") and info.filename.upper().endswith(
                    (".SF", ".RSA", ".EC", ".DSA", ".MF")):
                continue
            zout.writestr(info, zin.read(info.filename), compress_type=info.compress_type)
        for folder, prefix in ((zh_dir, "assets/gamedata/GameData/"),
                               (base_dir, "assets/gamedata/GameData/ZH_Generals/")):
            for name in retail_archives(folder):
                path = os.path.join(folder, name)
                total += os.path.getsize(path)
                # Stored, not deflated: AssetManager streams them straight out of the APK.
                zout.write(path, prefix + name, compress_type=zipfile.ZIP_STORED)
                print(f"  add {prefix}{name}")
    print(f"bundled {total / 2**30:.2f} GiB of game data")


if __name__ == "__main__":
    main()
