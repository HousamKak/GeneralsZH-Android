// ZH Commander landing site: static page from ./public, the APK from R2.
//
// The bucket holds the APKs under apk/ and a latest.json naming the current one (written by
// upload-apk.sh). /download streams that file; /api/latest gives the page its version and size.
// upload-apk.sh lists any game data an APK bundles before publishing it.
//
// /admin/upload/* (bearer UPLOAD_TOKEN) is how upload-apk.py publishes: an R2 multipart upload
// in parts small enough for a Worker request body, so an APK of any size goes up in one piece.

import { DATA_KEY_RE, currentManifest, dataFile, dataManifest, publishData, type DataEnv } from "./data";
import { IPA_KEY_RE, altstoreSource, readReleases, recordBuild, serveIpa } from "./ios";
import { listReports, receiveReport, reportRoute } from "./support";
import { notesApi, publishNotes, readAllNotes } from "./notes";
import { prune } from "./prune";

interface Env extends DataEnv {
	ASSETS: Fetcher;
	LICENSE: Fetcher; // the gzh-license Worker, which owns PayPal checkout and the key database
	UPLOAD_TOKEN: string;
	CONSOLE_TOKEN?: string; // the admin console (App Monitor): support and releases, read and mark only
}

interface Latest {
	key: string;
	version: string;
	size: number;
	sha256: string;
	published: string;
	build?: number; // versionCode
	mandatory?: boolean; // offered as a required update
	run_url?: string; // the GitHub Actions run that built it
	file_removed?: boolean; // the APK was deleted by /admin/prune; the entry stays as history
}

const KEY_RE = /^apk\/[A-Za-z0-9._-]{1,200}\.apk$/;

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		const url = new URL(request.url);
		const { pathname } = url;
		if (pathname.startsWith("/hero/")) {
			return heroAsset(request, env);
		}
		if (pathname === "/download") {
			return download(env);
		}
		// One exact APK, for the app's in-app update (the signed manifest names it, with its
		// SHA-256): /download stays whatever is current, this address never changes.
		if (pathname.startsWith("/download/apk/")) {
			const key = pathname.slice("/download/".length);
			if (!KEY_RE.test(key)) return new Response("Not found", { status: 404 });
			return serveApk(env, key, key.slice("apk/".length));
		}
		// iOS (ios.ts): the AltStore source, and the IPAs it points at.
		if (pathname === "/altstore.json") {
			return altstoreSource(env);
		}
		if (pathname.startsWith("/download/ipa/")) {
			return serveIpa(env, pathname.slice("/download/".length));
		}
		if ((pathname === "/admin/ios/record" || pathname === "/admin/ios/release") && request.method === "POST") {
			if (!(await isUploader(request, env))) {
				return Response.json({ error: "unauthorized" }, { status: 401 });
			}
			return recordBuild(request, env, pathname.endsWith("/release"));
		}
		// The newest build CI uploaded, released or not: what bundle-game-data.sh starts from.
		if (pathname === "/admin/build/latest") {
			if (!(await isUploader(request, env))) {
				return Response.json({ error: "unauthorized" }, { status: 401 });
			}
			const build = await env.APKS.get("builds/latest.json");
			if (!build) return Response.json({ error: "no_build" }, { status: 404 });
			const info = (await build.json()) as Latest;
			return serveApk(env, info.key, info.key.slice("apk/".length));
		}
		// Game data for activated apps (data.ts).
		if (pathname === "/api/data/manifest") {
			return dataManifest(request, env);
		}
		if (pathname.startsWith("/data/")) {
			return dataFile(request, env, decodeURIComponent(pathname.slice(1)));
		}
		if (pathname === "/admin/data/manifest" && request.method === "GET") {
			if (!(await isUploader(request, env))) {
				return Response.json({ error: "unauthorized" }, { status: 401 });
			}
			return currentManifest(env);
		}
		if (pathname === "/admin/data/publish" && request.method === "POST") {
			if (!(await isUploader(request, env))) {
				return Response.json({ error: "unauthorized" }, { status: 401 });
			}
			return publishData(request, env);
		}
		// Support reports from the game's SUPPORT button (support.ts).
		if (pathname === "/api/support/report" && request.method === "POST") {
			return receiveReport(request, env);
		}
		// The admin console's Support and Releases tabs (App Monitor), and support-reports.py.
		if (pathname === "/admin/support" || pathname.startsWith("/admin/support/") || pathname === "/admin/releases") {
			if (!(await isUploader(request, env)) && !(await isConsole(request, env))) {
				return Response.json({ error: "unauthorized", message: "missing or wrong token" }, { status: 401 });
			}
			if (pathname === "/admin/releases" && request.method === "GET") return releases(env);
			if (pathname === "/admin/support" && request.method === "GET") return listReports(url, env);
			if (pathname.startsWith("/admin/support/")) {
				return reportRoute(request, env, pathname.slice("/admin/support/".length));
			}
			return Response.json({ error: "not_found", message: "no such admin endpoint" }, { status: 404 });
		}
		if (pathname.startsWith("/api/paypal/")) {
			const target = new URL(request.url);
			target.pathname = `/v1/paypal/${pathname.slice("/api/paypal/".length)}`;
			return env.LICENSE.fetch(new Request(target, request));
		}
		// Release notes (notes.ts): read by the /changes page and the app; published by CI.
		if (pathname === "/api/notes" || pathname.startsWith("/api/notes/")) {
			return notesApi(env, pathname === "/api/notes" ? null : decodeURIComponent(pathname.slice("/api/notes/".length)));
		}
		if (pathname === "/admin/notes" && request.method === "POST") {
			if (!(await isUploader(request, env))) {
				return Response.json({ error: "unauthorized", message: "missing or wrong token" }, { status: 401 });
			}
			return publishNotes(request, env);
		}
		if (pathname === "/api/latest") {
			const latest = await readLatest(env);
			if (!latest) return Response.json({ error: "no_release" }, { status: 404 });
			const { key: _key, ...info } = latest;
			return Response.json(info, { headers: { "Cache-Control": "public, max-age=60" } });
		}
		if (pathname === "/admin/prune" && request.method === "POST") {
			if (!(await isUploader(request, env))) {
				return Response.json({ error: "unauthorized" }, { status: 401 });
			}
			return prune(env.APKS, url.searchParams.get("dry_run") === "1");
		}
		if (pathname.startsWith("/admin/upload/")) {
			if (!(await isUploader(request, env))) {
				return Response.json({ error: "unauthorized" }, { status: 401 });
			}
			return upload(request, url, env);
		}
		return env.ASSETS.fetch(request);
	},
} satisfies ExportedHandler<Env>;

