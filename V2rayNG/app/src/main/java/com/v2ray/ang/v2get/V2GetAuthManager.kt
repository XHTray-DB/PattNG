package com.v2ray.ang.v2get

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Signs in to the V2GET panel and turns the core settings it returns into the Aether profile the app runs. */
object V2GetAuthManager {

    /** The profile of the single-server build. Servers now are [V2GetConfigSource.GUID_PREFIX] plus a hash; this id is still cleaned up. */
    const val PROFILE_GUID = "v2get-aether-core-active"

    enum class LoginError { BAD_CREDENTIALS, SERVER, NETWORK, BAD_RESPONSE, NO_SERVER }

    sealed class LoginResult {
        data class Success(val session: V2GetSession) : LoginResult()
        data class Failure(val error: LoginError, val httpCode: Int = 0) : LoginResult()
        /** The panel knows a newer app and refuses this one. */
        data class UpdateRequired(val info: V2GetUpdateInfo) : LoginResult()
        /** The panel knows the account and will not let it in (blocked). */
        data class Blocked(val reason: V2GetBlockReason) : LoginResult()
    }

    /** The app's own version, told to the panel with every sign-in and account check. */
    private const val APP_VERSION_HEADER = "X-App-Version"

    /** 426: the panel wants a newer app. */
    private const val HTTP_UPGRADE_REQUIRED = 426

    fun isLoggedIn(): Boolean = V2GetSession.load() != null

