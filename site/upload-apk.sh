#!/usr/bin/env bash
#
# Publish an APK to the landing site's download link.
#
#   upload-apk.sh <apk>
#
# Uploads it to the zh-commander-apk R2 bucket, in parts, so any size works, and points
# latest.json at it; /download serves it from then on. Any game data bundled in the APK
# (assets/gamedata/GameData) is listed before the upload, since it becomes publicly downloadable.
#
# Needs the upload token in ~/.generalszh/license_admin_token (or GZH_UPLOAD_TOKEN); the site
# address defaults to the Worker's workers.dev URL (GZH_SITE_URL overrides it).
set -euo pipefail

APK="${1:?APK to publish}"
SITE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON="$(command -v python3 || command -v python)"
CONF="${HOME:-${USERPROFILE:-}}/.generalszh"
TOKEN="${GZH_UPLOAD_TOKEN:-$(tr -d '\r\n' < "${CONF}/license_admin_token" 2>/dev/null || true)}"
[[ -n "${TOKEN}" ]] || { echo "no upload token: put it in ${CONF}/license_admin_token"; exit 1; }
URL="${GZH_SITE_URL:-https://zh-commander.housam-kak20.workers.dev}"

SHA256="$("${PYTHON}" -I "${SITE}/check-apk.py" "${APK}")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
AAPT="$(ls -d "${SDK}"/build-tools/*/aapt* 2>/dev/null | sort -V | tail -n 1)"
[[ -n "${AAPT}" ]] || { echo "aapt not found (set ANDROID_HOME)"; exit 1; }
VERSION="$("${AAPT}" dump badging "${APK}" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -n 1)"
[[ -n "${VERSION}" ]] || { echo "could not read versionName"; exit 1; }

echo "==> Uploading ZH Commander ${VERSION} ($(( $(wc -c < "${APK}") / 1048576 )) MB) to ${URL}"
"${PYTHON}" -I "${SITE}/upload-apk.py" "${APK}" "${VERSION}" "${SHA256}" "${URL}" "${TOKEN}"
echo "==> /download now serves ZH Commander ${VERSION}"
