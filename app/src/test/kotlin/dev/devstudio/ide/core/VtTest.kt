package dev.devstudio.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VtScreen — the terminal emulator.
 *
 * Every sequence below is something a real program sends: a shell prompt, `ls`
 * colours, `top` painting the screen, `vi` switching to the alternate screen.
 * If the emulator gets these wrong the terminal shows garbage, so they are pinned
 * here rather than discovered on the phone.
 */
class VtTest {

    private fun screen(cols: Int = 20, rows: Int = 5) = VtScreen(cols, rows, scrollbackMax = 50)

    /** runs of one SCREEN row (row 0 = top of the visible screen) */
    private fun runs(v: VtScreen, row: Int): List<VtScreen.Run> {
        val lines = v.lines()
        val start = lines.size - v.rows          // lines() = scrollback + screen
        return lines[start + row]
    }

    private fun rowText(v: VtScreen, row: Int): String = runs(v, row).joinToString("") { it.text }

    @Test
    fun `prints text and wraps at the right edge`() {
        val v = screen(cols = 10)
        v.parse("abcdefghijkl")
        assertEquals("abcdefghij", rowText(v, 0).trimEnd())
        assertEquals("kl", rowText(v, 1).trimEnd())
    }

    @Test
    fun `carriage return and line feed behave differently`() {
        val v = screen()
        v.parse("abc\r\ndef")
        assertEquals("abc", rowText(v, 0).trimEnd())
        assertEquals("def", rowText(v, 1).trimEnd())
        v.parse("\rx")
        assertEquals("xef", rowText(v, 1).trimEnd())
    }

    @Test
    fun `backspace moves the cursor without erasing`() {
        val v = screen()
        v.parse("abc\b\bX")
        assertEquals("aXc", rowText(v, 0).trimEnd())
    }

    @Test
    fun `ansi colours are attached to the cells they cover`() {
        val v = screen()
        v.parse("\u001b[31mred\u001b[0m plain")
        val cells = runs(v, 0).filter { it.text.isNotBlank() }
        assertEquals("red", cells.first().text)
        assertEquals(1, cells.first().attr.fg)             // palette index 1 = red
        assertTrue("the rest must be default", cells.any { it.attr.fg == -1 })
    }

    @Test
    fun `bold, underline and reverse are recorded`() {
        val v = screen()
        v.parse("\u001b[1;4;7mX")
        val cell = runs(v, 0).firstOrNull { it.text.contains("X") }!!
        assertTrue(cell.attr.bold)
        assertTrue(cell.attr.underline)
        assertTrue(cell.attr.reverse)
    }

    @Test
    fun `256-colour and truecolour map to something renderable`() {
        val v = screen()
        v.parse("\u001b[38;5;196mA\u001b[38;2;18;52;86mB")
        val cells = runs(v, 0).filter { it.text.isNotBlank() }
        assertEquals(196, cells[0].attr.fg)
        assertEquals(0x1000000 or (18 shl 16) or (52 shl 8) or 86, cells[1].attr.fg)
    }

    @Test
    fun `cursor addressing lands where the program asked`() {
        val v = screen(cols = 20, rows = 5)
        v.parse("hello\u001b[1;1H")                       // CUP row 1, col 1
        v.parse("bye")
        assertEquals("byelo", rowText(v, 0).trimEnd())
        v.parse("\u001b[3;5HZ")                           // row 3, col 5
        assertEquals("    Z", rowText(v, 2).trimEnd())
    }

    @Test
    fun `erase line and erase display clear exactly what they should`() {
        val v = screen()
        v.parse("abcdef\u001b[1;3H\u001b[K")             // EL 0 from column 3
        assertEquals("ab", rowText(v, 0).trimEnd())
        v.parse("\u001b[2;1Hsecond\u001b[1;1H\u001b[J")   // ED 0: from cursor down
        assertEquals("", rowText(v, 0).trimEnd())
        assertEquals("", rowText(v, 1).trimEnd())
    }

