package dev.devstudio.ide.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Highlighter — small, dependency-free syntax colouring that works while you
 * type. Comment -> string -> keyword -> number order matters: it is what stops
 * "def" inside a string from being coloured as a keyword.
 *
 * Highlighting is **incremental**: the text is split into lines and every line is
 * coloured on its own, cached under (language, block-comment-at-start, text). An
 * edit re-colours only the lines that actually changed, so typing in a 3,000-line
 * file costs a couple of lines of work instead of the whole file. The only state
 * that crosses a line boundary is the open block comment, which is part of the
 * cache key — so scrolling back over old text hits the cache every time.
 */
object Highlighter {

    private val KEYWORDS = setOf(
        // python
        "def", "class", "import", "from", "return", "if", "elif", "else", "for", "while",
        "try", "except", "finally", "with", "as", "lambda", "pass", "break", "continue",
        "yield", "global", "nonlocal", "assert", "del", "raise", "async", "await",
        "and", "or", "not", "in", "is", "True", "False", "None", "self",
        // js / ts
        "function", "const", "let", "var", "new", "this", "typeof", "instanceof", "export",
        "default", "extends", "super", "null", "undefined", "interface", "type", "enum",
        "implements", "readonly", "public", "private", "protected", "static",
        // jvm / rust / go / c
        "package", "void", "int", "float", "double", "char", "long", "boolean", "String",
        "final", "throws", "throw", "catch", "switch", "case", "struct", "fn", "impl", "mut",
        "pub", "use", "match", "trait", "let", "loop", "const", "func", "var", "defer",
        "range", "map", "type", "goto", "include", "define", "sizeof", "unsigned", "extern"
    )

    private val KW = Color(0xFFC084FC)
    private val STR = Color(0xFF7BDF9B)
    private val COM = Color(0xFF5C6478)
    private val NUM = Color(0xFFF9A87C)
    private val FN = Color(0xFF69B7FF)

    private class Line(val text: String, val block: Boolean)

    /** 20k coloured lines is ~10 MB of annotated text — big enough, and bounded. */
    private val cache = object : LinkedHashMap<String, AnnotatedString>(2048, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>?) =
            size > 20_000
    }

    private var lastText: String? = null
    private var lastLang: String? = null
    private var lastResult: AnnotatedString? = null

    @Synchronized
    fun highlight(text: String, lang: String): AnnotatedString {
        // nothing changed since the last frame: no work at all
        if (text == lastText && lang == lastLang) lastResult?.let { return it }

        val out = buildAnnotatedString {
            var inBlock = false
            var start = 0
            val n = text.length
            while (true) {
                val nl = text.indexOf('\n', start)
                val end = if (nl < 0) n else nl
                val lineText = text.substring(start, end)
                val key = lang + "|" + (if (inBlock) "1" else "0") + "|" + lineText
                val styled = cache[key] ?: highlightLine(lineText, lang, inBlock).also { cache[key] = it }
                append(styled)
                inBlock = endsInBlockComment(lineText, lang, inBlock)
                if (nl < 0) break
                append('\n')
                start = nl + 1
            }
        }
        lastText = text
        lastLang = lang
        lastResult = out
        return out
    }

    private fun lineCommentOf(lang: String): String = when (lang) {
        "python", "ruby", "sh", "bash", "yaml", "toml" -> "#"
        else -> "//"
    }

    /** Does this line leave us inside a block comment? */
    private fun endsInBlockComment(line: String, lang: String, startsIn: Boolean): Boolean {
        if (!line.contains("/*") && !line.contains("*/") && !startsIn) return false
        var inBlock = startsIn
        var i = 0
        val lc = lineCommentOf(lang)
        while (i < line.length) {
            if (inBlock) {
                val close = line.indexOf("*/", i)
                if (close < 0) return true
                i = close + 2
                inBlock = false
                continue
            }
            if (line.startsWith(lc, i)) return false
            if (line.startsWith("/*", i)) {
                inBlock = true
                i += 2
                continue
            }
            i++
        }
        return inBlock
    }

    private fun highlightLine(line: String, lang: String, startsInBlock: Boolean): AnnotatedString {
        val lc = lineCommentOf(lang)
        return buildAnnotatedString {
            var i = 0
            val n = line.length
            var inBlock = startsInBlock
            while (i < n) {
                if (inBlock) {
                    val close = line.indexOf("*/", i)
                    val end = if (close < 0) n else close + 2
                    withStyle(SpanStyle(color = COM)) { append(line.substring(i, end)) }
                    i = end
                    inBlock = close < 0
                    continue
                }
                if (line.startsWith(lc, i)) {
                    withStyle(SpanStyle(color = COM)) { append(line.substring(i)) }
                    return@buildAnnotatedString
                }
                if (line.startsWith("/*", i)) {
                    inBlock = true
                    continue
                }
                val c = line[i]
                if (c == '"' || c == '\'' || c == '`') {
                    var j = i + 1
                    while (j < n) {
                        if (line[j] == '\\') { j += 2; continue }
                        if (line[j] == c) { j++; break }
                        j++
                    }
                    withStyle(SpanStyle(color = STR)) { append(line.substring(i, minOf(j, n))) }
                    i = minOf(j, n)
                    continue
                }
                if (c.isDigit() && (i == 0 || !line[i - 1].isLetter())) {
                    var j = i
                    while (j < n && (line[j].isDigit() || line[j] == '.' || line[j] == 'x')) j++
                    withStyle(SpanStyle(color = NUM)) { append(line.substring(i, j)) }
                    i = j
                    continue
                }
                if (c.isLetter() || c == '_') {
                    var j = i
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    val word = line.substring(i, j)
                    when {
                        word in KEYWORDS ->
                            withStyle(SpanStyle(color = KW, fontWeight = FontWeight.Bold)) { append(word) }
                        j < n && line[j] == '(' -> withStyle(SpanStyle(color = FN)) { append(word) }
                        else -> append(word)
                    }
                    i = j
                    continue
                }
                append(c)
                i++
            }
        }
    }

    @Synchronized
    fun cacheSize(): Int = cache.size

    fun langOf(path: String): String = path.substringAfterLast('.', "").lowercase()

    fun titleOf(path: String): String = path.substringAfterLast('/')
}
