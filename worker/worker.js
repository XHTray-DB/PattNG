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
  // The reason an account cannot be used, for the app's "account ended / blocked" screen.
  const status = user.disabled ? "disabled" : expired ? "expired" : exhausted ? "exhausted" : "active";
  return { expired, exhausted, status, active: status === "active" };
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
    status: state.status,
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
  `CREATE TABLE IF NOT EXISTS server_votes (
    server_key TEXT NOT NULL,
    username TEXT NOT NULL,
    ok INTEGER NOT NULL,
    at INTEGER NOT NULL,
    PRIMARY KEY (server_key, username)
  )`,
  `CREATE INDEX IF NOT EXISTS server_votes_at ON server_votes (at)`,
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
  // The newest sessions stay, up to the account's device limit; the oldest ones are signed out.
  const limit = await deviceLimit(env, username);
  if (limit > 0) {
    statements.push(
      env.DB.prepare(
        "DELETE FROM sessions WHERE username = ? AND token_id NOT IN " +
          "(SELECT token_id FROM sessions WHERE username = ? ORDER BY created DESC LIMIT ?)",
      ).bind(username, username, limit),
    );
  }
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

/** True when the token belongs to a live session whose user is gone: the account was deleted from the panel. */
async function sessionWithoutUser(request, env, nowMs) {
  const header = request.headers.get("Authorization") || "";
  if (!header.startsWith("Bearer ")) return false;
  const tokenId = toHex(await sha256(header.slice(7).trim()));
  const row = await env.DB.prepare("SELECT 1 AS found FROM sessions WHERE token_id = ? AND created > ?")
    .bind(tokenId, nowMs - SESSION_TTL * 1000)
    .first();
  return Boolean(row);
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
    status: state.status,
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
  if (body.maxDevices !== undefined) {
    if (body.maxDevices === null) {
      await env.DB.prepare("DELETE FROM app_settings WHERE key = ?").bind("devices:" + username).run();
    } else {
      await putSetting(env, "devices:" + username, Math.max(0, Math.min(100, Math.round(Number(body.maxDevices) || 0))));
    }
  }
  const saved = (await getUser(env, username)) || user;
  return json({ ok: true, user: userView(saved, nowMs) });
}

// ---------------------------------------------------------------------------------------------------------------
// Admin: the same secret as the curl calls. A web page at /admin uses these endpoints.
// ---------------------------------------------------------------------------------------------------------------

async function requireAdmin(request, env) {
  const given = request.headers.get("X-Admin-Secret") || "";
  if (!env.ADMIN_SECRET || !equalBytes(await sha256(given), await sha256(env.ADMIN_SECRET))) {
    return json({ error: "forbidden" }, 403);
  }
  return null;
}

