// ZH Commander landing site: static page from ./public, the APK from R2.
//
// The bucket holds the APKs under apk/ and a latest.json naming the current one (written by
// upload-apk.sh). /download streams that file; /api/latest gives the page its version and size.
// upload-apk.sh lists any game data an APK bundles before publishing it.
//
// /admin/upload/* (bearer UPLOAD_TOKEN) is how upload-apk.py publishes: an R2 multipart upload
// in parts small enough for a Worker request body, so an APK of any size goes up in one piece.

import { DATA_KEY_RE, dataFile, dataManifest, publishData, type DataEnv } from "./data";
import { IPA_KEY_RE, altstoreSource, recordBuild, serveIpa } from "./ios";

interface Env extends DataEnv {
	ASSETS: Fetcher;
	LICENSE: Fetcher; // the gzh-license Worker, which owns PayPal checkout and the key database
	UPLOAD_TOKEN: string;
}

interface Latest {
	key: string;
	version: string;
	size: number;
	sha256: string;
	published: string;
}

const KEY_RE = /^apk\/[A-Za-z0-9._-]{1,200}\.apk$/;

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		const url = new URL(request.url);
		const { pathname } = url;
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
		if (pathname === "/admin/data/publish" && request.method === "POST") {
			if (!(await isUploader(request, env))) {
				return Response.json({ error: "unauthorized" }, { status: 401 });
			}
			return publishData(request, env);
		}
		if (pathname.startsWith("/api/paypal/")) {
			const target = new URL(request.url);
			target.pathname = `/v1/paypal/${pathname.slice("/api/paypal/".length)}`;
			return env.LICENSE.fetch(new Request(target, request));
		}
		if (pathname === "/api/latest") {
			const latest = await readLatest(env);
			if (!latest) return Response.json({ error: "no_release" }, { status: 404 });
			const { key: _key, ...info } = latest;
			return Response.json(info, { headers: { "Cache-Control": "public, max-age=60" } });
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
		return Response.json({ [step === "publish" ? "published" : "recorded"]: latest.key });
	}
	return Response.json({ error: "not_found" }, { status: 404 });
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
