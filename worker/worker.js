/**
 * V2GET API (Cloudflare Worker)
 *
 * Bindings: DB (D1 database), ADMIN_SECRET (secret). V2GET_KV (the old KV namespace) is optional: while it is bound,
 * a user or a session that is not in D1 yet is read from it once and copied over, so nobody has to sign in again;
 * remove the binding when the move is done.
 *
 * Why D1 and not KV: KV's free plan allows 1000 writes a day, D1's allows 100,000 rows written and 5 million rows read.
 * D1 also does the counting in one atomic statement, so two reports at the same moment are both counted.
 *
 * Passwords are stored as PBKDF2-SHA256 hashes; sessions are random tokens stored hashed.
 * Time is the Worker's own: quota and expiry are decided here, never by the phone's clock.
 */

const enc = new TextEncoder();
const PBKDF2_ITERATIONS = 100000; // the most Workers' WebCrypto accepts
const SESSION_TTL = 7 * 24 * 3600;
const MAX_FAILS = 10;
const FAIL_WINDOW = 900;
const MAX_DELTA = 50 * 1024 ** 3;
const DUMMY_SALT = new Uint8Array(16);
const REPORT_KEEP_MS = 2 * 24 * 3600 * 1000; // how long a report id is remembered

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });

const toHex = (bytes) => [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
const fromHex = (hex) => Uint8Array.from(hex.match(/../g) || [], (h) => parseInt(h, 16));
const toB64Url = (bytes) =>
  btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

function equalBytes(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

async function sha256(text) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(text)));
}

async function pbkdf2(password, salt, iterations) {
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations, hash: "SHA-256" }, key, 256);
  return new Uint8Array(bits);
}

async function hashNewPassword(password) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await pbkdf2(password, salt, PBKDF2_ITERATIONS);
  return { salt: toHex(salt), hash: toHex(hash), iterations: PBKDF2_ITERATIONS };
}

function normalizeUsername(value) {
  const name = String(value || "").trim().toLowerCase();
  return /^[a-z0-9._-]{3,32}$/.test(name) ? name : null;
}

async function readJson(request) {
  try {
    const body = await request.json();
    return body && typeof body === "object" ? body : {};
  } catch {
    return {};
  }
}

/** The end of an expiry written as a date (yyyy-mm-dd) is the end of that day, UTC. */
function expiryMillis(expiresAt) {
  if (!expiresAt) return Infinity;
  const text = String(expiresAt);
  const ms = Date.parse(text.length === 10 ? `${text}T23:59:59Z` : text);
  return Number.isNaN(ms) ? 0 : ms;
}

function accountState(user, nowMs) {
  const expired = nowMs > expiryMillis(user.expiresAt);
  const exhausted = user.quotaBytes > 0 && user.usedBytes >= user.quotaBytes;
  return { expired, exhausted, active: !user.disabled && !expired && !exhausted };
}

function userView(user, nowMs) {
  const state = accountState(user, nowMs);
  return {
    username: user.displayName || user.username,
    quotaBytes: user.quotaBytes,
    usedBytes: user.usedBytes,
    expiresAt: user.expiresAt || "",
    subscriptionType: user.plan || "",
    isActive: state.active,
  };
}

