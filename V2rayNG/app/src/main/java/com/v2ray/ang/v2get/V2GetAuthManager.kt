package com.v2ray.ang.v2get

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AetherCore
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Signs in to the V2GET panel and turns the core settings it returns into the Aether profile the app runs. */
object V2GetAuthManager {

    /** The one profile V2GET keeps; a new sign-in replaces it in place. */
    const val PROFILE_GUID = "v2get-aether-core-active"

    const val DEFAULT_AETHER_COMMAND = "--masque -4 --scan turbo --noize firewall"

    data class CoreConfig(val core: String, val socksPort: Int, val aetherCommand: String)

    enum class LoginError { BAD_CREDENTIALS, SERVER, NETWORK, BAD_RESPONSE, NO_SERVER }

    sealed class LoginResult {
        data class Success(val session: V2GetSession, val config: CoreConfig?) : LoginResult()
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
        val config = root.obj("config")?.let {
            CoreConfig(
                core = it.text("core") ?: "aether",
                socksPort = it.long("socksPort")?.toInt() ?: 1819,
                aetherCommand = it.text("aetherCommand") ?: DEFAULT_AETHER_COMMAND,
            )
        }
        return LoginResult.Success(session, config)
    }

    /**
     * The command without its own listener: the app picks the port its Aether outbounds dial, and a
     * port named by the panel would be one nothing dials. An empty rest becomes [DEFAULT_AETHER_COMMAND].
     */
    internal fun sanitizeCommand(raw: String): String {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = mutableListOf<String>()
        var index = 0
        while (index < words.size) {
            val word = words[index]
            when {
                word == "--bind" -> index += 1 // its address is the next word
                word.startsWith("--bind=") -> Unit
                else -> kept.add(word)
            }
            index += 1
        }
        return kept.joinToString(" ").ifEmpty { DEFAULT_AETHER_COMMAND }
    }

    internal fun buildAetherProfile(config: CoreConfig): ProfileItem {
        val command = sanitizeCommand(config.aetherCommand)
        return ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "V2GET Cloud Aether"
            aetherCommand = command
            if ("--masque" in command.split(' ')) aetherProtocol = AetherProtocol.MASQUE.type
        }
    }

    /** Stores the profile for [config] and selects it. False, with the stored profile as it was, when the command is not one the core runs or the storage refused it. */
    fun applyAetherCoreProfile(config: CoreConfig): Boolean {
        val profile = buildAetherProfile(config)
        if (AetherCore.ofCommand(profile.aetherCommand.orEmpty()) == null) {
            LogUtil.e(AppConfig.TAG, "V2GetAuthManager: the core command from the panel is not readable")
            return false
        }
        return try {
            MmkvManager.encodeServerConfig(PROFILE_GUID, profile)
            MmkvManager.setSelectServer(PROFILE_GUID)
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "V2GetAuthManager: storing profile $PROFILE_GUID failed", e)
            false
        }
    }

    /** Forgets the account and its profile; the caller stops a running service first. */
    fun logout() {
        V2GetSession.clear()
        MmkvManager.tryRemoveServer(PROFILE_GUID)
    }

    private fun JsonObject.text(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.long(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}
