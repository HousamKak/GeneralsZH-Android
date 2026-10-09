// Support reports sent from inside the game (the SUPPORT button), kept in R2 for the owner.
//
// POST /api/support/report: the app's zip (support-report.txt plus logs), from an activated app
// only (X-ZH-License, checked like game data). It answers a short reference the game shows the
// player, which is how a report is matched to the person asking about it.
//
// Admin (bearer, see index.ts), the contract of App Monitor's Support tab
// (D:\dev\app-monitor\specs\console-modules.md, section 3.2 plus A2):
// GET  /admin/support?status=open|handled|all&device=<hex prefix>&limit=&cursor=
// GET  /admin/support/<ref>            the zip
// GET  /admin/support/<ref>/summary    {text}: support-report.txt from inside the zip
// POST /admin/support/<ref>            {status?, note?}: either alone keeps the other
// A report's status and note live in its object's custom metadata (no separate index to drift).

import { hasValidLicense, type DataEnv } from "./data";

const MAX_BYTES = 25 * 1024 * 1024;
const REF_RE = /^[A-Z2-9]{8}$/;
// No 0/O or 1/I: the reference is read off a phone screen and typed or said back.
const REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const DEVICE_PREFIX_RE = /^[0-9a-f]{8,64}$/;

function fail(error: string, message: string, status: number): Response {
	return Response.json({ error, message }, { status });
}

export async function receiveReport(request: Request, env: DataEnv): Promise<Response> {
	if (!(await hasValidLicense(request, env))) {
		return fail("not_activated", "only an activated app can send reports", 403);
	}
	const length = Number(request.headers.get("Content-Length") ?? "0");
	if (!request.body || !Number.isFinite(length) || length <= 0 || length > MAX_BYTES) {
		return fail("bad_size", "a report must be between 1 byte and 25 MB", 413);
	}
	const ref = newRef();
	const day = new Date().toISOString().slice(0, 10);
	const header = (name: string, max: number) => (request.headers.get(name) ?? "").slice(0, max).replace(/[^\w .:+()-]/g, "");
	await env.APKS.put(`support/${day}/${ref}.zip`, request.body, {
		httpMetadata: { contentType: "application/zip" },
		customMetadata: {
			ref,
			device: header("X-ZH-Device", 64).toLowerCase(),
			platform: header("X-ZH-Platform", 16),
			version: header("X-ZH-Version", 32),
			model: header("X-ZH-Model", 80),
			country: String((request as Request & { cf?: { country?: string } }).cf?.country ?? ""),
			status: "open",
			received: new Date().toISOString(),
		},
	});
	return Response.json({ ref });
}

interface Report {
	ref: string;
	uploaded: string;
	size: number;
	device: string;
	platform: string;
	version: string;
	model: string;
	country: string;
	status: string;
	note: string;
}

function toReport(o: R2Object): Report {
	const m = o.customMetadata ?? {};
	return {
		ref: m.ref ?? o.key.slice(-12, -4),
		// Saving a status rewrites the object (and its upload time); the first one is kept here.
		uploaded: m.received ?? o.uploaded.toISOString(),
		size: o.size,
		device: m.device ?? "",
		platform: m.platform ?? "",
		version: m.version ?? "",
		model: m.model ?? "",
		country: m.country ?? "",
		status: m.status ?? "open",
		note: m.note ?? "",
	};
}

// Every report, newest first. Support volume is small (one per player problem); listing the whole
// prefix keeps filtering and paging simple and exact.
async function allReports(env: DataEnv): Promise<Report[]> {
	const out: Report[] = [];
	let cursor: string | undefined;
	do {
		const page = await env.APKS.list({ prefix: "support/", include: ["customMetadata"], limit: 1000, cursor });
		for (const o of page.objects) if (o.key.endsWith(".zip")) out.push(toReport(o));
		cursor = page.truncated ? page.cursor : undefined;
	} while (cursor);
	return out.sort((a, b) => b.uploaded.localeCompare(a.uploaded));
}

export async function listReports(url: URL, env: DataEnv): Promise<Response> {
	const status = url.searchParams.get("status") ?? "all";
	if (!["open", "handled", "all"].includes(status)) return fail("bad_status", "status must be open, handled or all", 400);
	const device = url.searchParams.get("device")?.toLowerCase();
	if (device && !DEVICE_PREFIX_RE.test(device)) return fail("bad_device", "device must be at least 8 hex characters", 400);
	const limit = Math.min(Math.max(Number(url.searchParams.get("limit") ?? "100") | 0, 1), 500);
	const offset = Math.max(Number(url.searchParams.get("cursor") ?? "0") | 0, 0);
	const matching = (await allReports(env)).filter(
		(r) => (status === "all" || r.status === status) && (!device || r.device.startsWith(device)),
	);
	const page = matching.slice(offset, offset + limit);
	return Response.json({
		reports: page,
		next_cursor: offset + limit < matching.length ? String(offset + limit) : null,
	});
}

