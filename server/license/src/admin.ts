// Admin API for keys and their buyers: what App Monitor's Licenses tab and gzh-key.sh call.
// Contract: D:\dev\app-monitor\specs\console-modules.md, section 3.1 (plus A1 device lookup and
// A3 idempotent mint). Every error is {error, message}.
//
// A license is verified offline on the phone, so reset-device and revoke cannot stop a phone that
// already activated: reset only lets the key activate once more on a new one. The console says so.

import { formatCode, json, normalizeCode, randomCode, readJson } from "./codes";

export interface AdminEnv {
	DB: D1Database;
}

interface KeyRow {
	code: string;
	note: string | null;
	created_at: number;
	device: string | null;
	activated_at: number | null;
	revoked_at: number | null;
	buyer: string | null;
	contact: string | null;
	payment_method: string | null;
	payment_amount: number | null;
	payment_currency: string | null;
	payment_reference: string | null;
	resets: number;
	revoke_reason: string | null;
	last_seen: number | null;
	request_id: string | null;
}

const KEY_COLUMNS =
	"code, note, created_at, device, activated_at, revoked_at, buyer, contact, payment_method, payment_amount, " +
	"payment_currency, payment_reference, resets, revoke_reason, last_seen, request_id";
const REQUEST_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DEVICE_PREFIX_RE = /^[0-9a-f]{8,64}$/;
const DAY_MS = 24 * 60 * 60 * 1000;

export function fail(error: string, message: string, status: number): Response {
	return json({ error, message }, status);
}

function iso(ms: number | null): string | null {
	return ms ? new Date(ms).toISOString() : null;
}

function keyObject(r: KeyRow) {
	return {
		key: formatCode(r.code),
		status: r.revoked_at !== null ? "revoked" : r.device ? "activated" : "unused",
		buyer: r.buyer,
		contact: r.contact,
		payment: {
			method: r.payment_method,
			amount: r.payment_amount,
			currency: r.payment_currency,
			reference: r.payment_reference,
		},
		note: r.note,
		created: iso(r.created_at),
		activated: iso(r.activated_at),
		device: r.device,
		resets: r.resets ?? 0,
		revoked: iso(r.revoked_at),
		revoke_reason: r.revoke_reason,
		last_seen: iso(r.last_seen),
	};
}

function text(value: unknown, max: number): string | null {
	return typeof value === "string" && value.trim() !== "" ? value.trim().slice(0, max) : null;
}

function history(env: AdminEnv, code: string, action: string, detail: string | null) {
	return env.DB.prepare("INSERT INTO key_history (code, ts, action, detail) VALUES (?1, ?2, ?3, ?4)").bind(
		code,
		Date.now(),
		action,
		detail,
	);
}

async function getRow(env: AdminEnv, code: string): Promise<KeyRow | null> {
	return env.DB.prepare(`SELECT ${KEY_COLUMNS} FROM keys WHERE code = ?1`).bind(code).first<KeyRow>();
}

// GET /admin/keys?status=&q=&device=&limit=&cursor=
export async function listKeys(url: URL, env: AdminEnv): Promise<Response> {
	const where: string[] = [];
	const binds: unknown[] = [];
	const status = url.searchParams.get("status");
	if (status === "unused") where.push("device IS NULL AND revoked_at IS NULL");
	else if (status === "activated") where.push("device IS NOT NULL AND revoked_at IS NULL");
	else if (status === "revoked") where.push("revoked_at IS NOT NULL");
	else if (status) return fail("bad_status", "status must be unused, activated or revoked", 400);

	const device = url.searchParams.get("device")?.toLowerCase();
	if (device) {
		if (!DEVICE_PREFIX_RE.test(device)) return fail("bad_device", "device must be at least 8 hex characters", 400);
		binds.push(`${device}%`);
		where.push(`device LIKE ?${binds.length}`);
	}
	const q = url.searchParams.get("q")?.trim();
	if (q) {
		const code = normalizeCode(q);
		binds.push(`%${q.toLowerCase()}%`);
		const n = binds.length;
		const clauses = [`lower(buyer) LIKE ?${n}`, `lower(contact) LIKE ?${n}`, `lower(note) LIKE ?${n}`];
		// A partial key ("GZH-ABCD" or "ABCD") matches on the code itself.
		binds.push(`%${(code ?? q).toUpperCase().replace(/^GZH-?/, "").replace(/[^0-9A-Z]/g, "")}%`);
		clauses.push(`code LIKE ?${binds.length}`);
		where.push(`(${clauses.join(" OR ")})`);
	}
	const cursor = Number(url.searchParams.get("cursor") ?? "0");
	if (cursor > 0) {
		binds.push(cursor);
		where.push(`created_at < ?${binds.length}`);
	}
	const limit = Math.min(Math.max(Number(url.searchParams.get("limit") ?? "100") | 0, 1), 500);
	binds.push(limit + 1);
	const sql =
		`SELECT ${KEY_COLUMNS} FROM keys ${where.length ? `WHERE ${where.join(" AND ")}` : ""} ` +
		`ORDER BY created_at DESC LIMIT ?${binds.length}`;
	const { results } = await env.DB.prepare(sql).bind(...binds).all<KeyRow>();
	const page = results.slice(0, limit);
	return json({
		keys: page.map(keyObject),
		next_cursor: results.length > limit ? String(page[page.length - 1].created_at) : null,
	});
}

