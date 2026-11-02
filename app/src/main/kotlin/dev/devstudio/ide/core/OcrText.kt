package dev.devstudio.ide.core

/**
 * OcrText — repairs what OCR actually returns.
 *
 * ML Kit on a phone screenshot is good at words and bad at punctuation, paths and
 * box glyphs. The strings below are real output from a chat screenshot (the IDE Bot
 * one): spaces appear inside words, the first letter of a path goes missing, and the
 * tree connectors come back as digits.
 *
 * Everything here is pure text in → text out so it can be tested without a device.
 */
object OcrText {

    /**
     * Chat furniture that must never become a file or a line of code.
     * "Thought for 6 seconds", "0 Copy", "V deepseek" — all of it is the app's
     * chrome, not the project.
     */
    fun isChatNoise(raw: String): Boolean {
        // "└── Copy" is the copy button after the connector was repaired
        val t = raw.trim().trimStart { it in "├└│┌┣┗┝┕─–—·*|• \t" }.trim()
        if (t.isEmpty()) return true
        val lower = t.lowercase()
        if (lower.matches(Regex("^[0o°·]?\\s*copy$"))) return true
        if (lower == "text" || lower == "copy" || lower == "code") return true
        if (lower in setOf("deepseek", "chatgpt", "claude", "gemini", "copilot", "grok")) return true
        if (Regex("^[v▾›>]?\\s*(deepseek|chatgpt|claude|gemini|grok)$").matches(lower)) return true
        if (Regex("^thought for\\b.*(second|minute|s\\b)?").containsMatchIn(lower)) return true
        if (Regex("^thinking\\b").containsMatchIn(lower)) return true
        if (Regex("^(here('| i)s|i need|i will|i'll|let me|sure|certainly|of course|okay[,.]?|ok[,.]?)\\b")
                .containsMatchIn(lower)) return true
        if (Regex("^(project tree|file tree|folder structure|directory structure)\\b").containsMatchIn(lower))
            return true
        // ---- phone status bar, which a screenshot carries above the real content
        if (Regex("^\\d{1,2}[:.]\\d{2}(\\s*[ap]\\.?m\\.?)?$").matches(lower)) return true   // 12:38
        if (Regex("^\\d{1,3}\\s*%$").matches(lower)) return true                              // 84 %
        if (Regex("^\\d{1,3}\\s*%?\\s*(battery|charge)").containsMatchIn(lower)) return true
        if (lower.matches(Regex("^[\\d ]+[g45]?[g]?\\s*[lte5g+]*\\s*[▮▯|]{0,4}$"))) return true // signal bars
        if (lower in setOf("wifi", "wi-fi", "volte", "lte", "5g", "4g", "bluetooth")) return true
        if (Regex("^(globe|smart|tnt|tm|sun|dito)\\b").containsMatchIn(lower)) return true       // carrier names
        if (Regex("^[◀◁▷◁|]+$").matches(lower)) return true                                     // nav bar glyphs
        return false
    }

    /**
     * Fixes one line. Each rule comes from a real OCR error:
     *
     *  · `Automat ion/`        → `Automation/`      (space inside a word)
     *  · `Settings. gradle.kts`→ `settings.gradle.kts` (space after the dot)
     *  · `rapper/gradle -wrapper.properties`
     *                          → `wrapper/gradle-wrapper.properties`
     *  · `0 Copy` / `└ Copy`   → dropped by isChatNoise before this runs
     *  · `0 src/` `1 app/`     → `└── src/` `├── app/` (connector read as a digit)
     *  · `foo ,` `bar )`       → `foo,` `bar)`
     */
    fun repair(raw: String): String {
        var line = raw.replace('\u00A0', ' ').replace('\t', ' ').trimEnd()
        if (line.isBlank()) return ""
        // a connector that OCR read as a digit or a letter
        line = Regex("^(\\s*)[0oO°](?=\\s+\\S)").replace(line) { it.groupValues[1] + "└──" }
        line = Regex("^(\\s*)[1lI|](?=\\s+\\S)").replace(line) { it.groupValues[1] + "├──" }
        // a path broken after a dot or slash: "Settings. gradle.kts"
        line = Regex("([./\\\\])[ ]+(\\w)").replace(line) { m -> m.groupValues[1] + m.groupValues[2] }
        // a hyphen broken by a space: "gradle -wrapper" → "gradle-wrapper"
        line = Regex("(\\w)[ ]+-[ ]?(?=\\w)").replace(line) { m -> m.groupValues[1] + "-" }
        // a word split in two: "Automat ion" (first part ≥4 letters, second ≤3)
        //
        // The tail has to actually be a suffix. Joining on "short word" alone turned
        // "make app" into "makeapp" and "test run" into "testrun" — prose mangled by
        // a rule meant for identifiers. A suffix list is the honest test.
        line = Regex("\\b(\\p{L}{4,})[ ]+(\\p{L}{1,3})\\b(?=[^\\p{L}]|$)").replace(line) { m ->
            val head = m.groupValues[1]
            val tail = m.groupValues[2]
            val low = tail.lowercase()
            if (low in STANDALONE || low !in SUFFIX) m.value else head + tail
        }
        // space before punctuation
        line = Regex("[ ]+([,;:)\\]}])").replace(line, "$1")
        return line.trimEnd()
    }

