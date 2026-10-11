// ZH Commander 11/10/2026: what a running app asks at start, and the owner's messages to players.
//
// POST /v1/status {license} -> {status, reason?, contact?, messages: [...]}
//   status "ok":      the key is good on this phone.
//   status "revoked": the owner revoked the key (reason: the owner's note, if any).
//   status "moved":   the key was reset to another phone, so this one no longer holds it.
// The app blocks the game on "revoked" and "moved" and shows the reason. Any other failure (no
// network, server error) leaves the app as it was: a check that cannot happen never stops an
// honest player, up to the app's own offline limit.
//
// Messages (table messages, migration 0004) are for everyone, one key or one device. The app shows
// each id once. Admin: GET/POST /admin/messages, DELETE /admin/messages/:id.

import { formatCode, json, normalizeCode, readJson } from "./codes";
import { fail } from "./admin";

export interface StatusEnv {
	DB: D1Database;
}

const DEVICE_RE = /^[0-9a-f]{64}$/;
const MAX_TEXT = 1000;

interface MessageRow {
	id: number;
	target_type: string;
	target: string | null;
	title: string;
	body: string;
	button_label: string | null;
	button_url: string | null;
	created_at: number;
	expires_at: number | null;
	withdrawn_at: number | null;
}

// The license is "<base64 payload>.<base64 signature>"; the payload names the device. The device
// hash is a 64-hex SHA-256 only that phone knows, so the payload is all the status lookup needs.
function deviceOf(license: unknown): string | null {
	if (typeof license !== "string") return null;
	const dot = license.indexOf(".");
	if (dot <= 0) return null;
	try {
		const payload = JSON.parse(atob(license.slice(0, dot))) as { device?: unknown };
		const device = typeof payload.device === "string" ? payload.device.toLowerCase() : "";
		return DEVICE_RE.test(device) ? device : null;
	} catch {
		return null;
	}
}

export async function status(request: Request, env: StatusEnv): Promise<Response> {
	const body = await readJson(request);
	const device = deviceOf(body?.license);
	if (!device) return json({ error: "bad_request" }, 400);

	const row = await env.DB.prepare("SELECT code, revoked_at, revoke_reason FROM keys WHERE device = ?1")
		.bind(device)
		.first<{ code: string; revoked_at: number | null; revoke_reason: string | null }>();

	let state = "ok";
	let reason: string | null = null;
	if (!row) {
		state = "moved";
	} else if (row.revoked_at !== null) {
		state = "revoked";
		reason = row.revoke_reason;
	}
	if (row) {
		await env.DB.prepare("UPDATE keys SET last_seen = ?1 WHERE code = ?2").bind(Date.now(), row.code).run();
	}

	const now = Date.now();
	const { results } = await env.DB.prepare(
		"SELECT * FROM messages WHERE withdrawn_at IS NULL AND (expires_at IS NULL OR expires_at > ?1) " +
			"AND (target_type = 'all' OR (target_type = 'device' AND target = ?2) OR (target_type = 'key' AND target = ?3)) " +
			"ORDER BY created_at",
	)
		.bind(now, device, row?.code ?? "")
		.all<MessageRow>();

	return json({
		status: state,
		reason,
		messages: results.map((m) => ({
			id: m.id,
			title: m.title,
			body: m.body,
			button_label: m.button_label,
			button_url: m.button_url,
		})),
	});
}

function messageObject(m: MessageRow) {
	return {
		id: m.id,
		target: m.target_type === "all" ? "all" : m.target_type === "key" ? formatCode(m.target ?? "") : `device:${m.target}`,
		title: m.title,
		body: m.body,
		button_label: m.button_label,
		button_url: m.button_url,
		created: new Date(m.created_at).toISOString(),
		expires: m.expires_at ? new Date(m.expires_at).toISOString() : null,
		withdrawn: m.withdrawn_at ? new Date(m.withdrawn_at).toISOString() : null,
	};
}