const ADMIN_PAGE = `<!doctype html>
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>V2GET Admin</title>
<style>
:root{--ink:#2a1320;--muted:#7a5a68;--pink:#ff758f;--hot:#e20476;--soft:#fff0f3;--line:#f5c9d3;--ok:#10b981}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,Tahoma,sans-serif;background:linear-gradient(#fff5f7,#ffe3ea);color:var(--ink);min-height:100vh}
.wrap{max-width:900px;margin:0 auto;padding:14px}
h1{font-size:20px;margin:6px 0 12px}
.card{background:#fff;border:1px solid var(--line);border-radius:18px;padding:14px;margin-bottom:12px;box-shadow:0 6px 20px #e2047614}
input,textarea{width:100%;padding:10px;border:1px solid var(--line);border-radius:12px;font:inherit;background:var(--soft);margin:4px 0}
button{border:0;border-radius:999px;padding:9px 14px;font:inherit;font-weight:700;color:#fff;background:linear-gradient(90deg,var(--pink),var(--hot));cursor:pointer;margin:2px}
button.alt{background:#fff;color:var(--hot);border:1px solid var(--line)}
button.bad{background:#b3261e}
.tabs{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px}
.tabs button{background:#fff;color:var(--hot);border:1px solid var(--line)}
.tabs button.on{background:linear-gradient(90deg,var(--pink),var(--hot));color:#fff}
table{width:100%;border-collapse:collapse;font-size:13px}
td,th{padding:7px 5px;border-bottom:1px solid var(--line);text-align:right;vertical-align:top}
.scroll{overflow-x:auto}
.tag{display:inline-block;padding:2px 8px;border-radius:999px;font-size:11px;background:var(--soft);color:var(--hot)}
.tag.ok{background:#d9f7ec;color:#0b7a56}
.row{display:grid;grid-template-columns:1fr 1fr;gap:8px}
#msg{position:fixed;bottom:14px;left:14px;right:14px;background:var(--ink);color:#fff;padding:10px 14px;border-radius:14px;display:none;z-index:9}
.muted{color:var(--muted);font-size:12px}
</style>
</head>
<body>
<div class="wrap">
<h1>V2GET - پنل مدیریت</h1>

<div id="login" class="card">
<div>رمز ادمین (ADMIN_SECRET) را وارد کنید:</div>
<input id="secret" type="password" autocomplete="off">
<button id="go">ورود</button>
</div>

<div id="app" hidden>
<div class="tabs" id="tabs"></div>

<div id="t-users" class="tab">
<div class="card">
<input id="search" placeholder="جستجوی نام کاربری">
<div class="scroll"><table><thead><tr><th>کاربر</th><th>مصرف</th><th>انقضا</th><th>دستگاه</th><th>عملیات</th></tr></thead><tbody id="rows"></tbody></table></div>
<div class="muted" id="count"></div>
</div>
</div>

<div id="t-new" class="tab" hidden>
<div class="card">
<div class="row"><div>نام کاربری<input id="n-user"></div><div>رمز (حداقل ۸ حرف)<input id="n-pass"></div></div>
<div class="row"><div>حجم (گیگابایت)<input id="n-quota" type="number" value="50"></div><div>مدت (روز)<input id="n-days" type="number" value="30"></div></div>
<div class="row"><div>نام نمایشی<input id="n-name"></div><div>نوع اشتراک<input id="n-plan"></div></div>
<button id="n-save">ساخت کاربر</button>
<div class="muted">برای ویرایش کاربر موجود، همین فرم را با همان نام کاربری پر کنید (رمز خالی = بدون تغییر).</div>
</div>
</div>

<div id="t-release" class="tab" hidden>
<div class="card">
<div class="muted" id="r-now"></div>
<div class="row"><div>نسخه (مثل 2.4)<input id="r-ver"></div><div>لینک دانلود APK<input id="r-url" dir="ltr"></div></div>
<div>توضیح<input id="r-notes"></div>
<label><input id="r-force" type="checkbox" checked style="width:auto"> اجباری (کاربران قدیمی‌تر قفل می‌شوند)</label><br>
<button id="r-save">ثبت نسخه</button>
</div>
</div>

<div id="t-ann" class="tab" hidden>
<div class="card">
<div class="muted" id="a-now"></div>
<div>متن اعلان<textarea id="a-text" rows="3" maxlength="300"></textarea></div>
<div>لینک (اختیاری)<input id="a-url" dir="ltr"></div>
<button id="a-save">نمایش برای همه</button>
<button id="a-clear" class="alt">برداشتن اعلان</button>
</div>
</div>

<div id="t-set" class="tab" hidden>
<div class="card">
<div>حداکثر دستگاه هر حساب (۰ = نامحدود)<input id="s-dev" type="number" min="0"></div>
<button id="s-save">ذخیره</button>
<div class="muted">وقتی دستگاه جدید وارد شود، قدیمی‌ترین نشست‌ها خارج می‌شوند. برای یک کاربر خاص، در ساخت/ویرایش کاربر از API مقدار maxDevices بدهید.</div>
</div>
</div>
</div>
</div>
<div id="msg"></div>

<script>
var $ = function (id) { return document.getElementById(id); };
var secret = sessionStorage.getItem("v2g_s") || "";
var state = { users: [] };
var TABS = [["users", "کاربران"], ["new", "کاربر جدید"], ["release", "نسخه برنامه"], ["ann", "اعلان"], ["set", "تنظیمات"]];

function say(text) {
  var m = $("msg"); m.textContent = text; m.style.display = "block";
  setTimeout(function () { m.style.display = "none"; }, 3500);
}
function api(path, body) {
  return fetch(path, {
    method: body ? "POST" : "GET",
    headers: { "X-Admin-Secret": secret, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined
  }).then(function (r) {
    return r.json().catch(function () { return {}; }).then(function (t) {
      if (r.status === 403) { logout(); throw new Error("رمز اشتباه است"); }
      if (!r.ok) throw new Error(t.error || ("خطا " + r.status));
      return t;
    });
  });
}
function logout() {
  secret = ""; sessionStorage.removeItem("v2g_s");
  $("app").hidden = true; $("login").hidden = false;
}
function gb(bytes) { return (bytes / 1073741824).toFixed(2); }
function today() { return new Date().toISOString().slice(0, 10); }
function addDays(from, days) {
  var base = from && from > today() ? new Date(from + "T00:00:00Z") : new Date(today() + "T00:00:00Z");
  base.setUTCDate(base.getUTCDate() + days);
  return base.toISOString().slice(0, 10);
}
function show(tab) {
  TABS.forEach(function (t) {
    $("t-" + t[0]).hidden = t[0] !== tab;
    $("tab-" + t[0]).className = t[0] === tab ? "on" : "";
  });
}
function buildTabs() {
  var box = $("tabs"); box.textContent = "";
  TABS.forEach(function (t) {
    var b = document.createElement("button");
    b.id = "tab-" + t[0]; b.textContent = t[1];
    b.onclick = function () { show(t[0]); };
    box.appendChild(b);
  });
  show("users");
}
function load() {
  return api("/api/v1/admin/users").then(function (d) {
    state = d; renderUsers();
    $("s-dev").value = d.maxDevices || 0;
    $("r-now").textContent = d.release ? ("نسخه ثبت‌شده: " + d.release.version + " - " + d.release.url) : "نسخه‌ای ثبت نشده";
    $("a-now").textContent = d.announcement ? ("اعلان فعلی: " + d.announcement.text) : "اعلانی فعال نیست";
  });
}
function act(btnText, fn, cls) {
  var b = document.createElement("button");
  b.textContent = btnText; if (cls) b.className = cls; b.onclick = fn; return b;
}
function update(user, patch) {
  var body = { username: user.username };
  for (var k in patch) body[k] = patch[k];
  return api("/api/v1/admin/user", body).then(function () { say("انجام شد"); return load(); }).catch(function (e) { say(e.message); });
}
function renderUsers() {
  var q = $("search").value.trim().toLowerCase();
  var tbody = $("rows"); tbody.textContent = "";
  var list = state.users.filter(function (u) { return !q || u.username.toLowerCase().indexOf(q) >= 0; });
  list.forEach(function (u) {
    var tr = document.createElement("tr");
    var c1 = document.createElement("td");
    c1.textContent = u.username + (u.displayName ? " (" + u.displayName + ")" : "");
    var tag = document.createElement("div"); tag.className = u.status === "active" ? "tag ok" : "tag"; tag.textContent = u.status;
    c1.appendChild(document.createElement("br")); c1.appendChild(tag);
    var c2 = document.createElement("td"); c2.textContent = gb(u.usedBytes) + " / " + gb(u.quotaBytes) + " GB";
    var c3 = document.createElement("td"); c3.textContent = u.expiresAt || "-";
    var c4 = document.createElement("td"); c4.textContent = String(u.devices);
    var c5 = document.createElement("td");
    c5.appendChild(act("+روز", function () {
      var n = Number(prompt("چند روز اضافه شود؟", "30")); if (!n) return;
      update(u, { expiresAt: addDays(u.expiresAt, n) });
    }));
    c5.appendChild(act("+حجم", function () {
      var n = Number(prompt("چند گیگ اضافه شود؟", "10")); if (!n) return;
      update(u, { quotaGB: Number(gb(u.quotaBytes)) + n });
    }));
    c5.appendChild(act("ریست مصرف", function () { update(u, { resetUsage: true }); }, "alt"));
    c5.appendChild(act(u.disabled ? "باز کردن" : "مسدود", function () { update(u, { disabled: !u.disabled }); }, "alt"));
    c5.appendChild(act("رمز", function () {
      var p = prompt("رمز جدید (حداقل ۸ حرف)"); if (!p) return;
      update(u, { password: p });
    }, "alt"));
    c5.appendChild(act("حذف", function () {
      if (!confirm("حذف " + u.username + " ؟")) return;
      api("/api/v1/admin/user/delete", { username: u.username }).then(function () { say("حذف شد"); return load(); }).catch(function (e) { say(e.message); });
    }, "bad"));
    tr.appendChild(c1); tr.appendChild(c2); tr.appendChild(c3); tr.appendChild(c4); tr.appendChild(c5);
    tbody.appendChild(tr);
  });
  $("count").textContent = list.length + " کاربر";
}
function enter() {
  secret = $("secret").value.trim();
  if (!secret) return;
  load().then(function () {
    sessionStorage.setItem("v2g_s", secret);
    $("login").hidden = true; $("app").hidden = false; buildTabs();
  }).catch(function (e) { say(e.message); });
}
$("go").onclick = enter;
$("secret").onkeydown = function (e) { if (e.key === "Enter") enter(); };
$("search").oninput = renderUsers;
$("n-save").onclick = function () {
  var body = {
    username: $("n-user").value.trim(), quotaGB: Number($("n-quota").value), days: Number($("n-days").value),
    displayName: $("n-name").value.trim(), plan: $("n-plan").value.trim()
  };
  if ($("n-pass").value) body.password = $("n-pass").value;
  api("/api/v1/admin/user", body).then(function () { say("ذخیره شد"); return load(); }).catch(function (e) { say(e.message); });
};
$("r-save").onclick = function () {
  api("/api/v1/admin/release", {
    version: $("r-ver").value.trim(), url: $("r-url").value.trim(), notes: $("r-notes").value.trim(), force: $("r-force").checked
  }).then(function () { say("نسخه ثبت شد"); return load(); }).catch(function (e) { say(e.message); });
};
$("a-save").onclick = function () {
  api("/api/v1/admin/announcement", { text: $("a-text").value, url: $("a-url").value.trim() })
    .then(function () { say("اعلان فعال شد"); return load(); }).catch(function (e) { say(e.message); });
};
$("a-clear").onclick = function () {
  api("/api/v1/admin/announcement", { clear: true }).then(function () { say("اعلان برداشته شد"); return load(); }).catch(function (e) { say(e.message); });
};
$("s-save").onclick = function () {
  api("/api/v1/admin/settings", { maxDevices: Number($("s-dev").value) })
    .then(function () { say("ذخیره شد"); return load(); }).catch(function (e) { say(e.message); });
};
if (secret) { $("secret").value = secret; enter(); }
</script>
</body>
</html>
`;

