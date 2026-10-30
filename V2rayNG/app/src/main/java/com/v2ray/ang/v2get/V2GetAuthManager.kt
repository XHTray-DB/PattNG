package com.v2ray.ang.v2get

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object V2GetAuthManager {

    data class UserProfile(
        val username: String,
        val quotaBytes: Long,
        val usedBytes: Long,
        val expiresAt: String
    )

    data class CoreConfig(
        val core: String,
        val socksPort: Int,
        val aetherCommand: String
    )

    data class AuthResult(
        val user: UserProfile?,
        val config: CoreConfig?,
        val errorMessage: String?
    )

    suspend fun authenticateAndFetchConfig(
        workerApiUrl: String,
        userTokenOrCreds: String
    ): AuthResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$workerApiUrl/api/v1/auth/login")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val payload = JSONObject().apply {
                put("token", userTokenOrCreds)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(responseText)
                
                val userObj = root.optJSONObject("user")
                val cfgObj = root.optJSONObject("config")

                val profile = userObj?.let {
                    UserProfile(
                        username = it.optString("username", "V2GET User"),
                        quotaBytes = it.optLong("quota", 0L),
                        usedBytes = it.optLong("used", 0L),
                        expiresAt = it.optString("expiresAt", "")
                    )
                }

                val config = cfgObj?.let {
                    CoreConfig(
                        core = it.optString("core", "aether"),
                        socksPort = it.optInt("socksPort", 1819),
                        aetherCommand = it.optString("aetherCommand", "--masque -4 --scan turbo --noize firewall")
                    )
                }

                AuthResult(user = profile, config = config, errorMessage = null)
            } else if (responseCode == 403) {
                AuthResult(user = null, config = null, errorMessage = "حجم یا زمان اشتراک به پایان رسیده است.")
            } else {
                AuthResult(user = null, config = null, errorMessage = "خطا در احراز هویت (کد: $responseCode)")
            }
        } catch (e: Exception) {
            AuthResult(user = null, config = null, errorMessage = e.localizedMessage ?: "خطای ناشناخته در ارتباط با سرور")
        }
    }
}