async function upload(request: Request, url: URL, env: Env): Promise<Response> {
	const step = url.pathname.slice("/admin/upload/".length);
	const key = url.searchParams.get("key") ?? "";
	const uploadId = url.searchParams.get("uploadId") ?? "";
	// Uploads go to apk/ (APKs) or data/ (game data, see data.ts); publish/record name theirs in the body.
	const validKey = KEY_RE.test(key) || IPA_KEY_RE.test(key) || (DATA_KEY_RE.test(key) && !key.includes(".."));
	if (step !== "publish" && step !== "record" && !validKey) {
		return Response.json({ error: "bad_key" }, { status: 400 });
	}

	if (request.method === "POST" && step === "start") {
		const mpu = await env.APKS.createMultipartUpload(key, {
			httpMetadata: { contentType: key.startsWith("apk/") ? "application/vnd.android.package-archive" : "application/octet-stream" },
		});
		return Response.json({ uploadId: mpu.uploadId });
	}
	if (request.method === "PUT" && step === "part") {
		const partNumber = Number(url.searchParams.get("part"));
		if (!Number.isInteger(partNumber) || partNumber < 1 || !request.body) {
			return Response.json({ error: "bad_part" }, { status: 400 });
		}
		const part = await env.APKS.resumeMultipartUpload(key, uploadId).uploadPart(partNumber, request.body);
		return Response.json(part);
	}
	if (request.method === "POST" && step === "complete") {
		const { parts } = (await request.json()) as { parts: R2UploadedPart[] };
		const object = await env.APKS.resumeMultipartUpload(key, uploadId).complete(parts);
		return Response.json({ key: object.key, size: object.size });
	}
	if (request.method === "POST" && step === "abort") {
		await env.APKS.resumeMultipartUpload(key, uploadId).abort();
		return Response.json({ aborted: true });
	}
	// "record" notes an uploaded APK as the newest build (private: /admin/build/latest);
	// "publish" also makes it the site's public download. Both only for a complete upload.
	if (request.method === "POST" && (step === "publish" || step === "record")) {
		const latest = (await request.json()) as Latest;
		const head = KEY_RE.test(latest.key ?? "") ? await env.APKS.head(latest.key) : null;
		if (!head || head.size !== latest.size) {
			return Response.json({ error: "not_uploaded" }, { status: 409 });
		}
		await env.APKS.put(step === "publish" ? "latest.json" : "builds/latest.json", JSON.stringify(latest), {
			httpMetadata: { contentType: "application/json" },
		});
		// Every published release, newest first, for the admin console's Releases tab.
		if (step === "publish") {
			const history = (await readAndroidHistory(env)).filter((r) => r.version !== latest.version);
			history.unshift(latest);
			await env.APKS.put(ANDROID_HISTORY_KEY, JSON.stringify(history), {
				httpMetadata: { contentType: "application/json" },
			});
		}
		return Response.json({ [step === "publish" ? "published" : "recorded"]: latest.key });
	}
	return Response.json({ error: "not_found" }, { status: 404 });
}

const ANDROID_HISTORY_KEY = "apk/releases.json";

async function readAndroidHistory(env: Env): Promise<Latest[]> {
	const object = await env.APKS.get(ANDROID_HISTORY_KEY);
	if (object) return (await object.json()) as Latest[];
	// Before the history existed: start it from the current release.
	const latest = await readLatest(env);
	return latest ? [latest] : [];
}

