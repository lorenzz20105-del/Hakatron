package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `B64` — the last Android dependency in the build loop.
 *
 * Each vector is checked against the JDK's own encoder, so the guest receives
 * exactly the bytes `base64 -d` expects, including the padded tails a
 * hand-written encoder usually gets wrong.
 */
class B64Test {

    private fun jdk(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `the canonical vectors`() {
        assertEquals("", B64.encode(""))
        assertEquals("Zg==", B64.encode("f"))
        assertEquals("Zm8=", B64.encode("fo"))
        assertEquals("Zm9v", B64.encode("foo"))
        assertEquals("Zm9vYg==", B64.encode("foob"))
        assertEquals("Zm9vYmE=", B64.encode("fooba"))
        assertEquals("Zm9vYmFy", B64.encode("foobar"))
    }

    @Test
    fun `every length from 0 to 64 matches the jdk`() {
        for (n in 0..64) {
            val bytes = ByteArray(n) { (it * 7 + 3).toByte() }
            assertEquals(jdk(bytes), B64.encode(bytes), "length $n")
        }
    }

    @Test
    fun `all byte values survive`() {
        assertEquals(jdk(ByteArray(256) { it.toByte() }), B64.encode(ByteArray(256) { it.toByte() }))
    }

    @Test
    fun `text is utf8`() {
        val text = "héllo — ünïcode ✓"
        assertEquals(jdk(text.toByteArray(Charsets.UTF_8)), B64.encode(text))
    }

    @Test
    fun `a project payload keeps its separators`() {
        val payload = "main.py\u0001print(1)\u0000Cargo.toml\u0001[package]"
        assertEquals(jdk(payload.toByteArray()), B64.encode(payload))
    }

    @Test
    fun `no line breaks are ever inserted`() {
        val long = "x".repeat(500)
        assertEquals(false, B64.encode(long).contains("\n"))
        assertEquals(jdk(long.toByteArray()), B64.encode(long))
    }
}