    @Test
    fun `scrolling keeps history in the scrollback`() {
        val v = screen(cols = 10, rows = 3)
        v.parse("one\r\ntwo\r\nthree\r\nfour\r\nfive")
        // the oldest lines have scrolled into history, not vanished
        val all = v.plainText()
        assertTrue("scrollback lost: $all", all.contains("one"))
        assertTrue("scrollback lost: $all", all.contains("five"))
    }

    @Test
    fun `insert and delete line repaint like a full-screen program`() {
        val v = screen(cols = 10, rows = 4)
        v.parse("a\r\nb\r\nc\r\nd\u001b[2;1H\u001b[L")     // IL at row 2
        assertEquals("a", rowText(v, 0).trimEnd())
        assertEquals("", rowText(v, 1).trimEnd())
        assertEquals("b", rowText(v, 2).trimEnd())
        v.parse("\u001b[2;1H\u001b[M")                     // DL at row 2
        assertEquals("b", rowText(v, 1).trimEnd())
    }

    @Test
    fun `alternate screen does not destroy the shell history`() {
        val v = screen(cols = 12, rows = 3)
        v.parse("prompt$ ls\r\nfile.txt")
        val before = rowText(v, 0)
        v.parse("\u001b[?1049h\u001b[2J\u001b[Hvi-ish content")
        assertTrue(v.altScreen)
        assertTrue(rowText(v, 0).contains("vi-ish"))
        v.parse("\u001b[?1049l")
        assertFalse(v.altScreen)
        assertEquals(before, rowText(v, 0))
    }

    @Test
    fun `scroll region confines the cursor`() {
        val v = screen(cols = 10, rows = 6)
        v.parse("\u001b[2;4r")                             // DECSTBM rows 2..4
        v.parse("\u001b[4;1Hx\r\ny")                       // LF at the bottom of the region scrolls it
        assertEquals("y", rowText(v, 3).trimEnd())
    }

    @Test
    fun `title is read from OSC`() {
        val v = screen()
        v.parse("\u001b]0;root@hakatron: ~\u0007")
        assertEquals("root@hakatron: ~", v.title)
    }

    @Test
    fun `device status report answers with the cursor position`() {
        val v = screen()
        var reply = ""
        v.onReply = { reply = it }
        v.parse("\u001b[3;7H\u001b[6n")
        assertEquals("\u001b[3;7R", reply)
    }

    @Test
    fun `utf8 split across reads is reassembled`() {
        val v = screen()
        val bytes = "héllo → ok".toByteArray(Charsets.UTF_8)
        // feed it in two halves, splitting the multi-byte sequences on purpose
        v.parse(bytes.copyOfRange(0, 2), 2)
        v.parse(bytes.copyOfRange(2, bytes.size), bytes.size - 2)
        assertEquals("héllo → ok", rowText(v, 0).trimEnd())
    }

    @Test
    fun `a shell prompt round trip renders exactly as typed`() {
        val v = screen(cols = 40, rows = 4)
        // what `PS1='…\w\$ '` plus a command with colours looks like on the wire
        v.parse("\u001b[1;36mhakatron\u001b[0m:/root$ ls --color=always\r\n" +
            "\u001b[1;34msrc\u001b[0m  \u001b[32mrun.sh\u001b[0m\r\n")
        val first = rowText(v, 0).trimEnd()
        assertEquals("hakatron:/root$ ls --color=always", first)
        val second = rowText(v, 1)
        assertTrue(second.contains("src"))
        assertTrue(second.contains("run.sh"))
    }

    @Test
    fun `progress bars and carriage-return redraws overwrite in place`() {
        val v = screen(cols = 20)
        v.parse("downloading 10%\rdownloading 90%\rdone")
        assertTrue("in-place redraw broken: ${rowText(v, 0)}", rowText(v, 0).trimEnd().startsWith("done"))
    }

    @Test
    fun `reset clears everything`() {
        val v = screen()
        v.parse("junk\u001b[31m\u001b[2J\u001b[H\u001bc")
        assertEquals("", rowText(v, 0).trimEnd())
        assertEquals(-1, v.attr.fg)
    }
}
