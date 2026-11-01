package dev.devstudio.ide.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.devstudio.ide.Engine
import dev.devstudio.ide.core.Analyzer
import dev.devstudio.ide.core.Parts
import dev.devstudio.ide.core.Planner
import dev.devstudio.ide.core.Store
import dev.devstudio.ide.core.TreeOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Build Studio — three panes, one at a time.
 *
 * The first version stacked everything into a single 4,000-pixel scroll: paste
 * fields, the plan, the build output and the preview link all lived on the same
 * page, so the log you needed was always somewhere off-screen. Now the studio is
 * three exclusive panes behind a tab strip — **Compose** (what to build), **Plan**
 * (what it will do, then the Build button) and **Log** (what actually happened) —
 * and only the selected pane is composed.
 *
 * Building switches to Log on its own: progress you can watch, never a button that
 * looks idle while something runs.
 */
@Composable
fun BuildStudio(ready: Boolean, onPreview: (String) -> Unit = {}, onClose: () -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) }              // 0 compose · 1 plan · 2 log
    var structure by remember { mutableStateOf("") }
    var parts by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<Planner.Plan?>(null) }
    var log by remember { mutableStateOf<List<Planner.LogLine>>(emptyList()) }
    var result by remember { mutableStateOf<Planner.Result?>(null) }
    var running by remember { mutableStateOf(false) }
    var ocrBusy by remember { mutableStateOf(false) }
    var ocrProg by remember { mutableStateOf("") }
    var ocrNote by remember { mutableStateOf<String?>(null) }
    var stepNow by remember { mutableStateOf("") }

    // "this is source code, not a project tree" — detected, not guessed at run time
    val structureIsCode = remember(structure) { Analyzer.isSourceCode(structure) }
    val store = remember { Store(ctx) }

    val planner = remember {
        Planner(run = { cmd -> Engine.exec(ctx, cmd, 300_000) },
                onHostFix = { cmd -> Engine.applyHostFix(cmd) })
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ocrBusy = true
        ocrProg = ""
        ocrNote = null
        TreeOcr.read(ctx, uri, onProgress = { p -> ocrProg = "strip ${p.done}/${p.total}" }) { res, err ->
            ocrBusy = false
            ocrProg = ""
            if (res != null && res.ok) {
                structure = res.structure
                ocrNote = "read ${res.entries} entries" + (res.root?.let { " under $it" } ?: "") +
                    " · ${(res.confidence * 100).toInt()}% sure it is a tree — check it"
            } else {
                // A screenshot of source code is not a tree, but it *is* the parts.
                val recovered = res?.rawText?.takeIf { it.isNotBlank() }?.let { Parts.split(it) }
                if (recovered != null && recovered.count > 0) {
                    parts = recovered.toText()
                    ocrNote = "that image was code, not a tree — moved it into the parts as " +
                        "${recovered.count} file(s): ${recovered.files.joinToString(", ")}"
                } else {
                    ocrNote = err ?: "could not read that image"
                }
            }
        }
    }

    // Every paste is split into the files it contains — 0.16.1 put the whole project
    // in one `=== paste_here.kt ===` block, which is why the plan was nonsense.
    val split = remember(parts, structure) { Parts.split(parts) }
    val fixedParts = remember(parts, split) { if (split.count > 0) split.toText() else parts }

    fun analyze() {
        // accept the split on the way in, so what the planner sees is what is shown
        val body = if (split.count > 0) split.toText() else parts
        if (body != parts) parts = body
        plan = planner.plan("project", structure, body, notes)
        log = emptyList()
        result = null
        tab = 1
    }

    Screen(
        title = "Build Studio",
        subtitle = when (tab) {
            0 -> "1 structure · 2 parts · 3 notes · then analyze"
            1 -> "the plan, before anything runs"
            else -> "what actually ran"
        },
        accent = Pal.violet,
        actions = {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Pal.surface2)
                    .clickable { onClose() }
                    .padding(horizontal = 13.dp, vertical = 7.dp)
            ) { Text("Close", color = Pal.dim, fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
        }
    ) {
        TabStrip(
            tabs = listOf("Compose", "Plan", "Log"),
            current = tab,
            enabled = listOf(true, plan != null, true),
            onPick = { tab = it }
        )

        when (tab) {
            // ------------------------------------------------------ compose
            0 -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
            ) {
                if (!ready) {
                    TCard(Modifier.fillMaxWidth(), tone = Pal.amberSoft, radius = Tok.rM,
                        border = Pal.amber.copy(alpha = 0.4f)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Engine not set up yet", color = Pal.amber, fontWeight = FontWeight.Bold,
                                fontSize = 12.sp)
                            Text("Build Studio runs real commands — set up the built-in Alpine Linux " +
                                "first (System, ~1 minute).", color = Pal.dim, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                SectionLabel("FROM A SCREENSHOT")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Pressable(
                        onClick = { picker.launch("image/*") },
                        enabled = !ocrBusy
                    ) {
                        Box(
                            Modifier.clip(RoundedCornerShape(Tok.rS)).background(Pal.surface)
                                .border(BorderStroke(1.dp, if (ocrBusy) Pal.violet else Pal.line),
                                    RoundedCornerShape(Tok.rS))
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (ocrBusy) "◌" else "▣", color = Pal.cyan, fontSize = 12.sp)
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    if (ocrBusy) "reading the image… $ocrProg"
                                    else "Read a project tree from a picture",
                                    color = Pal.text, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Text(
                    ocrNote ?: "A screenshot of a tree (├── └── │). OCR runs on the phone, nothing is " +
                        "uploaded, and depth comes from the pixels, not the glyphs.",
                    color = if (ocrNote?.contains("could not") == true) Pal.amber else Pal.faint,
                    fontSize = 9.5.sp, lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )

                SectionLabel("0 · START FROM A TEMPLATE")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Templates.all.forEach { tpl ->
                        Pressable(onClick = {
                            structure = tpl.structure.trim('\n')
                            parts = tpl.parts.trim('\n')
                            notes = tpl.notes.trim('\n')
                            log = emptyList(); result = null; plan = null
                        }) {
                            TCard(tone = Pal.surface, radius = Tok.rS) {
                                Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                                    Text(tpl.name, color = tpl.accent, fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold)
                                    Text(tpl.tag, color = Pal.faint, fontSize = 8.5.sp)
                                }
                            }
                        }
                    }
                }

                SectionLabel("1 · PROJECT STRUCTURE")
                PasteField(structure, { structure = it }, 5,
                    "myapp/\n├── app.py\n├── requirements.txt\n└── templates/index.html")

                if (structureIsCode) {
                    TCard(Modifier.fillMaxWidth().padding(vertical = 8.dp), tone = Pal.amberSoft,
                        radius = Tok.rM, border = Pal.amber.copy(alpha = 0.45f)) {
                        Column(Modifier.padding(13.dp)) {
                            Text("THAT LOOKS LIKE CODE, NOT A TREE", color = Pal.amber, fontSize = 8.5.sp,
                                letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                            Text("Step 1 wants the file tree — folders and file names. What is in there " +
                                "is source code, so any plan from it would be about the wrong thing.",
                                color = Pal.dim, fontSize = 10.sp, lineHeight = 15.sp)
                            Spacer(Modifier.height(9.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pressable(onClick = {
                                    // split it: a screenshot of code is several files,
                                    // not one blob with an invented header
                                    val moved = Parts.split(structure.trim())
                                    val text = if (moved.count > 0) moved.toText() else structure.trim()
                                    parts = (if (parts.isBlank()) "" else parts.trimEnd() + "\n\n") + text
                                    structure = ""
                                }) {
                                    Box(Modifier.clip(RoundedCornerShape(Tok.rS))
                                        .background(Pal.violet)
                                        .padding(horizontal = 11.dp, vertical = 8.dp)
                                    ) {
                                        Text("Move it to the parts", color = Pal.ink, fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold)
                                    }
                                }
                                GhostButton("Clear step 1") { structure = "" }
                            }
                        }
                    }
                }

                SectionLabel("2 · THE PARTS")
                PasteField(fixedParts, { parts = it }, 7,
                    "Paste one file, several files, or a whole project — it is split for you.")

                // What the splitter found, shown before anything is planned.
                Row(Modifier.fillMaxWidth().padding(top = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        when (split.count) {
                            0 -> "no code yet"
                            1 -> "1 file"
                            else -> "${split.count} files"
                        },
                        if (split.count > 1) Pal.mint else Pal.faint,
                        filled = split.count > 1
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (split.count == 0) "paste code below and the files appear here"
                        else split.files.joinToString(" · "),
                        color = Pal.dim, fontSize = 9.5.sp, maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (split.count > 1 || (split.count == 1 && split.parts[0].inferred)) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (split.toText() != parts) {
                            Pressable(onClick = { parts = split.toText() }, scaleDown = 0.95f) {
                                Box(Modifier.clip(RoundedCornerShape(50)).background(Pal.violetSoft)
                                    .padding(horizontal = 12.dp, vertical = 7.dp)) {
                                    Text("Write the ${split.count} file header(s)",
                                        color = Pal.violet, fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text(
                            if (split.headerCount == 0) "names inferred from the code"
                            else "${split.headerCount} header(s) found",
                            color = Pal.faint, fontSize = 9.sp,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }

                SectionLabel("3 · BUILD NOTES (optional but powerful)")
                PasteField(notes, { notes = it }, 5,
                    "Tools needed first: gcc, make\nBefore building: mkdir -p build\nBuild: make\nRun: ./app")

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        if (Engine.lastSessionUserland == "termux") "Termux userland" else "Alpine guest",
                        if (ready) Pal.mint else Pal.faint
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("real commands · nothing leaves the phone", color = Pal.faint, fontSize = 9.sp)
                }
                Spacer(Modifier.height(12.dp))
                GlowButton(
                    text = "Analyze",
                    from = Pal.cyan, to = Pal.mint,
                    enabled = (structure.isNotBlank() || parts.isNotBlank()) && !structureIsCode
                ) { analyze() }
                Spacer(Modifier.height(100.dp))
            }

            // --------------------------------------------------------- plan
            1 -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
            ) {
                val p = plan
                if (p == null) {
                    EmptyPane("No plan yet", "Fill in the structure and the parts, then press Analyze.")
                } else {
                    PlanCard(p)
                    Spacer(Modifier.height(10.dp))

                    if (p.blocked != null) {
                        TCard(Modifier.fillMaxWidth(), tone = Pal.amberSoft, radius = Tok.rM,
                            border = Pal.amber.copy(alpha = 0.45f)) {
                            Column(Modifier.padding(13.dp)) {
                                Text("NOTHING TO BUILD YET", color = Pal.amber, fontSize = 8.5.sp,
                                    letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                                Text(p.blocked, color = Pal.dim, fontSize = 10.5.sp, lineHeight = 15.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Back to compose", Modifier.weight(1f)) { tab = 0 }
                        if (p.blocked == null) {
                            Box(Modifier.weight(1f)) {
                                GlowButton(
                                    text = if (running) "Building…" else "Build it",
                                    from = Pal.mint, to = Pal.cyan,
                                    enabled = !running && ready
                                ) {
                                    running = true
                                    log = emptyList()
                                    result = null
                                    stepNow = "planning the first command"
                                    tab = 2          // watch it happen
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            planner.build(p, store.memory(),
                                                onLearn = { k, v -> store.rememberFix(k, v) })
                                        }
                                        result = res
                                        log = res.log
                                        stepNow = ""
                                        running = false
                                        store.saveRecipe(p.name, p.stack,
                                            p.steps.map { "${it.kind}|${it.cmd}" },
                                            "Saved from Build Studio")
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(100.dp))
            }

            // ---------------------------------------------------------- log
            else -> Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                if (running) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pal.mint,
                        trackColor = Pal.surface3)
                    Text("running · $stepNow", color = Pal.mint, fontSize = 10.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }

                result?.let { r -> ResultCard(r, onPreview) }

                Spacer(Modifier.height(8.dp))
                if (log.isEmpty() && !running) {
                    EmptyPane("Nothing has run yet",
                        "Compose a project, press Analyze, then Build it here so you can watch every command.")
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        TCard(Modifier.fillMaxWidth(), tone = Pal.ink, radius = Tok.rM) {
                            Column(Modifier.padding(12.dp)) {
                                log.forEach { l ->
                                    val c = when (l.level) {
                                        "cmd" -> Pal.cyan; "ok" -> Pal.mint; "err" -> Pal.rose
                                        "fix" -> Pal.amber; "stop" -> Pal.amber; "learn" -> Pal.cyan
                                        else -> Pal.dim
                                    }
                                    Text(l.text, color = c, fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(100.dp))
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ pieces */

@Composable
private fun TabStrip(
    tabs: List<String>,
    current: Int,
    enabled: List<Boolean>,
    onPick: (Int) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50)).background(Pal.surface)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        tabs.forEachIndexed { i, label ->
            val on = current == i
            val tint by androidx.compose.animation.animateColorAsState(
                if (on) Pal.ink else if (enabled[i]) Pal.dim else Pal.faint,
                Motion.fade(150), label = "tab-$i")
            Pressable(
                onClick = { if (enabled[i]) onPick(i) },
                enabled = enabled[i],
                modifier = Modifier.weight(1f),
                scaleDown = 0.95f
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (on) Brush.horizontalGradient(listOf(Pal.violet, Pal.cyan))
                            else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        )
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = tint, fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Black else FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EmptyPane(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Pal.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(body, color = Pal.faint, fontSize = 10.5.sp, lineHeight = 15.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ResultCard(r: Planner.Result, onPreview: (String) -> Unit) {
    val ok = r.ok || r.port != null
    if (!ok) return
    TCard(Modifier.fillMaxWidth().padding(top = 4.dp), tone = Pal.mintSoft, radius = Tok.rM,
        glow = Pal.mint) {
        Column(Modifier.padding(12.dp)) {
            Text(if (r.ok) "Build finished" else "Stopped, but a server may be up",
                color = Pal.mint, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            Text("${r.fixes} fix(es) applied · " + (r.port?.let { "port $it" } ?: "no port"),
                color = Pal.dim, fontSize = 10.sp)
            if (r.phases.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    r.phases.forEach { (phase, passed) ->
                        StatusPill((if (passed) "✓ " else "✗ ") + phase,
                            if (passed) Pal.mint else Pal.rose, filled = passed)
                    }
                    if (r.done) StatusPill("done", Pal.mint, filled = true)
                }
            }
            r.port?.let { port ->
                Spacer(Modifier.height(9.dp))
                GlowButton("Preview localhost:$port", from = Pal.mint, to = Pal.cyan) {
                    onPreview("http://127.0.0.1:$port")
                }
            }
        }
    }
}

@Composable
private fun PlanCard(p: Planner.Plan) {
    // the bar fills on an expressive spring instead of appearing already full
    val fill by animateFloatAsState(
        targetValue = p.confidence.toFloat(), animationSpec = Motion.expressive(), label = "confidence")
    TCard(Modifier.fillMaxWidth(), tone = Pal.surface, radius = Tok.rM) {
        Column(Modifier.padding(14.dp)) {
            Text("BEST MATCH", color = Pal.violet, fontSize = 8.5.sp, letterSpacing = 1.1.sp,
                fontWeight = FontWeight.Bold)
            Text(p.label, color = Pal.text, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Text("${(fill * 100).toInt()}% confident · ${p.files.size} file(s)" +
                (if (p.runCmd.isBlank()) " · no run step" else " · runs: ${p.runCmd}"),
                color = Pal.dim, fontSize = 10.5.sp)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).background(Pal.surface3, RoundedCornerShape(50))) {
                Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).height(6.dp)
                    .background(Brush.horizontalGradient(listOf(Pal.violet, Pal.cyan)),
                        RoundedCornerShape(50)))
            }
            Spacer(Modifier.height(10.dp))
            p.evidence.take(5).forEachIndexed { i, (pts, why) ->
                Staggered(index = i, step = 45) { m ->
                    Text("+$pts  $why", color = Pal.dim, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, modifier = m)
                }
            }
            if (p.predictions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("PREDICTED", color = Pal.faint, fontSize = 8.5.sp, letterSpacing = 1.1.sp)
                p.predictions.take(4).forEach { pr ->
                    Text("${pr.where}: ${pr.what}", color = levelColour(pr.sev), fontSize = 10.sp)
                }
            }
            if (p.issues.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("REVIEW · ${p.issues.size} issue(s)", color = Pal.faint, fontSize = 8.5.sp,
                    letterSpacing = 1.1.sp)
                p.issues.take(4).forEach { i ->
                    Text("${i.file}:${i.line} ${i.msg}", color = levelColour(i.sev), fontSize = 10.sp)
                }
            }
            if (p.steps.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("STEPS · ${p.steps.size}", color = Pal.faint, fontSize = 8.5.sp,
                    letterSpacing = 1.1.sp)
                p.steps.forEachIndexed { i, s ->
                    Text("${i + 1}. [${s.kind}] ${s.cmd}", color = Pal.text, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                    Text("     ${s.why}", color = Pal.faint, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, color = Pal.faint, fontSize = 8.5.sp, letterSpacing = 1.1.sp,
        fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PasteField(value: String, onChange: (String) -> Unit, lines: Int, hint: String) {
    // one field shape for the whole app — see Kit.kt
    HkField(
        value = value,
        onChange = onChange,
        hint = hint,
        mono = true,
        singleLine = false,
        minLines = lines,
        maxLines = lines + 8
    )
}