// GET /admin/messages?all=1: the live messages (all=1 also the withdrawn and expired ones).
export async function listMessages(url: URL, env: StatusEnv): Promise<Response> {
	const everything = url.searchParams.get("all") === "1";
	const now = Date.now();
	const { results } = everything
		? await env.DB.prepare("SELECT * FROM messages ORDER BY created_at DESC LIMIT 200").all<MessageRow>()
		: await env.DB.prepare(
				"SELECT * FROM messages WHERE withdrawn_at IS NULL AND (expires_at IS NULL OR expires_at > ?1) ORDER BY created_at DESC",
			)
				.bind(now)
				.all<MessageRow>();
	return json({ messages: results.map(messageObject) });
}

// POST /admin/messages {target: "all" | "GZH-XXXX-XXXX-XXXX" | "device:<64 hex>", title, body,
//   button_label?, button_url?, expires_days?}
export async function createMessage(request: Request, env: StatusEnv): Promise<Response> {
	const body = await readJson(request);
	const text = (v: unknown) => (typeof v === "string" ? v.trim().slice(0, MAX_TEXT) : "");
	const title = text(body?.title).slice(0, 120);
	const message = text(body?.body);
	if (!title || !message) return fail("bad_request", "title and body are required", 400);

	const rawTarget = typeof body?.target === "string" ? body.target.trim() : "all";
	let targetType = "all";
	let target: string | null = null;
	if (rawTarget.toLowerCase().startsWith("device:")) {
		const device = rawTarget.slice(7).toLowerCase();
		if (!DEVICE_RE.test(device)) return fail("bad_target", "device: needs the 64-character device hash", 400);
		targetType = "device";
		target = device;
	} else if (rawTarget.toLowerCase() !== "all") {
		const code = normalizeCode(rawTarget);
		if (!code) return fail("bad_target", "target is all, a key, or device:<hash>", 400);
		const exists = await env.DB.prepare("SELECT 1 FROM keys WHERE code = ?1").bind(code).first();
		if (!exists) return fail("not_found", "no such key", 404);
		targetType = "key";
		target = code;
	}

	const buttonLabel = text(body?.button_label).slice(0, 40) || null;
	const buttonUrl = text(body?.button_url) || null;
	if ((buttonLabel === null) !== (buttonUrl === null)) {
		return fail("bad_request", "button_label and button_url go together", 400);
	}
	if (buttonUrl && !/^(https:\/\/|whatsapp:\/\/)/i.test(buttonUrl)) {
		return fail("bad_request", "button_url must be https:// (or whatsapp://)", 400);
	}
	const days = typeof body?.expires_days === "number" && body.expires_days > 0 ? Math.min(body.expires_days, 365) : null;
	const now = Date.now();
	const result = await env.DB.prepare(
		"INSERT INTO messages (target_type, target, title, body, button_label, button_url, created_at, expires_at) " +
			"VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
	)
		.bind(targetType, target, title, message, buttonLabel, buttonUrl, now, days ? now + days * 86400000 : null)
		.run();
	const row = await env.DB.prepare("SELECT * FROM messages WHERE id = ?1")
		.bind(result.meta.last_row_id)
		.first<MessageRow>();
	return json(messageObject(row!), 201);
}

// DELETE /admin/messages/:id: withdraw it (phones that already showed it keep nothing).
export async function withdrawMessage(id: string, env: StatusEnv): Promise<Response> {
	const n = Number(id);
	if (!Number.isInteger(n) || n <= 0) return fail("bad_request", "not a message id", 400);
	const result = await env.DB.prepare("UPDATE messages SET withdrawn_at = ?1 WHERE id = ?2 AND withdrawn_at IS NULL")
		.bind(Date.now(), n)
		.run();
	if (result.meta.changes === 0) return fail("not_found", "no such live message", 404);
	return json({ id: n, withdrawn: true });
}
