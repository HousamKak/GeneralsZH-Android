// License server for the Android launcher's activation gate.
//
// The owner mints one-time keys through the admin API (bearer token). The app sends a key plus a
// hash of its device id to /v1/activate; the first device to redeem a key owns it, and gets back
// a license signed with an ECDSA P-256 key that never leaves this Worker. The app verifies that
// signature offline against the public key compiled into it (LicenseGate.java), so a license
// cannot be forged and does not move to another phone. Activation is forever: nothing is
// re-checked after it.

import { json, normalizeCode, readJson } from "./codes";
import { fail, getKey, listKeys, mintKeys, resetDevice, revokeKey, stats, updateKey } from "./admin";
import { paypalCapture, paypalConfig, paypalKeyLookup, paypalOrder, type PayPalEnv } from "./paypal";

interface Env extends PayPalEnv {
	ADMIN_TOKEN: string;
	CONSOLE_TOKEN?: string; // App Monitor's Licenses tab (admin.ts)
	// PKCS#8 DER, base64. See README.md for how it is generated.
	LICENSE_PRIVATE_KEY: string;
}

const DEVICE_RE = /^[0-9a-f]{64}$/;

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		const url = new URL(request.url);
		try {
			if (request.method === "POST" && url.pathname === "/v1/activate") {
				return await activate(request, env);
			}
			if (url.pathname.startsWith("/v1/paypal/")) {
				const route = `${request.method} ${url.pathname.slice("/v1/paypal/".length)}`;
				if (route === "GET config") return paypalConfig(env);
				if (route === "POST order") return await paypalOrder(env);
				if (route === "POST capture") return await paypalCapture(request, env);
				if (route === "GET key") return await paypalKeyLookup(url, env);
			}
			if (url.pathname.startsWith("/admin/")) {
				if (!(await isAdmin(request, env))) {
					return fail("unauthorized", "missing or wrong admin token", 401);
				}
				return await adminRoute(request, url, env);
			}
			return fail("not_found", "no such endpoint", 404);
		} catch (err) {
			console.error(err);
			return fail("server_error", "the license server failed; see its logs", 500);
		}
	},
} satisfies ExportedHandler<Env>;

// The admin API (admin.ts): App Monitor's Licenses tab and gzh-key.sh.
async function adminRoute(request: Request, url: URL, env: Env): Promise<Response> {
	const path = url.pathname;
	const m = request.method;
	if (path === "/admin/keys") {
		if (m === "GET") return listKeys(url, env);
		if (m === "POST") return mintKeys(request, env);
	}
	if (path === "/admin/stats" && m === "GET") return stats(env);
	// Older form, kept for gzh-key.sh: POST /admin/keys/revoke {key}.
	if (path === "/admin/keys/revoke" && m === "POST") {
		const body = await readJson(request);
		return revokeKey(typeof body?.key === "string" ? body.key : "", typeof body?.reason === "string" ? body.reason : null, env);
	}
	const one = path.match(/^\/admin\/keys\/([^/]+)(\/reset-device|\/revoke)?$/);
	if (one) {
		const [, key, action] = one;
		if (!action && m === "GET") return getKey(key, env);
		if (!action && m === "PATCH") return updateKey(key, request, env);
		if (action === "/reset-device" && m === "POST") return resetDevice(key, request, env);
		if (action === "/revoke" && m === "POST") {
			const reason = (await readJson(request))?.reason;
			return revokeKey(key, typeof reason === "string" ? reason.slice(0, 200) : null, env);
		}
	}
	return fail("not_found", "no such admin endpoint", 404);
}

async function activate(request: Request, env: Env): Promise<Response> {
	const body = await readJson(request);
	const code = normalizeCode(body?.code);
	const device = typeof body?.device === "string" ? body.device.toLowerCase() : "";
	if (!code || !DEVICE_RE.test(device)) {
		return json({ error: "bad_request" }, 400);
	}

	const now = Date.now();
	// Claim the key only if nobody has: one statement, so two phones racing for the same key
	// cannot both win.
	const claim = await env.DB.prepare(
		"UPDATE keys SET device = ?1, activated_at = ?2 WHERE code = ?3 AND device IS NULL AND revoked_at IS NULL",
	)
		.bind(device, now, code)
		.run();

	if (claim.meta.changes === 0) {
		const row = await env.DB.prepare("SELECT device, revoked_at FROM keys WHERE code = ?1")
			.bind(code)
			.first<{ device: string | null; revoked_at: number | null }>();
		if (!row) return json({ error: "invalid_key" }, 404);
		if (row.revoked_at !== null) return json({ error: "revoked_key" }, 403);
		// The same phone again (app reinstalled, data cleared): hand its license back.
		if (row.device !== device) return json({ error: "key_used" }, 409);
	}

	// For the admin console: when this device last asked, and the first activation in the history.
	const statements = [env.DB.prepare("UPDATE keys SET last_seen = ?1 WHERE code = ?2").bind(now, code)];
	if (claim.meta.changes > 0) {
		statements.push(
			env.DB.prepare("INSERT INTO key_history (code, ts, action, detail) VALUES (?1, ?2, 'activated', ?3)")
				.bind(code, now, `device ${device.slice(0, 12)}`),
		);
	}
	await env.DB.batch(statements);

	const payload = JSON.stringify({ v: 1, device, key: code.slice(-4), iat: Math.floor(now / 1000) });
	return json({ license: `${toBase64(new TextEncoder().encode(payload))}.${await sign(env, payload)}` });
}

// The owner's token (gzh-key.sh) or the admin console's (App Monitor's Licenses tab, its own
// secret so either can be rotated alone).
async function isAdmin(request: Request, env: Env): Promise<boolean> {
	const header = request.headers.get("Authorization") ?? "";
	const given = new TextEncoder().encode(header.replace(/^Bearer\s+/i, ""));
	for (const token of [env.ADMIN_TOKEN, env.CONSOLE_TOKEN]) {
		const expected = new TextEncoder().encode(token ?? "");
		if (expected.byteLength >= 32 && given.byteLength === expected.byteLength
			&& crypto.subtle.timingSafeEqual(given, expected)) {
			return true;
		}
	}
	return false;
}

let signingKey: Promise<CryptoKey> | undefined;

async function sign(env: Env, payload: string): Promise<string> {
	signingKey ??= crypto.subtle.importKey(
		"pkcs8",
		fromBase64(env.LICENSE_PRIVATE_KEY.replace(/\s+/g, "")),
		{ name: "ECDSA", namedCurve: "P-256" },
		false,
		["sign"],
	);
	const raw = new Uint8Array(
		await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, await signingKey, new TextEncoder().encode(payload)),
	);
	// WebCrypto returns r||s; java.security.Signature("SHA256withECDSA") wants ASN.1 DER.
	return toBase64(rawToDer(raw));
}

function rawToDer(raw: Uint8Array): Uint8Array {
	const integer = (bytes: Uint8Array): number[] => {
		let i = 0;
		while (i < bytes.length - 1 && bytes[i] === 0) i++;
		const trimmed = Array.from(bytes.slice(i));
		return trimmed[0] & 0x80 ? [0x02, trimmed.length + 1, 0, ...trimmed] : [0x02, trimmed.length, ...trimmed];
	};
	const body = [...integer(raw.slice(0, 32)), ...integer(raw.slice(32))];
	return new Uint8Array([0x30, body.length, ...body]);
}






function toBase64(bytes: Uint8Array): string {
	let s = "";
	for (const b of bytes) s += String.fromCharCode(b);
	return btoa(s);
}

function fromBase64(b64: string): Uint8Array {
	return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}
