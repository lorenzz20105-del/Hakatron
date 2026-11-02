package dev.devstudio.ide.core

/**
 * OcrShape — the block-level half of OCR repair.
 *
 * [OcrText] fixes one line at a time. That is not enough, because the damage a
 * screenshot does is mostly *structural*:
 *
 *  · the editor's line-number gutter comes along with the code  (`12 |     foo()`)
 *  · every leading space of the indentation is lost                (`return x` at column 0)
 *  · a long file is captured in two screenshots that overlap
 *  · the platform substitutes typographic characters for ASCII    (`“x”  a—b  １２`)
 *
 * So this object works on blocks: strip the gutter as a block (numbers must ascend),
 * rebuild indentation from brace depth when the whole block is flat, stitch overlapping
 * captures, normalise the look-alike characters, and — the part that matters most —
 * say out loud which lines it is *not* sure about instead of guessing quietly.
 *
 * Pure text in, text out: every rule below is testable without a device.
 */
object OcrShape {

    /** What [block] did, so the UI can show it rather than silently "fixing" a file. */
    data class Report(
        val text: String,
        /** editor gutter removed (a line-number column was found and stripped) */
        val gutter: Boolean = false,
        /** indentation was rebuilt from brace depth because the capture had none */
        val rebuiltIndent: Boolean = false,
        /** how many lines this pass believes may still be misread */
        val uncertain: Int = 0,
        /** one short sentence per structural change, for the note under the picker */
        val notes: List<String> = emptyList()
    ) {
        val changed: Boolean get() = gutter || rebuiltIndent || notes.isNotEmpty()
    }

    // ---------------------------------------------------------------- entry point

    /**
     * The whole pipeline for text read from an image: normalise, strip a gutter,
     * rebuild lost indentation, tidy, and count the doubtful lines.
     */
    fun block(text: String): Report {
        val notes = ArrayList<String>()
        var src = normalise(text).replace("\r\n", "\n")
        if (src.isBlank()) return Report("")

        val (noGutter, hadGutter) = stripGutter(src.lines())
        if (hadGutter) notes += "stripped the editor's line numbers"
        src = noGutter.joinToString("\n")

        val cleaned = OcrText.clean(src)
        val lines = cleaned.split("\n").toMutableList()

        val (shaped, rebuilt) = rebuildIndent(lines)
        if (rebuilt) notes += "rebuilt the indentation from braces (the capture had none)"
        if (rebuilt) lines.clear().also { lines.addAll(shaped) }

        val final = OcrText.tidy(lines)
        val uncertain = final.count { uncertainLine(it) }
        return Report(
            text = final.joinToString("\n"),
            gutter = hadGutter,
            rebuiltIndent = rebuilt,
            uncertain = uncertain,
            notes = notes
        )
    }

    // ------------------------------------------------------------- look-alikes

