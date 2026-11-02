package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `KnowledgeBase.signature` — the key Devos remembers a fix by.
 *
 * The old key was `lastNonBlankLine.take(90)`, so the same failure on the same
 * phone produced a different key whenever a temp path, a PID or a line number
 * changed, and the memorised fix was almost never found again. These are real
 * outputs from real toolchains; each pair below *is* the same failure.
 */
class SignatureTest {

    @Test
    fun `gcc failure is stable across line numbers`() {
        val a = """
            main.c:12:5: error: 'foo' undeclared (first use in this function)
               12 |     foo();
                  |     ^~~
        """.trimIndent()
        val b = """
            main.c:87:9: error: 'foo' undeclared (first use in this function)
               87 |         foo();
                  |         ^~~
        """.trimIndent()
        assertEquals(KnowledgeBase.signature(a), KnowledgeBase.signature(b))
        assertTrue(KnowledgeBase.signature(a).contains("undeclared"))
    }

    @Test
    fun `the diagnostic line wins over the summary line`() {
        val out = """
            src/app.py:4:11: error: invalid syntax
            make: *** [Makefile:2: build] Error 1
        """.trimIndent()
        val sig = KnowledgeBase.signature(out)
        assertTrue(sig.contains("invalid syntax"), "was: $sig")
    }

    @Test
    fun `a pure count line is not the signature`() {
        val out = """
            error: expected ';' before '}' token
            2 errors generated.
        """.trimIndent()
        val sig = KnowledgeBase.signature(out)
        assertTrue(sig.contains("expected"), "was: $sig")
    }

    @Test
    fun `absolute paths are reduced to basenames`() {
        val sig = KnowledgeBase.signature(
            "Traceback: File \"/tmp/pytest-1183/test_x.py\", line 9, in test_y"
        )
        assertTrue(!sig.contains("/tmp/"), "was: $sig")
        assertTrue(sig.contains("test_x.py"), "was: $sig")
    }

    @Test
    fun `pointer addresses and large numbers are erased`() {
        val a = KnowledgeBase.signature("Segmentation fault at 0x7ffd1a2b3c40 after 4194312 tries")
        val b = KnowledgeBase.signature("Segmentation fault at 0x55ef00a1b2c0 after 8388608 tries")
        assertEquals(a, b)
    }

    @Test
    fun `python missing module is recognised and stable`() {
        val out = """
            Traceback (most recent call last):
              File "/app/main.py", line 3, in <module>
            ModuleNotFoundError: No module named 'requests'
        """.trimIndent()
        val sig = KnowledgeBase.signature(out)
        assertTrue(sig.contains("modulenotfounderror"), "was: $sig")
        assertEquals(sig, KnowledgeBase.signature(out.replace("main.py", "server.py")))
    }

    @Test
    fun `npm failure is recognised`() {
        val out = """
            npm ERR! code ENOENT
            npm ERR! syscall open
            npm ERR! path /home/user/projects/app/package.json
            npm ERR! errno -2
        """.trimIndent()
        assertTrue(KnowledgeBase.signature(out).startsWith("npm err! code enoent"),
            "was: ${KnowledgeBase.signature(out)}")
    }

    @Test
    fun `different failures do not collide`() {
        val a = KnowledgeBase.signature("error: 'x' was not declared in this scope")
        val b = KnowledgeBase.signature("error: expected ';' before '}' token")
        assertNotEquals(a, b)
    }

    @Test
    fun `signatures are short and never empty for real output`() {
        val out = """
            Building...
            [100%] Built target app
            [exit 0]
        """.trimIndent()
        val sig = KnowledgeBase.signature(out)
        assertTrue(sig.isNotEmpty())
        assertTrue(sig.length <= 120, "was ${sig.length}: $sig")
    }
}
