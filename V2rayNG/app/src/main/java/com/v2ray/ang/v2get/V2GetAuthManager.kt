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
    }

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
                val body = if (code == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    null
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

    internal fun parseLogin(code: Int, body: String?, usernameInput: String): LoginResult {
        when (code) {
            HttpURLConnection.HTTP_OK -> Unit
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                return LoginResult.Failure(LoginError.BAD_CREDENTIALS, code)
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
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val root = JsonParser.parseString(conn.inputStream.bufferedReader().use { it.readText() }).asJsonObject
                    SyncResult.Ok(storeFigures(session, root.obj("user")))
                }
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> SyncResult.Unauthorized
                else -> SyncResult.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "V2GetAuthManager: account sync failed", e)
            SyncResult.Failed("${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        } finally {
            conn?.disconnect()
        }
    }

    // A usage report is written down before it is sent and struck out once the panel took it, so a report
    // the process did not live to send, or the network refused, goes out with the next connection. The panel
    // counts a (session, seq) once, so the same report may be sent again.
    private const val KEY_PENDING = "V2GET_PENDING_REPORTS"

    private fun readPending(): org.json.JSONArray =
        runCatching { org.json.JSONArray(MmkvManager.decodeSettingsString(KEY_PENDING).orEmpty().ifEmpty { "[]" }) }
            .getOrDefault(org.json.JSONArray())

    @Synchronized
    private fun writePending(array: org.json.JSONArray) {
        MmkvManager.encodeSettings(KEY_PENDING, array.toString())
    }

    /**
     * Writes one report of a session's traffic down, without sending it ([seq] counts the session's reports from 0).
     * Quick, so that the bytes move from the live counters to the waiting reports at once and the usage the screens
     * show, which adds the two, never dips between them.
     */
    @Synchronized
    fun queueUsage(sessionId: String, seq: Int, up: Long, down: Long) {
        if (up <= 0 && down <= 0) return
        val list = readPending()
        list.put(org.json.JSONObject().put("sid", sessionId).put("seq", seq).put("up", up).put("down", down))
        writePending(list)
    }

    /** Writes one report of a session's traffic down and sends everything waiting. Blocking. */
    fun reportUsage(sessionId: String, seq: Int, up: Long, down: Long) {
        queueUsage(sessionId, seq, up, down)
        flushPending()
    }

    /** The bytes of the reports still waiting for the panel: used, but not yet in the panel's figure. */
    @Synchronized
    fun pendingBytes(): Long {
        val list = readPending()
        var total = 0L
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            total += item.optLong("up", 0L) + item.optLong("down", 0L)
        }
        return total
    }

    /** Sends the waiting usage reports; the ones refused stay. Returns whether none is left. Blocking. */
    @Synchronized
    fun flushPending(): Boolean {
        val list = readPending()
        if (list.length() == 0) return true
        val session = V2GetSession.load() ?: return false
        val left = org.json.JSONArray()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (!sendReport(session, item)) left.put(item)
        }
        writePending(left)
        return left.length() == 0
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
                true
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
        V2GetSession.clear()
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