async function findReport(env: DataEnv, ref: string): Promise<R2Object | null> {
	if (!REF_RE.test(ref)) return null;
	let cursor: string | undefined;
	do {
		const page = await env.APKS.list({ prefix: "support/", include: ["customMetadata"], limit: 1000, cursor });
		const hit = page.objects.find((o) => o.key.endsWith(`/${ref}.zip`));
		if (hit) return hit;
		cursor = page.truncated ? page.cursor : undefined;
	} while (cursor);
	return null;
}

// GET /admin/support/<ref>[/summary], POST /admin/support/<ref>
export async function reportRoute(request: Request, env: DataEnv, rest: string): Promise<Response> {
	const [ref, sub] = rest.split("/");
	const found = await findReport(env, ref.toUpperCase());
	if (!found) return fail("not_found", "no report with that reference", 404);

	if (request.method === "GET" && !sub) {
		const object = await env.APKS.get(found.key);
		if (!object) return fail("not_found", "no report with that reference", 404);
		return new Response(object.body, {
			headers: {
				"Content-Type": "application/zip",
				"Content-Disposition": `attachment; filename="zh-report-${ref.toUpperCase()}.zip"`,
			},
		});
	}
	if (request.method === "GET" && sub === "summary") {
		const object = await env.APKS.get(found.key);
		if (!object) return fail("not_found", "no report with that reference", 404);
		const text = await zipEntryText(new Uint8Array(await object.arrayBuffer()), "support-report.txt");
		return text === null
			? fail("no_summary", "this report has no support-report.txt", 404)
			: Response.json({ text });
	}
	if (request.method === "POST" && !sub) {
		let body: { status?: unknown; note?: unknown } = {};
		try {
			body = (await request.json()) as typeof body;
		} catch {
			return fail("bad_request", "expected a JSON body", 400);
		}
		const meta = { ...(found.customMetadata ?? {}) };
		meta.received ??= found.uploaded.toISOString();
		if (body.status !== undefined) {
			if (body.status !== "open" && body.status !== "handled") return fail("bad_status", "status must be open or handled", 400);
			meta.status = body.status;
		}
		if (body.note !== undefined) meta.note = String(body.note ?? "").slice(0, 1000);
		// R2 metadata cannot be edited in place: write the object again with the new metadata.
		const object = await env.APKS.get(found.key);
		if (!object) return fail("not_found", "no report with that reference", 404);
		const saved = await env.APKS.put(found.key, object.body, {
			httpMetadata: { contentType: "application/zip" },
			customMetadata: meta,
		});
		return Response.json(toReport(saved));
	}
	return fail("not_found", "no such support endpoint", 404);
}

// One entry of a zip, as text, found through the central directory (Java's ZipOutputStream writes
// sizes after the data, so the local headers alone do not say how long an entry is). Stored or
// deflated entries; the name matches the end of the path (iOS zips put files in a folder).
async function zipEntryText(zip: Uint8Array, name: string): Promise<string | null> {
	const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
	let eocd = -1;
	for (let i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 65535); i--) {
		if (view.getUint32(i, true) === 0x06054b50) {
			eocd = i;
			break;
		}
	}
	if (eocd < 0) return null;
	const entries = view.getUint16(eocd + 10, true);
	let p = view.getUint32(eocd + 16, true);
	for (let n = 0; n < entries && p + 46 <= zip.length; n++) {
		if (view.getUint32(p, true) !== 0x02014b50) return null;
		const method = view.getUint16(p + 10, true);
		const compressed = view.getUint32(p + 20, true);
		const nameLen = view.getUint16(p + 28, true);
		const extraLen = view.getUint16(p + 30, true);
		const commentLen = view.getUint16(p + 32, true);
		const local = view.getUint32(p + 42, true);
		const entryName = new TextDecoder().decode(zip.subarray(p + 46, p + 46 + nameLen));
		if (entryName === name || entryName.endsWith(`/${name}`)) {
			const dataStart = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
			const data = zip.subarray(dataStart, dataStart + compressed);
			if (method === 0) return new TextDecoder().decode(data);
			if (method !== 8) return null;
			const stream = new Blob([data]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
			return await new Response(stream).text();
		}
		p += 46 + nameLen + extraLen + commentLen;
	}
	return null;
}

function newRef(): string {
	const bytes = crypto.getRandomValues(new Uint8Array(8));
	return Array.from(bytes, (b) => REF_ALPHABET[b % REF_ALPHABET.length]).join("");
}
