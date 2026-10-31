package com.v2ray.ang.v2get

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class V2GetConfigSourceTest {

    private val a = "aether://?protocol=wg-over-masque&transport=h2&scan=balanced#Server%20A"
    private val b = "aether://?protocol=wg-over-masque#B"
    private val c = "aether://?protocol=wg-over-masque&scan=fast"

    @Test
    fun everyLinkOfTheFileIsFoundOnceInFileOrder() {
        val file = "ss://x@1.2.3.4:1#a\n$a\nvless://y\n$b\n$a\n$c"
        assertEquals(listOf(a, b, c), V2GetConfigSource.extractAetherUris(file))
        assertEquals(a, V2GetConfigSource.extractAetherUri(file))
        assertEquals(emptyList<String>(), V2GetConfigSource.extractAetherUris("ss://x@1.2.3.4:1#a"))
    }

    @Test
    fun linksInsideJsonAreFoundAndItsEscapesUndone() {
        val json = """{"servers":["aether:\/\/?protocol=wg-over-masque\u0026scan=fast#One","$b"]}"""
        assertEquals(
            listOf("aether://?protocol=wg-over-masque&scan=fast#One", b),
            V2GetConfigSource.extractAetherUris(json),
        )
    }

    @Test
    fun theSameLinkIsAlwaysTheSameProfile() {
        assertEquals(V2GetConfigSource.guidOf(a), V2GetConfigSource.guidOf(a))
        assertNotEquals(V2GetConfigSource.guidOf(a), V2GetConfigSource.guidOf(b))
        assertTrue(V2GetConfigSource.isV2GetGuid(V2GetConfigSource.guidOf(a)))
        assertTrue(V2GetConfigSource.isV2GetGuid(V2GetAuthManager.PROFILE_GUID))
        assertTrue(!V2GetConfigSource.isV2GetGuid("some-other-guid"))
        assertTrue(!V2GetConfigSource.isV2GetGuid(null))
    }

    @Test
    fun aServerIsNamedByItsFragmentAndKnownByItsProtocol() {
        val named = V2GetConfigSource.describe(a, 0)
        assertEquals("Server A", named.name)
        assertEquals("WG-OVER-MASQUE / H2", named.protocol)
        assertEquals("B", V2GetConfigSource.describe(b, 1).name)
        assertEquals("WG-OVER-MASQUE", V2GetConfigSource.describe(b, 1).protocol)
        // No fragment: the place in the file names it.
        assertEquals("V2GET 3", V2GetConfigSource.describe(c, 2).name)
    }
}

class V2GetOtherProtocolsTest {

    @Test
    fun linksOfEveryKindAreFoundInFileOrderAndASchemeIsNotReadInsideAnother() {
        val file = "vless://u@h.example:443?security=tls#V\nss://YWJj@1.2.3.4:8388#S\nvmess://eyJ2IjoiMiJ9\n" +
            "trojan://p@t.example:443#T\nhysteria2://p@h.example:443#H\nvless://u@h.example:443?security=tls#V"
        val links = V2GetConfigSource.extractLinks(file)
        assertEquals(
            listOf("vless://", "ss://", "vmess://", "trojan://", "hysteria2://"),
            links.map { it.substringBefore("//") + "//" },
        )
    }

    @Test
    fun linksInsideJsonAreFoundWhateverTheKind() {
        val json = """{"a":["vless:\/\/u@h.example:443?type=ws\u0026security=tls#One","ss:\/\/YWJj@1.2.3.4:8388#Two"]}"""
        assertEquals(
            listOf("vless://u@h.example:443?type=ws&security=tls#One", "ss://YWJj@1.2.3.4:8388#Two"),
            V2GetConfigSource.extractLinks(json),
        )
    }

    @Test
    fun aServerOfAnotherKindIsNamedByItsProfileAndKnownByItsTransport() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "My VLESS"
            network = "ws"
            security = "tls"
        }
        val server = V2GetConfigSource.describe("vless://x", 0, profile)
        assertEquals("My VLESS", server.name)
        assertEquals("VLESS / WS / TLS", server.protocol)
        assertEquals("VLESS", server.family)
        // A plain tcp profile without security says only what it is.
        val plain = ProfileItem.create(EConfigType.SHADOWSOCKS).apply { network = "tcp"; security = "none" }
        assertEquals("SHADOWSOCKS", V2GetConfigSource.describe("ss://x", 1, plain).protocol)
        assertEquals("V2GET 2", V2GetConfigSource.describe("ss://x", 1, plain).name)
    }

    @Test
    fun anAetherLinkIsDescribedAsBefore() {
        val aether = "aether://?protocol=wg-over-masque&transport=h2#Server%20A"
        val server = V2GetConfigSource.describe(aether, 0, null)
        assertEquals("AETHER", server.family)
        assertEquals("Server A", server.name)
    }
}

class V2GetCountryTest {

    @Test
    fun anAddressServiceAnswerIsReadAsACodeWhetherItIsACodeOrAName() {
        assertEquals("DE", V2GetCountry.normalize("de"))
        assertEquals("DE", V2GetCountry.normalize(" DE "))
        assertEquals("DE", V2GetCountry.normalize("Germany"))
        assertEquals("US", V2GetCountry.normalize("United States"))
        assertEquals(null, V2GetCountry.normalize("ZZ"))
        assertEquals(null, V2GetCountry.normalize(""))
        assertEquals(null, V2GetCountry.normalize(null))
    }

    @Test
    fun theFlagIsMadeFromTheCode() {
        assertEquals("\uD83C\uDDE9\uD83C\uDDEA", V2GetCountry.flag("de"))
        assertEquals("", V2GetCountry.flag("D"))
    }

    @Test
    fun aServerIsTitledByItsCountryOnceKnownAndByItsConfigurationNameBefore() {
        assertEquals("Config name", V2GetCountry.title("", "Config name"))
        assertTrue(V2GetCountry.title("DE", "Config name").startsWith(V2GetCountry.flag("DE")))
    }
}

class V2GetSettingsTest {

    @Test
    fun aBlankOrHttpsRemoteDnsIsEncryptedAndAPlainOneIsNot() {
        assertTrue(V2GetSettings.isEncryptedDns(null))
        assertTrue(V2GetSettings.isEncryptedDns(""))
        assertTrue(V2GetSettings.isEncryptedDns("https://8.8.8.8/dns-query"))
        assertTrue(V2GetSettings.isEncryptedDns("https://1.1.1.1/dns-query, https://8.8.8.8/dns-query"))
        assertTrue(!V2GetSettings.isEncryptedDns("8.8.8.8"))
        assertTrue(!V2GetSettings.isEncryptedDns("https://1.1.1.1/dns-query,8.8.8.8"))
    }
}

class V2GetGeoFilesTest {

    @Test
    fun everyFileTheCoreReadsIsAmongTheOnesFetched() {
        assertEquals(
            listOf("geosite.dat", "geoip.dat", "geoip-only-cn-private.dat"),
            V2GetGeoFiles.SOURCES.map { it.name },
        )
    }

    @Test
    fun onlyAbsentOrEmptyFilesAreMissing() {
        val dir = java.nio.file.Files.createTempDirectory("geo").toFile()
        try {
            assertEquals(V2GetGeoFiles.SOURCES.map { it.name }, V2GetGeoFiles.missing(dir))
            java.io.File(dir, "geosite.dat").writeBytes(ByteArray(10))
            java.io.File(dir, "geoip.dat").writeBytes(ByteArray(0))
            assertEquals(listOf("geoip.dat", "geoip-only-cn-private.dat"), V2GetGeoFiles.missing(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