async function getSetting(env, key) {
  const row = await env.DB.prepare("SELECT value FROM app_settings WHERE key = ?").bind(key).first();
  return row ? row.value : null;
}

async function putSetting(env, key, value) {
  await env.DB.prepare(
    "INSERT INTO app_settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
  )
    .bind(key, String(value))
    .run();
}

/** How many devices an account may be signed in on at once: its own limit, else the general one; 0 means no limit. */
async function deviceLimit(env, username) {
  const rows = await env.DB.prepare("SELECT key, value FROM app_settings WHERE key IN ('max_devices', ?)")
    .bind("devices:" + username)
    .all();
  let general = 0;
  let own = null;
  for (const row of rows.results || []) {
    if (row.key === "max_devices") general = Number(row.value) || 0;
    else own = Number(row.value);
  }
  return own !== null && Number.isFinite(own) ? own : general;
}

async function getAnnouncement(env) {
  const raw = await getSetting(env, "announcement");
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

async function adminAnnouncement(request, env, nowMs) {
  const denied = await requireAdmin(request, env);
  if (denied) return denied;
  const body = await readJson(request);
  if (body.clear) {
    await env.DB.prepare("DELETE FROM app_settings WHERE key = 'announcement'").run();
    return json({ ok: true });
  }
  const text = String(body.text || "").trim().slice(0, 300);
  if (!text) return json({ error: "invalid_text" }, 400);
  const url = String(body.url || "").trim();
  if (url && (!/^https:\/\/[^\s]+$/i.test(url) || url.length > 1000)) return json({ error: "invalid_url" }, 400);
  // The id is what the app remembers as dismissed, so a new announcement shows again.
  const item = { id: nowMs, text, url };
  await putSetting(env, "announcement", JSON.stringify(item));
  return json({ ok: true, announcement: item });
}

async function adminSettings(request, env) {
  const denied = await requireAdmin(request, env);
  if (denied) return denied;
  const body = await readJson(request);
  if (body.maxDevices !== undefined) {
    const value = Math.max(0, Math.min(100, Math.round(Number(body.maxDevices) || 0)));
    await putSetting(env, "max_devices", value);
  }
  return json({ ok: true });
}

async function adminUsers(request, env, nowMs) {
  const denied = await requireAdmin(request, env);
  if (denied) return denied;
  const rows = await env.DB.prepare("SELECT * FROM users ORDER BY username LIMIT 1000").all();
  const devices = await env.DB.prepare(
    "SELECT username, COUNT(*) AS c FROM sessions WHERE created > ? GROUP BY username",
  )
    .bind(nowMs - SESSION_TTL * 1000)
    .all();
  const counts = new Map((devices.results || []).map((r) => [r.username, r.c]));
  const users = (rows.results || []).map((row) => {
    const user = rowToUser(row);
    return {
      ...userView(user, nowMs),
      username: user.username,
      displayName: user.displayName || "",
      disabled: Boolean(user.disabled),
      devices: counts.get(user.username) || 0,
    };
  });
  return json({
    users,
    maxDevices: Number(await getSetting(env, "max_devices")) || 0,
    release: await getRelease(env),
    announcement: await getAnnouncement(env),
  });
}

async function adminDeleteUser(request, env) {
  const denied = await requireAdmin(request, env);
  if (denied) return denied;
  const username = normalizeUsername((await readJson(request)).username);
  if (!username) return json({ error: "invalid_username" }, 400);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM users WHERE username = ?").bind(username),
    env.DB.prepare("DELETE FROM sessions WHERE username = ?").bind(username),
    env.DB.prepare("DELETE FROM login_fails WHERE username = ?").bind(username),
    env.DB.prepare("DELETE FROM server_votes WHERE username = ?").bind(username),
    env.DB.prepare("DELETE FROM app_settings WHERE key = ?").bind("devices:" + username),
  ]);
  return json({ ok: true });
}

