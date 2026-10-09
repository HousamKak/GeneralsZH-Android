// iOS builds: unsigned .ipa files, installed and updated through AltStore/SideStore.
//
// site/upload-ipa.py uploads each build under ipa/ and records it (builds/ios-latest.json);
// a release adds it to ios/releases.json, which /altstore.json turns into an AltStore source
// (the format AltStore and SideStore read). Players add https://zerohour.housamkak.com/altstore.json
// as a source once; AltStore signs the app with their Apple ID and offers each new release as an
// update. /download/ipa/<name> serves the files themselves.

import { notesText, readAllNotes } from "./notes";

export interface IosEnv {
	APKS: R2Bucket;
}

export interface IosBuild {
	key: string;
	version: string;
	build?: number; // CFBundleVersion (the Android versionCode); what the app compares
	size: number;
	sha256: string;
	published: string;
	mandatory?: boolean;
	run_url?: string; // the GitHub Actions run that built it
}

export const IPA_KEY_RE = /^ipa\/[A-Za-z0-9._-]{1,200}\.ipa$/;
const RELEASES_KEY = "ios/releases.json";
const SITE = "https://zerohour.housamkak.com";

export async function recordBuild(request: Request, env: IosEnv, release: boolean): Promise<Response> {
	const build = (await request.json()) as IosBuild;
	const head = IPA_KEY_RE.test(build.key ?? "") ? await env.APKS.head(build.key) : null;
	if (!head || head.size !== build.size) {
		return Response.json({ error: "not_uploaded" }, { status: 409 });
	}
	await env.APKS.put("builds/ios-latest.json", JSON.stringify(build), { httpMetadata: { contentType: "application/json" } });
	if (!release) {
		return Response.json({ recorded: build.key });
	}
	const releases = await readReleases(env);
	// Newest first, one entry per version: re-releasing a version replaces it.
	const kept = releases.filter((r) => r.version !== build.version);
	kept.unshift(build);
	await env.APKS.put(RELEASES_KEY, JSON.stringify(kept.slice(0, 20)), { httpMetadata: { contentType: "application/json" } });
	return Response.json({ released: build.key });
}

export async function serveIpa(env: IosEnv, key: string): Promise<Response> {
	if (!IPA_KEY_RE.test(key)) return new Response("Not found", { status: 404 });
	const object = await env.APKS.get(key);
	if (!object) return new Response("Not found", { status: 404 });
	return new Response(object.body, {
		headers: {
			"Content-Type": "application/octet-stream",
			"Content-Disposition": `attachment; filename="${key.slice("ipa/".length)}"`,
			"Content-Length": String(object.size),
			ETag: object.httpEtag,
			"Cache-Control": "no-store",
		},
	});
}

export async function altstoreSource(env: IosEnv): Promise<Response> {
	const releases = await readReleases(env);
	// What each version brings, English then Arabic, shown in the store's update screen.
	const notes = new Map((await readAllNotes(env)).map((n) => [n.version, `${notesText(n, "en")}\n\n${notesText(n, "ar")}`]));
	const source = {
		name: "ZH Commander",
		identifier: "com.housamkak.zhcommander.source",
		subtitle: "Zero Hour on iPhone and iPad",
		website: SITE,
		iconURL: `${SITE}/icon.png`,
		tintColor: "#C2461B",
		apps: [
			{
				name: "ZH Commander",
				bundleIdentifier: "com.housamkak.zhcommander",
				developerName: "Housam Kak",
				subtitle: "Command & Conquer Generals: Zero Hour, on iOS",
				localizedDescription:
					"The original 2003 Zero Hour engine, running natively on iPhone and iPad. " +
					"Activate with a key, then the app downloads its game data. " +
					"An unofficial fan project, not affiliated with Electronic Arts.",
				iconURL: `${SITE}/icon.png`,
				tintColor: "#C2461B",
				category: "games",
				versions: releases.map((r) => ({
					version: r.version,
					...(r.build !== undefined ? { buildVersion: String(r.build) } : {}),
					date: r.published,
					localizedDescription: notes.get(r.version) ?? `ZH Commander ${r.version}`,
					downloadURL: `${SITE}/download/${r.key}`,
					size: r.size,
					minOSVersion: "16.4",
				})),
				appPermissions: {
					entitlements: [],
					privacy: { NSLocalNetworkUsageDescription: "Used for LAN multiplayer game discovery." },
				},
			},
		],
		news: [],
	};
	return Response.json(source, { headers: { "Cache-Control": "public, max-age=300" } });
}

export async function readReleases(env: IosEnv): Promise<IosBuild[]> {
	const object = await env.APKS.get(RELEASES_KEY);
	return object ? ((await object.json()) as IosBuild[]) : [];
}