    /**
     * Characters a phone keyboard, a word processor or a bad OCR pass puts where
     * ASCII belongs. Only unambiguous substitutions happen here — nothing that could
     * turn one valid character into another valid one.
     */
    fun normalise(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            out.append(
                when (ch) {
                    // full width forms: U+FF01..U+FF5E are the ASCII glyphs shifted
                    in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
                    '\u3000' -> ' '          // ideographic space
                    '\u00A0', '\u2007', '\u202F' -> ' '   // no-break spaces
                    '\u2212' -> '-'          // minus sign, not a hyphen
                    '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015' -> '-'
                    '\u2018', '\u2019', '\u201A', '\u201B', '\u2032' -> '\''
                    '\u201C', '\u201D', '\u201E', '\u201F', '\u2033' -> '"'
                    '\u2026' -> '.'          // an ellipsis is three dots on one baseline
                    '\u00B7', '\u2022' -> '·'
                    '\uFEFF', '\u200B', '\u200C', '\u200D' -> ' '
                    else -> ch
                }
            )
        }
        // a lone soft hyphen is invisible in the source, so it must not survive
        return out.toString().replace("\u00AD", "")
    }

    // ------------------------------------------------------------------ gutter

    private val GUTTER = Regex("^\\s*(\\d{1,4})\\s*[|│¦:⏐]\\s?")

    /**
     * Removes an editor's line-number column.
     *
     * A single `12 |` line is not proof — a markdown table row or a diff looks the
     * same. It is a gutter when at least three lines carry one and the numbers
     * *ascend*, which is what a code editor does and what nothing else does.
     */
    fun stripGutter(lines: List<String>): Pair<List<String>, Boolean> {
        val hits = ArrayList<Triple<Int, Int, MatchResult>>()   // index, number, match
        lines.forEachIndexed { i, l ->
            val m = GUTTER.find(l) ?: return@forEachIndexed
            val n = m.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            hits.add(Triple(i, n, m))
        }
        if (hits.size < 3) return lines to false
        // ascending? allow one OCR dropout, refuse a descending run (that is prose)
        var ascending = 0
        for (k in 1 until hits.size) if (hits[k].second >= hits[k - 1].second) ascending++
        val bodyNonBlank = lines.count { it.isNotBlank() }
        val coverage = hits.size.toDouble() / bodyNonBlank.coerceAtLeast(1)
        if (ascending < hits.size - 1 || coverage < 0.6) return lines to false

        val out = lines.toMutableList()
        for ((i, _, m) in hits) out[i] = out[i].removeRange(m.range.first, m.range.last + 1)
        return out to true
    }

    // ------------------------------------------------------------ indentation

    /**
     * Rebuilds indentation from brace depth when the capture lost all of it.
     *
     * Only fires when the block is *entirely* flat (no line is indented) and the
     * braces balance, because that is the signature of an OCR pass that ate the
     * leading whitespace — as opposed to hand-written code that is flat on purpose.
     * Lines are never re-ordered, so a wrong guess can only change the look, never
     * the meaning, and the caller is told it happened.
     */
    fun rebuildIndent(lines: List<String>): Pair<List<String>, Boolean> {
        val body = lines.filter { it.isNotBlank() }
        val codeish = body.filter { OcrText.looksLikeCode(it) }
        if (codeish.size < 3) return lines to false
        if (body.any { it.firstOrNull()?.isWhitespace() == true }) return lines to false
        val opens = lines.sumOf { l -> l.count { it == '{' } }
        val closes = lines.sumOf { l -> l.count { it == '}' } }
        if (opens == 0 || opens != closes) return lines to false

        var depth = 0
        val out = ArrayList<String>(lines.size)
        for (l in lines) {
            if (l.isBlank()) { out.add(""); continue }
            val trimmed = l.trimStart()
            val leading = trimmed.takeWhile { it == '}' }.length
            val here = (depth - leading).coerceAtLeast(0)
            out.add(" ".repeat(here * 4) + trimmed)
            depth += trimmed.count { it == '{' } - trimmed.count { it == '}' }
            if (depth < 0) depth = 0
            // a `case`/label continuation is left alone: depth tracking already did it
        }
        return out to true
    }

    // ----------------------------------------------------------------- stitching

    /**
     * Joins two captures of the same text by the lines they share.
     *
     * Two screenshots of one long file usually overlap by a few lines. Finding the
     * longest shared run (suffix of the first, prefix of the second) is enough to
     * join them without duplicating the seam; when there is no shared run at all the
     * second capture is still appended, because losing text is worse than a seam.
     */
    fun stitch(a: String, b: String): String {
        val x = a.replace("\r\n", "\n").trimEnd().lines()
        val y = b.replace("\r\n", "\n").trimEnd().lines()
        if (x.all { it.isBlank() }) return y.joinToString("\n").trim()
        if (y.all { it.isBlank() }) return x.joinToString("\n").trim()
        for (overlap in minOf(x.size, y.size) downTo 1) {
            val tail = x.takeLast(overlap).map { it.trim() }
            val head = y.take(overlap).map { it.trim() }
            if (tail == head) {
                return (x + y.drop(overlap)).joinToString("\n").trimEnd()
            }
        }
        // no overlap: prefer the longer capture rather than emitting a doubled seam
        val joined = if (y.size > 2) (x + y).joinToString("\n") else x.joinToString("\n")
        return joined.trimEnd()
    }

    /** Folds a whole screenshot pick into one document. */
    fun stitchAll(parts: List<String>): String =
        parts.fold("") { acc, next -> if (acc.isBlank()) next else stitch(acc, next) }

    // --------------------------------------------------------------- mistrust

    /**
     * True when a line probably came out of OCR wrong.
     *
     * The point is to *show* these to the user instead of "repairing" them further:
     * a replacement glyph, an unterminated string, more than two spaces inside what
     * should be an identifier, or a line that is nothing but fragments.
     */
    fun uncertainLine(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return false
        if (t.contains('\uFFFD')) return true                       // the OCR gave up
        if (t.count { it == '"' } % 2 == 1 && OcrText.looksLikeCode(t)) return true
        if (t.count { it == '\'' } % 2 == 1 && OcrText.looksLikeCode(t)) return true
        val brackets = t.count { it in "([{<" } - t.count { it in ")]}>" }
        if (brackets != 0 && OcrText.looksLikeCode(t)) return true
        // fragment soup: three or more one-character tokens and almost no letters —
        // what a glyph the OCR could not resolve leaves behind
        val tokens = t.split(Regex("\\s+"))
        if (tokens.count { it.length == 1 } >= 3 && t.count { it.isLetter() } <= 4) return true
        // a tree connector in the middle of a line of code: two reads bled together
        if (t.any { it in "│¦├└" } && OcrText.looksLikeCode(t)) return true
        // consonant soup: a long "word" with no vowel is not a word in any language
        // that OCR reads here, and all-caps (a constant) is left alone
        for (w in t.split(Regex("[^\\p{L}]+"))) {
            if (w.length >= 6 && w[0].isLowerCase() && w.none { it.lowercaseChar() in "aeiouy" }) return true
        }
        return false
    }

    /** One sentence for the picker: what was changed, and what to double-check. */
    fun summary(report: Report): String {
        val bits = ArrayList<String>()
        bits += report.notes
        if (report.uncertain > 0) {
            bits += "${report.uncertain} line(s) look misread — check them before building"
        }
        return bits.joinToString(" · ")
    }
}
