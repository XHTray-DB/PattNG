/**
 * V2GET API (Cloudflare Worker)
 *
 * Bindings: V2GET_KV (KV namespace), ADMIN_SECRET (secret).
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

const getUser = (env, username) => env.V2GET_KV.get(`user:${username}`, { type: "json" });
const putUser = (env, user) => env.V2GET_KV.put(`user:${user.username}`, JSON.stringify(user));

async function login(request, env, nowMs) {
  const body = await readJson(request);
  const username = normalizeUsername(body.username);
  const password = typeof body.password === "string" ? body.password : "";
  if (!username || password.length < 1 || password.length > 256) {
    return json({ error: "invalid_credentials" }, 401);
  }

  const failKey = `fail:${username}`;
  const fails = parseInt((await env.V2GET_KV.get(failKey)) || "0", 10);
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
    await env.V2GET_KV.put(failKey, String(fails + 1), { expirationTtl: FAIL_WINDOW });
    return json({ error: "invalid_credentials" }, 401);
  }
  if (user.disabled) return json({ error: "account_disabled" }, 403);

  await env.V2GET_KV.delete(failKey);
  const token = toB64Url(crypto.getRandomValues(new Uint8Array(32)));
  const tokenId = toHex(await sha256(token));
  await env.V2GET_KV.put(`sess:${tokenId}`, JSON.stringify({ username, created: nowMs }), {
    expirationTtl: SESSION_TTL,
  });

  const state = accountState(user, nowMs);
  return json({
    success: true,
    token,
    server_time: Math.floor(nowMs / 1000),
    user: userView(user, nowMs),
    // The worker hands out no core settings: the configuration is public and the app reads it from the repository.
  });
}

async function sessionUser(request, env) {
  const header = request.headers.get("Authorization") || "";
  if (!header.startsWith("Bearer ")) return null;
  const tokenId = toHex(await sha256(header.slice(7).trim()));
  const session = await env.V2GET_KV.get(`sess:${tokenId}`, { type: "json" });
  if (!session) return null;
  return getUser(env, session.username);
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

  // The same report sent twice counts once. KV is not atomic, so two reports landing at the same moment
  // can lose one; move to D1 or a Durable Object when exact counting matters.
  const reportKey = `use:${user.username}:${sessionId}:${seq}`;
  if (!(await env.V2GET_KV.get(reportKey))) {
    user.usedBytes += up + down;
    await putUser(env, user);
    await env.V2GET_KV.put(reportKey, "1", { expirationTtl: 2 * 24 * 3600 });
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
  if (body.resetUsage) user.usedBytes = 0;
  if (typeof body.displayName === "string") user.displayName = body.displayName;
  if (typeof body.plan === "string") user.plan = body.plan;
  user.quotaBytes = user.quotaBytes ?? 0;

  await putUser(env, user);
  return json({ ok: true, user: userView(user, nowMs) });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const nowMs = Date.now();
    const path = url.pathname;

    if (!env.V2GET_KV) return json({ error: "kv_not_bound" }, 500);

    if ((path === "/" || path === "/api/health") && request.method === "GET") {
      return json({ status: "online", brand: "V2GET", server_time: Math.floor(nowMs / 1000) });
    }
    if (path === "/api/v1/auth/login" && request.method === "POST") return login(request, env, nowMs);
    if (path === "/api/v1/admin/user" && request.method === "POST") return adminUser(request, env, nowMs);

    if (path === "/api/v1/user/account" || path === "/api/v1/usage") {
      const user = await sessionUser(request, env);
      if (!user) return json({ error: "unauthorized" }, 401);
      if (path === "/api/v1/user/account" && request.method === "GET") {
        return json({ server_time: Math.floor(nowMs / 1000), user: userView(user, nowMs) });
      }
      if (path === "/api/v1/usage" && request.method === "POST") return usage(request, env, user, nowMs);
    }
    return json({ error: "not_found" }, 404);
  },
};
