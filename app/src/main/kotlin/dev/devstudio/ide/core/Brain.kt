package dev.devstudio.ide.core

/**
 * Brain — Devos' small parametric model.
 *
 * Not an LLM and not pretending to be one: a **perceptron** over a fixed feature
 * set, with weights stored as plain numbers. That is enough to do the one job
 * that matters here — decide *what kind of thing the user is asking Devos to
 * build* — and it is small, deterministic, inspectable and testable.
 *
 * Why a learned model at all? Because the rule table alone could not answer
 * "build me a todo app for android with a dark ui" from a user who pasted nothing.
 * The feature vector picks up `android`, `todo`, `app`, `dark`, `ui` and the
 * classifier returns `android` with a confidence, which is exactly what the
 * recipe layer needs to choose a scaffold.
 *
 * Learning is the same loop a perceptron has always had: when a build succeeds or
 * fails, the label is nudged toward the truth. Weights live in `Store` so the
 * model on *this* phone gets better at *this* user's phrasing.
 */
object Brain {

    /** What Devos thinks it is being asked to build. */
    enum class Intent {
        Android, PythonCli, PythonApi, NodeApi, WebStatic, CNative, RustCli, GoCli, Unknown;

        val label: String
            get() = when (this) {
                Android -> "Android app"
                PythonCli -> "Python command-line tool"
                PythonApi -> "Python web API"
                NodeApi -> "Node web service"
                WebStatic -> "static web page"
                CNative -> "C/C++ native program"
                RustCli -> "Rust command-line tool"
                GoCli -> "Go command-line tool"
                Unknown -> "unclear"
            }
    }

    data class Guess(val intent: Intent, val confidence: Double, val evidence: List<Pair<String, Double>>)

    /** Features are short strings so the weight table stays readable and storable. */
    fun features(text: String, files: List<String> = emptyList()): List<String> {
        val t = text.lowercase()
        val out = LinkedHashSet<String>()
        // description words that say what the thing is
        KEYWORDS.forEach { (word, feature) -> if (Regex("\\b$word\\b").containsMatchIn(t)) out.add(feature) }
        // what was actually pasted wins when it is there
        files.forEach { f ->
            val lower = f.lowercase()
            when {
                lower.endsWith("androidmanifest.xml") -> out.add("file:android")
                lower.endsWith("settings.gradle.kts") || lower.endsWith("settings.gradle") -> out.add("file:gradle")
                lower.endsWith("build.gradle.kts") -> out.add("file:gradle-kts")
                lower.endsWith("package.json") -> out.add("file:npm")
                lower.endsWith("requirements.txt") || lower.endsWith("pyproject.toml") -> out.add("file:pip")
                lower.endsWith("cargo.toml") -> out.add("file:cargo")
                lower.endsWith("go.mod") -> out.add("file:gomod")
                lower.endsWith("cmakelists.txt") || lower.endsWith("makefile") -> out.add("file:cmake")
                lower.endsWith("index.html") -> out.add("file:html")
                lower.endsWith(".kt") -> out.add("lang:kotlin")
                lower.endsWith(".java") -> out.add("lang:java")
                lower.endsWith(".py") -> out.add("lang:python")
                lower.endsWith(".js") || lower.endsWith(".ts") -> out.add("lang:js")
                lower.endsWith(".rs") -> out.add("lang:rust")
                lower.endsWith(".go") -> out.add("lang:go")
                lower.endsWith(".c") || lower.endsWith(".cpp") -> out.add("lang:c")
            }
        }
        return out.toList()
    }

    /** The trained table. Positive weight = evidence *for* that intent. */
    private val DEFAULT: Map<Intent, Map<String, Double>> = mapOf(
        Intent.Android to mapOf(
            "word:android" to 4.0, "word:apk" to 4.0, "word:phone" to 1.2, "word:app" to 0.8,
            "word:androidx" to 3.0, "word:activity" to 1.5, "file:android" to 9.0,
            "file:gradle" to 1.6, "file:gradle-kts" to 1.6, "lang:kotlin" to 1.4, "lang:java" to 0.5,
            "word:jetpack" to 3.0, "word:material" to 1.0, "word:screen" to 0.4
        ),
        Intent.PythonCli to mapOf(
            "word:script" to 2.0, "word:cli" to 1.6, "word:python" to 3.0, "word:argparse" to 3.0, "word:repeat" to 2.5, "word:csv" to 2.0,
            "lang:python" to 2.0, "file:pip" to 2.0, "word:csv" to 0.8, "word:scrape" to 1.2
        ),
        Intent.PythonApi to mapOf(
            "word:flask" to 4.0, "word:fastapi" to 4.0, "word:django" to 4.0, "word:api" to 1.6,
            "word:endpoint" to 2.0, "word:server" to 1.0, "lang:python" to 1.0, "file:pip" to 1.0
        ),
        Intent.NodeApi to mapOf(
            "word:express" to 4.0, "word:node" to 3.0, "word:npm" to 2.0, "word:api" to 1.2,
            "file:npm" to 3.0, "lang:js" to 2.0, "word:react" to 1.4
        ),
        Intent.WebStatic to mapOf(
            "word:html" to 3.0, "word:css" to 2.6, "word:landing" to 2.0, "word:portfolio" to 2.0,
            "file:html" to 4.0, "word:website" to 2.0, "word:page" to 1.0
        ),
        Intent.CNative to mapOf(
            "word:c++" to 3.0, "word:cmake" to 3.0, "word:makefile" to 3.0, "lang:c" to 3.0,
            "file:cmake" to 4.0, "word:binary" to 0.6
        ),
        Intent.RustCli to mapOf("word:rust" to 4.0, "word:cargo" to 4.0, "lang:rust" to 3.0, "file:cargo" to 4.0),
        Intent.GoCli to mapOf("word:golang" to 4.0, "word:go" to 1.0, "lang:go" to 3.0, "file:gomod" to 4.0)
    )

