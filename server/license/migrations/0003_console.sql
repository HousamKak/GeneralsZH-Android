-- The admin console (App Monitor's Licenses tab): who bought a key, how they paid, how often its
-- device was reset, and a history of what was done to it. Personal data (buyer, contact) stays in
-- this database; the console reads it live and never copies it.
ALTER TABLE keys ADD COLUMN buyer TEXT;
ALTER TABLE keys ADD COLUMN contact TEXT;
ALTER TABLE keys ADD COLUMN payment_method TEXT;
ALTER TABLE keys ADD COLUMN payment_amount REAL;
ALTER TABLE keys ADD COLUMN payment_currency TEXT;
ALTER TABLE keys ADD COLUMN payment_reference TEXT;
ALTER TABLE keys ADD COLUMN resets INTEGER NOT NULL DEFAULT 0;
ALTER TABLE keys ADD COLUMN revoke_reason TEXT;
ALTER TABLE keys ADD COLUMN last_seen INTEGER;
-- A mint request's id: the same id within 24 h returns the keys it already minted.
ALTER TABLE keys ADD COLUMN request_id TEXT;

CREATE INDEX keys_device ON keys(device);
CREATE INDEX keys_request ON keys(request_id);

CREATE TABLE key_history (
	id INTEGER PRIMARY KEY,
	code TEXT NOT NULL,
	ts INTEGER NOT NULL,
	action TEXT NOT NULL,   -- minted | activated | edited | reset | revoked
	detail TEXT
);
CREATE INDEX key_history_code ON key_history(code, ts);
