package com.v2ray.ang.v2get

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
