# License server

A Cloudflare Worker (with a D1 database) that hands out activations for the Android launcher's
activation gate (`android/.../LicenseGate.java`, `ActivationActivity.java`).

How it works:

1. The owner mints one-time keys (`gzh-key.sh mint`), e.g. `GZH-7K3P-Q9XM-A2BC`.
2. On first start the app asks for a key and sends it to `POST /v1/activate`, with a SHA-256 of
   the phone's `ANDROID_ID`.
3. The first phone to redeem a key owns it. The server returns a license signed with an ECDSA
   P-256 key that only the Worker holds.
4. The app checks that signature offline on every start, against the public key compiled into
   `LicenseGate.PUBLIC_KEY_B64`, and that the license names this phone. Activation is permanent:
   nothing is checked online again.

Reinstalling the app on the same phone re-activates with the same key. A factory reset changes
`ANDROID_ID`, so that phone needs a new key. `revoke` only stops a key that has not been used yet.

The code being public does not weaken it: what protects it is the private key. Anyone can still
patch the check out of an APK; this stops casual sharing, not a determined cracker.

## Setup

Secrets live in `~/.generalszh/` (never in the repository):

```bash
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out license_private.pem
openssl pkcs8 -topk8 -nocrypt -in license_private.pem -outform DER | base64 -w0 > license_private_pkcs8_b64.txt
openssl pkey -in license_private.pem -pubout -outform DER | base64 -w0   # -> LicenseGate.PUBLIC_KEY_B64
openssl rand -hex 32 > license_admin_token
```

Deploy (from this folder):

```bash
npm install
npx wrangler login
npx wrangler d1 create gzh-license          # put the database_id into wrangler.jsonc
npx wrangler d1 migrations apply gzh-license --remote
npx wrangler secret put ADMIN_TOKEN < ~/.generalszh/license_admin_token
npx wrangler secret put LICENSE_PRIVATE_KEY < ~/.generalszh/license_private_pkcs8_b64.txt
npx wrangler deploy                           # its URL -> ~/.generalszh/license_url and LicenseGate.ACTIVATE_URL
```

Local test: put `ADMIN_TOKEN=` and `LICENSE_PRIVATE_KEY=` lines in `.dev.vars`, then
`npx wrangler d1 migrations apply gzh-license --local` and `npx wrangler dev`.

Changing the signing key invalidates every license already issued: each phone would need a new
key, and the app a new build with the new public key.
