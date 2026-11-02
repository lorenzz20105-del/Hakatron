package dev.devstudio.ide.core

/**
 * Parts — turn one pasted blob into the files it actually contains.
 *
 * 0.16.1 put every paste into a single `=== paste_here.kt ===` block, so a project
 * with a resolver, a model and a build file arrived as one file and the plan was
 * nonsense. Splitting is a text problem with strong signals, and this object uses
 * them in order of confidence:
 *
 *  1. **Explicit headers** — `=== path ===`, `--- path ---`, markdown fences with a
 *     path (` ```kotlin app/Foo.kt `).
 *  2. **Path + language lines** — a bare `core/VariableResolver.kt` line followed by
 *     a bare `kotlin` line. That is exactly how a chat answer pastes several files,
 *     and it is a header even though it carries no punctuation.
 *  3. **Comment headers** — `// app.js`, `# main.py`, `<!-- index.html -->`.
 *  4. **A new file starting** — a shebang, `package`, `<?php`, `<!DOCTYPE`, `FROM`
 *     in a Dockerfile, a top-level `class`/`def`/`fn`/`func`, or a language change
 *     after a blank line.
 *
 * Every part then gets a name: the header's, or one inferred from the code
 * (`package com.a.b` + `class Foo` → `Foo.kt`), or `part_<n>.<ext>` as a last
 * resort. Nothing is thrown away: text that belongs to no part becomes a part of
 * its own rather than being silently dropped.
 */
object Parts {

    data class Part(
        val path: String,
        val body: String,
        val lang: String,
        /** true when [path] was inferred from the code rather than given by a header */
        val inferred: Boolean = false
    )

    data class Split(val parts: List<Part>, val headerCount: Int, val dropped: Int = 0) {
        val count: Int get() = parts.size
        val files: List<String> get() = parts.map { it.path }

        /** The canonical text the planner reads: `=== path ===` then the body. */
        fun toText(): String = parts.joinToString("\n\n") { "=== ${it.path} ===\n${it.body.trimEnd()}" }

        /** One line for the UI: "4 files · app.py, main.rs, Cargo.toml, Dockerfile". */
        fun summary(): String = when (count) {
            0 -> "no files detected"
            1 -> "1 file · ${parts[0].path}"
            else -> "$count files · " + files.joinToString(", ")
        }
    }

    // ------------------------------------------------------------------ languages

    private val EXT = mapOf(
        "kotlin" to "kt", "java" to "java", "python" to "py", "javascript" to "js",
        "typescript" to "ts", "rust" to "rs", "go" to "go", "c" to "c", "cpp" to "cpp",
        "csharp" to "cs", "ruby" to "rb", "php" to "php", "shell" to "sh", "lua" to "lua",
        "swift" to "swift", "html" to "html", "css" to "css", "json" to "json",
        "yaml" to "yaml", "dockerfile" to "dockerfile", "plain" to "txt", "sql" to "sql"
    )

    /** Words a paste uses as a language label on their own line. */
    val LANGUAGE_WORDS = setOf(
        "kotlin", "java", "python", "py", "javascript", "js", "typescript", "ts",
        "rust", "go", "golang", "c", "cpp", "c++", "csharp", "cs", "c#", "ruby", "rb",
        "php", "bash", "sh", "shell", "zsh", "lua", "swift", "html", "css", "json",
        "yaml", "yml", "toml", "sql", "dockerfile", "text", "plaintext", "makefile"
    )

    fun extensionOf(lang: String): String = EXT[lang] ?: "txt"

