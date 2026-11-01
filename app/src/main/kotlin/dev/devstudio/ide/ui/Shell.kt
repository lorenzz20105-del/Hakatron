package dev.devstudio.ide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.devstudio.ide.Engine
import dev.devstudio.ide.core.Store
import dev.devstudio.ide.termux.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App shell — the five tabs from the design mockup:
 * Home · Files · Editor · Terminal · System.
 * Build Studio, Termux and Preview open from Home as full-screen flows.
 */
@Composable
fun HakatronApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) }
    var engineReady by remember { mutableStateOf(Engine.isReady(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var setupLog by remember { mutableStateOf("") }
    var showBuild by remember { mutableStateOf(false) }
    var showTermux by remember { mutableStateOf(false) }
    var previewUrl by remember { mutableStateOf<String?>(null) }
    var editorPath by remember { mutableStateOf<String?>(null) }
    val verdict = remember { TermuxBridge.verdict(ctx) }
    val runSetup: () -> Unit = {
        if (!busy) scope.launch {
            busy = true
            setupLog = "unpacking Alpine…"
            val msg = withContext(Dispatchers.IO) { Engine.setup(ctx) { s -> setupLog = s } }
            setupLog = msg
            engineReady = Engine.isReady(ctx)
            busy = false
        }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SharedFiles.ensure(ctx) } }

    val haptic = LocalHapticFeedback.current

    Scaffold(containerColor = Bg, bottomBar = {
        NavigationBar(containerColor = Color(0xFF0E1016), tonalElevation = 0.dp, modifier = Modifier.height(64.dp)) {
            listOf("Home", "Files", "Editor", "Shell", "System").forEachIndexed { i, label ->
                val on = tab == i
                // effect tokens: colour never bounces; spatial tokens: the pill grows
                val tint by animateColorAsState(
                    if (on) Violet else Dim, Motion.fade(160), label = "tint")
                val pillBy by androidx.compose.animation.animateColorAsState(
                    if (on) Color(0x332A1F4E) else Color.Transparent, Motion.fade(200), label = "pill")
                val pillW by animateDpAsState(if (on) 46.dp else 30.dp, Motion.expressive(), label = "pillW")
                val pillH by animateDpAsState(if (on) 27.dp else 22.dp, Motion.expressive(), label = "pillH")
                val lift by animateDpAsState(if (on) (-1).dp else 0.dp, Motion.expressive(), label = "lift")
                NavigationBarItem(
                    selected = on,
                    onClick = {
                        if (tab != i) {
                            runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            tab = i
                        }
                    },
                    icon = {
                        Box(
                            Modifier
                                .size(pillW, pillH)
                                .offset(y = lift)
                                .background(pillBy, RoundedCornerShape(if (on) 9.dp else 11.dp)),
                            contentAlignment = Alignment.Center
                        ) { TabIcon(i, tint) }
                    },
                    label = {
                        Text(label, fontSize = 9.5.sp, color = tint,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.graphicsLayer { alpha = if (on) 1f else 0.78f })
                    }
                )
            }
        }
    }) { pad ->
        // full-screen flows rise over the app like a sheet
        val flow: String? = when {
            showTermux -> "termux"
            showBuild -> "build"
            previewUrl != null -> "preview"
            else -> null
        }
        AnimatedVisibility(
            visible = flow != null,
            enter = slideInVertically(Motion.spatial()) { it / 12 } + fadeIn(Motion.fade(160)) +
                scaleIn(Motion.spatial(), initialScale = 0.985f),
            exit = slideOutVertically(Motion.spatial()) { it / 18 } + fadeOut(Motion.fade(120)) +
                scaleOut(Motion.spatial(), targetScale = 0.99f),
            label = "flow"
        ) {
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (flow) {
                    "termux" -> TermuxScreen { showTermux = false }
                    "build" -> BuildStudio(engineReady, onPreview = { previewUrl = it }, onClose = { showBuild = false })
                    "preview" -> previewUrl?.let { PreviewScreen(it) { previewUrl = null } }
                }
            }
        }

        // Tabs cross-fade along the direction you moved, so the app feels like one
        // surface being navigated rather than five screens being swapped.
        var prevTab by remember { mutableStateOf(tab) }
        val direction = if (tab >= prevTab) 1 else -1
        LaunchedEffect(tab) { prevTab = tab }
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (
                    slideInHorizontally(Motion.spatial()) { full -> (full / 14) * direction } +
                        fadeIn(Motion.fade<Float>(170))
                    ) togetherWith (
                    slideOutHorizontally(Motion.spatial()) { full -> -(full / 14) * direction } +
                        fadeOut(Motion.fade<Float>(120))
                    )
            },
            label = "tab-content"
        ) { current ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                when (current) {
                    0 -> HomeScreen(
                        ready = engineReady, busy = busy, setupLog = setupLog,
                        onSetup = runSetup, verdict = verdict,
                        onBuild = { showBuild = true },
                        onEditor = { tab = 2 },
                        onFiles = { tab = 1 },
                        onTermux = { showTermux = true },
                        onSystem = { tab = 4 }
                    )
                    1 -> FilesScreen(ready = engineReady, onOpenInEditor = { path ->
                        editorPath = path; tab = 2
                    })
                    2 -> EditorScreen(onPreview = { previewUrl = it }, initialPath = editorPath)
                    3 -> TerminalScreen(engineReady)
                    4 -> SystemScreen(engineReady, busy, setupLog,
                        onTermux = { showTermux = true }, onSetup = runSetup)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ home */

@Composable
private fun HomeScreen(
    ready: Boolean, busy: Boolean, setupLog: String, onSetup: () -> Unit,
    verdict: dev.devstudio.ide.termux.TermuxVerdict,
    onBuild: () -> Unit, onEditor: () -> Unit, onFiles: () -> Unit,
    onTermux: () -> Unit, onSystem: () -> Unit
) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var fileCount by remember { mutableStateOf(0) }
    val freeMb = remember { runCatching { Engine.freeSpaceMb(ctx) }.getOrDefault(0L) }
    val recents = remember { store.recents() }

    LaunchedEffect(ready) {
        if (ready) {
            fileCount = withContext(Dispatchers.IO) {
                runCatching { GuestFiles.list({ c -> Engine.exec(ctx, c, 20_000) }).size }.getOrDefault(0)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        // brand row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(Brush.linearGradient(listOf(Violet, Cyan)),
                RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Text("H", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Hakatron", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.3).sp)
                Text("Real Linux. Real code. On your phone.", color = Dim, fontSize = 11.sp)
            }
            Surface(shape = RoundedCornerShape(20.dp), color = Surf2,
                border = BorderStroke(1.dp, LineC)) {
                Text("v" + remember {
                    runCatching {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                    }.getOrDefault("")
                }, color = Dim, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(14.dp))

        // hero — continue card
        Staggered(index = 0) { m ->
        Card(modifier = m, colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(Color(0xFF1B1B3A), Color(0xFF241A44), Color(0xFF2A1636))),
                RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text(if (recents.isEmpty()) "START HERE" else "CONTINUE",
                    color = Color(0xFFB7A4FF), fontSize = 9.5.sp, letterSpacing = 1.2.sp)
                Text(
                    if (recents.isEmpty()) "Build something" else recents.first().substringAfterLast('/'),
                    color = Txt, fontSize = 19.sp, fontWeight = FontWeight.Black)
                Text(
                    if (recents.isEmpty()) "Paste a project structure and Hakatron builds it in real Linux."
                    else recents.first(), color = Color(0xFFC9C3E6), fontSize = 10.5.sp, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(if (ready) "● Linux ready" else "○ Linux not set up", if (ready) Green else Amber)
                    Pill("Alpine", Color(0xFFC9C3E6))
                    if (verdict.genuine) Pill("● Termux", Color(0xFF7BF3BE))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBuild, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                        Text("Build Studio", fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                    Button(onClick = onEditor, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF))) {
                        Text("Open editor", fontSize = 12.sp, color = Txt)
                    }
                }
            }
        }
        }
        if (!ready) {
            Spacer(Modifier.height(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF221A12)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF4A3A1E)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp)) {
                    Text("ONE-TIME SETUP", color = Amber, fontSize = 9.sp, letterSpacing = 1.1.sp)
                    Text("Install the Linux engine", color = Txt, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold)
                    Text("Unpacks Alpine Linux inside Hakatron (~120 MB). After this, the " +
                        "editor, shell and builds all run on your phone with no Termux.",
                        color = Color(0xFFCFC3AE), fontSize = 10.5.sp, lineHeight = 15.sp)
                    Spacer(Modifier.height(9.dp))
                    if (busy) Text(setupLog.ifBlank { "working…" }, color = Amber, fontSize = 10.5.sp)
                    else AccentButton("Install engine now", onClick = onSetup)
                }
            }
        }
        Spacer(Modifier.height(11.dp))

        // KPI tiles — bento row
        Staggered(index = 1) { m ->
            Row(m.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kpi(Modifier.weight(1f), "ENGINE", if (ready) "Ready" else "Setup",
                    if (ready) Green else Amber)
                Kpi(Modifier.weight(1f), "FILES", "$fileCount", Cyan)
                Kpi(Modifier.weight(1f), "STORAGE", "${freeMb / 1024} GB", Dim)
                Kpi(Modifier.weight(1f), "FIXES", "${store.fixCount()}", Violet)
            }
        }
        Spacer(Modifier.height(14.dp))

        Text("QUICK ACTIONS", color = Dim, fontSize = 9.5.sp, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(Modifier.weight(1f), "Build Studio", "structure → app", Violet, onBuild)
            Tile(Modifier.weight(1f), "Files", "browse the guest", Cyan, onFiles)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(Modifier.weight(1f), "Termux", "real pkg installs", Green, onTermux)
            Tile(Modifier.weight(1f), "System", "engine + runtimes", Amber, onSystem)
        }
        Spacer(Modifier.height(14.dp))

        TermuxCard(verdict, onTermux)

        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("RECENT FILES", color = Dim, fontSize = 9.5.sp, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(6.dp))
            recents.take(5).forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { onEditor() }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(p.substringAfterLast('/'), color = Txt, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    Text(p.substringBeforeLast('/').takeLast(22), color = Dim, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
internal fun Pill(text: String, tint: Color) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0x33000000),
        border = BorderStroke(1.dp, Color(0x33FFFFFF))) {
        Text(text, color = tint, fontSize = 9.5.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
internal fun Kpi(mod: Modifier, label: String, value: String, tint: Color) {
    val shown by animateFloatAsState(1f, Motion.spatial(), label = "kpi")
    Card(mod, shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = Surf)) {
        Column(Modifier.padding(10.dp).graphicsLayer { alpha = shown }) {
            Text(label, color = Dim, fontSize = 8.sp, letterSpacing = 1.sp)
            Text(value, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

@Composable
private fun Tile(mod: Modifier, title: String, sub: String, tint: Color, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = mod) {
        Card(shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Surf),
            border = BorderStroke(1.dp, LineC)) {
            Column(Modifier.padding(12.dp)) {
                Box(Modifier.size(6.dp).background(tint, RoundedCornerShape(3.dp)))
                Spacer(Modifier.height(6.dp))
                Text(title, color = Txt, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                Text(sub, color = Dim, fontSize = 9.5.sp)
            }
        }
    }
}

/* ----------------------------------------------------------------- files */

@Composable
fun FilesScreen(ready: Boolean, onOpenInEditor: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var root by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf<List<String>>(emptyList()) }
    val roots = listOf("~/projects" to "Guest projects", "shared" to "Shared with Termux")
    val run: (String) -> String = { c -> Engine.exec(ctx, c, 60_000) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var askName by remember { mutableStateOf<String?>(null) }        // dialog title
    var askValue by remember { mutableStateOf("") }
    var askAction by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var note by remember { mutableStateOf<String?>(null) }



    fun load() {
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (root == 0) GuestFiles.list({ c -> Engine.exec(ctx, c, 30_000) })
                else SharedFiles.list(ctx)
            }
            files = r
            loading = false
        }
    }
    LaunchedEffect(root, ready) { load() }

    fun doOp(ok: Boolean, what: String) {
        note = if (ok) what else "$what failed"
        load()
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Files", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("Inside the Linux guest and the shared folder", color = Dim, fontSize = 11.sp)
            }
            if (root == 0) {
                Text("＋file", color = Cyan, fontSize = 11.sp,
                    modifier = Modifier.clickable {
                        askValue = "~/projects/"
                        askName = "New file"
                        askAction = { p ->
                            if (p.isNotBlank()) doOp(GuestFiles.write(run, p, ""), "created $p")
                        }
                    }.padding(horizontal = 4.dp))
                Text("＋dir", color = Cyan, fontSize = 11.sp,
                    modifier = Modifier.clickable {
                        askValue = "~/projects/"
                        askName = "New folder"
                        askAction = { p -> if (p.isNotBlank()) doOp(GuestFiles.mkdir(run, p), "created $p") }
                    }.padding(horizontal = 4.dp))
            }
            Text("Reload", color = Cyan, fontSize = 11.sp, modifier = Modifier.clickable { load() })
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            roots.forEachIndexed { i, (_, label) ->
                val on = root == i
                Surface(shape = RoundedCornerShape(10.dp), color = if (on) Color(0xFF221A3F) else Surf,
                    border = BorderStroke(1.dp, if (on) Violet else LineC),
                    modifier = Modifier.clickable { root = i }) {
                    Text(label, color = if (on) Color(0xFFC4B5FD) else Dim, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (root == 1) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2B22)),
                shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(11.dp)) {
                    Text("Shared folder", color = Color(0xFF7BF3BE), fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold)
                    Text(SharedFiles.DIR + " — read/write from Hakatron and from Termux " +
                        "(run `termux-setup-storage` once in Termux).", color = Color(0xFFA9C7C0),
                        fontSize = 10.sp, lineHeight = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { SharedFiles.ensure(ctx); load() },
                        colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                        Text("Create / refresh folder", color = Color(0xFF06281C), fontSize = 11.5.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (loading) Text("loading…", color = Amber, fontSize = 11.sp)
        if (!loading && files.isEmpty()) {
            Text(if (root == 0) "No files yet — build a project in Build Studio."
                else "Seed the shared folder to start exchanging files with Termux.",
                color = Dim, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        note?.let {
            Text(it, color = if (it.endsWith("failed")) Red else Green, fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            files.forEach { f ->
                Row(Modifier.fillMaxWidth().clickable {
                    if (root == 0) onOpenInEditor(f) else onOpenInEditor(f)
                }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.substringAfterLast('/'), color = Txt, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Text(f.substringBeforeLast('/').takeLast(22), color = Dim, fontSize = 8.5.sp, maxLines = 1)
                    if (root == 0) Text(" ⋯", color = Cyan, fontSize = 13.sp,
                        modifier = Modifier.clickable { menuFor = f }.padding(start = 6.dp))
                }
                Divider(color = Color(0xFF1A1D25), thickness = 1.dp)
            }
        }
    }

    menuFor?.let { f ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = Surf,
            title = { Text(f.substringAfterLast('/'), color = Txt, fontSize = 14.sp,
                fontFamily = FontFamily.Monospace) },
            text = { Text(f.substringBeforeLast('/'), color = Dim, fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        askValue = f
                        askName = "Rename"
                        askAction = { to ->
                            if (to.isNotBlank() && to != f) doOp(GuestFiles.rename(run, f, to), "renamed")
                        }
                        menuFor = null
                    }) { Text("Rename", color = Cyan, fontSize = 12.sp) }
                    TextButton(onClick = {
                        doOp(GuestFiles.delete(run, f), "deleted")
                        menuFor = null
                    }) { Text("Delete", color = Red, fontSize = 12.sp) }
                }
            },
            dismissButton = { TextButton(onClick = { menuFor = null }) { Text("Cancel", color = Dim) } }
        )
    }
    askName?.let { title ->
        AlertDialog(
            onDismissRequest = { askName = null },
            containerColor = Surf,
            title = { Text(title, color = Txt, fontSize = 15.sp) },
            text = {
                Column {
                    OutlinedTextField(askValue, { askValue = it }, singleLine = true,
                        label = { Text("path", fontSize = 11.sp) },
                        textStyle = TextStyle(fontSize = 12.sp, color = Txt,
                            fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet,
                            unfocusedBorderColor = LineC))
                    Text("full path inside the guest, e.g. ~/projects/app/main.py",
                        color = Dim, fontSize = 9.5.sp, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { askAction?.invoke(askValue.trim()); askName = null }) {
                    Text("OK", color = Cyan, fontSize = 12.sp)
                }
            },
            dismissButton = { TextButton(onClick = { askName = null }) { Text("Cancel", color = Dim) } }
        )
    }
}

/* ----------------------------------------------------------------- icons */

/**
 * Hand-drawn tab icons (no icon font, no extra dependency): every tab gets a
 * distinct silhouette so the bar reads at a glance on a phone.
 */
@Composable
private fun TabIcon(index: Int, tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
        val w = size.width; val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.7f * density, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val p = androidx.compose.ui.graphics.Path()
        when (index) {
            0 -> { // house
                p.moveTo(w * 0.5f, h * 0.12f); p.lineTo(w * 0.94f, h * 0.48f)
                p.moveTo(w * 0.18f, h * 0.42f); p.lineTo(w * 0.18f, h * 0.9f)
                p.lineTo(w * 0.82f, h * 0.9f); p.lineTo(w * 0.82f, h * 0.42f)
                drawPath(p, tint, style = stroke)
            }
            1 -> { // folder
                p.moveTo(w * 0.08f, h * 0.26f); p.lineTo(w * 0.4f, h * 0.26f)
                p.lineTo(w * 0.5f, h * 0.4f); p.lineTo(w * 0.92f, h * 0.4f)
                p.lineTo(w * 0.92f, h * 0.82f); p.lineTo(w * 0.08f, h * 0.82f)
                p.close()
                drawPath(p, tint, style = stroke)
            }
            2 -> { // code chevrons
                p.moveTo(w * 0.38f, h * 0.24f); p.lineTo(w * 0.14f, h * 0.5f)
                p.lineTo(w * 0.38f, h * 0.76f)
                p.moveTo(w * 0.62f, h * 0.24f); p.lineTo(w * 0.86f, h * 0.5f)
                p.lineTo(w * 0.62f, h * 0.76f)
                drawPath(p, tint, style = stroke)
            }
            3 -> { // prompt >
                p.moveTo(w * 0.16f, h * 0.28f); p.lineTo(w * 0.44f, h * 0.5f)
                p.lineTo(w * 0.16f, h * 0.72f)
                drawPath(p, tint, style = stroke)
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.56f, h * 0.74f),
                    androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.74f),
                    strokeWidth = 1.7f * density, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            else -> { // sliders
                listOf(0.26f, 0.5f, 0.74f).forEachIndexed { i, y ->
                    drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.1f, h * y),
                        androidx.compose.ui.geometry.Offset(w * 0.9f, h * y),
                        strokeWidth = 1.6f * density, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    val x = if (i == 1) 0.66f else 0.34f
                    drawCircle(tint, radius = 2.2f * density,
                        center = androidx.compose.ui.geometry.Offset(w * x, h * y))
                }
            }
        }
    }
}
