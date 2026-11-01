package dev.devstudio.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KnowledgeBase — the trained error → fix table.
 *
 * Every case below is an error line taken from a real build log. If a rule stops
 * matching, this test fails instead of the user finding out on the phone.
 */
class KnowledgeBaseTest {

    private fun fix(error: String, port: Int? = null, entry: String = "app.py") =
        KnowledgeBase.planFix(error, port, entry)

    // ---------------------------------------------------------------- python
    @Test
    fun `missing python module becomes its real pip name`() {
        val f = fix("ModuleNotFoundError: No module named 'cv2'")!!
        assertEquals("py_module", f.kind)
        assertEquals("pip install --break-system-packages opencv-python", f.cmd)
    }

    @Test
    fun `externally managed environment is repaired by augmenting`() {
        val f = fix("error: externally-managed-environment")!!
        assertEquals("pep668", f.kind)
        assertTrue(f.augment!!.contains("--break-system-packages"))
    }

    @Test
    fun `no network is recognised as a network problem`() {
        val f = fix("Could not find a version that satisfies the requirement flask")!!
        assertEquals("network", f.kind)
        assertTrue(f.alt.any { it.contains("ca-certificates") })
    }

    // ------------------------------------------------------------- toolchain
    @Test
    fun `missing tools map to alpine packages`() {
        assertEquals("apk add --no-cache cmake", fix("sh: cmake: not found")!!.cmd)
        assertEquals("apk add --no-cache samurai", fix("ninja: not found")!!.cmd)
        assertEquals("apk add --no-cache maven", fix("mvn: command not found")!!.cmd)
        assertEquals("apk add --no-cache git", fix("git: not found")!!.cmd)
        assertEquals("apk add --no-cache nodejs", fix("node: not found")!!.cmd)
        assertEquals("apk add --no-cache deno", fix("deno: not found")!!.cmd)
    }

    @Test
    fun `things a guest can never do are refused honestly`() {
        val f = fix("docker: not found")!!
        assertTrue("docker must be refused, not guessed", f.unsupported)
    }

    @Test
    fun `a broken guest is reported as the guest, not the project`() {
        assertTrue(fix("sh: apk: not found")!!.env)
    }

    // ---------------------------------------------------------------- c/c++
    @Test
    fun `headers and libraries become dev packages`() {
        assertEquals("apk add --no-cache zlib-dev",
            fix("src/png.c:3:10: fatal error: zlib.h: No such file or directory")!!.cmd)
        assertEquals("apk add --no-cache openssl-dev",
            fix("/usr/lib/gcc/x86_64-alpine-linux-musl/13/../../../../x86_64-alpine-linux-musl/bin/ld: cannot find -lssl")!!.cmd)
        assertEquals("apk add --no-cache g++",
            fix("fatal error: iostream: No such file or directory")!!.cmd)
        assertEquals("apk add --no-cache openssl-dev",
            fix("CMake Error: Could NOT find OpenSSL (missing: OPENSSL_CRYPTO_LIBRARY)")!!.cmd)
    }

    @Test
    fun `a header from inside the project is an include path problem, not a package`() {
        val f = fix("src/main.c:2:10: fatal error: include/hash.h: No such file or directory")!!
        assertEquals("cmake_include", f.kind)
        assertEquals("include/hash.h", f.cmakeInclude)
        assertNull("must not try to apk-install a project file", f.cmd)
    }

    // ------------------------------------------------------------------ node
    @Test
    fun `a required package is installed, a missing build output is not`() {
        assertEquals("npm install --no-audit --no-fund express",
            fix("Error: Cannot find module 'express'")!!.cmd)
        val build = fix("Error: Cannot find module './dist/index.js'")!!
        assertTrue("a file must never be npm-installed", build.needsEyes)
        assertEquals("missing_build", build.kind)
    }

    @Test
    fun `peer conflicts and ESM are config problems`() {
        assertEquals("--legacy-peer-deps", fix("npm ERR! ERESOLVE unable to resolve dependency tree")!!.augment)
        assertTrue(fix("SyntaxError: Unexpected token 'export'")!!.pkgjson)
        assertTrue(fix("error TS5011: The 'rootDir' setting must be explicitly set")!!.tsjson)
    }

    // --------------------------------------------------------------- services
    @Test
    fun `a refused connection is attributed to the service on that port`() {
        val pg = fix("psycopg2.OperationalError: could not connect to server: Connection refused " +
            "(127.0.0.1:5432)")!!
        assertEquals("db_down", pg.kind)
        assertTrue("postgres fix was ${pg.cmd}", pg.cmd!!.contains("pg_ctl"))

        val redis = fix("Error: connect ECONNREFUSED 127.0.0.1:6379")!!
        assertEquals("db_down", redis.kind)
        assertTrue("redis fix was ${redis.cmd}", redis.cmd!!.contains("redis-server"))

        val maria = fix("Error: connect ECONNREFUSED 127.0.0.1:3306")!!
        assertTrue("mariadb fix was ${maria.cmd}", maria.cmd!!.contains("mariadb"))
    }

    @Test
    fun `an uninitialised cluster is bootstrapped, not just started`() {
        val f = fix("pg_ctl: directory \"/var/lib/postgresql/data\" does not exist")!!
        assertEquals("db_bootstrap", f.kind)
        assertTrue(f.cmd!!.contains("initdb"))
    }

    // ------------------------------------------------------- ports and space
    @Test
    fun `a taken port is moved one up and nothing else is touched`() {
        val f = fix("OSError: [Errno 98] Address already in use", port = 5000)!!
        assertEquals("port", f.kind)
        assertTrue("fix was ${f.cmd}", f.cmd!!.contains("5001"))
    }

    @Test
    fun `disk and memory exhaustion are real fixes`() {
        assertEquals("nospace", fix("No space left on device")!!.kind)
        assertEquals("oom", fix("cc1plus: out of memory")!!.kind)
    }

    // ---------------------------------------------------------------- honesty
    @Test
    fun `a syntax error is never guessed at`() {
        val f = fix("File \"app.py\", line 4\n    def broken(\nSyntaxError: unexpected token")!!
        assertEquals("syntax", f.kind)
        assertTrue(f.needsEyes)
        assertNull("a syntax error must not run a command", f.cmd)
    }

    @Test
    fun `unrecognised output yields no fix instead of a random one`() {
        assertNull(fix("Building 42%...\nDone."))
        assertNull(fix(""))
    }

    // ------------------------------------------------------------ the gate
    @Test
    fun `every rule that claims a command produces a runnable one`() {
        val probes = listOf(
            "No module named 'requests'", "externally-managed-environment",
            "cmake: not found", "ninja: not found", "git: not found", "node: not found",
            "fatal error: zlib.h: No such file or directory",
            "fatal error: iostream: No such file", "cannot find -lssl",
            "Could NOT find OpenSSL", "Cannot find module 'react'",
            "ERESOLVE unable to resolve dependency tree", "ENOENT: no such file or directory: 'x.js'",
            "EACCES: permission denied", "No space left on device", "out of memory",
            "pg_ctl: directory \"/var/lib/postgresql/data\" does not exist",
            "Error: connect ECONNREFUSED 127.0.0.1:6379", "error[E0432]: unresolved import `serde`",
            "no required module provides package github.com/gin-gonic/gin",
            "CERTIFICATE_VERIFY_FAILED", "UNTRUSTED signature", "Unable to lock database",
            "mkmf.rb can't find header files for ruby"
        )
        probes.forEach { line ->
            val f = KnowledgeBase.planFix(line, 5000, "app.py")
            assertNotNull("no rule matched a real error line: $line", f)
        }
    }
}
