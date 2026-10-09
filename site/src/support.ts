// Support reports sent from inside the game (the SUPPORT button), kept in R2 for the owner.
//
// POST /api/support/report: the app's zip (support-report.txt plus logs), from an activated app
// only (X-ZH-License, checked like game data). It answers a short reference the game shows the
// player, which is how a report is matched to the person asking about it.
// GET /admin/support (bearer UPLOAD_TOKEN): the newest reports with their device and version.
// GET /admin/support/<ref>: one report's zip.

import { hasValidLicense, type DataEnv } from "./data";

const MAX_BYTES = 25 * 1024 * 1024;
const REF_RE = /^[A-Z2-9]{8}$/;
// No 0/O or 1/I: the reference is read off a phone screen and typed or said back.
const REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

export async function receiveReport(request: Request, env: DataEnv): Promise<Response> {
	if (!(await hasValidLicense(request, env))) {
		return Response.json({ error: "not_activated" }, { status: 403 });
	}
	const length = Number(request.headers.get("Content-Length") ?? "0");
	if (!request.body || !Number.isFinite(length) || length <= 0 || length > MAX_BYTES) {
		return Response.json({ error: "bad_size" }, { status: 413 });
	}
	const ref = newRef();
	const day = new Date().toISOString().slice(0, 10);
	const header = (name: string, max: number) => (request.headers.get(name) ?? "").slice(0, max).replace(/[^\w .:+()-]/g, "");
	await env.APKS.put(`support/${day}/${ref}.zip`, request.body, {
		httpMetadata: { contentType: "application/zip" },
		customMetadata: {
			ref,
			device: header("X-ZH-Device", 64),
			platform: header("X-ZH-Platform", 16),
			version: header("X-ZH-Version", 32),
			model: header("X-ZH-Model", 80),
			country: String((request as Request & { cf?: { country?: string } }).cf?.country ?? ""),
		},
	});
	return Response.json({ ref });
}

export async function listReports(env: DataEnv): Promise<Response> {
	const listed = await env.APKS.list({ prefix: "support/", include: ["customMetadata"], limit: 1000 });
	const reports = listed.objects
		.map((o) => ({ key: o.key, size: o.size, uploaded: o.uploaded.toISOString(), ...o.customMetadata }))
		.sort((a, b) => b.uploaded.localeCompare(a.uploaded))
		.slice(0, 200);
	return Response.json({ reports });
}

export async function getReport(env: DataEnv, ref: string): Promise<Response> {
	if (!REF_RE.test(ref)) return new Response("Not found", { status: 404 });
	const listed = await env.APKS.list({ prefix: "support/", include: ["customMetadata"], limit: 1000 });
	const hit = listed.objects.find((o) => o.key.endsWith(`/${ref}.zip`));
	const object = hit ? await env.APKS.get(hit.key) : null;
	if (!object) return new Response("Not found", { status: 404 });
	return new Response(object.body, {
		headers: {
			"Content-Type": "application/zip",
			"Content-Disposition": `attachment; filename="zh-report-${ref}.zip"`,
		},
	});
}

function newRef(): string {
	const bytes = crypto.getRandomValues(new Uint8Array(8));
	return Array.from(bytes, (b) => REF_ALPHABET[b % REF_ALPHABET.length]).join("");
}