    /** Best-guess language of one body of code, from its own syntax. */
    fun languageOf(body: String): String {
        val t = body
        fun has(vararg p: String) = p.any { t.contains(it) }
        return when {
            has("<!DOCTYPE html", "<html", "<div", "</body>") -> "html"
            has("<?php") -> "php"
            has("package ") && has("fun ", "val ", "import k") -> "kotlin"
            has("import java.", "public class ", "public static void main") -> "java"
            has("fn main(", "let mut ", "impl ", "use std::", "pub struct ") -> "rust"
            has("func ", "package main") && has("fmt.", ":=") -> "go"
            has("#include", "std::", "int main(") ->
                if (has("#include <iostream>", "std::")) "cpp" else "c"
            has("using System;", "namespace ", "Console.") -> "csharp"
            has("def ", "import ", "print(") || Regex("(?m)^\\s*if __name__").containsMatchIn(t) -> "python"
            has("const ", "=>", "require(", "function ") || has("import React") -> "javascript"
            has("interface ", "type ", ": string", "export const") -> "typescript"
            has("<?xml", "<manifest") -> "plain"
            has("---") && Regex("(?m)^\\w[\\w-]*:\\s").containsMatchIn(t) -> "yaml"
            has("FROM ") && has("RUN ", "COPY ") -> "dockerfile"
            has("#!/bin/sh", "#!/bin/bash", "#!/usr/bin/env bash", "#!/data/data/com.termux") -> "shell"
            has("function ", "end", "local ") && has("echo ") -> "lua"
            t.trimStart().startsWith("{") && t.trimEnd().endsWith("}") && has("\":") -> "json"
            t.trimStart().startsWith("FROM ") -> "dockerfile"
            else -> "plain"
        }
    }

    // ------------------------------------------------------------------- splitting

    private val PATH_LINE = Regex("^[\\w.@+-][\\w./@+-]*\\.(kt|kts|java|py|js|ts|tsx|jsx|rs|go|c|h|cpp|cc|hpp|cs|rb|php|sh|bash|lua|swift|html|css|scss|json|yaml|yml|toml|xml|gradle|md|txt|sql|dockerfile|mk|ini|env)$", RegexOption.IGNORE_CASE)

    private val HEADER_EQ = Regex("^\\s*={3,}\\s*(.+?)\\s*={3,}\\s*$")
    private val HEADER_DASH = Regex("^\\s*-{3,}\\s*([\\w./@+-]+\\.[A-Za-z0-9]{1,10})\\s*-{3,}\\s*$")
    private val HEADER_COMMENT = Regex("^\\s*(?://|#|/\\*|<!--|--|;)\\s*([\\w./@+-]+\\.[A-Za-z0-9]{1,10})\\s*(?:\\*/|-->)?\\s*$")
    private val FENCE = Regex("^\\s*```\\s*([\\w+#.-]*)\\s*([\\w./@+-]*\\.[A-Za-z0-9]{1,10})?\\s*$")

    /** Lines that begin a new file when they appear at the top of a block. */
    private val STARTS = listOf(
        "#!/", "<!DOCTYPE", "<?php", "<?xml", "package ", "import ", "from ", "using ",
        "#include", "const ", "let ", "var ", "function ", "class ", "public ", "private ",
        "struct ", "fn ", "func ", "def ", "module ", "namespace ", "use ", "impl ",
        "FROM ", "RUN ", "COPY ", "services:", "version:", "[package]", "[dependencies]"
    )

    fun split(raw: String): Split {
        val text = raw.replace("\r\n", "\n").trim('\n')
        if (text.isBlank()) return Split(emptyList(), 0)

        // 1 — explicit `=== path ===` headers
        val explicit = sections(text, HEADER_EQ)
        if (explicit.size > 1) return finish(explicit.map { it.first to it.second }, explicit.size)

        // 2 — markdown fences that name a path
        val fenced = fencedSections(text)
        if (fenced.size > 1) return finish(fenced, fenced.count { it.first != null })

        // 3/4 — headers by shape, then by "a new file starts here"
        val blocks = heuristic(text)
        val headers = blocks.count { it.first != null }
        if (blocks.size <= 1) {
            val body = blocks.firstOrNull()?.second ?: text
            return finish(listOf((null to body)), headers)
        }
        return finish(blocks, headers)
    }

