#!/usr/bin/env bash
#
# Manage activation keys on the license server.
#
#   gzh-key.sh mint [count] [note]   new one-time keys (default 1)
#   gzh-key.sh list                  every key, its status and the device it went to
#   gzh-key.sh revoke GZH-XXXX-...   stop an unused key from being redeemed
#
# Reads the server address and admin token from ~/.generalszh/ (license_url, license_admin_token),
# or from GZH_LICENSE_URL / GZH_LICENSE_TOKEN.
set -euo pipefail

CONF="${HOME:-${USERPROFILE:-}}/.generalszh"
URL="${GZH_LICENSE_URL:-$(cat "${CONF}/license_url" 2>/dev/null || true)}"
TOKEN="${GZH_LICENSE_TOKEN:-$(cat "${CONF}/license_admin_token" 2>/dev/null || true)}"
[[ -n "${URL}" && -n "${TOKEN}" ]] || { echo "set up ${CONF}/license_url and license_admin_token first"; exit 1; }
URL="${URL%/}"

json_string() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

case "${1:-}" in
    mint)
        COUNT="${2:-1}"
        NOTE="$(json_string "${3:-}")"
        curl -fsS -X POST "${URL}/admin/keys" -H "Authorization: Bearer ${TOKEN}" \
            -H "Content-Type: application/json" -d "{\"count\":${COUNT},\"note\":\"${NOTE}\"}"
        ;;
    list)
        curl -fsS "${URL}/admin/keys" -H "Authorization: Bearer ${TOKEN}"
        ;;
    revoke)
        KEY="$(json_string "${2:?key to revoke}")"
        curl -fsS -X POST "${URL}/admin/keys/revoke" -H "Authorization: Bearer ${TOKEN}" \
            -H "Content-Type: application/json" -d "{\"key\":\"${KEY}\"}"
        ;;
    *)
        sed -n '3,10p' "$0"
        exit 1
        ;;
esac
echo
