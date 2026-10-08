// License server for the Android launcher's activation gate.
//
// The owner mints one-time keys through the admin API (bearer token). The app sends a key plus a
// hash of its device id to /v1/activate; the first device to redeem a key owns it, and gets back
// a license signed with an ECDSA P-256 key that never leaves this Worker. The app verifies that
// signature offline against the public key compiled into it (LicenseGate.java), so a license
// cannot be forged and does not move to another phone. Activation is forever: nothing is
// re-checked after it.

interface Env {
	DB: D1Database;
	ADMIN_TOKEN: string;
	// PKCS#8 DER, base64. See README.md for how it is generated.
	LICENSE_PRIVATE_KEY: string;
}

// Crockford-style base32 without the look-alikes I, L, O, U: easy to read out loud and type.
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const CODE_LENGTH = 12; // 60 bits: far beyond guessing range.
const DEVICE_RE = /^[0-9a-f]{64}$/;

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		const url = new URL(request.url);
		try {
			if (request.method === "POST" && url.pathname === "/v1/activate") {
				return await activate(request, env);
			}
			if (url.pathname.startsWith("/admin/")) {
				if (!(await isAdmin(request, env))) {
					return json({ error: "unauthorized" }, 401);
				}
				if (request.method === "POST" && url.pathname === "/admin/keys") {
					return await mintKeys(request, env);
				}
				if (request.method === "GET" && url.pathname === "/admin/keys") {
					return await listKeys(env);
				}
				if (request.method === "POST" && url.pathname === "/admin/keys/revoke") {
					return await revokeKey(request, env);
				}
			}
			return json({ error: "not_found" }, 404);
		} catch (err) {
			console.error(err);
			return json({ error: "server_error" }, 500);
		}
	},
} satisfies ExportedHandler<Env>;

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

	const payload = JSON.stringify({ v: 1, device, key: code.slice(-4), iat: Math.floor(now / 1000) });
	return json({ license: `${toBase64(new TextEncoder().encode(payload))}.${await sign(env, payload)}` });
}

async function mintKeys(request: Request, env: Env): Promise<Response> {
	const body = await readJson(request);
	const count = Math.min(Math.max(Number(body?.count ?? 1) | 0, 1), 50);
	const note = typeof body?.note === "string" ? body.note.slice(0, 200) : null;
	const now = Date.now();
	const codes: string[] = [];
	const insert = env.DB.prepare("INSERT INTO keys (code, note, created_at) VALUES (?1, ?2, ?3)");
	const statements = [];
	for (let i = 0; i < count; i++) {
		const code = randomCode();
		codes.push(code);
		statements.push(insert.bind(code, note, now));
	}
	await env.DB.batch(statements);
	return json({ keys: codes.map(formatCode), note });
}

async function listKeys(env: Env): Promise<Response> {
	const { results } = await env.DB.prepare(
		"SELECT code, note, created_at, device, activated_at, revoked_at FROM keys ORDER BY created_at DESC",
	).all<{ code: string; note: string | null; created_at: number; device: string | null; activated_at: number | null; revoked_at: number | null }>();
	return json({
		keys: results.map((r) => ({
			key: formatCode(r.code),
			note: r.note,
			created: new Date(r.created_at).toISOString(),
			status: r.revoked_at !== null ? "revoked" : r.device ? "activated" : "unused",
			device: r.device ? r.device.slice(0, 12) : null,
			activated: r.activated_at ? new Date(r.activated_at).toISOString() : null,
		})),
	});
}

// Stops an unused key from ever being redeemed. A phone that already activated keeps working:
// activation is permanent by design.
async function revokeKey(request: Request, env: Env): Promise<Response> {
	const code = normalizeCode((await readJson(request))?.key);
	if (!code) return json({ error: "bad_request" }, 400);
	const res = await env.DB.prepare("UPDATE keys SET revoked_at = ?1 WHERE code = ?2 AND revoked_at IS NULL")
		.bind(Date.now(), code)
		.run();
	return json({ revoked: res.meta.changes > 0 });
}

async function isAdmin(request: Request, env: Env): Promise<boolean> {
	const header = request.headers.get("Authorization") ?? "";
	const given = new TextEncoder().encode(header.replace(/^Bearer\s+/i, ""));
	const expected = new TextEncoder().encode(env.ADMIN_TOKEN ?? "");
	if (expected.byteLength < 32 || given.byteLength !== expected.byteLength) return false;
	return crypto.subtle.timingSafeEqual(given, expected);
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

function randomCode(): string {
	const bytes = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
	// 256 is a multiple of 32, so taking each byte mod 32 is unbiased.
	return Array.from(bytes, (b) => ALPHABET[b % ALPHABET.length]).join("");
}

function normalizeCode(input: unknown): string | null {
	if (typeof input !== "string") return null;
	let code = input.toUpperCase().replace(/[^0-9A-Z]/g, "");
	if (code.startsWith("GZH")) code = code.slice(3);
	code = code.replace(/O/g, "0").replace(/[IL]/g, "1");
	return code.length === CODE_LENGTH && [...code].every((c) => ALPHABET.includes(c)) ? code : null;
}

function formatCode(code: string): string {
	return `GZH-${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8)}`;
}

async function readJson(request: Request): Promise<Record<string, unknown> | null> {
	try {
		return (await request.json()) as Record<string, unknown>;
	} catch {
		return null;
	}
}

function json(data: unknown, status = 200): Response {
	return new Response(JSON.stringify(data), {
		status,
		headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
	});
}

function toBase64(bytes: Uint8Array): string {
	let s = "";
	for (const b of bytes) s += String.fromCharCode(b);
	return btoa(s);
}

function fromBase64(b64: string): Uint8Array {
	return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}
