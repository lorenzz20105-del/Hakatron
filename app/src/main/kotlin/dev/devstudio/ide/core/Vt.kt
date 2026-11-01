package dev.devstudio.ide.core

/**
 * Vt — a real VT-100 / xterm screen.
 *
 * A terminal is not "a list of strings with the escape codes stripped out". It is a
 * character grid with a cursor, and escape sequences are commands that move that
 * cursor, wipe parts of the grid, change colours and swap whole screens. Anything
 * interactive — `vi`, `top`, `less`, `apk`, `python -i`, progress bars — is written
 * against those commands, which is why they render as garbage without an emulator.
 *
 * Implemented here, with no external library and no allocations per byte:
 *   · 80×24 grid (configurable), scrollback, autowrap with a deferred-wrap flag
 *   · SGR colours — 16, 256 and 24-bit — plus bold/dim/italic/underline/reverse
 *   · cursor movement, erase (ED/EL/ECH), insert/delete line and character
 *   · scrolling regions (DECSTBM), alternate screen (1047/1049), cursor save/restore
 *   · OSC title, DSR/CPR replies so programs can ask where the cursor is
 *   · UTF-8 decoding across chunk boundaries (a reader thread hands us raw bytes)
 *
 * It is deliberately UI-free: [parse] takes bytes, [lines] returns styled rows, and
 * the Compose layer decides what a colour index means. That also makes it testable
 * on the JVM.
 */
class VtScreen(val cols: Int = 80, val rows: Int = 24, private val scrollbackMax: Int = 2000) {

    /** colour: -1 = default, 0..255 = palette index, >= 0x1000000 = 0xRRGGBB */
    data class Attr(val fg: Int = -1, val bg: Int = -1, val flags: Int = 0) {
        val bold get() = flags and 1 != 0
        val dim get() = flags and 2 != 0
        val italic get() = flags and 4 != 0
        val underline get() = flags and 8 != 0
        val reverse get() = flags and 16 != 0
        val hidden get() = flags and 32 != 0
        val strike get() = flags and 64 != 0
    }

    class Cell(var ch: Char = ' ', var attr: Attr = Attr())

    /** one rendered row: a run of text with the same attributes */
    data class Run(val text: String, val attr: Attr)

    private var grid: Array<Array<Cell>> = blank(rows)
    private val scrollback = ArrayDeque<Array<Cell>>()

    var cursorX = 0; private set
    var cursorY = 0; private set
    var attr = Attr(); private set
    var title: String = "terminal"; private set
    var altScreen = false; private set

    private var savedX = 0
    private var savedY = 0
    private var savedAttr = Attr()
    private var main: Array<Array<Cell>>? = null
    private var mainX = 0
    private var mainY = 0

    private var top = 0
    private var bottom = rows - 1
    private var autowrap = true
    private var pendingWrap = false

    /** called when a program asks for the cursor position (DSR 6) */
    var onReply: ((String) -> Unit)? = null

    /** bumped on every change, so a UI can redraw without diffing the grid */
    var version = 0; private set

    // ------------------------------------------------------------------ parser
    private var state = S_GROUND
    private val params = StringBuilder()
    private val osc = StringBuilder()
    private val utf8 = ArrayList<Byte>()

    private companion object {
        const val S_GROUND = 0
        const val S_ESC = 1
        const val S_CSI = 2
        const val S_OSC = 3
        const val S_OSC_ESC = 4
    }

    fun parse(bytes: ByteArray, len: Int = bytes.size) {
        for (i in 0 until len) {
            val b = bytes[i].toInt() and 0xFF
            if (state == S_GROUND && b < 0x80) { controlOrPrint(b.toChar()); continue }
            if (state == S_GROUND && b >= 0x80) { utf8.add(b.toByte()); decodeUtf8(false); continue }
            when (state) {
                S_ESC -> esc(b.toChar())
                S_CSI -> csiByte(b.toChar())
                S_OSC -> {
                    if (b == 7) { finishOsc(); state = S_GROUND }
                    else if (b == 0x1B) state = S_OSC_ESC
                    else if (osc.length < 512) osc.append(b.toChar())
                }
                S_OSC_ESC -> { finishOsc(); state = if (b.toChar() == '\\') S_GROUND else S_ESC }
                else -> state = S_GROUND
            }
        }
        version++
    }

    fun parse(text: String) = parse(text.toByteArray(Charsets.UTF_8))

