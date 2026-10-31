package com.v2ray.ang.v2get

import com.v2ray.ang.v2get.V2GetAuthManager.LoginError
import com.v2ray.ang.v2get.V2GetAuthManager.LoginResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class V2GetAuthManagerTest {

    private val body = """
        {"token":"abc","user":{"username":"sara","quotaBytes":64424509440,"usedBytes":1073741824,"expiresAt":"2026-12-31"}}
    """.trimIndent()

    @Test
    fun aSuccessfulLoginCarriesTheAccount() {
        val result = V2GetAuthManager.parseLogin(200, body, "typed") as LoginResult.Success
        assertEquals("abc", result.session.token)
        assertEquals("sara", result.session.username)
        assertEquals(60L * 1024 * 1024 * 1024, result.session.quotaBytes)
        assertEquals(1024L * 1024 * 1024, result.session.usedBytes)
        assertEquals("2026-12-31", result.session.expiresAt)
    }

    @Test
    fun theOlderFieldNamesAreStillRead() {
        val old = """{"token":"t","user":{"quota":100,"used":7}}"""
        val result = V2GetAuthManager.parseLogin(200, old, "typed") as LoginResult.Success
        assertEquals(100L, result.session.quotaBytes)
        assertEquals(7L, result.session.usedBytes)
        assertEquals("typed", result.session.username)
    }

    @Test
    fun failuresAreToldApart() {
        assertEquals(LoginResult.Failure(LoginError.BAD_CREDENTIALS, 401), V2GetAuthManager.parseLogin(401, null, "u"))
        assertEquals(LoginResult.Failure(LoginError.BAD_CREDENTIALS, 403), V2GetAuthManager.parseLogin(403, null, "u"))
        assertEquals(LoginResult.Failure(LoginError.SERVER, 500), V2GetAuthManager.parseLogin(500, null, "u"))
        assertEquals(LoginResult.Failure(LoginError.BAD_RESPONSE, 200), V2GetAuthManager.parseLogin(200, "not json", "u"))
        assertEquals(LoginResult.Failure(LoginError.BAD_RESPONSE, 200), V2GetAuthManager.parseLogin(200, "{}", "u"))
        assertEquals(LoginResult.Failure(LoginError.BAD_RESPONSE, 200), V2GetAuthManager.parseLogin(200, null, "u"))
    }

    @Test
    fun theConfigurationLinkIsFoundInTheFile() {
        val file = "ss://x@1.2.3.4:1#a\naether://?protocol=wg-over-masque&scan=balanced#n%20m\nvless://y"
        assertEquals("aether://?protocol=wg-over-masque&scan=balanced#n%20m", V2GetConfigSource.extractAetherUri(file))
        assertNull(V2GetConfigSource.extractAetherUri("ss://x@1.2.3.4:1#a"))
    }

    @Test
    fun onlyAnHttpsServerIsDialled() {
        assertEquals("https://panel.example.com/api/v1/auth/login", V2GetAuthManager.loginEndpoint("https://panel.example.com/"))
        assertEquals("https://panel.example.com/api/v1/auth/login", V2GetAuthManager.loginEndpoint(" https://panel.example.com "))
        assertNull(V2GetAuthManager.loginEndpoint("http://panel.example.com"))
        assertNull(V2GetAuthManager.loginEndpoint(""))
        assertNull(V2GetAuthManager.loginEndpoint("https://"))
        assertTrue(V2GetAuthManager.loginEndpoint("HTTPS://panel.example.com") != null)
    }
}
