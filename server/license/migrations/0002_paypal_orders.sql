-- One row per paid PayPal order: the key it bought. code matches a row in keys.
CREATE TABLE paypal_orders (
	order_id TEXT PRIMARY KEY,
	capture_id TEXT UNIQUE,
	code TEXT NOT NULL,
	payer_email TEXT,
	amount TEXT,
	created_at INTEGER NOT NULL
);