    /**
     * Repairs a line of **code** read from a screenshot.
     *
     * Somebody screenshots their editor and pastes it in; ML Kit keeps the gutter
     * digits and loses the spacing the syntax needs:
     *
     *  · `12 |     foo();`       → the `12 |` gutter is the editor's, not the code
     *  · `System . out . println` → `System.out.println`
     *  · `foo (x)` / `( x`       → `foo(x)` / `(x`
     *
     * Only lines that already look like code are touched, so prose is never
     * re-spaced and a bare path is never welded into an identifier.
     */
    fun code(line: String): String {
        var l = line
        // an editor gutter: "12 |" / "12 │" / "12:  " (only at the very start)
        l = Regex("^\\s*\\d{1,4}\\s*[|│] ?").replace(l, "")
        l = Regex("^\\d{1,4}:\\s{2,}").replace(l, "")
        if (!looksLikeCode(l)) return l
        l = Regex("\\s*\\.\\s*(?=\\p{L})").replace(l, ".")        // a . b
        l = Regex("(\\p{L}|\\d)\\s+\\(").replace(l) { m -> m.groupValues[1] + "(" }   // foo (
        l = Regex("\\(\\s+(?=[\\p{L}\\d\"])").replace(l, "(")   // ( x
        l = Regex("\\s+\\)").replace(l, ")")                     // x )
        return l
    }

    /**
     * True when the line has enough syntax to be source code rather than prose.
     *
     * Deliberately strict: a sentence with a parenthesis in it ("Complete Package
     * (One Drop)") must never be re-spaced, so brackets alone prove nothing.
     */
    fun looksLikeCode(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return false
        if (Regex("^(fun|def|class|public|private|protected|fn|func|let|var|val|const|import|from|#include|package|using|return|if|for|while|else|switch|case|struct|enum|impl|trait|async|suspend|throw|new|await|yield|@)\\b")
                .containsMatchIn(t)) return true
        if (t.any { it in "{};" }) return true
        if (Regex("(:=|=>|->|==|!=|&&|\\|\\|)").containsMatchIn(t)) return true
        if (Regex("\\w\\s*\\(\\s*[\"']").containsMatchIn(t)) return true   // call with a literal
        if (Regex("\\)\\s*\\{").containsMatchIn(t)) return true
        // a member chain ("System.out") — but "v1.2" is a version, not a call
        if (t.count { it in "()" } >= 2 && Regex("\\w{2,}\\.\\w{2,}").containsMatchIn(t)) return true
        return false
    }

    /**
     * The whole document: [repair] applied to **every** line, then [tidy].
     *
     * `repair` works on one line, and its connector rules are anchored — running it
     * on a multi-line string only ever fixed the first line, which is why a tree
     * pasted from a screenshot came back with one repaired connector and five
     * numerals. This is the entry point callers should use.
     */
    fun clean(text: String): String =
        tidy(text.replace("\r\n", "\n").split("\n").map { code(repair(it)) }).joinToString("\n")

    /**
     * Tidies a repaired document:
     *
     *  · drops specks — one or two punctuation marks where a glyph failed to read
     *  · drops a stray box fragment at the end of a tree line (`├── src/   |`)
     *  · collapses a repeated long line (OCR double-reads a block at a scroll seam)
     *  · keeps no more than one blank line in a row
     */
    fun tidy(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var prev = ""
        var blanks = 0
        for (raw in lines) {
            val line = raw.trimEnd()
            val t = line.trim()
            // a speck, but never a bracket — `}` and `)` are the code
            if (t.length <= 2 && t.isNotEmpty() && t.none { it.isLetterOrDigit() } &&
                t.none { it in "{}()[]<>;,=+-*/" }) continue
            val isTree = t.isNotEmpty() && t.first() in "├└│┌┣┗┝┕─"
            val fixed = if (isTree) line.replace(Regex("[ \\t]*[|¦]\\s*$"), "") else line
            if (fixed.isBlank()) {
                if (++blanks > 1) continue
                prev = ""
                out.add("")
                continue
            }
            blanks = 0
            if (fixed == prev && fixed.trim().length > 8) continue
            prev = fixed
            out.add(fixed)
        }
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
        return out
    }

    /**
     * The only two-or-three letter tails a broken word can end in. This is what makes
     * "Automat ion" → "Automation" safe while "make app" stays two words.
     */
    private val SUFFIX = setOf(
        // only endings that are never a word on their own
        "ion", "ing", "ity", "ive", "ies", "ial", "ian", "ied", "ise", "ize", "ish",
        "ent", "ant", "ure", "acy", "ery", "ary", "ory", "ate", "ble", "ous", "ful",
        "ism", "ist", "est", "ess", "age", "ade", "ely", "ily", "ment", "ness",
        "less", "ance", "ence", "able", "ible", "tion", "sion", "ical", "ular",
        "ed", "er", "or", "ly", "es", "al", "ic", "ty", "ry", "cy", "ss", "ff",
        "pp", "tt", "ck", "sh", "ch", "th", "gh", "ph", "ng", "nt", "ct", "pt", "ll"
    )

    /** Short words that are real words, so `repair` must not weld them onto the previous one. */
    private val STANDALONE = setOf(
        "a", "an", "i", "is", "it", "if", "in", "of", "on", "or", "to", "up", "we", "as",
        "at", "be", "by", "do", "go", "he", "my", "no", "so", "us", "the", "and", "for",
        "you", "not", "all", "one", "two", "run", "new", "old", "get", "set", "add", "raw",
        "me", "my", "its", "our", "her", "his", "him", "was", "are", "can", "may", "use",
        "out", "off", "own", "put", "way", "who", "why", "how", "yes", "few", "far"
    )

    /**
     * True when the line is a box-drawn tree entry: real connector glyphs, or the
     * substitutes this repair pass produces, or a bare `name/` directly under one.
     */
    fun isTreeLine(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return false
        if (t.any { it in "├└│┌┣┗┝┕─━┄¦" }) return true
        if (Regex("^[|+`][-–—_]{2,}\\s*\\S").containsMatchIn(t)) return true
        return false
    }
}