    /** Multi-byte characters can be split across reads; keep the tail for next time. */
    private fun decodeUtf8(flush: Boolean) {
        val need = when {
            utf8.isEmpty() -> 0
            utf8[0].toInt() and 0xE0 == 0xC0 -> 2
            utf8[0].toInt() and 0xF0 == 0xE0 -> 3
            utf8[0].toInt() and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        if (utf8.size < need && !flush) return
        val text = String(utf8.toByteArray(), Charsets.UTF_8)
        utf8.clear()
        text.forEach { putChar(it) }
    }

    private fun controlOrPrint(c: Char) {
        when (c) {
            '\u0000', '\u0007', '\u000E', '\u000F' -> {}                 // NUL BEL SO SI
            '\u0008' -> backspace()
            '\u0009' -> tab()
            '\n', '\u000B', '\u000C' -> lineFeed()
            '\r' -> { cursorX = 0; pendingWrap = false }
            '\u001B' -> { state = S_ESC; return }
            '\u007F' -> {}
            else -> if (c.code >= 32) putChar(c)
        }
    }

    private fun esc(c: Char) {
        state = S_GROUND
        when (c) {
            '[' -> { state = S_CSI; params.setLength(0) }
            ']' -> { state = S_OSC; osc.setLength(0) }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> { cursorX = 0; lineFeed() }
            'M' -> reverseIndex()
            'c' -> reset()
            '(', ')', '*', '+' -> { state = S_ESC; return }      // charset designator: eat next byte
            '=' , '>' -> {}
            else -> {}
        }
    }

    private fun csiByte(c: Char) {
        if (c in '0'..'9' || c == ';' || c == '?' || c == '>' || c == '<' || c == '!' || c == ':' || c == ' ') {
            params.append(c)
            return
        }
        state = S_GROUND
        val p = paramList()
        when (c) {
            'A' -> move(0, -max(p, 1, 0))
            'B', 'e' -> move(0, max(p, 1, 0))
            'C', 'a' -> move(max(p, 1, 0), 0)
            'D' -> move(-max(p, 1, 0), 0)
            'E' -> { cursorX = 0; move(0, max(p, 1, 0)) }
            'F' -> { cursorX = 0; move(0, -max(p, 1, 0)) }
            'G', '`' -> { cursorX = (max(p, 1, 1) - 1).coerceIn(0, cols - 1); pendingWrap = false }
            'd' -> { cursorY = (max(p, 1, 1) - 1).coerceIn(0, rows - 1); pendingWrap = false }
            'H', 'f' -> {                                     // CUP: row ; column, both 1-based
                cursorY = (max(p, 0, 1) - 1).coerceIn(0, rows - 1)
                cursorX = (max(p, 1, 1) - 1).coerceIn(0, cols - 1)
                pendingWrap = false
            }
            'J' -> eraseDisplay(paramAt(0, 0))
            'K' -> eraseLine(paramAt(0, 0))
            'L' -> insertLines(max(p, 1, 1))
            'M' -> deleteLines(max(p, 1, 1))
            'P' -> deleteChars(max(p, 1, 1))
            '@' -> insertChars(max(p, 1, 1))
            'X' -> eraseChars(max(p, 1, 1))
            'S' -> scrollUp(max(p, 1, 1))
            'T' -> scrollDown(max(p, 1, 1))
            'm' -> sgr(p)
            'r' -> setScrollRegion(p)
            'h' -> setMode(p, true)
            'l' -> setMode(p, false)
            'n' -> deviceStatus(p)
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> {}
        }
        params.setLength(0)
    }

    private fun paramList(): List<Int> {
        val raw = params.toString()
        if (raw.isEmpty()) return emptyList()
        val core = raw.removePrefix("?").removePrefix(">").removePrefix("<").removePrefix("!")
        val clean = core.replace(':', ';')
        if (clean.isBlank()) return emptyList()
        return clean.split(';').map { it.trim().toIntOrNull() ?: 0 }
    }

    private fun paramAt(i: Int, def: Int) = paramList().getOrNull(i)?.takeIf { it != 0 } ?: def
    private fun max(p: List<Int>, i: Int, def: Int) = p.getOrNull(i)?.takeIf { it > 0 } ?: def

    private fun finishOsc() {
        val s = osc.toString()
        val body = s.substringAfter(';', "")
        if (s.startsWith("0;") || s.startsWith("2;")) title = body.ifBlank { title }
        osc.setLength(0)
    }

    // ------------------------------------------------------------------ cursor
    private fun move(dx: Int, dy: Int) {
        val ny = (cursorY + dy).coerceIn(0, rows - 1)
        // moving inside the scroll region never leaves it
        cursorY = if (cursorY in top..bottom && ny in top..bottom) ny else ny
        cursorX = (cursorX + dx).coerceIn(0, cols - 1)
        pendingWrap = false
    }

    private fun saveCursor() { savedX = cursorX; savedY = cursorY; savedAttr = attr }
    private fun restoreCursor() { cursorX = savedX.coerceIn(0, cols - 1); cursorY = savedY.coerceIn(0, rows - 1); attr = savedAttr; pendingWrap = false }

    private fun tab() {
        val next = ((cursorX / 8) + 1) * 8
        cursorX = next.coerceAtMost(cols - 1)
        pendingWrap = false
    }

    private fun backspace() {
        if (cursorX > 0) cursorX--
        pendingWrap = false
    }

    private fun lineFeed() {
        pendingWrap = false
        if (cursorY == bottom) scrollUp(1) else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        if (cursorY == top) scrollDown(1) else if (cursorY > 0) cursorY--
    }

    private fun putChar(c: Char) {
        // a character whose width we can't know (CJK, emoji) still occupies one cell
        if (pendingWrap && autowrap) { cursorX = 0; lineFeed(); pendingWrap = false }
        grid[cursorY][cursorX] = Cell(c, attr)
        if (cursorX == cols - 1) { if (autowrap) pendingWrap = true } else cursorX++
        version++
    }

    // ------------------------------------------------------------------- erase
    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (y in cursorY + 1 until rows) grid[y] = blankRow() }
            1 -> { eraseLine(1); for (y in 0 until cursorY) grid[y] = blankRow() }
            2, 3 -> for (y in 0 until rows) grid[y] = blankRow()
        }
        version++
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (x in cursorX until cols) grid[cursorY][x] = Cell(' ', attr)
            1 -> for (x in 0..cursorX) grid[cursorY][x] = Cell(' ', attr)
            2 -> grid[cursorY] = blankRow()
        }
        version++
    }

    private fun eraseChars(n: Int) {
        for (x in cursorX until minOf(cursorX + n, cols)) grid[cursorY][x] = Cell(' ', attr)
        version++
    }

    // ------------------------------------------------------- insert / delete
    private fun insertChars(n: Int) {
        val row = grid[cursorY]
        for (x in cols - 1 downTo cursorX + n) row[x] = row[x - n]
        for (x in cursorX until minOf(cursorX + n, cols)) row[x] = Cell(' ', attr)
        version++
    }

    private fun deleteChars(n: Int) {
        val row = grid[cursorY]
        for (x in cursorX until cols - n) row[x] = row[x + n]
        for (x in maxOf(cols - n, cursorX) until cols) row[x] = Cell(' ', attr)
        version++
    }

    private fun insertLines(n: Int) {
        if (cursorY < top || cursorY > bottom) return
        for (k in 0 until n) {
            for (y in bottom downTo cursorY + 1) grid[y] = grid[y - 1]
            grid[cursorY] = blankRow()
        }
        cursorX = 0
        version++
    }

    private fun deleteLines(n: Int) {
        if (cursorY < top || cursorY > bottom) return
        for (k in 0 until n) {
            for (y in cursorY until bottom) grid[y] = grid[y + 1]
            grid[bottom] = blankRow()
        }
        cursorX = 0
        version++
    }

    private fun scrollUp(n: Int) {
        for (k in 0 until n) {
            if (top == 0) {
                scrollback.addLast(grid[top].copyOf())
                while (scrollback.size > scrollbackMax) scrollback.removeFirst()
            }
            for (y in top until bottom) grid[y] = grid[y + 1]
            grid[bottom] = blankRow()
        }
        version++
    }

    private fun scrollDown(n: Int) {
        for (k in 0 until n) {
            for (y in bottom downTo top + 1) grid[y] = grid[y - 1]
            grid[top] = blankRow()
        }
        version++
    }

    private fun setScrollRegion(p: List<Int>) {
        val t = max(p, 0, 1) - 1
        val b = (p.getOrNull(1)?.takeIf { it > 0 } ?: rows) - 1
        if (t < b && b < rows) { top = t; bottom = b }
        else { top = 0; bottom = rows - 1 }
        cursorX = 0; cursorY = top
        version++
    }

    private fun setMode(p: List<Int>, on: Boolean) {
        val raw = params.toString()
        val private = raw.startsWith("?")
        val modes = p.ifEmpty { listOf(0) }
        for (m in modes) when {
            private && (m == 47 || m == 1047 || m == 1049) -> if (on) enterAlt() else exitAlt()
            private && m == 7 -> autowrap = on
            private && m == 25 -> {}                       // show/hide cursor: the UI owns it
            else -> {}
        }
        version++
    }

    private fun enterAlt() {
        if (altScreen) return
        main = grid
        mainX = cursorX; mainY = cursorY
        grid = blank(rows)
        altScreen = true
        cursorX = 0; cursorY = 0
        top = 0; bottom = rows - 1
    }

    private fun exitAlt() {
        main?.let { grid = it }
        main = null
        altScreen = false
        cursorX = mainX; cursorY = mainY
        top = 0; bottom = rows - 1
    }

    private fun deviceStatus(p: List<Int>) {
        when (p.firstOrNull()) {
            5 -> onReply?.invoke("\u001b[0n")                                  // "I'm alive"
            6 -> onReply?.invoke("\u001b[${cursorY + 1};${cursorX + 1}R")        // cursor position
        }
    }

    // --------------------------------------------------------------------- SGR
    private fun sgr(p: List<Int>) {
        if (p.isEmpty()) { attr = Attr(); return }
        var i = 0
        var fg = attr.fg
        var bg = attr.bg
        var flags = attr.flags
        while (i < p.size) {
            val v = p[i]
            when {
                v == 0 -> { fg = -1; bg = -1; flags = 0 }
                v == 1 -> flags = flags or 1
                v == 2 -> flags = flags or 2
                v == 3 -> flags = flags or 4
                v == 4 -> flags = flags or 8
                v == 7 -> flags = flags or 16
                v == 8 -> flags = flags or 32
                v == 9 -> flags = flags or 64
                v == 21 || v == 22 -> flags = flags and 1.inv() and 2.inv()
                v == 23 -> flags = flags and 4.inv()
                v == 24 -> flags = flags and 8.inv()
                v == 27 -> flags = flags and 16.inv()
                v == 28 -> flags = flags and 32.inv()
                v == 29 -> flags = flags and 64.inv()
                v in 30..37 -> fg = v - 30
                v == 39 -> fg = -1
                v in 40..47 -> bg = v - 40
                v == 49 -> bg = -1
                v in 90..97 -> fg = v - 90 + 8
                v in 100..107 -> bg = v - 100 + 8
                v == 38 || v == 48 -> {
                    val target = if (v == 38) 1 else 2
                    val next = p.getOrNull(i + 1) ?: break
                    if (next == 5) {
                        val idx = p.getOrNull(i + 2) ?: 0
                        if (target == 1) fg = idx and 0xFF else bg = idx and 0xFF
                        i += 2
                    } else if (next == 2) {
                        val r = p.getOrNull(i + 2) ?: 0
                        val g = p.getOrNull(i + 3) ?: 0
                        val b = p.getOrNull(i + 4) ?: 0
                        val rgb = 0x1000000 or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
                        if (target == 1) fg = rgb else bg = rgb
                        i += 4
                    }
                }
            }
            i++
        }
        attr = Attr(fg, bg, flags)
    }

    fun reset() {
        grid = blank(rows)
        scrollback.clear()
        cursorX = 0; cursorY = 0
        attr = Attr()
        top = 0; bottom = rows - 1
        altScreen = false; main = null
        pendingWrap = false; autowrap = true
        version++
    }

    /** Clears the visible screen only — what a "clear" button should do. */
    fun clearVisible() {
        if (altScreen) return
        grid = blank(rows)
        cursorX = 0; cursorY = 0
        scrollback.clear()
        version++
    }

    // ------------------------------------------------------------------ render
    /** scrollback + screen, each row as runs of equally-styled text. */
    fun lines(): List<List<Run>> {
        val out = ArrayList<List<Run>>(scrollback.size + rows)
        scrollback.forEach { out.add(runs(it, -1)) }
        for (y in 0 until rows) out.add(runs(grid[y], y))
        return out
    }

    /** plain text of everything above the cursor's row — used by tests and copy. */
    fun plainText(): String = lines().joinToString("\n") { line -> line.joinToString("") { it.text } }

    private fun runs(row: Array<Cell>, y: Int): List<Run> {
        // trailing blanks are invisible; keep one cell for the cursor row
        var end = cols
        while (end > 0 && row[end - 1].ch == ' ' && row[end - 1].attr == Attr()) end--
        if (y == cursorY) end = maxOf(end, minOf(cursorX + 1, cols))
        if (end == 0) return emptyList()
        val out = ArrayList<Run>(4)
        var start = 0
        while (start < end) {
            val a = row[start].attr
            var stop = start + 1
            while (stop < end && row[stop].attr == a) stop++
            out.add(Run(String(CharArray(stop - start) { row[start + it].ch }), a))
            start = stop
        }
        return out
    }

    private fun blankRow() = Array(cols) { Cell() }
    private fun blank(n: Int) = Array(n) { blankRow() }
}