// ---------------------------------------------------------------------------------------------------------------
// Storage (D1). The tables are created by the Worker itself the first time it runs, so there is nothing to paste
// into a console; the same statements are in schema.sql for anyone who prefers to.
// ---------------------------------------------------------------------------------------------------------------

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS users (
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
  )`,
  `CREATE TABLE IF NOT EXISTS sessions (
    token_id TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    created INTEGER NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS login_fails (
    username TEXT PRIMARY KEY,
    count INTEGER NOT NULL,
    last_at INTEGER NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS app_settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS reports (
    username TEXT NOT NULL,
    session_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    at INTEGER NOT NULL,
    PRIMARY KEY (username, session_id, seq)
  )`,
];

let schemaReady = null;

/** Creates the tables once per Worker instance; a table that exists costs no row. */
function ensureSchema(env) {
  if (!schemaReady) {
    schemaReady = env.DB.batch(SCHEMA.map((sql) => env.DB.prepare(sql))).catch((error) => {
      schemaReady = null;
      throw error;
    });
  }
  return schemaReady;
}

function rowToUser(row) {
  if (!row) return null;
  return {
    username: row.username,
    displayName: row.display_name,
    salt: row.salt,
    hash: row.hash,
    iterations: row.iterations,
    quotaBytes: row.quota_bytes,
    usedBytes: row.used_bytes,
    expiresAt: row.expires_at,
    plan: row.plan,
    disabled: Boolean(row.disabled),
  };
}

const insertUserSql = `INSERT OR IGNORE INTO users
  (username, display_name, salt, hash, iterations, quota_bytes, used_bytes, expires_at, plan, disabled)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`;

/** A user of the old KV storage copied into D1 once (INSERT OR IGNORE: a user already in D1 is never overwritten). */
async function importKvUser(env, username) {
  if (!env.V2GET_KV) return;
  const old = await env.V2GET_KV.get(`user:${username}`, { type: "json" });
  if (!old || !old.salt || !old.hash) return;
  await env.DB.prepare(insertUserSql)
    .bind(
      username,
      old.displayName || "",
      old.salt,
      old.hash,
      old.iterations || PBKDF2_ITERATIONS,
      Math.max(0, Math.round(old.quotaBytes || 0)),
      Math.max(0, Math.round(old.usedBytes || 0)),
      old.expiresAt || "",
      old.plan || "",
      old.disabled ? 1 : 0,
    )
    .run();
}

async function getUser(env, username) {
  const query = () => env.DB.prepare("SELECT * FROM users WHERE username = ?").bind(username).first();
  let row = await query();
  if (!row && env.V2GET_KV) {
    await importKvUser(env, username);
    row = await query();
  }
  return rowToUser(row);
}


// ---------------------------------------------------------------------------------------------------------------
// App updates. The latest version and its download link live in D1 (table app_settings, key "release") and are set
// with one curl (POST /api/v1/admin/release). The app sends its version in X-App-Version; a version older than the
// latest gets 426 "update_required" with the link on login and on the account check, so it cannot go on.
// ---------------------------------------------------------------------------------------------------------------

/** "v2.3.14-beta" -> [2, 3, 14]; null when there is no number at all. */
function parseVersion(value) {
  const match = /^v?(\d+(?:\.\d+){0,3})/i.exec(String(value || "").trim());
  return match ? match[1].split(".").map(Number) : null;
}

/** Negative when a is older than b, 0 when equal, positive when newer. */
function compareVersions(a, b) {
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const diff = (a[i] || 0) - (b[i] || 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

async function getRelease(env) {
  const row = await env.DB.prepare("SELECT value FROM app_settings WHERE key = 'release'").first();
  if (!row) return null;
  try {
    return JSON.parse(row.value);
  } catch {
    return null;
  }
}

/** A 426 response when the app that asks is older than the latest release (and the release is forced), else null. */
async function updateGate(request, env) {
  const release = await getRelease(env);
  if (!release || !release.force) return null;
  const latest = parseVersion(release.version);
  if (!latest) return null;
  // An app that does not send its version is older than the check itself.
  const current = parseVersion(request.headers.get("X-App-Version")) || [0];
  if (compareVersions(current, latest) >= 0) return null;
  return json(
    { error: "update_required", latest_version: release.version, update_url: release.url, notes: release.notes || "" },
    426,
  );
}

async function adminRelease(request, env, nowMs) {
  const given = request.headers.get("X-Admin-Secret") || "";
  if (!env.ADMIN_SECRET || !equalBytes(await sha256(given), await sha256(env.ADMIN_SECRET))) {
    return json({ error: "forbidden" }, 403);
  }
  const body = await readJson(request);
  const version = String(body.version || "").trim().replace(/^v/i, "");
  const url = String(body.url || "").trim();
  if (!parseVersion(version)) return json({ error: "invalid_version" }, 400);
  if (!/^https:\/\/[^\s]+$/i.test(url) || url.length > 1000) return json({ error: "invalid_url" }, 400);
  const release = {
    version,
    url,
    notes: typeof body.notes === "string" ? body.notes.slice(0, 500) : "",
    // force:false only records the release; nobody is stopped until it is true.
    force: body.force === undefined ? true : Boolean(body.force),
    updatedAt: Math.floor(nowMs / 1000),
  };
  await env.DB.prepare(
    "INSERT INTO app_settings (key, value) VALUES ('release', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
  )
    .bind(JSON.stringify(release))
    .run();
  return json({ ok: true, release });
}

async function login(request, env, nowMs) {
  const body = await readJson(request);
  const username = normalizeUsername(body.username);
  const password = typeof body.password === "string" ? body.password : "";
  if (!username || password.length < 1 || password.length > 256) {
    return json({ error: "invalid_credentials" }, 401);
  }

  // An old app is stopped before any password work is done.
  const outdated = await updateGate(request, env);
  if (outdated) return outdated;

  const failRow = await env.DB.prepare("SELECT count, last_at FROM login_fails WHERE username = ?").bind(username).first();
  // The count lives for FAIL_WINDOW seconds after the last failure.
  const fails = failRow && nowMs - failRow.last_at < FAIL_WINDOW * 1000 ? failRow.count : 0;
  if (fails >= MAX_FAILS) return json({ error: "too_many_attempts" }, 429);

  const user = await getUser(env, username);
  // A missing user costs the same hashing as a present one, so timing does not say which names exist.
  const derived = await pbkdf2(
    password,
    user ? fromHex(user.salt) : DUMMY_SALT,
    user ? user.iterations || PBKDF2_ITERATIONS : PBKDF2_ITERATIONS,
  );
  const ok = user && equalBytes(derived, fromHex(user.hash));
  if (!ok) {
    await env.DB.prepare(
      "INSERT INTO login_fails (username, count, last_at) VALUES (?, ?, ?) " +
        "ON CONFLICT(username) DO UPDATE SET count = excluded.count, last_at = excluded.last_at",
    )
      .bind(username, fails + 1, nowMs)
      .run();
    return json({ error: "invalid_credentials" }, 401);
  }
  if (user.disabled) return json({ error: "account_disabled" }, 403);

  const token = toB64Url(crypto.getRandomValues(new Uint8Array(32)));
  const tokenId = toHex(await sha256(token));
  const statements = [
    env.DB.prepare("INSERT INTO sessions (token_id, username, created) VALUES (?, ?, ?)").bind(tokenId, username, nowMs),
    // Expired sessions are swept when someone signs in; nothing else ever removes them.
    env.DB.prepare("DELETE FROM sessions WHERE created < ?").bind(nowMs - SESSION_TTL * 1000),
  ];
  // Nothing is deleted when nothing failed: a row that is not there costs nothing to leave alone.
  if (failRow) statements.push(env.DB.prepare("DELETE FROM login_fails WHERE username = ?").bind(username));
  await env.DB.batch(statements);

  return json({
    success: true,
    token,
    server_time: Math.floor(nowMs / 1000),
    user: userView(user, nowMs),
    // The worker hands out no core settings: the configuration is public and the app reads it from the repository.
  });
}

async function sessionUser(request, env, nowMs) {
  const header = request.headers.get("Authorization") || "";
  if (!header.startsWith("Bearer ")) return null;
  const tokenId = toHex(await sha256(header.slice(7).trim()));
  const minCreated = nowMs - SESSION_TTL * 1000;
  const find = () =>
    env.DB.prepare(
      "SELECT u.* FROM sessions s JOIN users u ON u.username = s.username WHERE s.token_id = ? AND s.created > ?",
    )
      .bind(tokenId, minCreated)
      .first();
  let row = await find();
  if (!row && env.V2GET_KV) {
    // A session made before the move to D1: copied over once, with its own creation time, so it expires when it would have.
    const old = await env.V2GET_KV.get(`sess:${tokenId}`, { type: "json" });
    if (old && old.username && Number(old.created) > minCreated) {
      await importKvUser(env, old.username);
      await env.DB.prepare("INSERT OR IGNORE INTO sessions (token_id, username, created) VALUES (?, ?, ?)")
        .bind(tokenId, old.username, Number(old.created))
        .run();
      row = await find();
    }
  }
  return rowToUser(row);
}

async function usage(request, env, user, nowMs) {
  const body = await readJson(request);
  const sessionId = String(body.session_id || "");
  const seq = body.seq;
  const up = body.delta_uplink;
  const down = body.delta_downlink;
  const validDelta = (n) => Number.isInteger(n) && n >= 0 && n <= MAX_DELTA;
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(sessionId) || !Number.isInteger(seq) || seq < 0 || !validDelta(up) || !validDelta(down)) {
    return json({ error: "invalid_report" }, 400);
  }

  if (up + down > 0) {
    // One atomic batch: the report id is recorded, and the bytes are added only when it was not there yet (changes()
    // is 1 after an insert that happened, 0 after one that was ignored). The same report sent twice counts once, and
    // two reports at the same moment are both counted, because the addition is done by the database itself.
    await env.DB.batch([
      env.DB.prepare("INSERT OR IGNORE INTO reports (username, session_id, seq, at) VALUES (?, ?, ?, ?)").bind(
        user.username,
        sessionId,
        seq,
        nowMs,
      ),
      env.DB.prepare("UPDATE users SET used_bytes = used_bytes + ? WHERE username = ? AND changes() > 0").bind(
        up + down,
        user.username,
      ),
      // Report ids only matter while a phone may still resend one.
      env.DB.prepare("DELETE FROM reports WHERE at < ?").bind(nowMs - REPORT_KEEP_MS),
    ]);
    const fresh = await env.DB.prepare("SELECT used_bytes FROM users WHERE username = ?").bind(user.username).first();
    if (fresh) user.usedBytes = fresh.used_bytes;
  }
  const state = accountState(user, nowMs);
  return json({
    server_time: Math.floor(nowMs / 1000),
    is_active: state.active,
    quotaBytes: user.quotaBytes,
    usedBytes: user.usedBytes,
    action: state.active ? "CONTINUE" : "TERMINATE_SESSION",
  });
}

async function adminUser(request, env, nowMs) {
  const given = request.headers.get("X-Admin-Secret") || "";
  if (!env.ADMIN_SECRET || !equalBytes(await sha256(given), await sha256(env.ADMIN_SECRET))) {
    return json({ error: "forbidden" }, 403);
  }
  const body = await readJson(request);
  const username = normalizeUsername(body.username);
  if (!username) return json({ error: "invalid_username" }, 400);

  const existing = await getUser(env, username);
  const user = existing || { username, usedBytes: 0 };
  if (typeof body.password === "string" && body.password.length >= 8) {
    Object.assign(user, await hashNewPassword(body.password));
  } else if (!existing) {
    return json({ error: "password_min_8_chars" }, 400);
  }
  if (body.quotaGB !== undefined) user.quotaBytes = Math.max(0, Math.round(Number(body.quotaGB) * 1024 ** 3));
  if (body.days !== undefined) user.expiresAt = new Date(nowMs + Number(body.days) * 86400000).toISOString().slice(0, 10);
  if (typeof body.expiresAt === "string") user.expiresAt = body.expiresAt;
  if (body.disabled !== undefined) user.disabled = Boolean(body.disabled);
  const resetUsage = Boolean(body.resetUsage);
  if (resetUsage) user.usedBytes = 0;
  if (typeof body.displayName === "string") user.displayName = body.displayName;
  if (typeof body.plan === "string") user.plan = body.plan;
  user.quotaBytes = user.quotaBytes ?? 0;

  // An upsert that leaves used_bytes alone unless the usage is reset: a report that lands meanwhile is not overwritten.
  await env.DB.prepare(
    `INSERT INTO users (username, display_name, salt, hash, iterations, quota_bytes, used_bytes, expires_at, plan, disabled)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(username) DO UPDATE SET
       display_name = excluded.display_name, salt = excluded.salt, hash = excluded.hash,
       iterations = excluded.iterations, quota_bytes = excluded.quota_bytes, expires_at = excluded.expires_at,
       plan = excluded.plan, disabled = excluded.disabled,
       used_bytes = CASE WHEN ? THEN 0 ELSE users.used_bytes END`,
  )
    .bind(
      username,
      user.displayName || "",
      user.salt,
      user.hash,
      user.iterations || PBKDF2_ITERATIONS,
      user.quotaBytes,
      user.usedBytes || 0,
      user.expiresAt || "",
      user.plan || "",
      user.disabled ? 1 : 0,
      resetUsage ? 1 : 0,
    )
    .run();
  const saved = (await getUser(env, username)) || user;
  return json({ ok: true, user: userView(saved, nowMs) });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const nowMs = Date.now();
    const path = url.pathname;

    if (!env.DB) return json({ error: "d1_not_bound" }, 500);

    if ((path === "/" || path === "/api/health") && request.method === "GET") {
      return json({ status: "online", brand: "V2GET", server_time: Math.floor(nowMs / 1000) });
    }
    try {
      await ensureSchema(env);
    } catch (error) {
      return json({ error: "db_unavailable", detail: String((error && error.message) || error) }, 500);
    }
    if (path === "/api/v1/auth/login" && request.method === "POST") return login(request, env, nowMs);
    if (path === "/api/v1/admin/user" && request.method === "POST") return adminUser(request, env, nowMs);
    if (path === "/api/v1/admin/release" && request.method === "POST") return adminRelease(request, env, nowMs);
    if (path === "/api/v1/app/latest" && request.method === "GET") {
      const release = await getRelease(env);
      return release ? json({ version: release.version, url: release.url }) : json({ error: "not_found" }, 404);
    }

    if (path === "/api/v1/user/account" || path === "/api/v1/usage") {
      const user = await sessionUser(request, env, nowMs);
      if (!user) return json({ error: "unauthorized" }, 401);
      if (path === "/api/v1/user/account" && request.method === "GET") {
        // The account check is what a connection asks first, so an old app cannot connect either.
        const outdated = await updateGate(request, env);
        if (outdated) return outdated;
        return json({ server_time: Math.floor(nowMs / 1000), user: userView(user, nowMs) });
      }
      if (path === "/api/v1/usage" && request.method === "POST") return usage(request, env, user, nowMs);
    }
    return json({ error: "not_found" }, 404);
  },
};
