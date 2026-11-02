package dev.devstudio.ide.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.devstudio.ide.Engine
import dev.devstudio.ide.core.Store
import dev.devstudio.ide.core.TermuxUserland
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AppShell — the whole app in one place, rebuilt.
 *
 * The previous shell rendered the tab content **and** stacked Build Studio / Termux
 * on top of it, so two screens were drawn at once and the old one showed through
 * the new one. That is a layout bug with a layout fix: there is now exactly one
 * [Route] at a time, and [AnimatedContent] draws exactly that one. Nothing can
 * overlay anything, by construction.
 *
 * The navigation model is a **floating dock** rather than a Material navigation
 * bar: five destinations, a moving highlight, and a raised primary action that
 * takes you straight into Build Studio from anywhere.
 */
enum class Route(val label: String, val glyph: String) {
    Home("Home", "◈"),
    Files("Files", "▤"),
    Editor("Editor", "⌘"),
    Shell("Shell", "❯"),
    System("System", "⚙"),
    Build("Build", "＋"),
    Preview("Preview", "◎")
}

@Composable
fun HakatronApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var route by remember { mutableStateOf(Route.Home) }
    var engineReady by remember { mutableStateOf(Engine.isReady(ctx)) }
    var termuxReady by remember { mutableStateOf(TermuxUserland.isInstalled(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var setupLog by remember { mutableStateOf("") }
    var previewUrl by remember { mutableStateOf<String?>(null) }
    var editorPath by remember { mutableStateOf<String?>(null) }
    var paletteOpen by remember { mutableStateOf(false) }

    // The one navigation verb. Haptics confirm the change, like a physical detent.
    val go: (Route) -> Unit = { target ->
        if (target != route) {
            runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            route = target
        }
    }

    val runSetup: () -> Unit = {
        if (!busy) scope.launch {
            busy = true
            setupLog = "unpacking the Linux engine…"
            setupLog = withContext(Dispatchers.IO) { Engine.setup(ctx) { setupLog = it } }
            engineReady = Engine.isReady(ctx)
            busy = false
        }
    }

    val animationsOn = rememberAnimationsEnabled()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SharedFiles.ensure(ctx) } }

    val dur = if (animationsOn) 1 else 0
    Box(Modifier.fillMaxSize().background(Pal.ink)) {
        // Exactly one destination is composed at a time.
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                if (dur == 0) {
                    // "remove animations" in system settings: swap instantly, no motion
                    fadeIn(androidx.compose.animation.core.tween(0)) togetherWith
                        fadeOut(androidx.compose.animation.core.tween(0))
                } else {
                    val forward = targetState.ordinal > initialState.ordinal
                    val dir = if (forward) 1 else -1
                    (fadeIn(Motion.fade<Float>(160)) +
                        slideInVertically(Motion.spatial()) { h -> (h / 22) * dir }) togetherWith
                        (fadeOut(Motion.fade<Float>(110)) +
                            slideOutVertically(Motion.spatial()) { h -> -(h / 26) * dir })
                }
            },
            label = "route"
        ) { current ->
            Box(Modifier.fillMaxSize().padding(bottom = 78.dp)) {
                when (current) {
                    Route.Home -> HomeScreen(
                        ready = engineReady, termuxReady = termuxReady, busy = busy,
                        setupLog = setupLog,
                        onSetup = runSetup, go = go, store = store,
                        onPalette = { paletteOpen = true }
                    )
                    Route.Files -> FilesScreen(ready = engineReady) { path ->
                        editorPath = path
                        go(Route.Editor)
                    }
                    Route.Editor -> EditorScreen(
                        onPreview = { url -> previewUrl = url; go(Route.Preview) },
                        initialPath = editorPath
                    )
                    Route.Shell -> TerminalScreen(engineReady)
                    Route.System -> SystemScreen(
                        ready = engineReady, busy = busy, log = setupLog,
                        onSetup = runSetup,
                        onTermuxChanged = { termuxReady = it },
                        onBack = { go(Route.Home) }
                    )
                    Route.Build -> BuildStudio(engineReady,
                        onPreview = { url -> previewUrl = url; go(Route.Preview) },
                        onClose = { go(Route.Home) })
                    Route.Preview -> previewUrl?.let { url ->
                        PreviewScreen(url) { go(Route.Editor) }
                    }
                }
            }
        }

        // Floating dock — always present, sits above the content, never duplicates it.
        Dock(
            current = route,
            onGo = go,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        CommandPalette(
            open = paletteOpen,
            onDismiss = { paletteOpen = false },
            onPick = { target ->
                paletteOpen = false
                go(target)
            }
        )
    }
}

/* ------------------------------------------------------------------- dock */