// GET /admin/keys/:key
export async function getKey(rawKey: string, env: AdminEnv): Promise<Response> {
	const code = normalizeCode(decodeURIComponent(rawKey));
	if (!code) return fail("bad_key", "not a key", 400);
	const row = await getRow(env, code);
	if (!row) return fail("not_found", "no such key", 404);
	const { results } = await env.DB.prepare("SELECT ts, action, detail FROM key_history WHERE code = ?1 ORDER BY ts DESC")
		.bind(code)
		.all<{ ts: number; action: string; detail: string | null }>();
	return json({ ...keyObject(row), history: results.map((h) => ({ ts: iso(h.ts), action: h.action, detail: h.detail })) });
}

function paymentFields(body: Record<string, unknown> | null) {
	const p = (body?.payment ?? {}) as Record<string, unknown>;
	const amount = typeof p.amount === "number" ? p.amount : typeof p.amount === "string" ? Number(p.amount) : NaN;
	return {
		method: text(p.method, 40),
		amount: Number.isFinite(amount) ? amount : null,
		currency: text(p.currency, 8)?.toUpperCase() ?? null,
		reference: text(p.reference, 120),
	};
}

// POST /admin/keys {count, buyer?, contact?, payment?, note?, request_id?}
export async function mintKeys(request: Request, env: AdminEnv): Promise<Response> {
	const body = await readJson(request);
	const count = Math.min(Math.max(Number(body?.count ?? 1) | 0, 1), 50);
	const requestId = text(body?.request_id, 64);
	if (requestId && !REQUEST_ID_RE.test(requestId)) return fail("bad_request_id", "request_id must be a uuid", 400);
	if (requestId) {
		const { results } = await env.DB.prepare(
			`SELECT ${KEY_COLUMNS} FROM keys WHERE request_id = ?1 AND created_at > ?2 ORDER BY code`,
		)
			.bind(requestId, Date.now() - DAY_MS)
			.all<KeyRow>();
		if (results.length > 0) return json({ keys: results.map(keyObject), repeated: true });
	}
	const buyer = text(body?.buyer, 120);
	const contact = text(body?.contact, 60);
	const note = text(body?.note, 200);
	const pay = paymentFields(body);
	const now = Date.now();
	const insert = env.DB.prepare(
		"INSERT INTO keys (code, note, created_at, buyer, contact, payment_method, payment_amount, payment_currency, " +
			"payment_reference, request_id) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
	);
	const codes: string[] = [];
	const statements = [];
	for (let i = 0; i < count; i++) {
		const code = randomCode();
		codes.push(code);
		statements.push(insert.bind(code, note, now, buyer, contact, pay.method, pay.amount, pay.currency, pay.reference, requestId));
		statements.push(history(env, code, "minted", buyer ? `for ${buyer}` : null));
	}
	await env.DB.batch(statements);
	const rows = await Promise.all(codes.map((c) => getRow(env, c)));
	return json({ keys: rows.filter((r): r is KeyRow => r !== null).map(keyObject) });
}

