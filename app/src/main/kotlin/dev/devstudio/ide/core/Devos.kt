package dev.devstudio.ide.core

/**
 * Devos — the trained system, and now the **first** thing a paste meets.
 *
 * Before 0.19.0 the UI split the text itself (`Parts.split`) and Devos only saw
 * the result, so "it's only one part" was decided by a regex before the planner
 * ever looked at it. [divide] is now the single entry point: it does the OCR
 * clean-up, lifts the tree out, cuts the files, and — the part that matters —
 * *records why each file exists* and *asks about anything it is unsure of*.
 *
 * Nothing here guesses silently. Every file carries a [Decision] with the evidence
 * that produced it and a confidence; every doubt becomes a [Question] the user can
 * read before pressing Analyze.
 */
object Devos {

    /** Why one file ended up in the intake. */
    data class Decision(
        val path: String,
        val lines: Int,
        /** "named" — a header said so; "inferred" — the code said so; "materialised" — created from the tree */
        val how: String,
        val confidence: Double,
        /** one sentence a human can check */
        val why: String
    )

    /** Something Devos is not sure about, said out loud instead of acted on. */
    data class Question(val about: String, val ask: String, val severe: Boolean = false)

    /**
     * The result of dividing one paste. Members line up with `Parts.Split` so the
     * UI can treat it as the same thing, plus the evidence.
     */
    data class Intake(
        val parts: List<Parts.Part>,
        val tree: String,
        val dropped: Int,
        val headerCount: Int,
        val decisions: List<Decision>,
        val questions: List<Question>,
        val fromScreenshot: Boolean,
        val confidence: Double
    ) {
        val count: Int get() = parts.size
        val files: List<String> get() = parts.map { it.path }
        val totalLines: Int get() = parts.sumOf { p -> p.body.lines().count { it.isNotBlank() } }
        val empty: Boolean get() = parts.isEmpty()

        /** Canonical text the planner reads: `=== path ===` then the body. */
        fun toText(): String = parts.joinToString("\n\n") { "=== ${it.path} ===\n${it.body.trimEnd()}" }

        /** Drop one file and return the canonical text. */
        fun without(index: Int): String = parts.filterIndexed { i, _ -> i != index }
            .joinToString("\n\n") { "=== ${it.path} ===\n${it.body.trimEnd()}" }

        /** Rewrite one file's body and return the canonical text. */
        fun withBody(index: Int, body: String): String = parts.mapIndexed { i, p ->
            if (i == index) p.copy(body = body) else p
        }.joinToString("\n\n") { "=== ${it.path} ===\n${it.body.trimEnd()}" }

        /** One line for a header: "4 files · app.py, main.rs, Cargo.toml, Dockerfile". */
        fun summary(): String = when (count) {
            0 -> "nothing divided yet"
            1 -> "1 file · ${parts[0].path}"
            else -> "$count files · " + files.take(3).joinToString(", ") +
                (if (count > 3) " +${count - 3}" else "")
        }

        /** True when at least one file was named by the paste rather than guessed. */
        val anyNamed: Boolean get() = decisions.any { it.how == "named" }
    }

    /** Devos divides a paste. This is the only intake path the UI uses. */
    fun divide(raw: String, ocr: Boolean = false): Intake {
        val text = raw.replace("\r\n", "\n")
        if (text.isBlank()) {
            return Intake(emptyList(), "", 0, 0, emptyList(),
                listOf(Question("the paste", "There is nothing to divide yet — paste code, a tree, " +
                    "or a screenshot of either.", severe = true)), ocr, 0.0)
        }

        val split = Parts.split(text, ocr)
        val decisions = split.parts.map { part ->
            val lines = part.body.lines().count { it.isNotBlank() }
            val how = if (part.inferred) "inferred" else "named"
            // a name in the paste is strong evidence whatever the body length; an
            // inferred name is a reading of the code, and a short body gives it little
            val confidence = when {
                how == "named" && lines > 0 -> 0.97
                how == "named" -> 0.7
                lines > 2 -> 0.68
                else -> 0.5
            }
            Decision(
                path = part.path, lines = lines, how = how, confidence = confidence,
                why = when {
                    how == "named" && lines > 2 ->
                        "the paste named it and ${lines} non-blank lines followed"
                    how == "named" ->
                        "the paste named it, but only $lines line(s) of body"
                    lines > 2 ->
                        "no name in the paste — Devos read the language and the identifiers " +
                            "(${part.lang}) and called it ${part.path}"
                    else ->
                        "no name and almost no body — Devos made up ${part.path} so nothing " +
                            "was thrown away"
                }
            )
        }

        val questions = ArrayList<Question>()

        // a name used twice is a real problem: one file would overwrite the other
        decisions.groupBy { it.path.substringAfterLast('/') }
            .filter { it.value.size > 1 }
            .forEach { (base, same) ->
                questions.add(Question(base,
                    "${same.size} files are called \"$base\" (${same.joinToString { it.path }}). " +
                        "Rename one, or they will overwrite each other in the guest.",
                    severe = true))
            }

        val inferred = decisions.count { it.how == "inferred" }
        if (inferred > 0)
            questions.add(Question("names",
                "$inferred file(s) had no name in the paste — Devos inferred them from the code. " +
                    "Tap a file to check the name before building."))

        split.parts.forEach { part ->
            val nested = part.body.lines().count { line ->
                val t = line.trim()
                t.length in 5..90 && t.startsWith("===") && t.endsWith("===")
            }
            if (nested > 0)
                questions.add(Question(part.path,
                    "${part.path} contains $nested header line(s) inside it — it may really be " +
                        "more than one file. Use Text view to split it by hand."))
        }

        if (split.tree.isNotBlank())
            questions.add(Question("tree",
            "A project tree was found and lifted out of the files. It belongs in step 1, " +
                "not inside a file — Devos put it there."))

        if (ocr && split.dropped > 0)
            questions.add(Question("screenshot",
                "${split.dropped} line(s) of chat furniture or phone status bar were dropped " +
                    "(model names, \"Thought for N seconds\", clock, battery)."))

        if (split.parts.isEmpty())
            questions.add(Question("the paste",
                "Devos could not find a file in this text. If it is one file with no name, " +
                    "put a line like \"=== main.py ===\" above it.",
                severe = true))

        decisions.filter { it.lines > 400 }.forEach {
            questions.add(Question(it.path,
                "${it.path} holds ${it.lines} lines — that is probably several files glued together."))
        }

        val base = if (decisions.isEmpty()) 0.0
        else decisions.map { it.confidence }.average()
        val penalty = questions.count { it.severe } * 0.15 + questions.count { !it.severe } * 0.04
        val confidence = (base - penalty).coerceIn(0.0, 1.0)

        return Intake(
            parts = split.parts,
            tree = split.tree,
            dropped = split.dropped,
            headerCount = split.headerCount,
            decisions = decisions,
            questions = questions,
            fromScreenshot = ocr,
            confidence = confidence
        )
    }

    /** One sentence for the top of the intake card — never boasts about a guess. */
    fun verdict(intake: Intake): String = when {
        intake.empty -> "Devos found no files in that paste."
        intake.questions.any { it.severe } ->
            "Devos divided this into ${intake.count} file(s), but something needs your eyes first."
        intake.decisions.all { it.how == "named" } ->
            "Devos divided this into ${intake.count} file(s), every one named in the paste."
        else ->
            "Devos divided this into ${intake.count} file(s) — ${intake.decisions.count { it.how == "inferred" }} " +
                "name(s) inferred from the code, all listed below."
    }
}