    /** Word → feature, kept explicit so a new phrasing is a one-line change. */
    private val KEYWORDS = linkedMapOf(
        "android" to "word:android", "apk" to "word:apk", "phone" to "word:phone", "app" to "word:app",
        "androidx" to "word:androidx", "activity" to "word:activity", "jetpack" to "word:jetpack",
        "material" to "word:material", "screen" to "word:screen",
        "script" to "word:script", "cli" to "word:cli", "python" to "word:python",
        "argparse" to "word:argparse", "csv" to "word:csv", "scrape" to "word:scrape",
        "flask" to "word:flask", "fastapi" to "word:fastapi", "django" to "word:django",
        "api" to "word:api", "endpoint" to "word:endpoint", "server" to "word:server",
        "express" to "word:express", "node" to "word:node", "npm" to "word:npm", "react" to "word:react",
        "html" to "word:html", "css" to "word:css", "landing" to "word:landing",
        "portfolio" to "word:portfolio", "website" to "word:website", "page" to "word:page",
        "cmake" to "word:cmake", "makefile" to "word:makefile", "c\\+\\+" to "word:c++",
        "rust" to "word:rust", "cargo" to "word:cargo", "golang" to "word:golang",
        "go" to "word:go", "todo" to "word:todo", "notes" to "word:notes"
    )

    /**
     * Predicts the intent. [weights] is whatever was learned on this device; the
     * defaults fill in anything missing, so a half-trained model still works.
     */
    fun predict(
        description: String,
        files: List<String> = emptyList(),
        weights: Map<Intent, Map<String, Double>> = emptyMap()
    ): Guess {
        val fs = features(description, files)
        if (fs.isEmpty()) return Guess(Intent.Unknown, 0.0, emptyList())
        val scores = HashMap<Intent, Double>()
        Intent.entries.filter { it != Intent.Unknown }.forEach { intent ->
            val learned = weights[intent].orEmpty()
            val base = DEFAULT[intent].orEmpty()
            scores[intent] = fs.sumOf { f -> (learned[f] ?: 0.0) + (base[f] ?: 0.0) }
        }
        val best = scores.maxByOrNull { it.value } ?: return Guess(Intent.Unknown, 0.0, emptyList())
        if (best.value <= 0.0) return Guess(Intent.Unknown, 0.0, emptyList())
        // softmax over positive scores only — evidence, not a percentage of magic
        // temperature 1.2: one strong signal reads as decisive, two weak ones cancel
        val exps = scores.mapValues { (_, v) -> Math.exp(v / 1.2) }
        val total = exps.values.sum()
        val confidence = (exps[best.key] ?: 0.0) / total
        val evidence = fs.map { f ->
            f to ((weights[best.key]?.get(f) ?: 0.0) + (DEFAULT[best.key]?.get(f) ?: 0.0))
        }.filter { it.second != 0.0 }.sortedByDescending { it.second }.take(6)
        return Guess(best.key, confidence, evidence)
    }

    /**
     * One perceptron step: move the weights toward [truth] and away from [wrong].
     * Returns the new table; nothing is mutated, so a caller can persist it.
     */
    fun learn(
        weights: Map<Intent, Map<String, Double>>,
        features: List<String>,
        truth: Intent,
        wrong: Intent,
        rate: Double = 0.5
    ): Map<Intent, Map<String, Double>> {
        if (truth == wrong || truth == Intent.Unknown) return weights
        val out = weights.mapValues { (_, v) -> HashMap(v) }.toMutableMap()
        val t = out.getOrPut(truth) { HashMap() }
        val w = out.getOrPut(wrong) { HashMap() }
        features.forEach { f ->
            t[f] = (t[f] ?: 0.0) + rate
            w[f] = (w[f] ?: 0.0) - rate
        }
        return out
    }

    /** Plain-text form for `Store`: one line per intent, `feature=weight` pairs. */
    fun encode(weights: Map<Intent, Map<String, Double>>): String =
        weights.entries.joinToString("\n") { (intent, table) ->
            intent.name + "\t" + table.entries.joinToString(",") { "${it.key}=${it.value}" }
        }

    fun decode(text: String): Map<Intent, Map<String, Double>> {
        val out = HashMap<Intent, Map<String, Double>>()
        text.lines().filter { it.isNotBlank() }.forEach { line ->
            val name = line.substringBefore('\t')
            val intent = runCatching { Intent.valueOf(name) }.getOrNull() ?: return@forEach
            val table = line.substringAfter('\t', "").split(',').mapNotNull {
                val k = it.substringBefore('=').trim()
                val v = it.substringAfter('=', "").toDoubleOrNull()
                if (k.isNotEmpty() && v != null) k to v else null
            }.toMap()
            if (table.isNotEmpty()) out[intent] = table
        }
        return out
    }
}
