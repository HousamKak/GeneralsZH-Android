// Key codes and JSON replies, shared by the admin API, activation and PayPal checkout.

// Crockford-style base32 without the look-alikes I, L, O, U: easy to read out loud and type.
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
export const CODE_LENGTH = 12; // 60 bits: far beyond guessing range.

export function randomCode(): string {
	const bytes = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
	// 256 is a multiple of 32, so taking each byte mod 32 is unbiased.
	return Array.from(bytes, (b) => ALPHABET[b % ALPHABET.length]).join("");
}

export function normalizeCode(input: unknown): string | null {
	if (typeof input !== "string") return null;
	let code = input.toUpperCase().replace(/[^0-9A-Z]/g, "");
	if (code.startsWith("GZH")) code = code.slice(3);
	code = code.replace(/O/g, "0").replace(/[IL]/g, "1");
	return code.length === CODE_LENGTH && [...code].every((c) => ALPHABET.includes(c)) ? code : null;
}

export function formatCode(code: string): string {
	return `GZH-${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8)}`;
}

export async function readJson(request: Request): Promise<Record<string, unknown> | null> {
	try {
		return (await request.json()) as Record<string, unknown>;
	} catch {
		return null;
	}
}

export function json(data: unknown, status = 200): Response {
	return new Response(JSON.stringify(data), {
		status,
		headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
	});
}
