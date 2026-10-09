// Game data for activated apps: asset archives downloaded by the app
// after activation instead of being bundled in the APK.
//
// site/upload-data.py uploads a folder's files under data/<version>/ and publishes
// data/manifest.json: { version, files: [{ path, size, sha256, key }] }. The app (DataDownloadActivity)
// fetches /api/data/manifest, then each /data/<key>, resuming with Range after a dropped
// connection and checking every file's SHA-256. Both require the X-ZH-License header: the
// license the license server signed for that device (LicenseGate), verified here against
// the license server's public key.

export interface DataEnv {
	APKS: R2Bucket;
	LICENSE_PUBLIC_KEY: string; // SubjectPublicKeyInfo (DER, base64) of the license signing key
}

export interface DataFile {
	path: string;
	size: number;
	sha256: string;
	key: string;
}

export interface DataManifest {
	version: string;
	published?: string;
	files: DataFile[];
	// Optional packs (upload-data.py): mods a player turns on in the game.
	packs?: { id: string; title: { en: string; ar: string }; description?: { en: string; ar: string }; gameplay?: boolean; size?: number; files: DataFile[] }[];
}

export const DATA_KEY_RE = /^data\/[A-Za-z0-9._-]{1,64}\/(?:[A-Za-z0-9._ !-]{1,120}\/){0,6}[A-Za-z0-9._ !-]{1,120}$/; // deep enough for _packs/<id>/...
const MANIFEST_KEY = "data/manifest.json";

export async function dataManifest(request: Request, env: DataEnv): Promise<Response> {
	if (!(await hasValidLicense(request, env))) return Response.json({ error: "not_activated" }, { status: 403 });
	const object = await env.APKS.get(MANIFEST_KEY);
	if (!object) return Response.json({ error: "no_data" }, { status: 404 });
	return new Response(object.body, { headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
}

export async function dataFile(request: Request, env: DataEnv, key: string): Promise<Response> {
	if (!DATA_KEY_RE.test(key) || key.includes("..")) return new Response("Not found", { status: 404 });
	if (!(await hasValidLicense(request, env))) return new Response("Not activated", { status: 403 });

	// Resume support: "Range: bytes=<from>-" continues a part-downloaded file.
	const range = parseRange(request.headers.get("Range"));
	const object = await env.APKS.get(key, range ? { range: { offset: range } } : undefined);
	if (!object) return new Response("Not found", { status: 404 });
	const headers = new Headers({
		"Content-Type": "application/octet-stream",
		"Accept-Ranges": "bytes",
		ETag: object.httpEtag,
		"Cache-Control": "no-store",
	});
	if (range !== null) {
		if (range >= object.size) return new Response(null, { status: 416, headers });
		headers.set("Content-Range", `bytes ${range}-${object.size - 1}/${object.size}`);
		headers.set("Content-Length", String(object.size - range));
		return new Response(object.body, { status: 206, headers });
	}
	headers.set("Content-Length", String(object.size));
	return new Response(object.body, { headers });
}

// GeneralsX @tweak Codex 08/10/2026 Accept all archive hashes without a retail blocklist.
// Owner only (bearer token, checked by the caller): make an uploaded set of files the current
// game data, once every file it lists is in the bucket at the size it claims.
export async function publishData(request: Request, env: DataEnv): Promise<Response> {
	const manifest = (await request.json()) as DataManifest;
	if (!manifest?.version || !Array.isArray(manifest.files) || manifest.files.length === 0) {
		return Response.json({ error: "bad_manifest" }, { status: 400 });
	}
	// Optional packs (mods a player turns on in the game): each lists its own files, kept apart
	// from "files" so app versions from before packs never download them.
	const packs = Array.isArray(manifest.packs) ? manifest.packs : [];
	for (const pack of packs) {
		if (!/^[a-z0-9][a-z0-9-]{0,39}$/.test(pack?.id ?? "") || !Array.isArray(pack.files) || pack.files.length === 0
			|| !pack.title?.en || !pack.title?.ar) {
			return Response.json({ error: "bad_pack", message: "each pack needs an id, an en/ar title and files" }, { status: 400 });
		}
	}
	for (const file of [...manifest.files, ...packs.flatMap((p) => p.files)]) {
		if (!DATA_KEY_RE.test(file.key ?? "") || file.key.includes("..") || !/^[0-9a-f]{64}$/.test(file.sha256 ?? "")) {
			return Response.json({ error: "bad_file", file: file.path }, { status: 400 });
		}
		const head = await env.APKS.head(file.key);
		if (!head || head.size !== file.size) {
			return Response.json({ error: "not_uploaded", file: file.path }, { status: 409 });
		}
	}
	await env.APKS.put(MANIFEST_KEY, JSON.stringify(manifest), { httpMetadata: { contentType: "application/json" } });
	return Response.json({ published: manifest.version, files: manifest.files.length });
}

function parseRange(header: string | null): number | null {
	const m = header?.match(/^bytes=(\d+)-$/);
	return m ? Number(m[1]) : null;
}

let licenseKey: Promise<CryptoKey> | undefined;

// A license is base64(payload) + "." + base64(DER ECDSA signature), as the license server issues
// and LicenseGate stores it. Device binding is the app's job; here any genuine license counts.
export async function hasValidLicense(request: Request, env: DataEnv): Promise<boolean> {
	const license = request.headers.get("X-ZH-License") ?? "";
	const dot = license.indexOf(".");
	if (dot <= 0 || license.length > 4096) return false;
	try {
		const payload = fromBase64(license.slice(0, dot));
		const signature = derToRaw(fromBase64(license.slice(dot + 1)));
		if (!signature) return false;
		licenseKey ??= crypto.subtle.importKey(
			"spki",
			fromBase64(env.LICENSE_PUBLIC_KEY),
			{ name: "ECDSA", namedCurve: "P-256" },
			false,
			["verify"],
		);
		const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, await licenseKey, signature, payload);
		if (!ok) return false;
		const claims = JSON.parse(new TextDecoder().decode(payload)) as { v?: number };
		return claims.v === 1;
	} catch {
		return false;
	}
}

// WebCrypto verifies r||s (32 bytes each); the license carries ASN.1 DER.
function derToRaw(der: Uint8Array): Uint8Array | null {
	if (der[0] !== 0x30) return null;
	let i = 2;
	const out = new Uint8Array(64);
	for (let part = 0; part < 2; part++) {
		if (der[i] !== 0x02) return null;
		const len = der[i + 1];
		let value = der.slice(i + 2, i + 2 + len);
		while (value.length > 32 && value[0] === 0) value = value.slice(1);
		if (value.length > 32) return null;
		out.set(value, part * 32 + (32 - value.length));
		i += 2 + len;
	}
	return out;
}

function fromBase64(b64: string): Uint8Array {
	return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

// GET /admin/data/manifest (upload token): the published manifest, so upload-data.py --reuse can
// keep the uploads of files that did not change instead of sending gigabytes again.
export async function currentManifest(env: DataEnv): Promise<Response> {
	const object = await env.APKS.get(MANIFEST_KEY);
	return object
		? new Response(object.body, { headers: { "Content-Type": "application/json" } })
		: Response.json({ error: "no_manifest", message: "no game data published yet" }, { status: 404 });
}
