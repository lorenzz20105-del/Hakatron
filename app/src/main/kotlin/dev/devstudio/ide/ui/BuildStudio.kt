package dev.devstudio.ide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.devstudio.ide.core.TreeOcr
import dev.devstudio.ide.Engine
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import dev.devstudio.ide.core.Analyzer
import dev.devstudio.ide.core.Planner
import dev.devstudio.ide.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
@Composable
fun BuildStudio(ready: Boolean, onPreview: (String) -> Unit = {},
                onClose: () -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var structure by remember { mutableStateOf("") }
    var parts by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<Planner.Plan?>(null) }
    var log by remember { mutableStateOf<List<Planner.LogLine>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var setupText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Planner.Result?>(null) }
    var ocrNote by remember { mutableStateOf<String?>(null) }
    var ocrBusy by remember { mutableStateOf(false) }
    var ocrProg by remember { mutableStateOf("") }
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
                    " — check the tree, then paste the code"
            } else {
                ocrNote = err ?: "could not read that image"
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Build Studio", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("1 structure · 2 parts · 3 notes · then build", color = Dim, fontSize = 11.sp)
            }
            Text("Close", color = Cyan, fontSize = 11.sp, modifier = Modifier.clickable { onClose() })
        }
        Spacer(Modifier.height(12.dp))

        if (!ready) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A14)),
                shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Engine not set up yet", color = Amber, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    Text("Build Studio runs real commands — set up the built-in Alpine Linux first " +
                        "(System tab, ~1 minute).", color = Color(0xFFE8D6C8), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        SectionLabel("FROM A SCREENSHOT")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(11.dp), color = Surf,
                border = BorderStroke(1.dp, if (ocrBusy) Violet else LineC),
                modifier = Modifier.clickable(enabled = !ocrBusy) { picker.launch("image/*") }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ocrBusy) "◌" else "▣", color = Cyan, fontSize = 13.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(if (ocrBusy) "reading the image… $ocrProg" else "Read a project tree from a picture",
                        color = Txt, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(ocrNote ?: "Paste a screenshot of a tree (├── └── │) — OCR runs on the phone, " +
            "nothing is uploaded, and the depth comes from the pixels, not the glyphs.",
            color = if (ocrNote?.contains("could not") == true || ocrNote?.contains("no project") == true)
                Amber else Dim, fontSize = 10.sp, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 6.dp))

        SectionLabel("0 · START FROM A TEMPLATE")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Templates.all.forEach { tpl ->
                Surface(shape = RoundedCornerShape(11.dp), color = Surf,
                    border = BorderStroke(1.dp, LineC),
                    modifier = Modifier.clickable {
                        structure = tpl.structure.trim('\n')
                        parts = tpl.parts.trim('\n')
                        notes = tpl.notes.trim('\n')
                        log = emptyList(); result = null
                    }) {
                    Column(Modifier.padding(horizontal = 11.dp, vertical = 7.dp)) {
                        Text(tpl.name, color = tpl.accent, fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold)
                        Text(tpl.tag, color = Dim, fontSize = 9.sp)
                    }
                }
            }
        }
        Text("or paste your own — structure first, then the files.",
            color = Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))

        SectionLabel("1 · PROJECT STRUCTURE")
        PasteField(structure, { structure = it }, 6,
            "myapp/\n├── app.py\n├── requirements.txt\n└── templates/index.html")

        // A structure box filled with code produced "Kotlin project · 547 files" and
        // then a build that wrote nothing. Say it plainly and offer the move.
        if (structureIsCode) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1F12)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF5A4420)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(13.dp)) {
                    Text("THAT LOOKS LIKE CODE, NOT A TREE", color = Amber, fontSize = 9.sp,
                        letterSpacing = 1.1.sp)
                    Text("Step 1 wants the file tree (folders and file names). What is in there " +
                        "now is source code — imports and statements — so the plan it produces " +
                        "would be about the wrong thing.",
                        color = Color(0xFFDFCFB4), fontSize = 10.5.sp, lineHeight = 15.sp)
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = RoundedCornerShape(10.dp), color = Violet,
                            modifier = Modifier.clickable {
                                // move it to the parts box, under a === file === header
                                val body = structure.trim()
                                parts = (if (parts.isBlank()) "" else parts.trimEnd() + "\n") +
                                    "=== paste_here.kt ===\n$body"
                                structure = ""
                            }) {
                            Text("Move it to the parts", color = Color.White, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                        }
                        Surface(shape = RoundedCornerShape(10.dp), color = Color(0x22FFFFFF),
                            border = BorderStroke(1.dp, LineC),
                            modifier = Modifier.clickable { structure = "" }) {
                            Text("Clear step 1", color = Txt, fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                        }
                    }
                }
            }
        }

        SectionLabel("2 · THE PARTS")
        PasteField(parts, { parts = it }, 8,
            "=== app.py ===\nfrom flask import Flask\n...\n=== requirements.txt ===\nflask==3.0.0")
        Text("Paste file by file, or the whole project with === path === headers.",
            color = Dim, fontSize = 10.sp)

        SectionLabel("3 · BUILD NOTES (optional but powerful)")
        PasteField(notes, { notes = it }, 6,
            "Tools needed first: gcc, make\nBefore building: mkdir -p build\nBuild: make\nRun: ./app")

        // One engine, no picker: the build runs in the guest that owns this app.
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(if (Engine.lastSessionUserland == "termux") "Termux userland" else "Alpine guest",
                Pal.mint)
            Spacer(Modifier.width(7.dp))
            Text("self-fixing builds · nothing leaves the phone", color = Pal.faint, fontSize = 9.5.sp)
        }
        Spacer(Modifier.height(12.dp))
        Pressable(
            onClick = {
                plan = planner.plan("project", structure, parts, notes)
                log = emptyList()
            },
            enabled = (structure.isNotBlank() || parts.isNotBlank()) && !structureIsCode,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()
                .background(
                    if ((structure.isNotBlank() || parts.isNotBlank()) && !structureIsCode)
                        Brush.horizontalGradient(listOf(Cyan, Color(0xFF67E8F9)))
                    else Brush.horizontalGradient(listOf(Color(0xFF2A2E38), Color(0xFF2A2E38))),
                    RoundedCornerShape(50))
                .padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Text("Analyze", color = Color(0xFF04222A), fontWeight = FontWeight.Black,
                    fontSize = 14.sp)
            }
        }

        plan?.let { p ->
            Spacer(Modifier.height(14.dp))
            PlanCard(p)
            Spacer(Modifier.height(10.dp))
            if (p.blocked != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF221A12)),
                    shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Color(0xFF4A3A1E)),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp)) {
                        Text("NOTHING TO BUILD YET", color = Amber, fontSize = 9.sp, letterSpacing = 1.1.sp)
                        Text(p.blocked, color = Color(0xFFCFC3AE), fontSize = 10.5.sp, lineHeight = 15.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (p.blocked == null) Button(onClick = {
                running = true
                log = emptyList()
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        planner.build(p, store.memory(),
                            onLearn = { k, v -> store.rememberFix(k, v) })
                    }
                    result = res
                    log = res.log
                    running = false
                    store.saveRecipe(p.name, p.stack, p.steps.map { "${it.kind}|${it.cmd}" },
                        "Saved from Build Studio")
                }
            }, enabled = !running && ready, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                Text(if (running) "Building…" else "Build it", color = Color(0xFF06281C),
                    fontWeight = FontWeight.Black)
            }
        }

        result?.let { r ->
            if (r.ok || r.port != null) {
                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2B22)),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (r.ok) "Build finished" else "Stopped, but a server may be up",
                            color = Color(0xFF7BF3BE), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${r.fixes} fix(es) applied · " +
                            (r.port?.let { "port $it" } ?: "no port"), color = Color(0xFFA9C7C0), fontSize = 10.5.sp)
                        // a build is "done" only when every phase the plan has, passed
                        if (r.phases.isNotEmpty()) {
                            Spacer(Modifier.height(7.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                r.phases.forEach { (phase, ok) ->
                                    Surface(shape = RoundedCornerShape(7.dp),
                                        color = if (ok) Color(0xFF12352A) else Color(0xFF3A1E22),
                                        border = BorderStroke(1.dp,
                                            if (ok) Color(0xFF2E7D5B) else Color(0xFF7A2E37))) {
                                        Text((if (ok) "✓ " else "✗ ") + phase,
                                            color = if (ok) Color(0xFF7BF3BE) else Color(0xFFF8A5A5),
                                            fontSize = 9.sp,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                                    }
                                }
                                if (r.done) StatusPill("done", Pal.mint)
                            }
                        }
                        r.port?.let { p2 ->
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { onPreview("http://127.0.0.1:$p2") },
                                colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                                Text("Preview localhost:$p2", color = Color(0xFF06281C), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        if (log.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF080A0E)),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    log.forEach { l ->
                        val c = when (l.level) {
                            "cmd" -> Cyan; "ok" -> Green; "err" -> Red
                            "fix" -> Amber; "stop" -> Amber; "learn" -> Color(0xFF22D3EE)
                            else -> Dim
                        }
                        Text(l.text, color = c, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlanCard(p: Planner.Plan) {
    // the bar fills on an expressive spring instead of appearing already full
    val fill by animateFloatAsState(
        targetValue = p.confidence.toFloat(), animationSpec = Motion.expressive(), label = "confidence")
    Card(colors = CardDefaults.cardColors(containerColor = Surf), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("BEST MATCH", color = Color(0xFFB7A4FF), fontSize = 9.sp, letterSpacing = 1.1.sp)
            Text(p.label, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Text("${(fill * 100).toInt()}% confident · ${p.files.size} file(s)" +
                (if (p.runCmd.isBlank()) " · no run step" else " · runs: ${p.runCmd}"),
                color = Dim, fontSize = 10.5.sp)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(6.dp)
                .background(Color(0x66000000), RoundedCornerShape(50))) {
                Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).height(6.dp)
                    .background(Brush.horizontalGradient(listOf(Violet, Cyan)), RoundedCornerShape(50)))
            }
            Spacer(Modifier.height(10.dp))
            p.evidence.take(5).forEachIndexed { i, (pts, why) ->
                Staggered(index = i, step = 45) { m ->
                    Text("+$pts  $why", color = Color(0xFFC6CBD9), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, modifier = m)
                }
            }
            if (p.predictions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("PREDICTED", color = Dim, fontSize = 9.sp, letterSpacing = 1.1.sp)
                p.predictions.take(4).forEach { pr ->
                    val c = if (pr.sev == "high") Red else if (pr.sev == "medium") Amber else Cyan
                    Text("${pr.where}: ${pr.what}", color = c, fontSize = 10.sp)
                }
            }
            if (p.issues.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("REVIEW · ${p.issues.size} issue(s)", color = Dim, fontSize = 9.sp, letterSpacing = 1.1.sp)
                p.issues.take(4).forEach { i ->
                    val c = if (i.sev == "high") Red else if (i.sev == "medium") Amber else Dim
                    Text("${i.file}:${i.line} ${i.msg}", color = c, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("STEPS · ${p.steps.size}", color = Dim, fontSize = 9.sp, letterSpacing = 1.1.sp)
            p.steps.forEachIndexed { i, s ->
                Text("${i + 1}. [${s.kind}] ${s.cmd}", color = Color(0xFFCFE1F5), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                Text("     ${s.why}", color = Color(0xFF7C8496), fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, color = Dim, fontSize = 9.5.sp, letterSpacing = 1.1.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PasteField(value: String, onChange: (String) -> Unit, lines: Int, hint: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        placeholder = { Text(hint, color = Color(0xFF5C6478), fontSize = 10.sp,
            fontFamily = FontFamily.Monospace) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = Txt),
        minLines = lines, maxLines = lines + 6,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Default),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Violet, unfocusedBorderColor = LineC,
            focusedContainerColor = Color(0xFF0A0C11), unfocusedContainerColor = Color(0xFF0A0C11))
    )
}
