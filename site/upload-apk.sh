#!/usr/bin/env bash
#
# Publish an APK to the landing site's download link.
#
#   upload-apk.sh <apk>
#
# Uploads it to the zh-commander-apk R2 bucket and points latest.json at it; /download serves it
# from then on. Refuses any APK carrying game data (assets/gamedata/GameData): only the plain
# CI build may ever be published.
set -euo pipefail

APK="${1:?APK to publish}"
SITE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUCKET="zh-commander-apk"
PYTHON="$(command -v python3 || command -v python)"

SHA256="$("${PYTHON}" -I "${SITE}/check-apk.py" "${APK}")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
AAPT="$(ls -d "${SDK}"/build-tools/*/aapt* 2>/dev/null | sort -V | tail -n 1)"
[[ -n "${AAPT}" ]] || { echo "aapt not found (set ANDROID_HOME)"; exit 1; }
VERSION="$("${AAPT}" dump badging "${APK}" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -n 1)"
[[ -n "${VERSION}" ]] || { echo "could not read versionName"; exit 1; }

SIZE="$(wc -c < "${APK}" | tr -d ' ')"
KEY="apk/ZH-Commander-${VERSION}-${SHA256:0:8}.apk"
PUBLISHED="$(date -u +%Y-%m-%d)"
LATEST="$(mktemp)"
trap 'rm -f "${LATEST}"' EXIT
printf '{"key":"%s","version":"%s","size":%s,"sha256":"%s","published":"%s"}\n' \
    "${KEY}" "${VERSION}" "${SIZE}" "${SHA256}" "${PUBLISHED}" > "${LATEST}"

cd "${SITE}"
echo "==> Uploading ${KEY} ($((SIZE / 1048576)) MB)"
npx wrangler r2 object put "${BUCKET}/${KEY}" --file "${APK}" --remote \
    --content-type application/vnd.android.package-archive
npx wrangler r2 object put "${BUCKET}/latest.json" --file "${LATEST}" --remote \
    --content-type application/json
echo "==> /download now serves ZH Commander ${VERSION}"
