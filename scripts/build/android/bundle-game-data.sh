#!/usr/bin/env bash
#
# Build a personal all-in-one APK: a CI (or local) build plus your own game files.
#
#   bundle-game-data.sh <zero_hour_dir> <base_generals_dir> [apk] [out.apk]
#
# With no APK given, downloads the APK artifact of the latest successful "Build Android" run
# of this repository (needs the GitHub CLI, logged in). Signs with ZH Commander's own key, the
# one CI uses, so the result installs over a CI build and the other way round: by default
# ~/.generalszh/zhcommander-release.jks with its password in release_keystore_password
# (GX_KEYSTORE_FILE / GX_KEYSTORE_PASSWORD / GX_KEY_ALIAS override them).
#
# The output carries EA's copyrighted game data: it goes to build-local/, which is ignored.
# Never commit, upload or share it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
ZH_DIR="${1:?Zero Hour folder (the one with INIZH.big)}"
BASE_DIR="${2:?base Generals folder (the one with INI.big)}"
APK="${3:-}"
OUT_DIR="${REPO}/build-local"
mkdir -p "${OUT_DIR}"

if [[ -z "${APK}" ]]; then
    RUN_ID="$(gh run list --workflow build-android.yml --status success --limit 1 --json databaseId --jq '.[0].databaseId')"
    [[ -n "${RUN_ID}" ]] || { echo "no successful Build Android run found"; exit 1; }
    rm -rf "${OUT_DIR}/ci-apk"
    gh run download "${RUN_ID}" --dir "${OUT_DIR}/ci-apk" --pattern '*apk*'
    APK="$(find "${OUT_DIR}/ci-apk" -type f -name '*.apk' | head -n 1)"
    [[ -n "${APK}" ]] || { echo "run ${RUN_ID} has no APK artifact"; exit 1; }
    echo "==> CI run ${RUN_ID}: ${APK}"
fi
OUT="${4:-${OUT_DIR}/$(basename "${APK%.apk}")-bundled.apk}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BUILD_TOOLS="$(ls -d "${SDK}"/build-tools/* 2>/dev/null | sort -V | tail -n 1)"
[[ -n "${BUILD_TOOLS}" ]] || { echo "Android SDK build-tools not found (set ANDROID_HOME)"; exit 1; }
ZIPALIGN="${BUILD_TOOLS}/zipalign"; [[ -x "${ZIPALIGN}" ]] || ZIPALIGN="${ZIPALIGN}.exe"
APKSIGNER="${BUILD_TOOLS}/apksigner"; [[ -x "${APKSIGNER}" ]] || APKSIGNER="${APKSIGNER}.bat"

CONF="${HOME:-${USERPROFILE:-}}/.generalszh"
KEYSTORE="${GX_KEYSTORE_FILE:-${CONF}/zhcommander-release.jks}"
KEY_ALIAS="${GX_KEY_ALIAS:-zhcommander}"
[[ -f "${KEYSTORE}" ]] || { echo "signing key not found: ${KEYSTORE}"; exit 1; }
export GX_KEYSTORE_PASSWORD="${GX_KEYSTORE_PASSWORD:-$(tr -d '\r\n' < "${CONF}/release_keystore_password" 2>/dev/null || true)}"
[[ -n "${GX_KEYSTORE_PASSWORD}" ]] || { echo "no keystore password: put it in ${CONF}/release_keystore_password"; exit 1; }

PYTHON="$(command -v python3 || command -v python)"
UNSIGNED="${OUT}.unsigned"; ALIGNED="${OUT}.aligned"
trap 'rm -f "${UNSIGNED}" "${ALIGNED}"' EXIT

echo "==> Adding game data"
"${PYTHON}" -I "${SCRIPT_DIR}/bundle-game-data.py" "${APK}" "${UNSIGNED}" "${ZH_DIR}" "${BASE_DIR}"
echo "==> Aligning (16 KiB pages for native libraries)"
"${ZIPALIGN}" -f -P 16 4 "${UNSIGNED}" "${ALIGNED}"
echo "==> Signing"
"${APKSIGNER}" sign --ks "${KEYSTORE}" --ks-pass env:GX_KEYSTORE_PASSWORD \
    --ks-key-alias "${KEY_ALIAS}" --key-pass env:GX_KEYSTORE_PASSWORD --out "${OUT}" "${ALIGNED}"
rm -f "${OUT}.idsig"
"${APKSIGNER}" verify "${OUT}"
echo "==> ${OUT} ($(du -h "${OUT}" | cut -f1))"
