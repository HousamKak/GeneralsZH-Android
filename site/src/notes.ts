// Release notes, English and Arabic, one per version (written in release-notes/<version>.md in the
// repository, turned into JSON by scripts/release/release-notes.py and published by CI with
// site/upload-notes.py). They feed the /changes page, the app's update offer, the AltStore source
// and the admin console's Releases tab.
//
// POST /admin/notes (upload token): one version's notes; GET /api/notes: all, newest first;
// GET /api/notes/<version>: one.

export interface NotesEnv {
	APKS: R2Bucket;
}

export interface NotesSection {
	title: string;
	items: string[];
}

export interface Notes {
	version: string;
	date: string;
	en: NotesSection;
	ar: NotesSection;
}

const INDEX_KEY = "notes/index.json";
const VERSION_RE = /^[0-9]+(\.[0-9]+){1,3}$/;

function fail(error: string, message: string, status: number): Response {
	return Response.json({ error, message }, { status });
}

function validSection(s: unknown): s is NotesSection {
	const v = s as NotesSection;
	return !!v && typeof v.title === "string" && v.title.length > 0 && v.title.length <= 200
		&& Array.isArray(v.items) && v.items.length > 0 && v.items.length <= 20
		&& v.items.every((i) => typeof i === "string" && i.length > 0 && i.length <= 400);
}

// Newest first: by date, then by version number.
function compareVersions(a: string, b: string): number {
	const pa = a.split(".").map(Number);
	const pb = b.split(".").map(Number);
	for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
		const d = (pa[i] ?? 0) - (pb[i] ?? 0);
		if (d !== 0) return d;
	}
	return 0;
}

export async function readAllNotes(env: NotesEnv): Promise<Notes[]> {
	const object = await env.APKS.get(INDEX_KEY);
	return object ? ((await object.json()) as Notes[]) : [];
}

export async function readNotes(env: NotesEnv, version: string): Promise<Notes | null> {
	return (await readAllNotes(env)).find((n) => n.version === version) ?? null;
}

export async function publishNotes(request: Request, env: NotesEnv): Promise<Response> {
	let notes: Notes;
	try {
		notes = (await request.json()) as Notes;
	} catch {
		return fail("bad_request", "expected the notes as JSON", 400);
	}
	if (!notes || typeof notes.version !== "string" || !VERSION_RE.test(notes.version)) {
		return fail("bad_version", "version must look like 0.1.11", 400);
	}
	if (!validSection(notes.en) || !validSection(notes.ar)) {
		return fail("bad_notes", "en and ar each need a title and 1 to 20 items", 400);
	}
	const clean: Notes = {
		version: notes.version,
		date: typeof notes.date === "string" ? notes.date.slice(0, 10) : "",
		en: { title: notes.en.title, items: notes.en.items },
		ar: { title: notes.ar.title, items: notes.ar.items },
	};
	const all = (await readAllNotes(env)).filter((n) => n.version !== clean.version);
	all.push(clean);
	all.sort((a, b) => b.date.localeCompare(a.date) || compareVersions(b.version, a.version));
	await env.APKS.put(INDEX_KEY, JSON.stringify(all), { httpMetadata: { contentType: "application/json" } });
	return Response.json({ published: clean.version, count: all.length });
}

export async function notesApi(env: NotesEnv, version: string | null): Promise<Response> {
	const headers = { "Cache-Control": "public, max-age=60", "Access-Control-Allow-Origin": "*" };
	if (version === null) {
		return Response.json({ notes: await readAllNotes(env) }, { headers });
	}
	const notes = await readNotes(env, version);
	return notes ? Response.json(notes, { headers }) : fail("not_found", "no notes for that version", 404);
}

// Plain text for places that show one block (the AltStore source).
export function notesText(notes: Notes, lang: "en" | "ar"): string {
	const s = notes[lang];
	return `${s.title}\n\n${s.items.map((i) => `• ${i}`).join("\n")}`;
}
