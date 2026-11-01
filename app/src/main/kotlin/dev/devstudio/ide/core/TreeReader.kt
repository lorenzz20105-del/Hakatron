package dev.devstudio.ide.core

/**
 * TreeReader — turn a screenshot of a project tree into a structure Hakatron can plan.
 *
 * People share whole projects as pictures: a chat answer with a box-drawing tree
 * (├── └── │), or a file manager listing. OCR gives us text lines plus, crucially,
 * the **left edge of each line in pixels** — a second depth signal, because OCR
 * often drops a │ and because last children have no vertical bar at all.
 *
 * Depth rule (validated against the real 720×46,063 px DeepSeek screenshot):
 *   depth = (indent spaces before the first mark) / 4 + (number of box marks)
 *   then a line may be at most one level deeper than the line above it.
 *
 * Braces are expanded (`res/values/{a.xml, b.xml}` → folder + two children) and
 * prose around the tree ("Project Tree", "Copy", fences) is dropped instead of
 * being turned into files.
 */
object TreeReader {

    data class OcrLine(val text: String, val left: Float)

    data class Result(
        val structure: String,
        val entries: Int,
        val root: String?,
        val ignored: Int,
        /** How sure we are that this is a tree and not a wall of source code. 0..1 */
        val confidence: Float = 1f,
        /** Why it was rejected, when it was. Null when it parsed cleanly. */
        val reason: String? = null,
        /** The raw OCR text, so the caller can offer it to the parts box instead. */
        val rawText: String = ""
    ) {
        /**
         * A tree needs *tree structure*: either box-drawing marks, or names that look
         * like paths and folders. 703 lone tokens with no glyphs and no paths is a
         * screenshot of code, and saying so is the whole point of this flag.
         */
        val ok: Boolean get() = entries > 0 && reason == null
    }

    // box drawing + the characters OCR commonly substitutes for them
    private const val STRUCT = "├└┣┗┝┕╞╘┠┖┌┬┐⎣⎡│┃|｜¦┆┊╎"
    private const val DASHES = "─━┄┈╌╍—–‒-‐"

    private fun isStructure(c: Char) = STRUCT.indexOf(c) >= 0
    private fun isDash(c: Char) = DASHES.indexOf(c) >= 0

