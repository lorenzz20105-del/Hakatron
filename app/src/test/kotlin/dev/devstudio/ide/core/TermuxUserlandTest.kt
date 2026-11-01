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
            assertTrue(b.approxMb in 20..60, "implausible size for ${b.arch}: ${b.approxMb}")
        }
    }

    @Test
    fun `the + in the release tag is percent-encoded`() {
        // 0.16.0 shipped an unencoded/expired tag and every install ended in HTTP 404
        TermuxUserland.BUILDS.forEach { b ->
            assertTrue(b.url.contains("bootstrap-2026.11.01-r1%2Bapt.android-7"),
                "release tag missing or not encoded: ${b.url}")
            assertTrue(!b.url.contains("+"), "raw '+' in URL path breaks the download: ${b.url}")
            assertTrue(!b.url.contains("2024.10.24"), "that release no longer exists: ${b.url}")
        }
    }

    @Test
    fun `every pinned build carries a real sha256`() {
        TermuxUserland.BUILDS.forEach { b ->
            assertEquals(64, b.sha256.length, "no checksum for ${b.arch}")
            assertTrue(b.sha256.all { it in "0123456789abcdef" }, "not hex: ${b.sha256}")
        }
    }

    @Test
    fun `x86 device gets the i686 bootstrap`() {
        assertEquals("i686", TermuxUserland.buildFor(listOf("x86")).arch)
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

/**
 * The bootstrap ships **no** symlinks — only a SYMLINKS.txt that names 1,213 of
 * them. Getting this format wrong produces a userland whose libraries do not
 * resolve, so the parser is pinned here against real lines from the archive.
 */
class TermuxSymlinksTest {

    private val real = """
        libxxhash.so.0.8.4←./lib/libxxhash.so.0
        libnettle.so.8.11←./lib/libnettle.so
        dash←./bin/sh
        libssl.so.3←./lib/libssl.so
    """.trimIndent()

    @Test
    fun `parses every real line`() {
        val links = TermuxUserland.parseSymlinks(real)
        assertEquals(4, links.size)
    }

    @Test
    fun `dash provides bin sh`() {
        val links = TermuxUserland.parseSymlinks(real).toMap()
        assertEquals("dash", links["./bin/sh"])
        assertEquals("libxxhash.so.0.8.4", links["./lib/libxxhash.so.0"])
    }

    @Test
    fun `junk lines are dropped instead of throwing`() {
        val links = TermuxUserland.parseSymlinks("garbage\n\n←./a\nb←\nok←./c\n")
        assertEquals(1, links.size)
        assertEquals("./c", links[0].first)
    }

    @Test
    fun `empty file yields nothing`() {
        assertEquals(0, TermuxUserland.parseSymlinks("").size)
    }
}

/** Absolute Termux paths inside the bootstrap have to be re-pointed at our prefix. */
class TermuxRemapTest {

    private val prefix = "/data/user/0/dev.devstudio.ide/files/termux/usr"

    @Test
    fun `absolute termux link targets are rewritten`() {
        // one of the 20 real dangling links: bin/zipinfo -> /data/data/.../bin/unzip
        assertEquals("$prefix/bin/unzip",
            TermuxUserland.remapTarget("/data/data/com.termux/files/usr/bin/unzip", prefix))
        assertEquals("$prefix/share/termux-keyring/termux-autobuilds.gpg",
            TermuxUserland.remapTarget(
                "/data/data/com.termux/files/usr/share/termux-keyring/termux-autobuilds.gpg", prefix))
    }

    @Test
    fun `relative targets are left alone`() {
        // "dash" and "libxxhash.so.0.8.4" stay correct once the tree is moved
        assertEquals(null, TermuxUserland.remapTarget("dash", prefix))
        assertEquals(null, TermuxUserland.remapTarget("libxxhash.so.0.8.4", prefix))
    }

    @Test
    fun `an unrelated absolute path is not touched`() {
        assertEquals(null, TermuxUserland.remapTarget("/system/bin/sh", prefix))
    }
}
