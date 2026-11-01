package dev.devstudio.ide.core

/**
 * Analyzer — reads a pasted project structure + pasted code and works out what
 * kind of project it is, what must be installed, and how to run it.
 *
 * Ported 1:1 from the tested Python prototype (ide/smart/analyzer.py). The
 * Python version scored 27/27 on stack detection; this keeps the same weights,
 * the same alias tables and the same honest confidence formula.
 */
object Analyzer {

    val EXT_LANG = mapOf(
        "py" to "python", "pyw" to "python",
        "js" to "node", "mjs" to "node", "cjs" to "node", "jsx" to "node",
        "ts" to "node", "tsx" to "node", "vue" to "node", "svelte" to "node",
        "go" to "go", "rs" to "rust", "c" to "c", "h" to "c", "cpp" to "cpp", "cc" to "cpp",
        "java" to "jvm", "kt" to "kotlin", "php" to "php", "rb" to "ruby",
        "ipynb" to "notebook", "sh" to "shell", "bash" to "shell",
        "html" to "web", "css" to "web"
    )

    private val MANIFESTS = mapOf(
        "requirements.txt" to ("python" to 40), "pyproject.toml" to ("python" to 40),
        "setup.py" to ("python" to 35), "pipfile" to ("python" to 35),
        "package.json" to ("node" to 40), "tsconfig.json" to ("node" to 30),
        "deno.json" to ("node" to 35), "bun.lockb" to ("node" to 35),
        "go.mod" to ("go" to 45), "cargo.toml" to ("rust" to 45),
        "pom.xml" to ("jvm" to 45), "build.gradle" to ("jvm" to 40), "build.gradle.kts" to ("jvm" to 40),
        "composer.json" to ("php" to 45), "gemfile" to ("ruby" to 45),
        "cmakelists.txt" to ("c" to 35), "makefile" to ("c" to 30)
    )

    val PY_ALIAS = mapOf(
        "pil" to "pillow", "cv2" to "opencv-python", "yaml" to "pyyaml", "bs4" to "beautifulsoup4",
        "sklearn" to "scikit-learn", "dotenv" to "python-dotenv", "jwt" to "pyjwt",
        "dateutil" to "python-dateutil", "serial" to "pyserial", "flask_cors" to "flask-cors",
        "flask_sqlalchemy" to "flask-sqlalchemy", "flask_login" to "flask-login",
        "django_filters" to "django-filter", "mysql" to "mysql-connector-python",
        "postgresql" to "psycopg2-binary", "telegram" to "python-telegram-bot",
        "discord" to "discord.py", "crypto" to "pycryptodome", "attr" to "attrs",
        "docx" to "python-docx", "pptx" to "python-pptx", "fitz" to "pymupdf"
    )

    val STDLIB = setOf(
        "os", "sys", "re", "json", "time", "datetime", "math", "random", "typing", "pathlib",
        "subprocess", "threading", "collections", "itertools", "functools", "socket", "ssl",
        "http", "urllib", "sqlite3", "csv", "io", "shutil", "tempfile", "logging", "unittest",
        "asyncio", "dataclasses", "enum", "hashlib", "base64", "uuid", "glob", "argparse",
        "traceback", "string", "copy", "pickle", "signal", "struct", "textwrap", "warnings",
        "abc", "contextlib", "importlib", "inspect", "statistics", "platform", "__future__"
    )

    private val NODE_BUILTIN = setOf(
        "fs", "path", "os", "http", "https", "util", "events", "crypto", "child_process",
        "url", "querystring", "stream", "zlib", "assert", "net", "tls", "dns", "buffer",
        "timers", "worker_threads"
    )

    val STACK_LABEL = mapOf(
        "notebook" to "Jupyter notebook", "python" to "Python project", "node" to "Node.js project",
        "go" to "Go project", "rust" to "Rust project", "c" to "C project", "cpp" to "C++ project",
        "jvm" to "JVM project", "kotlin" to "Kotlin project", "php" to "PHP project",
        "ruby" to "Ruby project", "shell" to "Shell scripts", "web" to "Static web page",
        "unknown" to "Unknown project"
    )

    data class Parsed(val files: List<String>, val dirs: List<String>)

