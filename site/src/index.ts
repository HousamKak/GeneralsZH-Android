// ZH Commander landing site: static page from ./public, the APK from R2.
//
// The bucket holds the APKs under apk/ and a latest.json naming the current one (written by
// upload-apk.sh). /download streams that file; /api/latest gives the page its version and size.
// APKs here never carry game data: upload-apk.sh refuses any that does.

interface Env {
	ASSETS: Fetcher;
	APKS: R2Bucket;
	LICENSE: Fetcher; // the gzh-license Worker, which owns PayPal checkout and the key database
}

interface Latest {
	key: string;
	version: string;
	size: number;
	sha256: string;
	published: string;
}

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		const { pathname } = new URL(request.url);
		if (pathname === "/download") {
			return download(env);
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
		return env.ASSETS.fetch(request);
	},
} satisfies ExportedHandler<Env>;

async function download(env: Env): Promise<Response> {
	const latest = await readLatest(env);
	const object = latest ? await env.APKS.get(latest.key) : null;
	if (!latest || !object) {
		return new Response("No release has been published yet.", { status: 404 });
	}
	return new Response(object.body, {
		headers: {
			"Content-Type": "application/vnd.android.package-archive",
			"Content-Disposition": `attachment; filename="ZH-Commander-${latest.version}.apk"`,
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
