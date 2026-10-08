// PayPal checkout: a $10 sale payment mints one activation key.
//
// The landing page's PayPal button asks for an order (/v1/paypal/order), the buyer approves it in
// PayPal's window, and the page asks us to capture it (/v1/paypal/capture). Only a capture that
// PayPal reports COMPLETED, for exactly PRICE in CURRENCY, mints a key. Each order maps to one
// key, so a retried or repeated capture returns the same key, and a buyer can look theirs up
// again by the transaction id on their PayPal receipt (/v1/paypal/key).

import { formatCode, json, randomCode, readJson } from "./codes";

export interface PayPalEnv {
	DB: D1Database;
	PAYPAL_ENV?: string; // "live" or "sandbox"
	PAYPAL_CLIENT_ID?: string;
	PAYPAL_SECRET?: string;
}

// GeneralsX @tweak Codex 08/10/2026 Charge the advertised $10 sale price (regularly $15).
const PRICE = "10.00";
const CURRENCY = "USD";
const ORDER_ID_RE = /^[A-Z0-9]{10,30}$/;

function apiBase(env: PayPalEnv): string {
	return env.PAYPAL_ENV === "live" ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com";
}

function enabled(env: PayPalEnv): boolean {
	return Boolean(env.PAYPAL_CLIENT_ID && env.PAYPAL_SECRET);
}

export function paypalConfig(env: PayPalEnv): Response {
	if (!enabled(env)) return json({ enabled: false });
	return json({ enabled: true, clientId: env.PAYPAL_CLIENT_ID, currency: CURRENCY, price: PRICE });
}

export async function paypalOrder(env: PayPalEnv): Promise<Response> {
	if (!enabled(env)) return json({ error: "paypal_disabled" }, 503);
	const res = await paypalFetch(env, "/v2/checkout/orders", {
		intent: "CAPTURE",
		purchase_units: [
			{
				amount: { currency_code: CURRENCY, value: PRICE },
				description: "ZH Commander activation key (one phone)",
			},
		],
	});
	const order = (await res.json()) as { id?: string };
	if (!res.ok || !order.id) {
		console.error("create order failed", res.status, JSON.stringify(order));
		return json({ error: "paypal_error" }, 502);
	}
	return json({ id: order.id });
}

export async function paypalCapture(request: Request, env: PayPalEnv): Promise<Response> {
	if (!enabled(env)) return json({ error: "paypal_disabled" }, 503);
	const orderId = (await readJson(request))?.orderID;
	if (typeof orderId !== "string" || !ORDER_ID_RE.test(orderId)) {
		return json({ error: "bad_request" }, 400);
	}

	const known = await keyForOrder(env, orderId);
	if (known) return json(known);

	// PayPal-Request-Id makes a retried capture return the first result instead of failing.
	let res = await paypalFetch(env, `/v2/checkout/orders/${orderId}/capture`, {}, `capture-${orderId}`);
	let order = (await res.json()) as PayPalOrder;
	if (!res.ok && order.details?.some((d) => d.issue === "ORDER_ALREADY_CAPTURED")) {
		res = await paypalFetch(env, `/v2/checkout/orders/${orderId}`);
		order = (await res.json()) as PayPalOrder;
	}
	if (!res.ok) {
		console.error("capture failed", res.status, JSON.stringify(order));
		return json({ error: "payment_failed" }, 402);
	}

	const capture = order.purchase_units?.[0]?.payments?.captures?.[0];
	if (!capture || capture.status !== "COMPLETED") {
		return json({ error: capture?.status === "PENDING" ? "payment_pending" : "payment_failed" }, 402);
	}
	if (capture.amount?.currency_code !== CURRENCY || capture.amount?.value !== PRICE) {
		console.error("unexpected amount", JSON.stringify(capture.amount));
		return json({ error: "payment_failed" }, 402);
	}

	const now = Date.now();
	const email = order.payer?.email_address ?? null;
	// Whoever inserts the order row first decides its code; the key row follows that code, so
	// two racing captures of the same order still produce exactly one key.
	await env.DB.batch([
		env.DB.prepare(
			"INSERT OR IGNORE INTO paypal_orders (order_id, capture_id, code, payer_email, amount, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
		).bind(orderId, capture.id, randomCode(), email, `${capture.amount.value} ${capture.amount.currency_code}`, now),
		env.DB.prepare(
			"INSERT OR IGNORE INTO keys (code, note, created_at) SELECT code, ?2, ?3 FROM paypal_orders WHERE order_id = ?1",
		).bind(orderId, `PayPal ${capture.id}${email ? ` ${email}` : ""}`, now),
	]);
	const minted = await keyForOrder(env, orderId);
	return minted ? json(minted) : json({ error: "server_error" }, 500);
}

// A buyer who lost the page gets their key back with the order id or the receipt's transaction id.
export async function paypalKeyLookup(url: URL, env: PayPalEnv): Promise<Response> {
	const id = (url.searchParams.get("id") ?? "").trim().toUpperCase();
	if (!ORDER_ID_RE.test(id)) return json({ error: "bad_request" }, 400);
	const row = await env.DB.prepare(
		"SELECT code, capture_id FROM paypal_orders WHERE order_id = ?1 OR capture_id = ?1",
	)
		.bind(id)
		.first<{ code: string; capture_id: string }>();
	return row ? json({ key: formatCode(row.code), transaction: row.capture_id }) : json({ error: "not_found" }, 404);
}

async function keyForOrder(env: PayPalEnv, orderId: string): Promise<{ key: string; transaction: string } | null> {
	const row = await env.DB.prepare("SELECT code, capture_id FROM paypal_orders WHERE order_id = ?1")
		.bind(orderId)
		.first<{ code: string; capture_id: string }>();
	return row ? { key: formatCode(row.code), transaction: row.capture_id } : null;
}

interface PayPalOrder {
	details?: { issue?: string }[];
	payer?: { email_address?: string };
	purchase_units?: {
		payments?: {
			captures?: { id: string; status: string; amount: { value: string; currency_code: string } }[];
		};
	}[];
}

let token: { value: string; expires: number } | undefined;

async function accessToken(env: PayPalEnv): Promise<string> {
	if (token && token.expires > Date.now() + 60_000) return token.value;
	const res = await fetch(`${apiBase(env)}/v1/oauth2/token`, {
		method: "POST",
		headers: {
			Authorization: `Basic ${btoa(`${env.PAYPAL_CLIENT_ID}:${env.PAYPAL_SECRET}`)}`,
			"Content-Type": "application/x-www-form-urlencoded",
		},
		body: "grant_type=client_credentials",
	});
	const body = (await res.json()) as { access_token?: string; expires_in?: number };
	if (!res.ok || !body.access_token) throw new Error(`PayPal token request failed: ${res.status}`);
	token = { value: body.access_token, expires: Date.now() + (body.expires_in ?? 300) * 1000 };
	return token.value;
}

async function paypalFetch(env: PayPalEnv, path: string, body?: unknown, requestId?: string): Promise<Response> {
	const headers: Record<string, string> = {
		Authorization: `Bearer ${await accessToken(env)}`,
		"Content-Type": "application/json",
	};
	if (requestId) headers["PayPal-Request-Id"] = requestId;
	return fetch(`${apiBase(env)}${path}`, {
		method: body === undefined ? "GET" : "POST",
		headers,
		body: body === undefined ? undefined : JSON.stringify(body),
	});
}