// PATCH /admin/keys/:key {buyer?, contact?, payment?, note?}
export async function updateKey(rawKey: string, request: Request, env: AdminEnv): Promise<Response> {
	const code = normalizeCode(decodeURIComponent(rawKey));
	if (!code) return fail("bad_key", "not a key", 400);
	const body = await readJson(request);
	if (!body) return fail("bad_request", "expected a JSON body", 400);
	const sets: string[] = [];
	const binds: unknown[] = [];
	const set = (column: string, value: unknown) => {
		binds.push(value);
		sets.push(`${column} = ?${binds.length}`);
	};
	if ("buyer" in body) set("buyer", text(body.buyer, 120));
	if ("contact" in body) set("contact", text(body.contact, 60));
	if ("note" in body) set("note", text(body.note, 200));
	if ("payment" in body) {
		const pay = paymentFields(body);
		set("payment_method", pay.method);
		set("payment_amount", pay.amount);
		set("payment_currency", pay.currency);
		set("payment_reference", pay.reference);
	}
	if (sets.length === 0) return fail("nothing_to_change", "send buyer, contact, payment or note", 400);
	binds.push(code);
	const res = await env.DB.prepare(`UPDATE keys SET ${sets.join(", ")} WHERE code = ?${binds.length}`).bind(...binds).run();
	if (res.meta.changes === 0) return fail("not_found", "no such key", 404);
	await history(env, code, "edited", Object.keys(body).filter((k) => k !== "request_id").join(", ")).run();
	return json(keyObject((await getRow(env, code))!));
}

// POST /admin/keys/:key/reset-device {reason}
export async function resetDevice(rawKey: string, request: Request, env: AdminEnv): Promise<Response> {
	const code = normalizeCode(decodeURIComponent(rawKey));
	if (!code) return fail("bad_key", "not a key", 400);
	const reason = text((await readJson(request))?.reason, 200);
	const row = await getRow(env, code);
	if (!row) return fail("not_found", "no such key", 404);
	if (row.revoked_at !== null) return fail("revoked_key", "a revoked key cannot be reset", 409);
	if (!row.device) return fail("not_activated", "this key has no device to reset", 409);
	await env.DB.batch([
		env.DB.prepare("UPDATE keys SET device = NULL, activated_at = NULL, resets = resets + 1 WHERE code = ?1").bind(code),
		history(env, code, "reset", `device ${row.device.slice(0, 12)}${reason ? `: ${reason}` : ""}`),
	]);
	return json(keyObject((await getRow(env, code))!));
}

// POST /admin/keys/:key/revoke {reason}, and the older POST /admin/keys/revoke {key}
export async function revokeKey(rawKey: string, reason: string | null, env: AdminEnv): Promise<Response> {
	const code = normalizeCode(decodeURIComponent(rawKey));
	if (!code) return fail("bad_key", "not a key", 400);
	const row = await getRow(env, code);
	if (!row) return fail("not_found", "no such key", 404);
	if (row.revoked_at === null) {
		await env.DB.batch([
			env.DB.prepare("UPDATE keys SET revoked_at = ?1, revoke_reason = ?2 WHERE code = ?3").bind(Date.now(), reason, code),
			history(env, code, "revoked", reason),
		]);
	}
	return json({ ...keyObject((await getRow(env, code))!), revoked_now: row.revoked_at === null });
}

// GET /admin/stats
export async function stats(env: AdminEnv): Promise<Response> {
	const totals = await env.DB.prepare(
		"SELECT count(*) AS total, " +
			"sum(CASE WHEN device IS NULL AND revoked_at IS NULL THEN 1 ELSE 0 END) AS unused, " +
			"sum(CASE WHEN device IS NOT NULL AND revoked_at IS NULL THEN 1 ELSE 0 END) AS activated, " +
			"sum(CASE WHEN revoked_at IS NOT NULL THEN 1 ELSE 0 END) AS revoked FROM keys",
	).first<{ total: number; unused: number; activated: number; revoked: number }>();
	const { results } = await env.DB.prepare(
		"SELECT strftime('%Y-%m', created_at / 1000, 'unixepoch') AS month, coalesce(payment_currency, '') AS currency, " +
			"count(*) AS count, coalesce(sum(payment_amount), 0) AS amount FROM keys " +
			"WHERE payment_method IS NOT NULL OR payment_amount IS NOT NULL GROUP BY month, currency ORDER BY month DESC, currency",
	).all<{ month: string; currency: string; count: number; amount: number }>();
	return json({
		total: totals?.total ?? 0,
		unused: totals?.unused ?? 0,
		activated: totals?.activated ?? 0,
		revoked: totals?.revoked ?? 0,
		sold_by_month: results.map((r) => ({ ...r, currency: r.currency || null })),
	});
}
