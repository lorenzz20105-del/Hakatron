package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Devos is the one who divides. These lock the contract the UI depends on:
 * every file carries its evidence, every doubt becomes a question, and nothing is
 * silently invented.
 */
class DevosDivideTest {

    @Test
    fun `an empty paste asks instead of inventing`() {
        val i = Devos.divide("")
        assertTrue(i.empty)
        assertTrue(i.questions.any { it.severe })
        assertEquals("Devos found no files in that paste.", Devos.verdict(i))
    }

    @Test
    fun `named files are decisions with the paste as evidence`() {
        val i = Devos.divide(
            "=== app.py ===\nimport yaml\nprint(1)\n\n=== requirements.txt ===\nyaml==6.0"
        )
        assertEquals(listOf("app.py", "requirements.txt"), i.files)
        assertEquals(2, i.decisions.size)
        assertTrue(i.decisions.all { it.how == "named" }, i.decisions.toString())
        assertTrue(i.decisions.all { it.confidence > 0.95 }, i.decisions.toString())
        assertTrue(i.decisions.first().why.contains("named"), i.decisions.first().why)
    }

    @Test
    fun `an unnamed block is an inferred decision, and it is admitted`() {
        val i = Devos.divide("def main():\n    print('hi')\n    return 0")
        assertEquals(1, i.count)
        assertEquals("inferred", i.decisions[0].how)
        assertTrue(i.decisions[0].confidence < 0.9, i.decisions.toString())
        assertTrue(i.questions.any { it.about == "names" }, i.questions.toString())
        assertTrue(Devos.verdict(i).contains("inferred"))
    }

    @Test
    fun `two files with the same name is a severe question`() {
        val i = Devos.divide(
            "=== a/main.py ===\nx = 1\n\n=== b/main.py ===\ny = 2"
        )
        val q = i.questions.firstOrNull { it.severe }
        assertTrue(q != null && q.ask.contains("main.py"), i.questions.toString())
    }

    @Test
    fun `two headers in one paste are two files, not one`() {
        val i = Devos.divide("=== all.txt ===\nhello\n=== inner.py ===\nprint(1)")
        assertEquals(listOf("all.txt", "inner.py"), i.files)
        assertTrue(i.decisions.all { it.how == "named" }, i.decisions.toString())
    }

    @Test
    fun `a header-looking divider left inside a body is questioned`() {
        val i = Devos.divide("=== README.md ===\ntext\n=== not a file ===\nmore text")
        // either the splitter cut it (two files) or Devos asked — never silently kept
        assertTrue(i.count == 2 || i.questions.any { it.ask.contains("more than one file") },
            i.files.toString() + " / " + i.questions.toString())
    }

    @Test
    fun `a tree is lifted out and reported`() {
        val i = Devos.divide(
            "Project Tree\napp/\n├── main.py\n└── util.py\n\n=== main.py ===\nimport util"
        )
        assertTrue(i.tree.contains("main.py"), i.tree)
        assertTrue(i.questions.any { it.about == "tree" }, i.questions.toString())
    }

    @Test
    fun `a screenshot reports what it threw away`() {
        val i = Devos.divide(
            "12:38\n84 %\nThought for 4 seconds\n\n=== app.py ===\nprint(1)", ocr = true)
        assertTrue(i.dropped > 0, "dropped=${i.dropped}")
        assertTrue(i.questions.any { it.about == "screenshot" }, i.questions.toString())
        assertTrue(i.fromScreenshot)
        assertEquals(listOf("app.py"), i.files)
    }

    @Test
    fun `confidence drops for each doubt`() {
        val clean = Devos.divide("=== a.py ===\nx = 1\n\n=== b.py ===\ny = 2")
        val ambiguous = Devos.divide("=== a/x.py ===\nx = 1\n\n=== b/x.py ===\ny = 2")
        assertTrue(clean.confidence > ambiguous.confidence,
            "${clean.confidence} vs ${ambiguous.confidence}")
        assertTrue(clean.confidence in 0.0..1.0 && ambiguous.confidence in 0.0..1.0)
    }

    @Test
    fun `the intake is stable and idempotent`() {
        val raw = "=== app.py ===\nimport os\n\n=== Dockerfile ===\nFROM python:3.12"
        val once = Devos.divide(raw)
        val twice = Devos.divide(once.toText())
        assertEquals(once.files, twice.files)
        assertEquals(once.toText(), twice.toText())
    }

    @Test
    fun `dropping a file keeps the rest canonical`() {
        val i = Devos.divide("=== a.py ===\nx = 1\n\n=== b.py ===\ny = 2\n\n=== c.py ===\nz = 3")
        val after = Devos.divide(i.without(1))
        assertEquals(listOf("a.py", "c.py"), after.files)
        assertEquals("=== a.py ===\nx = 1\n\n=== c.py ===\nz = 3", i.without(1))
    }

    @Test
    fun `a huge single file is called out`() {
        val body = (1..420).joinToString("\n") { "value_$it = $it" }
        val i = Devos.divide("=== big.py ===\n$body")
        assertTrue(i.questions.any { it.ask.contains("several files") }, i.questions.toString())
    }

    @Test
    fun `the summary never claims more than was found`() {
        val i = Devos.divide("=== a.py ===\nx = 1")
        assertEquals("1 file · a.py", i.summary())
        val many = Devos.divide(
            "=== a.py ===\n1\n\n=== b.py ===\n2\n\n=== c.py ===\n3\n\n=== d.py ===\n4")
        assertEquals("4 files · a.py, b.py, c.py +1", many.summary())
        assertFalse(many.summary().contains("5"))
    }
}
