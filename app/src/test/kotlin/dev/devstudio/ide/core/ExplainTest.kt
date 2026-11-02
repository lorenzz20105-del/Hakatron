package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KnowledgeBase.explain` — one sentence of *why*, next to the raw log line.
 *
 * These are the real error strings Devos meets in the guest. The rule is the same
 * as everywhere else: a sentence that says something true, or nothing at all.
 */
class ExplainTest {

    @Test
    fun `a missing command is a package problem, not a code problem`() {
        val why = KnowledgeBase.explain("sh: npm: not found")
        assertNotNull(why)
        assertTrue(why.contains("package"), why)
    }

    @Test
    fun `a missing python module names the two real causes`() {
        val why = KnowledgeBase.explain("ModuleNotFoundError: No module named 'requests'")!!
        assertTrue(why.contains("not installed") && why.contains("pasted"), why)
    }

    @Test
    fun `pep 668 is explained instead of worked around silently`() {
        val why = KnowledgeBase.explain("error: externally-managed-environment")!!
        assertTrue(why.contains("PEP 668"), why)
    }

    @Test
    fun `a link error is distinguished from a compile error`() {
        val why = KnowledgeBase.explain("undefined reference to `sqrt'")!!
        assertTrue(why.contains("link") && why.contains("compiled"), why)
    }

    @Test
    fun `a syntax error points at the file, not the build`() {
        val why = KnowledgeBase.explain("SyntaxError: invalid syntax (app.py, line 4)")!!
        assertTrue(why.contains("source"), why)
    }

    @Test
    fun `memory and disk limits are named as limits`() {
        assertTrue(KnowledgeBase.explain("Killed")!!.contains("memory"))
        assertTrue(KnowledgeBase.explain("No space left on device")!!.contains("full"))
    }

    @Test
    fun `no network says no network`() {
        assertTrue(KnowledgeBase.explain("Could not resolve host: proxy.golang.org")!!
            .contains("network"))
    }

    @Test
    fun `a syscall block blames the launcher, not the command`() {
        val why = KnowledgeBase.explain("proot: exit status 159 (SIGSYS)")!!
        assertTrue(why.contains("launcher"), why)
    }

    @Test
    fun `an unknown failure gets no explanation at all`() {
        assertNull(KnowledgeBase.explain("everything went fine actually"))
        assertNull(KnowledgeBase.explain(""))
    }

    @Test
    fun `every explanation is one sentence a glance can take`() {
        listOf(
            "No module named 'yaml'", "sh: cmake: not found", "undefined reference to `x'",
            "Killed", "No space left on device", "SyntaxError: invalid syntax"
        ).forEach { e ->
            val why = KnowledgeBase.explain(e)!!
            assertTrue(why.length in 40..220, "$e → ${why.length} chars")
            assertEquals(1, why.count { it == '.' }, "$e → $why")
        }
    }
}
