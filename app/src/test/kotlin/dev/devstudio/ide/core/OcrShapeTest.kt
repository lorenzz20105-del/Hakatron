package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The block-level OCR rules: gutters, indentation, stitching, look-alikes, mistrust.
 */
class OcrShapeTest {

    @Test
    fun `typographic look-alikes become ascii and nothing else moves`() {
        assertEquals("\"x\" - y", OcrShape.normalise("\u201Cx\u201D \u2014 y"))
        assertEquals("ab 12 cd", OcrShape.normalise("\uFF41\uFF42 \uFF11\uFF12 cd"))
        assertEquals("a - b", OcrShape.normalise("a \u2212 b"))
        assertEquals("a b", OcrShape.normalise("a\u00A0b"))
        // a legit non-ASCII path is left alone: only unambiguous substitutions happen
        assertEquals("notes/résumé.txt", OcrShape.normalise("notes/résumé.txt"))
    }

    @Test
    fun `an ascending line-number column is removed as a block`() {
        val lines = listOf("11 | fun main() {", "12 |     println(1)", "13 | }")
        val (out, stripped) = OcrShape.stripGutter(lines)
        assertTrue(stripped)
        assertEquals(listOf("fun main() {", "    println(1)", "}"), out)
    }

    @Test
    fun `a markdown table is not a gutter`() {
        val lines = listOf("1 | one", "2 | two")
        assertFalse(OcrShape.stripGutter(lines).second, "two rows are not enough proof")
        val table = listOf("5 | five", "3 | three", "9 | nine", "1 | one")
        assertFalse(OcrShape.stripGutter(table).second, "a descending run is prose, not a gutter")
    }

    @Test
    fun `flat code gets its indentation back from braces`() {
        val flat = listOf("fun main() {", "val x = 1", "if (x > 0) {", "println(x)", "}", "}")
        val (out, rebuilt) = OcrShape.rebuildIndent(flat)
        assertTrue(rebuilt)
        assertEquals("fun main() {", out[0])
        assertEquals("    val x = 1", out[1])
        assertEquals("    if (x > 0) {", out[2])
        assertEquals("        println(x)", out[3])
        assertEquals("    }", out[4])
        assertEquals("}", out[5])
    }

    @Test
    fun `indentation is never rebuilt when the block was already indented`() {
        val indented = listOf("fun main() {", "    val x = 1", "}")
        assertFalse(OcrShape.rebuildIndent(indented).second)
    }

    @Test
    fun `unbalanced braces mean the capture is not safe to re-indent`() {
        val broken = listOf("fun main() {", "val x = 1", "println(x)")
        assertFalse(OcrShape.rebuildIndent(broken).second)
    }

    @Test
    fun `two overlapping screenshots join once`() {
        val a = "line one\nline two\nline three"
        val b = "line three\nline four"
        assertEquals("line one\nline two\nline three\nline four", OcrShape.stitch(a, b))
    }

    @Test
    fun `a capture with no overlap is not silently dropped`() {
        val out = OcrShape.stitch("alpha\nbeta", "gamma\ndelta\nepsilon")
        assertTrue(out.contains("beta"), out)
        assertTrue(out.contains("epsilon"), out)
    }

    @Test
    fun `three screenshots fold into one document`() {
        val out = OcrShape.stitchAll(listOf("a\nb\nc", "c\nd", "d\ne"))
        assertEquals("a\nb\nc\nd\ne", out)
    }

    @Test
    fun `a whole block is repaired and reports what it did`() {
        val shot = "12 | fun main() {\n13 | val x = 1\n14 | }"
        val r = OcrShape.block(shot)
        assertTrue(r.gutter, "the gutter should be reported")
        assertTrue(r.notes.isNotEmpty())
        assertFalse(r.text.contains("12 |"), r.text)
        assertTrue(OcrShape.summary(r).contains("line numbers"))
    }

    @Test
    fun `lines that are probably misread are counted, not hidden`() {
        assertTrue(OcrShape.uncertainLine("val s = \"unterminated"))
        assertTrue(OcrShape.uncertainLine("fun f( x"))
        assertTrue(OcrShape.uncertainLine("x | y z"))            // fragments
        assertTrue(OcrShape.uncertainLine("val qwrtpsz = 1"))    // consonant soup
        assertFalse(OcrShape.uncertainLine("val ok = \"closed\""))
        assertFalse(OcrShape.uncertainLine("Complete Package (One Drop)"))
    }

    @Test
    fun `the report says how many lines to double-check`() {
        val r = OcrShape.block("fun f( x\nval s = \"open\nplain word line")
        assertTrue(r.uncertain >= 2, "uncertain=${r.uncertain}")
        assertTrue(OcrShape.summary(r).contains("look misread"))
    }

    @Test
    fun `prose is not welded by the word-split rule`() {
        // the old rule joined any short word, so "make app" became "makeapp"
        assertEquals("make app", OcrText.repair("make app"))
        assertEquals("test run", OcrText.repair("test run"))
        // but a real split identifier is still repaired
        assertEquals("Automation/", OcrText.repair("Automat ion/"))
    }
}
