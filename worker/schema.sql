CREATE TABLE IF NOT EXISTS users (
    username TEXT PRIMARY KEY,
    display_name TEXT NOT NULL DEFAULT '',
    salt TEXT NOT NULL,
    hash TEXT NOT NULL,
    iterations INTEGER NOT NULL DEFAULT 100000,
    quota_bytes INTEGER NOT NULL DEFAULT 0,
    used_bytes INTEGER NOT NULL DEFAULT 0,
    expires_at TEXT NOT NULL DEFAULT '',
    plan TEXT NOT NULL DEFAULT '',
    disabled INTEGER NOT NULL DEFAULT 0
  );

CREATE TABLE IF NOT EXISTS sessions (
    token_id TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    created INTEGER NOT NULL
  );

CREATE TABLE IF NOT EXISTS login_fails (
    username TEXT PRIMARY KEY,
    count INTEGER NOT NULL,
    last_at INTEGER NOT NULL
  );

CREATE TABLE IF NOT EXISTS reports (
    username TEXT NOT NULL,
    session_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    at INTEGER NOT NULL,
    PRIMARY KEY (username, session_id, seq)
  );
