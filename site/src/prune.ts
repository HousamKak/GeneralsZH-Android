// GeneralsX @feature ZH Commander 10/10/2026 Storage retention for published builds.
//
// POST /admin/prune (bearer UPLOAD_TOKEN), called by upload-apk.py and upload-ipa.py after a
// release; ?dry_run=1 lists what would go without deleting. Keeps:
//   Android: the APKs of the newest 2 entries in apk/releases.json (the current release and one
//     to roll back to), plus whatever latest.json and builds/latest.json name. Pruned history
//     entries stay, marked file_removed, so the console's Releases tab keeps them (no link).
//   iOS: the IPAs of the newest 2 entries in ios/releases.json (what /altstore.json lists), plus
//     builds/ios-latest.json. ios/releases.json is trimmed to the kept entries, so the AltStore
//     source never links a missing file.
// Only apk/*.apk and ipa/*.ipa are ever deleted: data/, support/, notes/, builds/ and the JSON
// files are not touched.

const KEEP = 2;
const APK_RE = /^apk\/[A-Za-z0-9._-]{1,200}\.apk$/;
const IPA_RE = /^ipa\/[A-Za-z0-9._-]{1,200}\.ipa$/;

interface Entry {
	key: string;
	version: string;
	file_removed?: boolean;
}

async function readJson<T>(bucket: R2Bucket, key: string): Promise<T | null> {
	const object = await bucket.get(key);
	return object ? ((await object.json()) as T) : null;
}

async function listKeys(bucket: R2Bucket, prefix: string): Promise<R2Object[]> {
	const out: R2Object[] = [];
	let cursor: string | undefined;
	do {
		const page = await bucket.list({ prefix, cursor, limit: 1000 });
		out.push(...page.objects);
		cursor = page.truncated ? page.cursor : undefined;
	} while (cursor);
	return out;
}

const putJson = (bucket: R2Bucket, key: string, value: unknown) =>
	bucket.put(key, JSON.stringify(value), { httpMetadata: { contentType: "application/json" } });

export async function prune(bucket: R2Bucket, dryRun: boolean): Promise<Response> {
	const androidHistory = (await readJson<Entry[]>(bucket, "apk/releases.json")) ?? [];
	const iosHistory = (await readJson<Entry[]>(bucket, "ios/releases.json")) ?? [];
	const pointers = await Promise.all(
		["latest.json", "builds/latest.json", "builds/ios-latest.json"].map((k) => readJson<Entry>(bucket, k)),
	);
	const keep = new Set<string>([
		...androidHistory.filter((r) => !r.file_removed).slice(0, KEEP).map((r) => r.key),
		...iosHistory.slice(0, KEEP).map((r) => r.key),
		...pointers.filter((p): p is Entry => !!p?.key).map((p) => p.key),
	]);

	const builds = [...(await listKeys(bucket, "apk/")), ...(await listKeys(bucket, "ipa/"))].filter(
		(o) => APK_RE.test(o.key) || IPA_RE.test(o.key),
	);
	const doomed = builds.filter((o) => !keep.has(o.key));
	const deleted = doomed.map((o) => ({ key: o.key, bytes: o.size }));
	const bytes = deleted.reduce((s, d) => s + d.bytes, 0);

	// What remains afterwards; an entry whose file is gone now or was already missing is marked.
	const present = new Set(builds.filter((o) => keep.has(o.key)).map((o) => o.key));
	const android = androidHistory.map((r) => (present.has(r.key) ? r : { ...r, file_removed: true }));
	const ios = iosHistory.filter((r) => present.has(r.key));
	const result = {
		dry_run: dryRun,
		deleted,
		bytes_freed: bytes,
		android_marked_removed: android.filter((r, i) => r.file_removed && !androidHistory[i].file_removed).map((r) => r.version),
		ios_dropped_from_source: iosHistory.filter((r) => !present.has(r.key)).map((r) => r.version),
	};
	if (dryRun) return Response.json(result);

	for (let i = 0; i < deleted.length; i += 1000) await bucket.delete(deleted.slice(i, i + 1000).map((d) => d.key));
	if (result.android_marked_removed.length) await putJson(bucket, "apk/releases.json", android);
	if (result.ios_dropped_from_source.length) await putJson(bucket, "ios/releases.json", ios);
	return Response.json(result);
}
