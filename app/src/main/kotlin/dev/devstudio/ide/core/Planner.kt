package dev.devstudio.ide.core

/**
 * Planner — turns "structure + parts (+ notes)" into an ordered plan, predicts
 * what will break, then builds it step by step, fixing as it goes.
 *
 * Everything here runs against the REAL guest through Engine.exec — the same
 * rules that scored 23/27 builds and 0 unexpected failures in the Python
 * prototype (ide/smart/).
 */
class Planner(
    private val run: (String) -> String,
    /** host-side remedies (PROOT_*, environment of the launcher) — applied where they work */
    private val onHostFix: (String) -> Boolean = { false }
) {

    data class Step(val kind: String, val cmd: String, val why: String, val source: String = "inferred")
    data class Issue(val file: String, val sev: String, val line: Int, val msg: String)
    data class Prediction(val sev: String, val where: String, val what: String, val fix: String)
    data class LogLine(val level: String, val text: String)

    data class Plan(
        val name: String,
        val stack: String,
        val label: String,
        val confidence: Double,
        val evidence: List<Pair<Int, String>>,
        val steps: List<Step>,
        val predictions: List<Prediction>,
        val issues: List<Issue>,
        val pip: List<String>,
        val npm: List<String>,
        val files: List<String>,
        val contents: Map<String, String>,
        val port: Int?,
        val runCmd: String,
        val ecosystem: String?,
        /** set when the plan must not be executed, with the reason a human can act on */
        val blocked: String? = null
    )

    // ---------------------------------------------------------------- notes
    /** "Tools needed first: gcc, make" / "Build: make" / "Run: ./app" — tolerant. */
    fun parseNotes(text: String): Map<String, MutableList<String>> {
        val out = HashMap<String, MutableList<String>>()
        text.lines().forEach { raw ->
            val line = raw.trim().removePrefix("-").removePrefix("#").trim()
            if (line.isBlank()) return@forEach
            val m = Regex("^([A-Za-z0-9 _/-]{2,24})\\s*[:=]\\s*(.+)$").find(line)
            if (m != null) {
                val key = m.groupValues[1].lowercase().trim()
                val value = m.groupValues[2].trim()
                val bucket = when {
                    key.startsWith("tool") || key.startsWith("require") || key.startsWith("need") ||
                        key.startsWith("prereq") || key.startsWith("install first") -> "tools"
                    key.startsWith("before") || key.startsWith("prepare") || key.startsWith("setup") -> "before"
                    key.startsWith("build") || key.startsWith("compile") -> "build"
                    key.startsWith("test") || key.startsWith("check") -> "test"
                    key.startsWith("run") || key.startsWith("start") || key.startsWith("serve") -> "run"
                    key.startsWith("env") || key.startsWith("export") -> "env"
                    key.startsWith("note") || key.startsWith("warning") -> "note"
                    else -> null
                }
                if (bucket != null) out.getOrPut(bucket) { ArrayList() }.add(value)
            }
        }
        return out
    }

    private fun installCmds(tools: List<String>): List<Step> {
        val apk = tools.map { t -> t.trim().lowercase() }
            .map { it to APK_TOOLS[it] }
            .filter { it.second != null }
            .map { it.second!! }
            .distinct()
        if (apk.isEmpty()) return emptyList()
        return listOf(Step("install", "apk add --no-cache ${apk.joinToString(" ")}",
            "tools your notes said are needed first", "notes"))
    }

    // ------------------------------------------------------------ ecosystems
    private fun detectEcosystem(files: List<String>, contents: Map<String, String>): Pair<String, List<Step>>? {
        fun any(ext: String) = files.any { it.endsWith(ext) }
        return when {
            any("CMakeLists.txt") -> "cmake" to listOf(
                Step("install", "apk add --no-cache cmake build-base samurai", "CMake project", "inferred"),
                Step("build", "cmake -S . -B build -G Ninja", "CMake convention", "inferred"),
                Step("build", "cmake --build build", "CMake convention", "inferred"),
                Step("run", "./build/${files.firstOrNull { it.endsWith(".c") || it.endsWith(".cpp") }
                    ?.substringAfterLast('/')?.substringBeforeLast('.') ?: "app"}", "built binary", "inferred"))
            any("pom.xml") -> "maven" to listOf(
                Step("install", "apk add --no-cache maven openjdk17", "Maven project", "inferred"),
                Step("build", "mvn -q package", "Maven convention", "inferred"))
            any("build.gradle") || any("build.gradle.kts") -> "gradle" to listOf(
                Step("install", "apk add --no-cache gradle openjdk17", "Gradle project", "inferred"),
                Step("build", "gradle build", "Gradle convention", "inferred"))
            any("Gemfile") -> "ruby" to listOf(
                Step("install", "apk add --no-cache ruby ruby-bundler ruby-dev build-base", "Ruby project", "inferred"),
                Step("install", "bundle install", "Gemfile present", "inferred"),
                Step("run", "bundle exec ruby ${files.firstOrNull { it.endsWith(".rb") } ?: "app.rb"}",
                    "Ruby entry point", "inferred"))
            any("deno.json") -> "deno" to listOf(
                Step("install", "apk add --no-cache deno", "Deno project", "inferred"),
                Step("run", "deno run --allow-all ${files.firstOrNull { it.endsWith(".ts") } ?: "main.ts"}",
                    "Deno needs explicit permissions", "inferred"))
            any(".ipynb") -> "notebook" to listOf(
                Step("install", "apk add --no-cache python3 py3-pip", "notebook", "inferred"),
                Step("install", "pip install --break-system-packages nbconvert ipykernel", "notebook tooling", "inferred"),
                Step("build", "jupyter nbconvert --to script *.ipynb", "convert cells to a script", "inferred"))
            else -> null
        }
    }

    // ----------------------------------------------------------------- plan
    fun plan(name: String, structure: String, parts: String, notes: String): Plan {
        val parsed = Analyzer.readStructure(structure)
        val contents: Map<String, String> = if (parts.contains("===") || parts.contains("```"))
            Analyzer.readBundle(parts) else partsToMap(parsed.files, parts)
        val base = Analyzer.analyze(parsed.files, parsed.dirs, contents)

        val noteMap = parseNotes(notes)
        val steps = ArrayList<Step>()
        val eco = detectEcosystem(base.files, contents)

        if (noteMap["tools"] != null) steps.addAll(installCmds(noteMap["tools"]!!))
        noteMap["before"]?.forEach { steps.add(Step("prepare", it, "from your notes (before build)", "notes")) }
        if (steps.isEmpty() && eco != null) steps.addAll(eco.second)
        noteMap["build"]?.forEach { steps.add(Step("build", it, "from your notes", "notes")) }
        noteMap["test"]?.forEach { steps.add(Step("test", it, "from your notes", "notes")) }
        noteMap["run"]?.forEach { steps.add(Step("run", it, "from your notes", "notes")) }

        // A plan with nothing to write is not a plan: 0.14.0 happily "built" an empty
        // project and ran `true`, which then failed with a driver error that had
        // nothing to do with the user's code.
        val nothingToDo = contents.isEmpty() && parsed.files.isEmpty()
        if (!nothingToDo && steps.none { it.kind == "run" && base.run.isNotBlank() && base.run != "true" }) {
            if (!steps.any { it.kind == "install" } && base.pip.isNotEmpty()) {
                // `-r requirements.txt` only when that file actually lists something;
                // an empty one would install nothing and leave the imports broken.
                val req = base.files.firstOrNull { it.endsWith("requirements.txt") }
                val reqBody = req?.let { contents[it] ?: contents.entries
                    .firstOrNull { e -> e.key.endsWith("requirements.txt") }?.value } ?: ""
                val usesFile = !req.isNullOrBlank() && reqBody.isNotBlank()
                steps.add(0, Step("install",
                    if (usesFile) "pip install --break-system-packages -r requirements.txt"
                    else "pip install --break-system-packages ${base.pip.joinToString(" ")}",
                    if (usesFile) "python dependencies declared in requirements.txt"
                    else "python dependencies found by reading the imports"))
            }
            if (base.npm.isNotEmpty() && base.files.any { it.endsWith("package.json") }) {
                steps.add(Step("install", "npm install", "node dependencies in package.json"))
            }
            steps.add(Step("run", base.run, "inferred from structure"))
        }

        // --- Devos v10: the project already says how it is built and tested.
        // package.json "scripts", a Makefile and CTest are declarations, not
        // guesses — reading them beats inventing commands.
        val pkgScripts = contents.entries.firstOrNull { it.key.endsWith("package.json") }
            ?.value?.let { body -> Regex("\"scripts\"\\s*:\\s*\\{([^}]*)}").find(body)?.groupValues?.get(1) }
            ?: ""
        fun script(name: String) =
            Regex("\"" + name + "\"\\s*:\\s*\"").containsMatchIn(pkgScripts)
        val cmakeBody = contents.entries.firstOrNull { it.key.endsWith("CMakeLists.txt") }?.value ?: ""
        val makefile = contents.entries.firstOrNull { it.key.lowercase().endsWith("makefile") }?.value

        if (script("build") && steps.none { it.kind == "build" })
            steps.add(Step("build", "npm run build",
                "package.json declares a build script — that IS the build", "inferred"))
        if (script("test") && steps.none { it.kind == "test" })
            steps.add(Step("test", "npm test", "package.json declares a test script", "inferred"))
        if (script("start")) {
            val i = steps.indexOfFirst { it.kind == "run" && it.source != "notes" }
            if (i >= 0) steps[i] = Step("run", "npm start", "package.json declares a start script", "inferred")
        }
        if (makefile != null && steps.none { it.kind == "build" } && cmakeBody.isBlank()) {
            val targets = Regex("(?m)^([A-Za-z0-9_.-]+)\\s*:").findAll(makefile)
                .map { it.groupValues[1] }.filter { it != ".PHONY" }.toList()
            val goal = if ("all" in targets) "all" else targets.firstOrNull() ?: ""
            val at = steps.indexOfFirst { it.kind == "run" }.let { if (it < 0) steps.size else it }
            steps.add(at, Step("build", ("make " + goal).trim(),
                "the project ships a Makefile — use it instead of guessing", "inferred"))
            if ("test" in targets && steps.none { it.kind == "test" })
                steps.add(at + 1, Step("test", "make test", "the Makefile has a test target", "inferred"))
        }
        if (cmakeBody.isNotBlank() && Regex("enable_testing\\(|add_test\\(").containsMatchIn(cmakeBody)
            && steps.none { it.kind == "test" }) {
            val at = steps.indexOfFirst { it.kind == "run" }.let { if (it < 0) steps.size else it }
            steps.add(at, Step("test", "ctest --test-dir build --output-on-failure",
                "CMakeLists declares tests — CTest runs them", "inferred"))
        }

        val predictions = predict(base, contents, steps)
        val issues = review(contents)
        val keptSteps = steps.filterNot { it.cmd.isBlank() || it.cmd == "true" }
        val blocked = when {
            nothingToDo ->
                "Nothing to build: no files and no tree were read. Paste the tree in step 1 " +
                "and the code in step 2 (or use \u201cRead a project tree from a picture\u201d)."
            contents.isEmpty() && parsed.files.isNotEmpty() ->
                "The tree lists ${parsed.files.size} file(s) but none of their contents were pasted, " +
                "so there is nothing to compile. Add them in step 2, or build just to create the folders."
            keptSteps.isEmpty() ->
                "No build command could be inferred from this project, so nothing is run. " +
                "Add a \u201cBuild:\u201d line to step 3 and it will be used exactly as written."
            else -> null
        }
        return Plan(name = name.ifBlank { base.label }, stack = base.stack, label = base.label,
            confidence = base.confidence, evidence = base.evidence, steps = keptSteps,
            predictions = predictions, issues = issues, pip = base.pip, npm = base.npm,
            files = base.files, contents = contents, port = base.port, runCmd = base.run,
            ecosystem = eco?.first, blocked = blocked)
    }

    private fun partsToMap(files: List<String>, parts: String): Map<String, String> =
        if (files.size == 1 && parts.isNotBlank()) mapOf(files[0] to parts) else emptyMap()

    private fun predict(base: Analyzer.Plan, contents: Map<String, String>,
                        steps: List<Step>): List<Prediction> {
        val out = ArrayList<Prediction>()
        // imports that point at a file nobody pasted
        base.files.filter { it.endsWith(".py") }.forEach { f ->
            val body = contents[f] ?: return@forEach
            Regex("^(?:from|import)\\s+([\\w.]+)", RegexOption.MULTILINE).findAll(body).forEach { m ->
                val target = m.groupValues[1].split(".")[0]
                if (target in Analyzer.STDLIB) return@forEach
                val local = base.files.any { it.endsWith("$target.py") || it.contains("/$target/") }
                val pkgDir = base.files.any { it.contains("/$target/") }
                if (!local && pkgDir) out.add(Prediction("high", f,
                    "imports '$target' but the package has no such module pasted", "paste it or create it"))
            }
        }
        base.pip.forEach { out.add(Prediction("info", "dependencies", "$it must be installed first",
            "pip install $it")) }
        if (base.confidence < 0.55) out.add(Prediction("medium", "stack",
            "the stack is a guess (${(base.confidence * 100).toInt()}% confident)", "pick the stack manually"))
        return out
    }

    /** Cheap, reliable checks — no false positives dressed up as findings. */
    private fun review(contents: Map<String, String>): List<Issue> {
        val out = ArrayList<Issue>()
        contents.forEach { (path, body) ->
            if (path.endsWith(".py")) {
                body.lines().forEachIndexed { i, l ->
                    if (Regex("^\\s*except\\s*:").containsMatchIn(l))
                        out.add(Issue(path, "medium", i + 1, "bare except swallows real errors"))
                    if (Regex("==\\s*None").containsMatchIn(l))
                        out.add(Issue(path, "low", i + 1, "== None — use 'is None'"))
                    if (Regex("\\beval\\(|\\bexec\\(").containsMatchIn(l))
                        out.add(Issue(path, "high", i + 1, "eval/exec on untrusted input is RCE"))
                    if (Regex("(?i)(api[_-]?key|secret|token)\\s*=\\s*['\"][^'\"]{8,}").containsMatchIn(l))
                        out.add(Issue(path, "high", i + 1, "hardcoded secret — move it to .env"))
                }
            }
            if (path.endsWith(".js") || path.endsWith(".ts")) {
                body.lines().forEachIndexed { i, l ->
                    if (Regex("[^=!<>]==[^=]").containsMatchIn(l))
                        out.add(Issue(path, "low", i + 1, "loose equality — use ==="))
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- build
    data class Result(val ok: Boolean, val log: List<LogLine>, val fixes: Int,
                      val stopped: String?, val port: Int?, val gotoLine: Int?,
                      val phases: Map<String, Boolean> = emptyMap()) {
        /** done means every phase the plan actually has, passed */
        val done: Boolean get() = ok && phases.isNotEmpty() && phases.values.all { it }
    }

    fun build(plan: Plan, memory: MutableMap<String, String> = HashMap(),
              onLearn: (String, String) -> Unit = { _, _ -> }, maxAttempts: Int = 4): Result {
        val log = ArrayList<LogLine>()
        plan.blocked?.let { why ->
            log.add(LogLine("stop", why))
            return Result(false, log, 0, why, plan.port, null)
        }
        var fixes = 0
        var port = plan.port
        val env = HashMap<String, String>()
        val passed = LinkedHashSet<String>()
        val work = HashMap(plan.contents)          // config patches rewrite these
        var lastFix = ""
        var sameFix = 0

        // write the files first — the guest needs them before anything runs
        writeProject(plan.name, work)
        log.add(LogLine("info", "wrote ${work.size} file(s) into ~/projects/${plan.name}"))

        plan.steps.forEach { step ->
            var cmd = step.cmd
            var attempt = 0
            while (attempt < maxAttempts) {
                attempt++
                val full = (env.entries.joinToString(" ") { "${it.key}=${it.value}" } + " " + cmd).trim()
                log.add(LogLine("cmd", "$ ${step.kind}: $full"))
                val out = run("cd ~/projects/${plan.name} && $full")
                val exit = Regex("\\[exit (-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (exit == 0) {
                    passed.add(step.kind)
                    log.add(LogLine("ok", out.lines().firstOrNull { it.isNotBlank() } ?: "ok"))
                    break
                }
                val errLine = out.lines().lastOrNull { it.isNotBlank() && !it.startsWith("[exit") } ?: out
                log.add(LogLine("err", errLine))
                // learned first: the same error on this device is answered instantly
                val errKey = errLine.trim().take(90)
                val learnedCmd = memory[errKey]
                if (learnedCmd != null) {
                    log.add(LogLine("fix", "remembered: ${learnedCmd}"))
                    val lo = run(learnedCmd)
                    val lex = Regex("\\[exit (-?\\d+)").find(lo)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    fixes++
                    if (lex == 0) {
                        if (learnedCmd.startsWith("PORT="))
                            learnedCmd.removePrefix("PORT=").toIntOrNull()?.let {
                                port = it; env["PORT"] = it.toString()
                            }
                        continue
                    }
                    log.add(LogLine("err", "remembered fix no longer works — re-analysing"))
                }
                val fix = KnowledgeBase.planFix(out, port, plan.runCmd.split(" ").last())
                if (fix == null) {
                    log.add(LogLine("stop", "no known fix — stopped here, nothing else was touched"))
                    return Result(false, log, fixes, "unknown error", port, null)
                }
                if (fix.needsEyes) {
                    val line = Regex("line (\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
                    log.add(LogLine("stop", fix.why))
                    return Result(false, log, fixes, fix.why, port, line)
                }
                if (fix.unsupported || fix.env) {
                    log.add(LogLine("stop", fix.why))
                    return Result(false, log, fixes, fix.why, port, null)
                }
                if (fix.augment != null) {
                    cmd = "$cmd ${fix.augment}"
                    log.add(LogLine("fix", fix.why))
                    fixes++
                    continue
                }
                if (fix.cmakeInclude != null) {
                    val what = patchCmakeInclude(work, fix.cmakeInclude)
                    if (what != null) {
                        log.add(LogLine("fix", "${fix.why}  →  $what"))
                        writeProject(plan.name, work)
                        fixes++
                        continue
                    }
                    log.add(LogLine("stop", fix.why + " — no CMakeLists.txt to fix"))
                    return Result(false, log, fixes, fix.why, port, null)
                }
                // a config file is the real-world remedy for these two; the
                // user's own source is never touched
                if (fix.tsjson || fix.pkgjson) {
                    val what = if (fix.tsjson) patchTsconfig(work) else patchPackageJson(work)
                    if (what != null) {
                        log.add(LogLine("fix", "${fix.why}  →  $what"))
                        writeProject(plan.name, work)
                        fixes++
                        continue
                    }
                    log.add(LogLine("err", "no config file to patch — stopping instead of guessing"))
                    return Result(false, log, fixes, fix.why, port, null)
                }
                // Some remedies are not guest commands at all. `export PROOT_NO_SECCOMP=1`
                // run inside the guest is a no-op: the variable has to be in PRoot's own
                // environment. Asking the host to set it, then retrying the same step, is
                // what actually fixes exit 159.
                val hostish = fix.kind == "seccomp" ||
                    (fix.cmd != null && Regex("^export\\s+(PROOT_|ANDROID_)").containsMatchIn(fix.cmd))
                if (hostish && fix.cmd != null && onHostFix(fix.cmd)) {
                    log.add(LogLine("fix", "${fix.why}  →  applied to the launcher, retrying"))
                    fixes++
                    continue
                }
                if (fix.cmd != null) {
                    if (fix.cmd == lastFix && ++sameFix >= 3) {
                        log.add(LogLine("stop", "the same fix was applied 3x without changing the error — " +
                            "stopped instead of looping"))
                        return Result(false, log, fixes, "fix made no difference", port, null)
                    }
                    if (fix.cmd != lastFix) { lastFix = fix.cmd; sameFix = 1 }
                    log.add(LogLine("fix", "${fix.why}  →  ${fix.cmd}"))
                    val fixOut = run(fix.cmd)
                    val fixExit = Regex("\\[exit (-?\\d+)").find(fixOut)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    fixes++
                    if (fixExit != 0) {
                        log.add(LogLine("err", "fix failed: ${fixOut.lines().lastOrNull { it.isNotBlank() }}"))
                        return Result(false, log, fixes, "fix command failed", port, null)
                    }
                    if (!memory.containsKey(errKey)) {
                        memory[errKey] = fix.cmd
                        onLearn(errKey, fix.cmd)
                        log.add(LogLine("learn", "memorised: ${fix.why}"))
                    }
                    if (fix.kind == "port") {
                        val p = Regex("PORT=(\\d+)").find(fix.cmd)?.groupValues?.get(1)?.toIntOrNull()
                        if (p != null) { port = p; env["PORT"] = p.toString() }
                    }
                }
            }
            if (attempt >= maxAttempts) {
                log.add(LogLine("stop", "step '${step.cmd}' failed $maxAttempts times"))
                return Result(false, log, fixes, "step retries exhausted", port, null)
            }
        }
        val phases = plan.steps.map { it.kind }.distinct()
            .associateWith { it in passed }
        log.add(LogLine("ok", "build complete — ${fixes} fix(es) applied · phases " +
            phases.entries.joinToString(" ") { (k, v) -> "$k" + (if (v) "✓" else "✗") }))
        return Result(true, log, fixes, null, port, null, phases)
    }

    /**
     * CMakeLists.txt: give EVERY target the include root. A
     * target_include_directories line on the executable alone does not help the
     * libraries it links against — that is the classic modular-C++ breakage.
     */
    private fun patchCmakeInclude(work: MutableMap<String, String>, header: String): String? {
        val root = work.keys.firstOrNull { it.endsWith("/$header") }
            ?.dropLast(header.length + 1) ?: return null
        val key = work.keys.firstOrNull { it.endsWith("CMakeLists.txt") } ?: return null
        val body = work[key] ?: return null
        val names = Regex("add_(?:library|executable)\\s*\\(\\s*([A-Za-z0-9_.+-]+)")
            .findAll(body).map { it.groupValues[1] }.toList()
        if (names.isEmpty()) {
            if (Regex("(?m)^\\s*include_directories\\s*\\([^)]*\\b" +
                    Regex.escape(root) + "\\b").containsMatchIn(body)) return null
            work[key] = body.trimEnd() + "\ninclude_directories($root)\n"
            return "include_directories($root)"
        }
        val missing = names.filter { n ->
            !Regex("target_include_directories\\s*\\(\\s*" + Regex.escape(n) +
                "\\b[^)]*\\b" + Regex.escape(root) + "\\b").containsMatchIn(body)
        }
        if (missing.isEmpty()) return null
        work[key] = body.trimEnd() + "\n\n# added by Devos — your headers live under $root/\n" +
            missing.joinToString("") { "target_include_directories($it PUBLIC $root)\n" }
        return "include dirs -> $key (" + missing.joinToString() + ")"
    }

    /** tsconfig.json: set compilerOptions.rootDir to the real source dir. */
    private fun patchTsconfig(work: MutableMap<String, String>): String? {
        val key = work.keys.firstOrNull { it.endsWith("tsconfig.json") } ?: return null
        val src = work.keys.map { it.substringBefore('/', "") }
            .firstOrNull { it in setOf("src", "app", "lib", "source") } ?: "src"
        val body = work[key] ?: "{}"
        val m = Regex("\\{\\s*\"compilerOptions\"\\s*:\\s*\\{").find(body)
        val patched = if (m != null) {
            val at = m.range.last + 1
            body.substring(0, at) + "\"rootDir\":\"$src\"," + body.substring(at)
        } else if (body.trim() == "{}" || body.isBlank()) {
            "{\"compilerOptions\":{\"rootDir\":\"$src\"}}"
        } else {
            body.replaceFirst("{", "{\"compilerOptions\":{\"rootDir\":\"$src\"},")
        }
        work[key] = patched
        return "rootDir=$src in $key"
    }

    /** package.json: mark the package as ESM. */
    private fun patchPackageJson(work: MutableMap<String, String>): String? {
        val key = work.keys.firstOrNull { it.endsWith("package.json") } ?: "package.json"
        if (!work.containsKey(key)) work[key] = "{\n  \"name\": \"app\",\n  \"version\": \"1.0.0\"\n}\n"
        val body = work[key] ?: "{}"
        if (Regex("\"type\"\\s*:").containsMatchIn(body)) return null
        val patched = body.replaceFirst(Regex("^\\{\\s*"), "{\n  \"type\": \"module\",\n  ")
        work[key] = patched
        return "type=module in $key"
    }

    private fun writeProject(name: String, contents: Map<String, String>) {
        if (contents.isEmpty()) return
        val b64 = android.util.Base64.encodeToString(
            (contents.entries.joinToString("\u0000") { "${it.key}\u0001${it.value}" }).toByteArray(),
            android.util.Base64.NO_WRAP)
        run("mkdir -p ~/projects/$name && echo '$b64' | base64 -d | " +
            "awk 'BEGIN{RS=\"\\0\";FS=\"\\001\"}{if(NF>1){cmd=\"mkdir -p \\\"$(dirname \\\"\"$1\"\\\")\\\"\";system(cmd);" +
            "f=$1; $1=\"\"; print substr($0,2) > f; close(f)}}' && echo FILES-WRITTEN")
    }

    companion object {
        private val APK_TOOLS = mapOf(
            "gcc" to "build-base", "make" to "build-base", "musl-dev" to "build-base",
            "build-essential" to "build-base", "g++" to "build-base", "cmake" to "cmake",
            "python3" to "python3", "python" to "python3", "pip" to "py3-pip",
            "node" to "nodejs", "nodejs" to "nodejs", "npm" to "npm", "git" to "git",
            "curl" to "curl", "go" to "go", "rustc" to "rust", "cargo" to "cargo",
            "java" to "openjdk17", "javac" to "openjdk17", "maven" to "maven", "gradle" to "gradle",
            "php" to "php82", "composer" to "composer", "ruby" to "ruby", "sqlite3" to "sqlite"
        )
    }
}