    suspend fun loginWithCredentials(apiUrl: String, username: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            val endpoint = loginEndpoint(apiUrl) ?: return@withContext LoginResult.Failure(LoginError.NO_SERVER)
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty(APP_VERSION_HEADER, BuildConfig.VERSION_NAME)
                    doOutput = true
                    connectTimeout = 12_000
                    readTimeout = 12_000
                }
                val payload = JsonObject().apply {
                    addProperty("username", username)
                    addProperty("password", password)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = when (code) {
                    HttpURLConnection.HTTP_OK -> conn.inputStream.bufferedReader().use { it.readText() }
                    HTTP_UPGRADE_REQUIRED, HttpURLConnection.HTTP_FORBIDDEN ->
                        conn.errorStream?.bufferedReader()?.use { it.readText() }
                    else -> null
                }
                parseLogin(code, body, username)
            } catch (e: Exception) {
                // The request carries the password, so only the failure itself is logged.
                LogUtil.e(AppConfig.TAG, "V2GetAuthManager: login request failed", e)
                LoginResult.Failure(LoginError.NETWORK)
            } finally {
                conn?.disconnect()
            }
        }

    /** The login URL under [apiUrl], or null when [apiUrl] is not an https address: the password never travels in clear. */
    internal fun loginEndpoint(apiUrl: String): String? {
        val base = apiUrl.trim().trimEnd('/')
        if (!base.startsWith("https://", ignoreCase = true) || base.length <= "https://".length) return null
        return "$base/api/v1/auth/login"
    }

    /** The `error` field of a panel answer, or null. */
    private fun errorOf(body: String?): String? = try {
        JsonParser.parseString(body.orEmpty()).takeIf { it.isJsonObject }?.asJsonObject?.text("error")
    } catch (_: Exception) {
        null
    }

    internal fun parseLogin(code: Int, body: String?, usernameInput: String): LoginResult {
        when (code) {
            HttpURLConnection.HTTP_OK -> Unit
            HttpURLConnection.HTTP_FORBIDDEN -> {
                val blocked = V2GetBlock.fromError(errorOf(body))
                return if (blocked != null) LoginResult.Blocked(blocked)
                else LoginResult.Failure(LoginError.BAD_CREDENTIALS, code)
            }
            HttpURLConnection.HTTP_UNAUTHORIZED ->
                return LoginResult.Failure(LoginError.BAD_CREDENTIALS, code)
            HTTP_UPGRADE_REQUIRED -> return LoginResult.UpdateRequired(V2GetUpdateInfo.parse(body))
            else -> return LoginResult.Failure(LoginError.SERVER, code)
        }
        val root = try {
            JsonParser.parseString(body.orEmpty()).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return LoginResult.Failure(LoginError.BAD_RESPONSE, code)

        val token = root.text("token").orEmpty()
        if (token.isEmpty()) return LoginResult.Failure(LoginError.BAD_RESPONSE, code)

        val user = root.obj("user")
        // A quota of 0 is what the panel sends, or what is assumed, for an account with no limit.
        val session = V2GetSession(
            token = token,
            username = user?.text("username")?.takeIf { it.isNotEmpty() } ?: usernameInput,
            quotaBytes = user?.long("quotaBytes") ?: user?.long("quota") ?: 0L,
            usedBytes = user?.long("usedBytes") ?: user?.long("used") ?: 0L,
            expiresAt = user?.text("expiresAt").orEmpty(),
        )
        return LoginResult.Success(session)
    }

    /** Result of asking the panel for the account as it stands now. */
    sealed class SyncResult {
        data class Ok(val session: V2GetSession) : SyncResult()
        data object Unauthorized : SyncResult()
        data class UpdateRequired(val info: V2GetUpdateInfo) : SyncResult()
        /** The account is deleted, blocked, ended or out of quota. */
        data class Blocked(val reason: V2GetBlockReason) : SyncResult()
        data class Failed(val reason: String) : SyncResult()
    }

    private fun apiBase(): String = BuildConfig.V2GET_API_URL.trim().trimEnd('/')

    /** Replaces the quota, usage and expiry of the stored session with the panel's figures. */
    private fun storeFigures(old: V2GetSession, user: JsonObject?): V2GetSession {
        if (user == null) return old
        val fresh = old.copy(
            quotaBytes = user.long("quotaBytes") ?: old.quotaBytes,
            usedBytes = user.long("usedBytes") ?: old.usedBytes,
            expiresAt = user.text("expiresAt") ?: old.expiresAt,
        )
        V2GetSession.save(fresh)
        return fresh
    }

    /** GET /api/v1/user/account: the usage figures, checked against the panel. Blocking. */
    fun syncAccount(): SyncResult {
        val session = V2GetSession.load() ?: return SyncResult.Unauthorized
        if (!apiBase().startsWith("https://", ignoreCase = true)) return SyncResult.Failed("آدرس سرور HTTPS نیست")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("${apiBase()}/api/v1/user/account").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${session.token}")
                setRequestProperty("Accept", "application/json")
                setRequestProperty(APP_VERSION_HEADER, BuildConfig.VERSION_NAME)
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val root = JsonParser.parseString(conn.inputStream.bufferedReader().use { it.readText() }).asJsonObject
                    val fresh = storeFigures(session, root.obj("user"))
                    // The panel's announcement to every account, or none: what is there now replaces what was kept.
                    V2GetNotices.saveAnnouncement(root.get("announcement"))
                    val blocked = V2GetBlock.fromStatus(root.obj("user")?.text("status"))
                    if (blocked != null) {
                        V2GetBlock.enforce(blocked)
                        SyncResult.Blocked(blocked)
                    } else {
                        V2GetBlock.clear()
                        SyncResult.Ok(fresh)
                    }
                }
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    val blocked = V2GetBlock.fromError(errorOf(conn.errorStream?.bufferedReader()?.use { it.readText() }))
                    if (blocked != null) {
                        V2GetBlock.enforce(blocked)
                        SyncResult.Blocked(blocked)
                    } else {
                        SyncResult.Unauthorized
                    }
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> SyncResult.Unauthorized
                HTTP_UPGRADE_REQUIRED ->
                    SyncResult.UpdateRequired(V2GetUpdateInfo.parse(conn.errorStream?.bufferedReader()?.use { it.readText() }))
                else -> SyncResult.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "V2GetAuthManager: account sync failed", e)
            SyncResult.Failed("${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        } finally {
            conn?.disconnect()
        }
    }

    // The usage is told to the panel at four moments only: signing in, signing out, a connection starting and a
    // connection ending (the panel's storage allows a limited number of writes a day, so there is no report while a
    // connection runs). A report is written down before it is sent and struck out once the panel took it, so a report
    // the process did not live to send, or the network refused, goes out at the next moment. The panel counts a
    // (session, seq) once, so the same report may be sent again.
    private const val KEY_PENDING = "V2GET_PENDING_REPORTS"

    /** Held only while the waiting list is read or written, never while a request is on the wire. */
    private val pendingLock = Any()

    /** Held by the one flush that is running, so two do not send the same report at once. */
    private val flushLock = Any()

    private fun readPending(): org.json.JSONArray =
        runCatching { org.json.JSONArray(MmkvManager.decodeSettingsString(KEY_PENDING).orEmpty().ifEmpty { "[]" }) }
            .getOrDefault(org.json.JSONArray())

    private fun writePending(array: org.json.JSONArray) {
        MmkvManager.encodeSettings(KEY_PENDING, array.toString())
    }

    private fun reportKey(item: org.json.JSONObject) = "${item.optString("sid")}:${item.optInt("seq")}"

    /**
     * Writes one report of a session's traffic down, without sending it ([seq] counts the session's reports from 0).
     * Quick, so that the bytes move from the live counters to the waiting reports at once and the usage the screens
     * show, which adds the two, never dips between them. The report belongs to the account signed in now.
     */
    fun queueUsage(sessionId: String, seq: Int, up: Long, down: Long) {
        if (up <= 0 && down <= 0) return
        val owner = V2GetSession.load()?.username.orEmpty()
        synchronized(pendingLock) {
            val list = readPending()
            list.put(org.json.JSONObject().put("sid", sessionId).put("seq", seq).put("up", up).put("down", down).put("user", owner))
            writePending(list)
        }
    }

    /** Writes one report of a session's traffic down and sends everything waiting. Blocking. */
    fun reportUsage(sessionId: String, seq: Int, up: Long, down: Long) {
        queueUsage(sessionId, seq, up, down)
        flushPending()
    }

    /** The bytes of the reports still waiting for the panel: used, but not yet in the panel's figure. */
    fun pendingBytes(): Long = synchronized(pendingLock) {
        val list = readPending()
        var total = 0L
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            total += item.optLong("up", 0L) + item.optLong("down", 0L)
        }
        total
    }

    /**
     * Sends the waiting usage reports of the account signed in; the ones refused stay. A report that belongs to
     * another account is dropped: it must not be charged to this one. Returns whether none is left. Blocking.
     */
    fun flushPending(): Boolean = synchronized(flushLock) {
        val session = V2GetSession.load() ?: return false
        val snapshot = synchronized(pendingLock) { readPending() }
        if (snapshot.length() == 0) return true
        val done = HashSet<String>()
        for (i in 0 until snapshot.length()) {
            val item = snapshot.optJSONObject(i) ?: continue
            val owner = item.optString("user")
            // A report of another account is never sent with this account's token.
            if (owner.isNotEmpty() && owner != session.username) {
                done.add(reportKey(item))
                continue
            }
            if (sendReport(session, item)) done.add(reportKey(item))
        }
        // Struck out from the list as it is now: a report queued meanwhile stays.
        synchronized(pendingLock) {
            val current = readPending()
            val left = org.json.JSONArray()
            for (i in 0 until current.length()) {
                val item = current.optJSONObject(i) ?: continue
                if (reportKey(item) !in done) left.put(item)
            }
            writePending(left)
            left.length() == 0
        }
    }

    /**
     * Sends what is waiting and, when the network refuses (a connection that is only coming up, a tunnel that just went
     * down), tries again a few times, so that usage is not left unreported until the next time the app is opened.
     * Blocking; call it off the main thread.
     */
    fun flushWithRetry(delaysMs: LongArray = longArrayOf(0L, 5_000L, 15_000L, 45_000L)) {
        for (delay in delaysMs) {
            if (delay > 0) Thread.sleep(delay)
            if (V2GetSession.load() == null) return
            if (flushPending()) return
        }
    }

    /**
     * Tells the panel whether a connection to the server [key] came up, which it counts (one word per account and
     * server) to leave out the servers most accounts could not use. Best effort: a failure is not retried. Blocking.
     */
    fun reportServer(key: String, ok: Boolean) {
        val session = V2GetSession.load() ?: return
        if (!apiBase().startsWith("https://", ignoreCase = true)) return
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("${apiBase()}/api/v1/user/server-report").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer ${session.token}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty(APP_VERSION_HEADER, BuildConfig.VERSION_NAME)
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            val payload = JsonObject().apply {
                addProperty("key", key)
                addProperty("ok", ok)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
    }

    private fun sendReport(session: V2GetSession, item: org.json.JSONObject): Boolean {
        if (!apiBase().startsWith("https://", ignoreCase = true)) return false
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("${apiBase()}/api/v1/usage").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer ${session.token}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            val payload = JsonObject().apply {
                addProperty("session_id", item.getString("sid"))
                addProperty("seq", item.getInt("seq"))
                addProperty("delta_uplink", item.getLong("up"))
                addProperty("delta_downlink", item.getLong("down"))
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val root = JsonParser.parseString(conn.inputStream.bufferedReader().use { it.readText() }).asJsonObject
                storeFigures(session, root)
                // The panel took the report; if it also says the account can no longer be used, the tunnel stops now.
                V2GetBlock.fromStatus(root.text("status"))?.let { V2GetBlock.enforce(it) }
                true
            } else if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                // The account is gone or blocked: the report can never be taken, so it is dropped, and the tunnel stops.
                val blocked = V2GetBlock.fromError(errorOf(conn.errorStream?.bufferedReader()?.use { it.readText() }))
                if (blocked != null) V2GetBlock.enforce(blocked)
                blocked != null
            } else {
                // A report the panel calls invalid never will be valid; keeping it would block the rest.
                code == HttpURLConnection.HTTP_BAD_REQUEST
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "V2GetAuthManager: usage report failed", e)
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** Forgets the account and its profile; the caller stops a running service first. */
    fun logout() {
        // Moment 2 of 4 (sign-out): the usage of the connection just stopped is waited for, then told to the panel,
        // before the account that owns it is forgotten.
        V2GetTraffic.settleBeforeSignOut()
        flushPending()
        V2GetSession.clear()
        V2GetBlock.clear()
        MmkvManager.encodeSettings(KEY_PENDING, "")
        V2GetConfigSource.clear()
    }

    private fun JsonObject.text(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.long(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}