function releaseEntry(r: Latest, urlPrefix: string) {
	return {
		version: r.version,
		build: r.build ?? null,
		published: r.published,
		size: r.size,
		sha256: r.sha256,
		url: r.file_removed ? null : `${urlPrefix}${r.key}`,
		file_removed: r.file_removed ?? false,
		mandatory: r.mandatory ?? null,
		run_url: r.run_url ?? null,
	};
}

// GET /admin/releases: the admin console's Releases tab (read only; releasing stays in CI).
async function releases(env: Env): Promise<Response> {
	const site = "https://zerohour.housamkak.com/download/";
	// Each release with its notes (both languages), when they were published.
	const notes = new Map((await readAllNotes(env)).map((n) => [n.version, { en: n.en, ar: n.ar }]));
	const withNotes = (r: Latest) => ({ ...releaseEntry(r, site), notes: notes.get(r.version) ?? null });
	const android = (await readAndroidHistory(env)).map(withNotes);
	const ios = (await readReleases(env)).map((r) => withNotes(r as Latest));
	return Response.json({
		android: { current: android[0] ?? null, history: android },
		// iOS releases are always required: the app does not start below the newest one.
		ios: { current: ios[0] ? { ...ios[0], mandatory: true } : null, history: ios.map((r) => ({ ...r, mandatory: true })) },
	});
}

// The admin console's own token, separate from the upload token so either can be rotated alone.
async function isConsole(request: Request, env: Env): Promise<boolean> {
	const header = request.headers.get("Authorization") ?? "";
	const given = new TextEncoder().encode(header.replace(/^Bearer\s+/i, ""));
	const expected = new TextEncoder().encode(env.CONSOLE_TOKEN ?? "");
	if (expected.byteLength < 32 || given.byteLength !== expected.byteLength) return false;
	return crypto.subtle.timingSafeEqual(given, expected);
}

async function isUploader(request: Request, env: Env): Promise<boolean> {
	const header = request.headers.get("Authorization") ?? "";
	const given = new TextEncoder().encode(header.replace(/^Bearer\s+/i, ""));
	const expected = new TextEncoder().encode(env.UPLOAD_TOKEN ?? "");
	if (expected.byteLength < 32 || given.byteLength !== expected.byteLength) return false;
	return crypto.subtle.timingSafeEqual(given, expected);
}

async function download(env: Env): Promise<Response> {
	const latest = await readLatest(env);
	if (!latest) {
		return new Response("No release has been published yet.", { status: 404 });
	}
	return serveApk(env, latest.key, `ZH-Commander-${latest.version}.apk`);
}

async function serveApk(env: Env, key: string, filename: string): Promise<Response> {
	const object = await env.APKS.get(key);
	if (!object) {
		return new Response("Not found", { status: 404 });
	}
	return new Response(object.body, {
		headers: {
			"Content-Type": "application/vnd.android.package-archive",
			"Content-Disposition": `attachment; filename="${filename}"`,
			"Content-Length": String(object.size),
			ETag: object.httpEtag,
			"Cache-Control": "no-store",
		},
	});
}

async function readLatest(env: Env): Promise<Latest | null> {
	const object = await env.APKS.get("latest.json");
	return object ? ((await object.json()) as Latest) : null;
}

// The hero loop's videos. Static assets answer a Range request with the whole file (200), and
// Safari will not play a video that way: it asks for "bytes=0-1" first and needs a 206. The
// files are under 2 MB, so the slice is cut here from the asset itself.
async function heroAsset(request: Request, env: Env): Promise<Response> {
	const asset = await env.ASSETS.fetch(request);
	const header = request.headers.get("Range");
	const m = header ? /^bytes=(\d*)-(\d*)$/.exec(header.trim()) : null;
	if (!asset.ok || !m || (m[1] === "" && m[2] === "")) {
		const headers = new Headers(asset.headers);
		headers.set("Accept-Ranges", "bytes");
		return new Response(asset.body, { status: asset.status, headers });
	}
	const body = await asset.arrayBuffer();
	const size = body.byteLength;
	let start: number;
	let end: number;
	if (m[1] === "") {
		start = Math.max(0, size - Number(m[2])); // "bytes=-N": the last N bytes
		end = size - 1;
	} else {
		start = Number(m[1]);
		end = m[2] === "" ? size - 1 : Math.min(Number(m[2]), size - 1);
	}
	const headers = new Headers(asset.headers);
	headers.set("Accept-Ranges", "bytes");
	if (start >= size || start > end) {
		headers.set("Content-Range", `bytes */${size}`);
		headers.delete("Content-Length");
		return new Response(null, { status: 416, headers });
	}
	headers.set("Content-Range", `bytes ${start}-${end}/${size}`);
	headers.set("Content-Length", String(end - start + 1));
	return new Response(body.slice(start, end + 1), { status: 206, headers });
}