@Composable
private fun Dock(current: Route, onGo: (Route) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(Route.Home, Route.Files, Route.Editor, Route.Shell, Route.System)
    Column(modifier.fillMaxWidth()) {
        // one hairline, fading at the edges — the dock's only edge highlight
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(
                Brush.horizontalGradient(listOf(Color.Transparent, Pal.line, Color.Transparent)))
        )
        Row(
            Modifier.fillMaxWidth()
                .background(Pal.ink.copy(alpha = 0.94f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val on = current == item
                val tint by androidx.compose.animation.animateColorAsState(
                    if (on) Pal.violet else Pal.faint, Motion.fade(160), label = "dock-tint-$item")
                val pillW by androidx.compose.animation.core.animateDpAsState(
                    if (on) 52.dp else 34.dp, Motion.expressive(), label = "dock-w-$item")
                Pressable(onClick = { onGo(item) }, modifier = Modifier.weight(1f), scaleDown = 0.92f) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(pillW, 26.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (on) Pal.violetSoft else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(item.glyph, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(item.label, color = tint, fontSize = 8.5.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
            Pressable(onClick = { onGo(Route.Build) }, scaleDown = 0.9f) {
                Box(
                    Modifier.padding(start = 4.dp).size(46.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Pal.violet, Pal.cyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("＋", color = Pal.ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

/* ---------------------------------------------------------- command palette */

@Composable
private fun CommandPalette(open: Boolean, onDismiss: () -> Unit, onPick: (Route) -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(Motion.fade(140)),
        exit = fadeOut(Motion.fade(120))
    ) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC04050A))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null) { onDismiss() }
        ) {
            AnimatedVisibility(
                visible = open,
                enter = slideInVertically(Motion.spatial()) { it / 8 } + fadeIn(Motion.fade(140)) +
                    scaleIn(Motion.spatial(), initialScale = 0.97f),
                exit = slideOutVertically(Motion.spatial()) { it / 10 } + fadeOut(Motion.fade(100))
            ) {
                TCard(
                    modifier = Modifier.fillMaxWidth().padding(20.dp).padding(top = 60.dp),
                    tone = Pal.surface2, radius = Tok.rL, border = Pal.line
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Label("Go anywhere")
                        Spacer(Modifier.height(8.dp))
                        listOf(
                            Route.Build to "Build a project from a tree or a screenshot",
                            Route.Files to "Browse the guest filesystem",
                            Route.Editor to "Write and run code",
                            Route.Shell to "A real terminal — pty, colours, vi",
                            Route.System to "Engine, storage, diagnostics",
                            Route.Home to "Dashboard"
                        ).forEachIndexed { i, (r, why) ->
                            Staggered(index = i, step = 30) { m ->
                                Row(
                                    m.fillMaxWidth()
                                        .clip(RoundedCornerShape(Tok.rS))
                                        .clickable { onPick(r) }
                                        .padding(vertical = 9.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                                            .background(Pal.violetSoft), contentAlignment = Alignment.Center
                                    ) { Text(r.glyph, color = Pal.violet, fontSize = 12.sp) }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(r.label, color = Pal.text, fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold)
                                        Text(why, color = Pal.dim, fontSize = 9.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------- home */

@Composable
private fun HomeScreen(
    ready: Boolean,
    termuxReady: Boolean,
    busy: Boolean,
    setupLog: String,
    onSetup: () -> Unit,
    go: (Route) -> Unit,
    store: Store,
    onPalette: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileCount by remember { mutableStateOf(0) }
    var tools by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    val recents = remember { runCatching { store.recents() }.getOrDefault(emptyList()) }
    val fixCount = remember { runCatching { store.fixCount() }.getOrDefault(0) }
    val freeGb = remember { runCatching { Engine.freeSpaceMb(ctx) / 1024 }.getOrDefault(0L) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("")
    }

    LaunchedEffect(ready) {
        if (ready) {
            tools = withContext(Dispatchers.IO) { runCatching { Engine.tools(ctx) }.getOrDefault(emptyList()) }
            fileCount = withContext(Dispatchers.IO) {
                runCatching { GuestFiles.list({ c -> Engine.exec(ctx, c, 20_000) }).size }.getOrDefault(0)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Ambient()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)) {

            // brand line
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                        .background(Brush.linearGradient(listOf(Pal.violet, Pal.cyan))),
                    contentAlignment = Alignment.Center
                ) { Text("H", color = Pal.ink, fontWeight = FontWeight.Black, fontSize = 13.sp) }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hakatron", color = Pal.text, fontSize = 16.sp, fontWeight = FontWeight.Black,
                        letterSpacing = (-0.3).sp)
                    Text("v$version · on-device Linux", color = Pal.faint, fontSize = 9.sp)
                }
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                        .background(Pal.surface2)
                        .clickable { onPalette() },
                    contentAlignment = Alignment.Center
                ) { Text("⌘", color = Pal.dim, fontSize = 14.sp) }
            }

            // engine status — the one thing that decides whether anything works
            Staggered(0) { m ->
                TCard(m.fillMaxWidth(), tone = Pal.surface, radius = Tok.rL,
                    glow = if (ready) Pal.mint else Pal.amber) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(contentAlignment = Alignment.Center) {
                            Ring(
                                progress = if (ready) 1f else if (busy) 0.45f else 0.06f,
                                tone = if (ready) Pal.mint else Pal.amber, size = 48.dp
                            )
                            Text(if (ready) "✓" else "!", color = if (ready) Pal.mint else Pal.amber,
                                fontSize = 15.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (ready) "Linux engine ready" else if (busy) "Setting up…" else "Engine not set up",
                                color = Pal.text, fontSize = 14.sp, fontWeight = FontWeight.Black
                            )
                            Text(
                                if (ready) "Alpine · PRoot · ${Engine.launchMode} mode"
                                else if (busy) setupLog.ifBlank { "unpacking…" }
                                else "One-time · unpacks inside Hakatron · no root",
                                color = Pal.dim, fontSize = 10.sp, maxLines = 2
                            )
                            if (ready && tools.isNotEmpty()) {
                                Spacer(Modifier.height(5.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    tools.filter { it.second }.take(4).forEach { (name, _) ->
                                        StatusPill(name, Pal.mint)
                                    }
                                }
                            }
                        }
                        if (!ready && !busy) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.clip(RoundedCornerShape(50))
                                    .background(Pal.amber)
                                    .clickable { onSetup() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) { Text("Set up", color = Pal.ink, fontSize = 11.sp, fontWeight = FontWeight.Black) }
                        }
                    }
                }
            }

            // primary action
            Staggered(1) { m ->
                Spacer(Modifier.height(12.dp))
                TCard(m.fillMaxWidth(), tone = Color(0xFF141126), radius = Tok.rL,
                    border = Pal.violet.copy(alpha = 0.28f), glow = Pal.violet) {
                    Column(Modifier.padding(16.dp)) {
                        Label("Start here", Pal.violet)
                        Spacer(Modifier.height(3.dp))
                        Text("Build something", color = Pal.text, fontSize = 20.sp,
                            fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp)
                        Text("Paste a tree — or a screenshot of one — and Devos plans, builds and fixes it.",
                            color = Pal.dim, fontSize = 10.5.sp, lineHeight = 15.sp)
                        Spacer(Modifier.height(13.dp))
                        GlowButton("Open Build Studio", glyph = "▶",
                            modifier = Modifier.fillMaxWidth()) { go(Route.Build) }
                    }
                }
            }

            // Continue: the one thing a returning user wants and had to dig for.
            if (recents.isNotEmpty()) {
                HkSection(null, "Continue", "last file you had open", tone = Pal.mint)
                Staggered(2) { m ->
                    val last = recents.first()
                    Pressable(onClick = { go(Route.Editor) }, modifier = m.fillMaxWidth()) {
                        TCard(tone = Pal.surface, radius = Tok.rM) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                                        .background(Pal.mintSoft), contentAlignment = Alignment.Center
                                ) { Text("▶", color = Pal.mint, fontSize = 12.sp) }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(last.substringAfterLast('/'), color = Pal.text,
                                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(last.substringBeforeLast('/', "~"),
                                        color = Pal.faint, fontSize = 9.sp, maxLines = 1,
                                        fontFamily = FontFamily.Monospace)
                                }
                                Spacer(Modifier.width(8.dp))
                                StatusPill("open", Pal.mint)
                            }
                        }
                    }
                }
            }

            HkSection(null, "Workspace", "everything runs in the guest", tone = Pal.cyan)
            Staggered(2) { m ->
                Row(m.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile("▤", "Files", "$fileCount in guest", Pal.cyan,
                        Modifier.weight(1f)) { go(Route.Files) }
                    ActionTile("❯", "Terminal", "pty · real shell", Pal.mint,
                        Modifier.weight(1f)) { go(Route.Shell) }
                }
                Spacer(Modifier.height(8.dp))
                Row(m.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile("⌘", "Editor", "write & run", Pal.violet,
                        Modifier.weight(1f)) { go(Route.Editor) }
                    ActionTile("⚙", "System", if (termuxReady) "Termux userland" else "engine & storage",
                        Pal.amber, Modifier.weight(1f)) { go(Route.System) }
                }
            }

            HkSection(null, "At a glance", tone = Pal.amber)
            Staggered(3) { m ->
                Row(m.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    StatTile("STORAGE", "$freeGb GB", Pal.dim, Modifier.weight(1f))
                    StatTile("FIXES LEARNED", "$fixCount", Pal.violet, Modifier.weight(1f))
                    StatTile("USERLAND", if (termuxReady) "Termux" else "Alpine",
                        if (termuxReady) Pal.mint else Pal.cyan, Modifier.weight(1f))
                }
            }

            if (recents.isNotEmpty()) {
                HkSection(null, "Recent", "open editor", tone = Pal.violet)
                Staggered(4) { m ->
                    TCard(m.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            recents.take(4).forEach { path ->
                                Row(Modifier.fillMaxWidth()
                                    .clickable { go(Route.Editor) }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("·", color = Pal.violet, fontSize = 14.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(path.substringAfterLast('/'), color = Pal.text,
                                            fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(path.substringBeforeLast('/', ""), color = Pal.faint,
                                            fontSize = 8.5.sp, maxLines = 1,
                                            fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "Devos never edits your source — only config files, and only when the error says so.",
                color = Pal.faint, fontSize = 9.sp, lineHeight = 13.sp
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