    /** "myapp/\n├── app.py\n└── templates/index.html" -> file + dir list. */
    fun readStructure(text: String): Parsed {
        val files = ArrayList<String>()
        val dirs = ArrayList<String>()
        var stack = ArrayList<String>()
        // Indentation is described by *rank*, not by a fixed 3-space unit:
        // screenshots and hand-made trees indent by 2, 3, 4 or 6 spaces and a
        // hard-coded divisor silently flattened every deep directory.
        val raws = text.lines().map { it.replace("\u00A0", " ") }
        val indents = raws.map { raw ->
            raw.replace("│", " ").replace("├──", " ").replace("└──", " ").replace("─", " ").trimEnd()
        }.filter { it.isNotBlank() }
            .map { it.length - it.trimStart().length }.distinct().sorted()
        fun levelOf(indent: Int): Int =
            indents.indexOf(indent).let { if (it >= 0) it else indents.count { s -> s < indent } }
        raws.forEach { raw ->
            var line = raw.replace("│", " ").replace("├──", " ")
                .replace("└──", " ").replace("─", " ").trimEnd()
            if (line.isBlank()) return@forEach
            val depth = levelOf(line.length - line.trimStart().length)
            var name = line.trim().split(Regex("\\s{2,}")).last().trim().trim('/')
            name = name.replace(Regex("\\s*#.*$"), "").trim()
            if (name.isBlank() || name == "." || name == "..") return@forEach
            stack = ArrayList(stack.take(depth).toList())
            if (!name.contains(".")) {
                val path = (stack + name).joinToString("/")
                dirs.add(path)
                stack = ArrayList(stack + name)
            } else {
                files.add((stack + name).joinToString("/"))
            }
        }
        return Parsed(files, dirs)
    }

    private val HEADER_PATTERNS = listOf(
        Regex("^===\\s*(.+?)\\s*===$"),
        Regex("^---\\s*(.+?)\\s*---$"),
        Regex("^#\\s*FILE:\\s*(.+)$", RegexOption.IGNORE_CASE),
        Regex("^//\\s*FILE:\\s*(.+)$", RegexOption.IGNORE_CASE),
        Regex("^```[\\w+-]*\\s+([\\w./-]+\\.[\\w]+)\\s*$")
    )

    /** One big paste split on "=== path ===" headers into path -> content. */
    fun readBundle(text: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        var cur: String? = null
        val buf = StringBuilder()
        text.lines().forEach { raw ->
            val line = raw.trim()
            val hit = HEADER_PATTERNS.firstNotNullOfOrNull { rx -> rx.find(line)?.groupValues?.get(1)?.trim() }
            if (hit != null) {
                cur?.let { out[it] = buf.toString().trim() + "\n" }
                cur = hit
                buf.setLength(0)
            } else if (cur != null) {
                if (line != "```") buf.append(raw).append('\n')
            }
        }
        cur?.let { out[it] = buf.toString().trim() + "\n" }
        return out
    }

    private fun importsOf(path: String, content: String): Pair<String?, Set<String>> {
        val ext = path.substringAfterLast('.', "").lowercase()
        val lang = EXT_LANG[ext] ?: return null to emptySet()
        val found = HashSet<String>()
        when (lang) {
            "python" -> Regex("^\\s*(?:from|import)\\s+([A-Za-z_][\\w.]*)", RegexOption.MULTILINE)
                .findAll(content).forEach { found.add(it.groupValues[1].split(".")[0].lowercase()) }
            "node" -> {
                Regex("require\\(\\s*['\"]([^'\"./][^'\"]*)['\"]").findAll(content)
                    .forEach { found.add(it.groupValues[1].lowercase()) }
                Regex("from\\s+['\"]([^'\"./][^'\"]*)['\"]").findAll(content)
                    .forEach { found.add(it.groupValues[1].lowercase()) }
            }
            "go" -> Regex("^\\s*\"([\\w./-]+)\"", RegexOption.MULTILINE).findAll(content)
                .forEach { found.add(it.groupValues[1].split("/")[0].lowercase()) }
            "rust" -> Regex("^\\s*use\\s+([\\w:]+)", RegexOption.MULTILINE).findAll(content)
                .forEach { found.add(it.groupValues[1].split(":")[0].lowercase()) }
        }
        return lang to found
    }

    data class Plan(
        val stack: String, val label: String, val confidence: Double,
        val evidence: List<Pair<Int, String>>, val pip: List<String>, val npm: List<String>,
        val run: String, val port: Int?, val files: List<String>, val dirs: List<String>,
        val contents: Map<String, String>
    )

    fun localModules(files: List<String>): Set<String> {
        val names = HashSet<String>()
        files.forEach { f ->
            val base = f.substringAfterLast('/')
            if (base.endsWith(".py")) names.add(base.removeSuffix(".py"))
            val dir = f.substringBeforeLast('/', "")
            if (dir.isNotBlank()) names.add(dir.substringAfterLast('/'))
        }
        return names
    }