    /**
     * "│   ├── src/" → ("src/", 2 marks).
     *
     * Only the indentation *before the first mark* is real indentation — the
     * spaces that draw a vertical bar's column are decoration and must never be
     * counted as four more levels (that is what flattened real trees before).
     */
    fun stripPrefix(raw: String): Pair<String, Int> {
        var i = 0
        var marks = 0
        var spaces = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == ' ' || c == '\t' -> {
                    if (marks == 0) spaces += if (c == ' ') 1 else 4
                    i++
                }
                isStructure(c) -> {
                    marks++
                    i++
                }
                isDash(c) -> i++
                else -> break
            }
        }
        return raw.substring(i).trim() to (spaces / 4 + marks)
    }

    private fun hasMark(raw: String): Boolean = raw.any { isStructure(it) }

    /**
     * OCR rarely renders a box connector as itself. The common substitutes are `|--`, `+--`,
     * `` `-- ``, `|__` and a bare `- `; they all mean the same thing, so they are
     * normalised to the canonical marks *before* depth is counted. Without this a
     * whole screenshot comes back one level deep.
     */
    fun normaliseGlyphs(raw: String): String {
        var line = raw.replace("\u00A0", " ")
        // a leading connector built from substitutes: |-- +-- `-- |__ +__ I-- L__
        line = Regex("^(\\s*)[|+`:IL│][-_=–—]{2,}").replace(line) { m ->
            m.groupValues[1] + "├──"
        }
        // a lone `- ` or `* ` used as a bullet at the start of an indented line
        line = Regex("^(\\s{2,})[-*.]\\s+").replace(line) { m ->
            m.groupValues[1] + "├── "
        }
        return line
    }

    /**
     * Prose and OCR furniture must never become files. A tree entry is one of:
     * a folder (`x/`), a path (`a/b.kt`), a file (`a.kt`), or a single bare token
     * (`Makefile`). "Project Tree", "Copy", "Thought for 6 seconds" are none of
     * those — they are words with no path in them.
     */
    private fun isNoise(name: String): Boolean {
        // `res/values/{strings.xml, themes.xml}` — the space after the comma is
        // layout, not a separator; squashing it keeps the entry (and brace
        // expansion trims it back out again).
        val n = name.trim().let { if (it.contains('{') && it.contains('}')) it.replace(" ", "") else it }
        if (n.isEmpty()) return true
        if (n.startsWith("```") || n.equals("text", true) || n.equals("copy", true)) return true
        if (n.startsWith("#") && !n.contains(".")) return true
        if (n == "." || n == "..") return true
        if (n.contains(' ') && !n.contains('/') && !n.contains('.') && !n.endsWith("/")) return true
        if (n.contains(':') && !n.contains('/')) return true
        if (isCode(n)) return true
        return n.count { it.isLetter() } == 0
    }

    /**
     * Source lines are not file names. OCR of a code screenshot produced 703
     * "entries" like `val regex = "^" + Regex.escape(pattern)/` and a root called
     * `val regex`; these checks are what stop that from ever reaching the planner.
     */
    private fun isCode(n: String): Boolean {
        val t = n.trim()
        // `res/values/{strings.xml, themes.xml}` is a real tree entry with braces in
        // it, not a code block — brace expansion happens after this filter.
        if (Regex("^[\\w./@+{},-]+/?$").matches(t) && (t.contains('/') || t.contains('.'))) return false
        if (t.contains('=') && !t.endsWith("=")) return true                 // assignments, ==, =>
        if (t.contains('(') || t.contains(')') || t.contains('{') || t.contains('}')) return true
        if (t.contains(';') || t.contains('"') || t.contains('`')) return true
        if (t.contains("->") || t.contains("&&") || t.contains("||") || t.contains("::")) return true
        if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) return true
        if (t.startsWith("import ") || t.startsWith("from ") || t.startsWith("package ")) return true
        if (t.startsWith("return ") || t.startsWith("print") || t.startsWith("echo ")) return true
        if (Regex("^(val|var|fun|def|class|const|let|public|private|static|if|for|while|else|try|catch|end)\b")
                .containsMatchIn(t)) return true
        if (t.endsWith(",") || t.endsWith(":") || t.endsWith(")") || t.endsWith(";")) return true
        // a lone token with no separator is fine (Makefile), a sentence is not
        return t.split(' ').size > 4
    }

    /**
     * `res/values/{strings.xml, themes.xml}` is two files inside a folder — so it
     * becomes the folder plus both children, not one weird filename.
     */
    private fun expandBraces(name: String): List<Pair<String, Int>> {
        val open = name.indexOf('{')
        val close = name.indexOf('}')
        if (open < 0 || close < open) return listOf(name to 0)
        val head = name.substring(0, open)
        val tail = name.substring(close + 1)
        val parts = name.substring(open + 1, close).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf(name to 0)
        val out = ArrayList<Pair<String, Int>>()
        if (head.isNotEmpty()) out.add(head.trimEnd('/') + "/" to 0)
        parts.forEach { out.add(it + tail to 1) }
        return out
    }

    /** A real name: `app.py`, `src/`, `com/example/App.kt`, `Makefile`. */
    fun looksLikeFileName(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (n.endsWith("/")) return true
        val leaf = n.substringAfterLast('/')
        if (leaf.isEmpty() || leaf.contains(' ')) return false
        if (Regex("^[\\w.-]+\\.[A-Za-z0-9]{1,10}$").matches(leaf)) return true
        // extensionless but clearly a path or a known build file
        if (n.contains('/') && Regex("^[\\w.-]+$").matches(leaf)) return true
        return leaf in setOf(
            "Makefile", "Dockerfile", "Gemfile", "Rakefile", "LICENSE", "README",
            "Procfile", "Cargo", "gradlew"
        )
    }

    private fun isDir(name: String): Boolean {
        val leaf = name.trimEnd('/').substringAfterLast('/')
        return name.endsWith("/") || !leaf.contains('.') || leaf.startsWith(".")
    }

    /** Depth from pixel indentation: near-identical left edges are the same level. */
    private fun depthFromLefts(lefts: List<Float?>): List<Int> {
        val known = lefts.filterNotNull()
        if (known.size < 3) return lefts.map { -1 }
        val buckets = ArrayList<Float>()
        known.sorted().forEach { x ->
            if (buckets.none { kotlin.math.abs(it - x) <= 12f }) buckets.add(x)
        }
        return lefts.map { x ->
            if (x == null) -1
            else buckets.indexOfFirst { kotlin.math.abs(it - x) <= 12f }.coerceAtLeast(0)
        }
    }

    private class Entry(val name: String, val marks: Int, val left: Float?, val raw: String)

    /** OCR lines with bounding boxes — the good path. */
    fun parse(lines: List<OcrLine>): Result {
        val kept = ArrayList<Entry>()
        var ignored = 0
        for (l in lines) {
            val raw = normaliseGlyphs(l.text).trimEnd()
            if (raw.isBlank()) continue
            val (name0, marks) = stripPrefix(raw)
            val name = name0
                .replace(Regex("\\s{2,}#.*$"), "")
                .replace(Regex("\\s{2,}//.*$"), "")
                .trim()
            if (isNoise(name)) { ignored++; continue }
            kept.add(Entry(name, marks, l.left, raw))
        }
        if (kept.isEmpty()) return Result("", 0, null, ignored)

        // Where the tree starts: the line above the first line that carries a
        // box character. Looking at raw glyphs (not at computed depth) keeps the
        // root folder in play — otherwise app/ silently becomes the root.
        val firstMark = kept.indexOfFirst { hasMark(it.raw) }
        val rootIdx = when {
            firstMark > 0 -> firstMark - 1
            firstMark == 0 -> -1
            else -> kept.indexOfFirst { isDir(it.name) }          // no glyphs at all
        }
        val tree = if (rootIdx >= 0) kept.subList(rootIdx + 1, kept.size) else kept
        if (tree.isEmpty()) return Result(if (rootIdx >= 0) kept[rootIdx].name.trimEnd('/') + "/\n" else "", 0,
            kept.getOrNull(rootIdx)?.name, ignored)

        val px = depthFromLefts(tree.map { it.left })
        val usePixels = px.none { it < 0 } && px.toSet().size in 2..24
        val base = if (rootIdx >= 0) 1 else 0

        val out = ArrayList<String>()
        var entries = 0
        var prev = base
        tree.forEachIndexed { i, e ->
            val raw = maxOf(e.marks, if (usePixels) px[i] + 1 else 0)
            // a child can never be more than one level deeper than the line above it
            val depth = if (i == 0) base
            else if (raw > prev) minOf(raw, prev + 1)
            else raw
            prev = depth
            expandBraces(e.name).forEach { (n, extra) ->
                val clean = n.trim()
                if (clean.isNotEmpty()) {
                    out.add("    ".repeat((depth + extra).coerceIn(0, 24)) + clean)
                    entries++
                }
            }
        }
        val root = kept.getOrNull(rootIdx)?.name?.trimEnd('/')
        val head = if (root != null) root + "/\n" else ""
        val structure = head + out.joinToString("\n")

        // ---- is this really a tree?
        //
        // A tree has two independent tells: box characters drawn on the lines, or
        // names that are actually file/folder names. Source code has neither — it
        // is one long name-like line ("core/VariableResolver.kt") surrounded by
        // statements, which is exactly the OCR that produced 703 fake entries.
        val marked = kept.count { hasMark(it.raw) }
        val total = kept.size.coerceAtLeast(1)
        val fileish = kept.count { looksLikeFileName(it.name) }
        val markedShare = marked.toFloat() / total
        val fileShare = fileish.toFloat() / total
        val looksLikeTree = marked >= maxOf(1, total / 3) ||
            (total >= 2 && fileShare >= 0.6f)
        val confidence = (markedShare * 0.6f + fileShare * 0.4f).coerceIn(0f, 1f)
        val raw = lines.joinToString("\n") { it.text }
        val reason = when {
            entries == 0 -> "no project tree found in that image"
            entries > 2000 -> "that image produced $entries entries — too flat to be a tree"
            !looksLikeTree ->
                "that image is not a project tree — ${kept.size} lines, $marked drawn as a tree, " +
                    "$fileish that look like file names"
            else -> null
        }
        return Result(structure, entries, root, ignored, confidence, reason, raw)
    }

    /** Pasted text (no pixel data): depth comes from the glyphs and the indent. */
    fun parse(text: String): Result =
        parse(text.lines().map { OcrLine(it, -1f) })
}
