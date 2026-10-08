-- One row per activation key. device is the SHA-256 the app sends (never the raw device id);
-- it is set once, by the first phone to redeem the key.
CREATE TABLE keys (
	code TEXT PRIMARY KEY,
	note TEXT,
	created_at INTEGER NOT NULL,
	device TEXT,
	activated_at INTEGER,
	revoked_at INTEGER
);