    /** Splits on `=== path ===` style markers; the text before the first one is kept. */
    private fun sections(text: String, marker: Regex): List<Pair<String?, String>> {
        val lines = text.lines()
        val out = ArrayList<Pair<String?, String>>()
        var name: String? = null
        val buf = StringBuilder()
        var seenHeader = false
        lines.forEach { line ->
            val m = marker.matchEntire(line)
            if (m != null) {
                if (buf.isNotBlank() || name != null) out.add(name to buf.toString())
                buf.clear()
                name = cleanName(m.groupValues[1])
                seenHeader = true
            } else buf.appendLine(line)
        }
        if (buf.isNotBlank() || name != null) out.add(name to buf.toString())
        if (!seenHeader) return listOf(null to text)
        // the text before the first header is only interesting if it has content
        return out.filter { it.first != null || it.second.isNotBlank() }
    }

    private fun fencedSections(text: String): List<Pair<String?, String>> {
        val out = ArrayList<Pair<String?, String>>()
        var name: String? = null
        val buf = StringBuilder()
        var inside = false
        text.lines().forEach { line ->
            val m = FENCE.matchEntire(line)
            if (m != null) {
                if (inside) {
                    out.add(name to buf.toString()); buf.clear(); name = null; inside = false
                } else {
                    inside = true
                    name = m.groupValues[2].takeIf { it.isNotBlank() }
                }
            } else if (inside) buf.appendLine(line)
        }
        if (buf.isNotBlank()) out.add(name to buf.toString())
        return out.filter { it.second.isNotBlank() }
    }

    /**
     * The workhorse. Walks the lines and cuts a new block whenever a header appears
     * or a new file plainly starts.
     */
    private fun heuristic(text: String): List<Pair<String?, String>> {
        val lines = text.lines()
        val out = ArrayList<Pair<String?, String>>()
        var name: String? = null
        var body = StringBuilder()
        var currentLang = "plain"
        var blankRun = 0

        fun cut() {
            if (body.isNotBlank() || name != null) out.add(name to body.toString().trim('\n'))
            body = StringBuilder()
            name = null
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // ---- header: a bare path line, optionally followed by a bare language line
            if (trimmed.isNotEmpty() && PATH_LINE.matches(trimmed) && !looksLikeCode(trimmed)) {
                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                val langLine = next.lowercase() in LANGUAGE_WORDS
                val prevBlank = i == 0 || lines[i - 1].isBlank()
                if (langLine || (i > 0 && prevBlank && looksLikeCode(lines.getOrNull(i + 1).orEmpty()))) {
                    cut()
                    name = cleanName(trimmed)
                    currentLang = if (langLine) normalise(next) else "plain"
                    i += if (langLine) 2 else 1
                    continue
                }
            }

            // ---- header: `--- path ---` or a comment that is only a file name
            val dash = HEADER_DASH.matchEntire(line)
            if (dash != null) { cut(); name = cleanName(dash.groupValues[1]); i++; continue }
            val comment = HEADER_COMMENT.matchEntire(line)
            if (comment != null && i + 1 < lines.size) {
                val after = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (after.isNotEmpty() && looksLikeCode(after)) {
                    cut(); name = cleanName(comment.groupValues[1]); i++; continue
                }
            }

            // ---- a new file starts here
            if (trimmed.isNotEmpty() && body.isNotBlank() && blankRun > 0) {
                val startsNew = STARTS.any { trimmed.startsWith(it) }
                val langNow = languageOf(trimmed)
                val switched = startsNew && langNow != "plain" && langNow != currentLang
                if (switched || (startsNew && isWholeFileStart(trimmed, body.toString()))) {
                    cut()
                    currentLang = if (langNow != "plain") langNow else currentLang
                }
            }

            if (trimmed.isEmpty()) blankRun++ else blankRun = 0
            body.appendLine(line)
            i++
        }
        cut()
        return out.filter { it.second.isNotBlank() }
    }

