package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `OcrText.clean` / `tidy` — the document-level pass.
 *
 * The regression these lock down: `repair` is written for one line, and it was
 * being called on the whole document, so only the first line ever got its
 * connectors fixed.
 */
class OcrCleanTest {

    @Test
    fun `repair runs on every line, not just the first`() {
        val doc = """
            0 src/
            1 app.py
            0 lib/
        """.trimIndent()
        val lines = OcrText.clean(doc).lines()
        assertEquals("└── src/", lines[0])
        assertEquals("├── app.py", lines[1])
        assertEquals("└── lib/", lines[2])
    }

    @Test
    fun `specks where a glyph failed are dropped`() {
        val cleaned = OcrText.clean("core/\n|\n.\n·\nmain.kt")
        assertEquals(listOf("core/", "main.kt"), cleaned.lines())
    }

    @Test
    fun `a stray box fragment at the end of a tree line is dropped`() {
        assertEquals("├── app.py", OcrText.clean("├── app.py   |").lines().first())
    }

    @Test
    fun `a pipe at the end of a real code line is kept`() {
        val code = "val x = a or b |"
        assertEquals(code, OcrText.clean(code).lines().first())
    }

    @Test
    fun `a long line double-read at a scroll seam is collapsed once`() {
        val line = "fun computeTheThing(input: String): Int = input.length"
        val cleaned = OcrText.clean("$line\n$line\n$line")
        assertEquals(1, cleaned.lines().count { it.isNotBlank() })
    }

    @Test
    fun `short repeated lines stay — braces are code`() {
        val cleaned = OcrText.clean("}\n}")
        assertEquals(2, cleaned.lines().size)
    }

    @Test
    fun `blank runs collapse to one`() {
        val cleaned = OcrText.clean("a = 1\n\n\n\n\nb = 2")
        assertEquals("a = 1\n\nb = 2", cleaned)
    }

    @Test
    fun `a screenshot of a tree keeps every entry`() {
        val doc = """
            Project Tree
            IDEBotAutomat ion/
            0 src/
            1 app.py
            1 build.gradle.kts
            0 Copy
        """.trimIndent()
        val split = Parts.split(doc, ocr = true)
        assertTrue(split.tree.contains("src/"), "tree was: ${split.tree}")
        assertTrue(split.tree.contains("app.py"), "tree was: ${split.tree}")
        assertFalse(split.tree.contains("Copy"), "chat furniture leaked into the tree")
        assertFalse(split.tree.contains("Project Tree"), "heading leaked into the tree")
    }

    // ------------------------------------------------------- phone furniture

    @Test
    fun `a status bar is not part of the project`() {
        val shot = """
            12:38
            Smart 5G
            84 %
            Project Tree
            app/
            0 src/
            1 main.py
        """.trimIndent()
        val split = Parts.split(shot, ocr = true)
        val everything = split.tree + "\n" + split.toText()
        assertTrue(!everything.contains("12:38"), everything)
        assertTrue(!everything.contains("84"), everything)
        assertTrue(!everything.contains("Smart"), everything)
        assertTrue(split.tree.contains("src/"), split.tree)
    }

    @Test
    fun `a real percentage in code survives`() {
        val cleaned = OcrText.clean("val pct = 84.5")
        assertTrue(cleaned.contains("84.5"), cleaned)
    }
}
