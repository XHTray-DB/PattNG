package com.v2ray.ang.v2get

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2GetReportTest {

    @Test
    fun linksTokensAndSecretsAreTakenOut() {
        val log = listOf(
            "Authorization: Bearer abcdef0123456789abcdef0123456789",
            "I/v2rayNG: loaded vless://uuid-1234@1.2.3.4:443?security=tls#name",
            "password=hunter2 user=sara",
            "token: \"ZXhhbXBsZV9sb25nX3Rva2VuX3ZhbHVlXzEyMzQ1Njc4OTA\"",
            "W/v2rayNG: [aether] [-] tunnel ended",
        ).joinToString("\n")
        val clean = V2GetReport.scrub(log)
        assertFalse(clean.contains("abcdef0123456789"))
        assertFalse(clean.contains("vless://"))
        assertFalse(clean.contains("hunter2"))
        assertFalse(clean.contains("ZXhhbXBsZV9s"))
        assertTrue(clean.contains("tunnel ended"))
        assertTrue(clean.contains("user=sara"))
    }
}