    /**
     * A block only counts as finished when the new start token really is a new file:
     * `package x` after code, a shebang, `<!DOCTYPE`, `<?php`, or a class/def at
     * column 0 that follows a complete-looking body.
     */
    private fun isWholeFileStart(line: String, body: String): Boolean {
        if (line.startsWith("#!") || line.startsWith("<!DOCTYPE") || line.startsWith("<?php")) return true
        if (line.startsWith("package ") && body.contains("class ")) return true
        if (body.lines().count { it.isNotBlank() } < 4) return false
        return Regex("^(class|def|fn|func|public class|const \\w+ =|function \\w+)").containsMatchIn(line)
    }

    private fun looksLikeCode(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("//") || t.startsWith("#") || t.startsWith("*")) return true
        if (t.contains('=') || t.contains('(') || t.contains('{') || t.contains(';')) return true
        if (t.endsWith(":") || t.endsWith(",") || t.endsWith(")")) return true
        return Regex("\\b(val|var|fun|def|class|import|return|function|const|let|if|for|while)\\b")
            .containsMatchIn(t)
    }

    private fun normalise(word: String): String = when (word.lowercase()) {
        "py" -> "python"; "js" -> "javascript"; "ts" -> "typescript"; "rb" -> "ruby"
        "sh", "bash", "zsh", "shell" -> "shell"; "c++" -> "cpp"; "c#" -> "csharp"
        "yml" -> "yaml"; "golang" -> "go"; "text", "plaintext" -> "plain"
        else -> word.lowercase()
    }

    private fun cleanName(raw: String): String =
        raw.trim().trim('`', '*', '=', '-', ':', ' ', '\t')
            .removePrefix("./").removePrefix("/").removeSuffix(":")

    // ---------------------------------------------------------------- naming

    private fun finish(blocks: List<Pair<String?, String>>, headers: Int): Split {
        var dropped = 0
        val cleaned = blocks.mapNotNull { (given, body) ->
            val text = body.trim('\n')
            if (text.isBlank() && given == null) return@mapNotNull null
            // A block with no header that is just prose or chat is not a file. It is
            // dropped and counted, never smuggled in as `main.cs`.
            val hasRealHeader = given != null && !isPlaceholder(given)
            if (!hasRealHeader && isProse(text)) {
                dropped++
                return@mapNotNull null
            }
            val lang = languageOf(text)
            val path = (given?.takeIf { it.isNotBlank() && !isPlaceholder(it) }
                ?: inferName(text, lang))
            Part(path, text, lang, inferred = given == null || isPlaceholder(given))
        }
        if (cleaned.isEmpty()) return Split(emptyList(), headers, dropped)
        // unique names, so two `main.kt` blocks cannot overwrite each other
        val used = HashMap<String, Int>()
        val unique = cleaned.map { p ->
            val n = (used[p.path] ?: 0) + 1
            used[p.path] = n
            if (n == 1) p else p.copy(path = addSuffix(p.path, n))
        }
        return Split(unique, headers, dropped)
    }

    /**
     * Chat furniture and prose that a screenshot of a conversation drags in with it.
     * These lines used to end up as a "file" called `main.cs` holding the whole
     * preamble, which made an 8-file paste look like one part.
     */
    private val CHAT_NOISE = listOf(
        Regex("^(V\\s+)?(deepseek|chatgpt|gemini|claude|copilot|grok)\\b", RegexOption.IGNORE_CASE),
        Regex("^thought for\\s+\\d+", RegexOption.IGNORE_CASE),
        Regex("^\\d+\\s*(copy|copies)$", RegexOption.IGNORE_CASE),
        Regex("^(copy|text|plaintext|project tree|tree|output|answer)$", RegexOption.IGNORE_CASE),
        Regex("i need all in one", RegexOption.IGNORE_CASE),
        Regex("complete package|one drop|everything:.*readme", RegexOption.IGNORE_CASE),
        Regex("^copy these into", RegexOption.IGNORE_CASE),
        Regex("^(here is|here's|sure[,!]|okay[,!]|below is)\\b", RegexOption.IGNORE_CASE),
        Regex("^(would you like|let me know|hope this helps)", RegexOption.IGNORE_CASE)
    )

    fun isChatNoise(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        return CHAT_NOISE.any { it.containsMatchIn(t) }
    }

    /**
     * A block is prose, not a file, when nothing in it is code and most lines read
     * like sentences. One or two stray words ("V deepseek") are enough to reject a
     * block that carries no structure at all.
     */
    fun isProse(body: String): Boolean {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return true
        // chat lines containing ">" or "(" look like code to a token check, so they
        // are excluded before the code count — that was why a preamble survived.
        val chatLines = lines.count { isChatNoise(it) }
        val codeLines = lines.count { !isChatNoise(it) && looksLikeCode(it) }
        if (chatLines > 0 && codeLines == 0) return true
        if (codeLines > 0) return false
        val wordyLines = lines.count { it.split(' ').size >= 4 }
        return wordyLines.toFloat() / lines.size >= 0.5f
    }

    /** `paste_here.kt` and friends are placeholders, not file names. */
    fun isPlaceholder(path: String): Boolean =
        Regex("(paste[_-]?here|untitled|file|code|snippet|part)[0-9]*\\.[A-Za-z0-9]+", RegexOption.IGNORE_CASE)
            .matches(path.trim())

    private fun addSuffix(path: String, n: Int): String {
        val dot = path.lastIndexOf('.')
        return if (dot <= 0) "$path-$n" else path.substring(0, dot) + "-" + n + path.substring(dot)
    }

    /** Names a body of code after what is in it. */
    fun inferName(body: String, lang: String = languageOf(body)): String {
        val t = body
        // whole-file shapes that name themselves
        if (Regex("(?m)^FROM\\s+\\S+").containsMatchIn(t) && t.contains("RUN ")) return "Dockerfile"
        if (t.contains("<!DOCTYPE html") || t.contains("<html")) return "index.html"
        if (lang == "plain" && Regex("(?m)^[a-zA-Z0-9_.-]+\\s*(==|>=|~=)\\s*[0-9]").containsMatchIn(t))
            return "requirements.txt"
        if (lang == "makefile" || Regex("(?m)^[\\w.-]+:\\s*$").containsMatchIn(t) && t.contains("\t"))
            return "Makefile"
        if (Regex("(?m)^\\[package\\]").containsMatchIn(t)) return "Cargo.toml"

        // a declared name is the best name
        Regex("(?m)^\\s*(?:public\\s+|private\\s+|internal\\s+|data\\s+|open\\s+|abstract\\s+)*class\\s+(\\w+)")
            .find(t)?.groupValues?.get(1)?.let { return it + "." + extensionOf(lang) }
        Regex("(?m)^\\s*(?:public\\s+)?(?:interface|enum|struct|record)\\s+(\\w+)")
            .find(t)?.groupValues?.get(1)?.let { return it + "." + extensionOf(lang) }
        Regex("(?m)^\\s*def\\s+(\\w+)\\s*\\(").find(t)?.groupValues?.get(1)
            ?.let { if (it != "main") return it + ".py" }
        Regex("(?m)^\\s*fn\\s+(\\w+)\\s*\\(").find(t)?.groupValues?.get(1)
            ?.let { if (it != "main") return it + ".rs" }
        Regex("(?m)^\\s*func\\s+(\\w+)\\s*\\(").find(t)?.groupValues?.get(1)
            ?.let { if (it != "main") return it + ".go" }

        // a package path tells us the folder, which is better than nothing
        Regex("(?m)^\\s*package\\s+([\\w.]+)").find(t)?.groupValues?.get(1)?.let { pkg ->
            val folder = pkg.replace('.', '/')
            return "$folder/Main." + extensionOf(lang)
        }
        return "main." + extensionOf(lang)
    }
}
