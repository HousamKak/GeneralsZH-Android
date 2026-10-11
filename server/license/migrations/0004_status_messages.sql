-- ZH Commander 11/10/2026: messages from the owner to players, shown once in the game. A message
-- is for every player ('all'), one key ('key', target = the 12-character code) or one device
-- ('device', target = the device hash). The app asks POST /v1/status at start and shows each
-- message it has not shown yet; withdrawn or expired ones are no longer sent.
CREATE TABLE messages (
	id INTEGER PRIMARY KEY,
	target_type TEXT NOT NULL,      -- all | key | device
	target TEXT,                    -- null for 'all'
	title TEXT NOT NULL,
	body TEXT NOT NULL,
	button_label TEXT,              -- optional: a second button that opens button_url
	button_url TEXT,
	created_at INTEGER NOT NULL,
	expires_at INTEGER,             -- null: until withdrawn
	withdrawn_at INTEGER
);
CREATE INDEX messages_target ON messages(target_type, target);

-- When a device last checked its status (the console shows it as "last seen" already, via keys.last_seen).
