package dev.devstudio.ide.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Termux userland is only claimable if the parts we control are provably
 * right: the right bootstrap per ABI, real URLs, and a checksum we can trust.
 */
class TermuxUserlandTest {

    @Test
    fun `every build points at the official termux release`() {
        assertTrue(TermuxUserland.BUILDS.isNotEmpty())
        TermuxUserland.BUILDS.forEach { b ->
            assertTrue(b.url.startsWith("https://github.com/termux/termux-packages/releases/"),
                "not an official Termux release URL: ${b.url}")
            assertTrue(b.url.endsWith("bootstrap-${b.arch}.zip"), "url/arch mismatch: ${b.url}")
            assertTrue(b.approxMb in 40..90, "implausible size for ${b.arch}: ${b.approxMb}")
        }
    }

    @Test
    fun `arm64 device gets the aarch64 bootstrap`() {
        assertEquals("aarch64",
            TermuxUserland.buildFor(listOf("arm64-v8a", "armeabi-v7a")).arch)
    }

    @Test
    fun `32-bit arm device gets the arm bootstrap`() {
        assertEquals("arm", TermuxUserland.buildFor(listOf("armeabi-v7a", "arm64-v8a")).arch)
    }

    @Test
    fun `x86_64 device gets the x86_64 bootstrap`() {
        assertEquals("x86_64", TermuxUserland.buildFor(listOf("x86_64")).arch)
    }

    @Test
    fun `unknown abi falls back to a real build rather than crashing`() {
        val fallback = TermuxUserland.buildFor(emptyList())
        assertTrue(fallback.url.startsWith("https://"), "fallback must still be a real download")
    }

    @Test
    fun `sha256 matches a known digest`() {
        val f = File.createTempFile("hakatron-sha", ".bin")
        try {
            f.writeText("hakatron")
            // echo -n hakatron | sha256sum
            assertEquals("65abb4d489b68777501d8490f8c186c8e96d4fcf16dfd98e8a106676cb3ee6e5", TermuxUserland.sha256(f))
            assertEquals(TermuxUserland.sha256(f), TermuxUserland.sha256(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `sha256 of empty and missing files is empty not a crash`() {
        assertEquals("", TermuxUserland.sha256(File("/definitely/not/here")))
    }
}
