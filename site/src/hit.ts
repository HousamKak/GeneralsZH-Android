// GeneralsX @feature ZH Commander 10/10/2026 Site visits, downloads and buy clicks to App Monitor.
//
// Counted server-side and sent to App Monitor's ingest as platform "web" events, so downloads sit
// next to the app's installs. No cookies and nothing stored in the browser. The visitor id is
// SHA-256(daySalt + ip + userAgent), daySalt = HMAC-SHA-256(SITE_HASH_SALT, "YYYY-MM-DD" UTC): it
// means "one visitor on one day", cannot be linked across days or to the app's install id, and the
// IP and user agent are used only inside the hash (never stored or forwarded). Sending never blocks
// or fails the visitor's request: it runs in waitUntil and errors are dropped.

export interface HitEnv {
	APKS: R2Bucket;
	SITE_HASH_SALT?: string;
	MONITOR_INGEST_URL?: string; // https://ingest.housamkak.com
	MONITOR_APP_KEY?: string; // the app's public ingest key
}

const BOT_RE = /bot|crawl|spider|preview|slurp|facebookexternalhit|headless|lighthouse/i;
const PAGES = new Set(["/", "/changes", "/privacy"]);
const OWN_HOSTS = new Set(["zerohour.housamkak.com", "zh-commander.housam-kak20.workers.dev"]);

let versionCache: { at: number; version: string } | null = null;

async function currentVersion(env: HitEnv): Promise<string> {
	if (versionCache && Date.now() - versionCache.at < 60_000) return versionCache.version;
	const object = await env.APKS.get("latest.json");
	const version = object ? ((await object.json()) as { version: string }).version : "unknown";
	versionCache = { at: Date.now(), version };
	return version;
}

const hex = (buf: ArrayBuffer) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");

async function visitorId(salt: string, ip: string, ua: string): Promise<string> {
	const enc = new TextEncoder();
	const key = await crypto.subtle.importKey("raw", enc.encode(salt), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
	const daySalt = hex(await crypto.subtle.sign("HMAC", key, enc.encode(new Date().toISOString().slice(0, 10))));
	return hex(await crypto.subtle.digest("SHA-256", enc.encode(daySalt + ip + ua)));
}

// A coarse browser family, never the full user agent.
function browserFamily(ua: string): string {
	const app = /Instagram/.test(ua) ? "Instagram" : /FBAN|FBAV/.test(ua) ? "Facebook" : /TikTok|musical_ly/i.test(ua) ? "TikTok" : null;
	if (/Android/.test(ua)) return `Android ${app ?? (/SamsungBrowser/.test(ua) ? "Samsung Internet" : /Firefox/.test(ua) ? "Firefox" : /Chrome/.test(ua) ? "Chrome" : "Browser")}`;
	if (/iPhone|iPad|iPod/.test(ua)) return `iOS ${app ?? (/CriOS/.test(ua) ? "Chrome" : /FxiOS/.test(ua) ? "Firefox" : "Safari")}`;
	return "Desktop";
}

function refHost(ref: unknown): string | null {
	if (typeof ref !== "string" || !ref) return null;
	try {
		const host = new URL(ref).hostname.toLowerCase().replace(/^www\./, "").replace(/^m\./, "");
		return host && !OWN_HOSTS.has(host) ? host.slice(0, 64) : null;
	} catch {
		return null;
	}
}

// Sends one event. Call inside ctx.waitUntil; never throws.
export async function track(request: Request, env: HitEnv, name: string, props: Record<string, string>): Promise<void> {
	try {
		const ua = request.headers.get("User-Agent") ?? "";
		if (!env.SITE_HASH_SALT || !env.MONITOR_APP_KEY || !env.MONITOR_INGEST_URL || BOT_RE.test(ua)) return;
		const ip = request.headers.get("CF-Connecting-IP") ?? "";
		const country = (request as Request & { cf?: { country?: string } }).cf?.country;
		const body = {
			install_id: await visitorId(env.SITE_HASH_SALT, ip, ua),
			platform: "web",
			version: await currentVersion(env),
			os: browserFamily(ua),
			sdk: "zh-site/1",
			events: [{ name, ts: Date.now(), props: { ...props, ...(country && country !== "XX" ? { country } : {}) } }],
		};
		await fetch(`${env.MONITOR_INGEST_URL}/i/v1/events`, {
			method: "POST",
			headers: { "Content-Type": "application/json", "X-App-Key": env.MONITOR_APP_KEY },
			body: JSON.stringify(body),
		});
	} catch {
		// Telemetry is best effort.
	}
}

// POST /api/hit from the pages (hit.js, navigator.sendBeacon): site_visit and site_buy_click only,
// with their props checked here. Always answers 204.
export async function hit(request: Request, env: HitEnv, ctx: ExecutionContext): Promise<Response> {
	let data: { name?: unknown; page?: unknown; lang?: unknown; ref?: unknown; method?: unknown } = {};
	try {
		data = JSON.parse(await request.text());
	} catch {
		return new Response(null, { status: 204 });
	}
	const lang = data.lang === "ar" ? "ar" : "en";
	if (data.name === "site_visit") {
		const page = typeof data.page === "string" ? data.page.replace(/\.html$/, "").replace(/\/+$/, "") || "/" : "/";
		const ref = refHost(data.ref);
		ctx.waitUntil(track(request, env, "site_visit", { page: PAGES.has(page) ? page : "other", lang, ...(ref ? { ref_host: ref } : {}) }));
	} else if (data.name === "site_buy_click" && (data.method === "whish" || data.method === "paypal")) {
		ctx.waitUntil(track(request, env, "site_buy_click", { method: data.method, lang }));
	}
	return new Response(null, { status: 204 });
}

// A /download that starts a file (not a resumed Range request) counts once.
export function isNewDownload(request: Request): boolean {
	if (request.method !== "GET") return false;
	const range = request.headers.get("Range");
	return !range || /^bytes=0-\s*$/.test(range.trim());
}