// ---------------------------------------------------------------------------------------------------------------
// Which servers work for the people who use them. The app tells whether a connection to a server came up; the last
// word of each account counts, one per account and server, so one person cannot outvote everyone. The builder of
// conf2.json asks for the servers most accounts could not use and leaves them out.
// ---------------------------------------------------------------------------------------------------------------

async function serverReport(request, env, user, nowMs) {
  const body = await readJson(request);
  const key = String(body.key || "");
  if (!/^[0-9a-f]{12}$/.test(key)) return json({ error: "invalid_key" }, 400);
  const statements = [
    env.DB.prepare(
      "INSERT INTO server_votes (server_key, username, ok, at) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT(server_key, username) DO UPDATE SET ok = excluded.ok, at = excluded.at",
    ).bind(key, user.username, body.ok ? 1 : 0, nowMs),
  ];
  // Old votes are swept now and then, not on every report.
  if (Math.random() < 0.02) {
    statements.push(env.DB.prepare("DELETE FROM server_votes WHERE at < ?").bind(nowMs - 7 * 24 * 3600 * 1000));
  }
  await env.DB.batch(statements);
  return json({ ok: true });
}

async function badServers(request, env, url, nowMs) {
  const denied = await requireAdmin(request, env);
  if (denied) return denied;
  const clamp = (value, low, high, fallback) => {
    if (value === null || value === "") return fallback;
    const number = Math.round(Number(value));
    return Number.isFinite(number) ? Math.max(low, Math.min(high, number)) : fallback;
  };
  const hours = clamp(url.searchParams.get("hours"), 1, 168, 48);
  const minUsers = clamp(url.searchParams.get("min_users"), 1, 100, 3);
  const rows = await env.DB.prepare(
    "SELECT server_key, SUM(CASE WHEN ok = 0 THEN 1 ELSE 0 END) AS fails, " +
      "SUM(CASE WHEN ok = 1 THEN 1 ELSE 0 END) AS oks FROM server_votes WHERE at > ? " +
      "GROUP BY server_key HAVING fails >= ? AND fails > oks * 2",
  )
    .bind(nowMs - hours * 3600 * 1000, minUsers)
    .all();
  return json({ bad: (rows.results || []).map((r) => r.server_key), hours, min_users: minUsers });
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
    if (path === "/admin" && request.method === "GET") {
      return new Response(ADMIN_PAGE, {
        headers: {
          "Content-Type": "text/html; charset=utf-8",
          "Cache-Control": "no-store",
          "X-Frame-Options": "DENY",
          "Referrer-Policy": "no-referrer",
        },
      });
    }
    try {
      await ensureSchema(env);
    } catch (error) {
      return json({ error: "db_unavailable", detail: String((error && error.message) || error) }, 500);
    }
    if (path === "/api/v1/auth/login" && request.method === "POST") return login(request, env, nowMs);
    if (path === "/api/v1/admin/user" && request.method === "POST") return adminUser(request, env, nowMs);
    if (path === "/api/v1/admin/release" && request.method === "POST") return adminRelease(request, env, nowMs);
    if (path === "/api/v1/admin/announcement" && request.method === "POST") return adminAnnouncement(request, env, nowMs);
    if (path === "/api/v1/admin/settings" && request.method === "POST") return adminSettings(request, env);
    if (path === "/api/v1/admin/users" && request.method === "GET") return adminUsers(request, env, nowMs);
    if (path === "/api/v1/admin/user/delete" && request.method === "POST") return adminDeleteUser(request, env);
    if (path === "/api/v1/servers/bad" && request.method === "GET") return badServers(request, env, url, nowMs);
    if (path === "/api/v1/app/latest" && request.method === "GET") {
      const release = await getRelease(env);
      return release ? json({ version: release.version, url: release.url }) : json({ error: "not_found" }, 404);
    }

    if (path === "/api/v1/user/account" || path === "/api/v1/usage" || path === "/api/v1/user/server-report") {
      const user = await sessionUser(request, env, nowMs);
      if (!user) {
        // A session that still stands for a user that no longer exists is a removed account, not an expired session.
        if (await sessionWithoutUser(request, env, nowMs)) return json({ error: "account_removed" }, 403);
        return json({ error: "unauthorized" }, 401);
      }
      if (path === "/api/v1/user/account" && request.method === "GET") {
        // The account check is what a connection asks first, so an old app cannot connect either.
        const outdated = await updateGate(request, env);
        if (outdated) return outdated;
        return json({
          server_time: Math.floor(nowMs / 1000),
          user: userView(user, nowMs),
          announcement: await getAnnouncement(env),
        });
      }
      if (path === "/api/v1/user/server-report" && request.method === "POST") return serverReport(request, env, user, nowMs);
      if (path === "/api/v1/usage" && request.method === "POST") return usage(request, env, user, nowMs);
    }
    return json({ error: "not_found" }, 404);
  },
};
