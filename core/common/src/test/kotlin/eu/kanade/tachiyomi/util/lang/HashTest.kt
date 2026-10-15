package eu.kanade.tachiyomi.util.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class HashTest {
    @Test
    fun `hex encoding matches the format previously used at call sites`() {
        val bytes = MessageDigest.getInstance("SHA-1").digest("page".toByteArray()) + byteArrayOf(0, -1, 15, -128)

        assertEquals(bytes.joinToString("") { "%02x".format(it) }, Hash.encodeHex(bytes))
        assertEquals("", Hash.encodeHex(ByteArray(0)))
    }
}