    fun analyze(filesIn: List<String>, dirs: List<String>, contents: Map<String, String>): Plan {
        val files = (filesIn + contents.keys).distinct().sorted()
        val score = HashMap<String, Int>()
        val extCount = HashMap<String, Int>()
        val evidence = ArrayList<Pair<Int, String>>()
        val pip = ArrayList<String>()
        val npm = ArrayList<String>()

        fun add(lang: String, pts: Int, why: String) {
            score[lang] = (score[lang] ?: 0) + pts
            evidence.add(pts to why)
        }

        files.forEach { path ->
            val ext = path.substringAfterLast('.', "").lowercase()
            val base = path.substringAfterLast('/').lowercase()
            MANIFESTS[base]?.let { (lang, pts) -> add(lang, pts, "$base present") }
            EXT_LANG[ext]?.let { lang ->
                score[lang] = (score[lang] ?: 0) + 12
                extCount[lang] = (extCount[lang] ?: 0) + 1
            }
        }
        extCount.forEach { (lang, n) -> evidence.add((n * 12) to "$n source file(s): $lang") }

        val declared = HashSet<String>()
        contents["requirements.txt"]?.lines()?.forEach { l ->
            val name = l.trim().split(Regex("[<>=!~\\[; ]")).firstOrNull()?.lowercase().orEmpty()
            if (name.isNotBlank() && !name.startsWith("#")) declared.add(name.replace('_', '-'))
        }
        contents["package.json"]?.let { js ->
            Regex("\"(dependencies|devDependencies)\"\\s*:\\s*\\{([^}]*)\\}").findAll(js).forEach { m ->
                Regex("\"([^\"]+)\"").findAll(m.groupValues[2]).forEach { declared.add(it.groupValues[1].lowercase()) }
            }
        }

        val locals = localModules(files)
        val pyImports = HashSet<String>()
        val nodeImports = HashSet<String>()
        contents.forEach { (path, body) ->
            val (lang, mods) = importsOf(path, body)
            when (lang) {
                "python" -> pyImports.addAll(mods)
                "node" -> nodeImports.addAll(mods)
            }
        }
        pyImports.removeAll(STDLIB + locals)
        nodeImports.removeAll(NODE_BUILTIN)

        pyImports.sorted().forEach { mod ->
            val pkg = PY_ALIAS[mod] ?: mod.replace('_', '-')
            if (pkg.replace('_', '-') in declared || mod in declared) {
                evidence.add(4 to "'$mod' already declared -> skip")
            } else {
                pip.add(pkg)
                val alias = if (pkg != mod) " (alias)" else ""
                evidence.add(6 to "code imports '$mod' -> pip $pkg$alias")
            }
        }
        nodeImports.sorted().forEach { mod ->
            npm.add(mod)
            evidence.add(6 to "require('$mod') -> npm install $mod")
        }

        val primary = score.maxByOrNull { it.value }?.key ?: "unknown"
        val total = score.values.sum().coerceAtLeast(1)
        val pts = score[primary] ?: 1
        val confidence = minOf(0.98, (pts / (pts + 18.0)) * (0.6 + 0.4 * (pts.toDouble() / total)))

        val (run, port) = runCommand(primary, files, contents)
        return Plan(
            stack = primary, label = STACK_LABEL[primary] ?: primary,
            confidence = confidence, evidence = evidence.sortedByDescending { it.first },
            pip = pip, npm = npm, run = run, port = port,
            files = files, dirs = dirs, contents = contents
        )
    }

    fun runCommand(stack: String, files: List<String>, contents: Map<String, String>): Pair<String, Int?> {
        val all = contents.values.joinToString("\n").lowercase()
        return when (stack) {
            "python", "notebook" -> {
                val entry = files.firstOrNull { it.substringAfterLast('/') in
                    listOf("main.py", "app.py", "bot.py", "run.py") }
                    ?: files.firstOrNull { it.endsWith(".py") } ?: "main.py"
                val port = if (all.contains("flask")) 5000 else if (all.contains("django")) 8000 else null
                "python3 $entry" to port
            }
            "node" -> {
                val pkg = contents["package.json"] ?: contents.entries.firstOrNull { it.key.endsWith("package.json") }?.value
                val start = Regex("\"start\"\\s*:\\s*\"([^\"]+)\"").find(pkg ?: "")?.groupValues?.get(1)
                (start ?: run {
                    val entry = files.firstOrNull { it.substringAfterLast('/') in
                        listOf("index.js", "server.js", "app.js", "main.js", "index.ts") } ?: "index.js"
                    "node $entry"
                }) to 3000
            }
            "go" -> "go run ." to 8080
            "rust" -> "cargo run" to null
            "c", "cpp" -> {
                val src = files.firstOrNull { it.endsWith(".c") || it.endsWith(".cpp") } ?: "main.c"
                val cc = if (stack == "cpp" || src.endsWith(".cpp")) "g++" else "gcc"
                "$cc -O2 $src -o app && ./app" to null
            }
            "jvm" -> if (files.any { it.endsWith("pom.xml") }) "mvn -q package" to null
                     else "gradle build" to null
            "ruby" -> "bundle exec ruby ${files.firstOrNull { it.endsWith(".rb") } ?: "app.rb"}" to null
            "php" -> "php ${files.firstOrNull { it.endsWith(".php") } ?: "index.php"}" to null
            "shell" -> "sh ${files.firstOrNull { it.endsWith(".sh") } ?: "run.sh"}" to null
            else -> "true" to null
        }
    }
}
