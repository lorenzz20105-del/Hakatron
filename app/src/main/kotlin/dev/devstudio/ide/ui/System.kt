package dev.devstudio.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.devstudio.ide.Engine
import dev.devstudio.ide.core.TermuxUserland
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * System — engine, userland, storage, diagnostics.
 *
 * Rebuilt around one idea: every card answers "is this working, and if not, what
 * exactly is wrong?" No switches that look on while something is off, and no
 * "Termux needed" instructions that point at another app.
 */
@Composable
fun SystemScreen(
    ready: Boolean,
    busy: Boolean,
    log: String,
    onSetup: () -> Unit,
    onTermuxChanged: (Boolean) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var termux by remember { mutableStateOf(TermuxUserland.isInstalled(ctx)) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var phase by remember { mutableStateOf("") }
    var tlog by remember { mutableStateOf<String?>(null) }
    var diag by remember { mutableStateOf<String?>(null) }
    var access by remember { mutableStateOf(SharedFiles.hasAccess(ctx)) }
    var sizeMb by remember { mutableStateOf(0L) }
    var freeMb by remember { mutableStateOf(0L) }
    var tools by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }

    LaunchedEffect(ready, termux) {
        withContext(Dispatchers.IO) {
            freeMb = runCatching { Engine.freeSpaceMb(ctx) }.getOrDefault(0)
            sizeMb = runCatching { Engine.guestSizeMb(ctx) }.getOrDefault(0)
            if (ready) tools = runCatching { Engine.tools(ctx) }.getOrDefault(emptyList())
        }
    }

    Screen(title = "System", subtitle = "Engine · userland · storage", accent = Pal.amber) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)) {

            // ------------------------------------------------ engine
            SectionRow("LINUX ENGINE")
            TCard(Modifier.fillMaxWidth(), radius = Tok.rL,
                glow = if (ready) Pal.mint else Pal.amber) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(contentAlignment = Alignment.Center) {
                            Ring(if (ready) 1f else 0.06f, if (ready) Pal.mint else Pal.amber, 44.dp)
                            Text(if (ready) "✓" else "!", color = if (ready) Pal.mint else Pal.amber,
                                fontSize = 13.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (ready) "Alpine guest ready" else "Not set up",
                                color = Pal.text, fontSize = 13.5.sp, fontWeight = FontWeight.Black)
                            Text(
                                if (ready) "$sizeMb MB on disk · ${Engine.launchMode} launch · no root"
                                else "Unpacks inside the app (~120 MB free needed)",
                                color = Pal.dim, fontSize = 10.sp
                            )
                        }
                    }
                    if (busy) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pal.amber,
                            trackColor = Pal.surface3)
                        Text(log.ifBlank { "working…" }, color = Pal.amber, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Spacer(Modifier.height(11.dp))
                        GlowButton(
                            if (ready) "Re-check the engine" else "Install the engine",
                            from = Pal.amber, to = Pal.mint
                        ) {
                            if (ready) scope.launch {
                                diag = withContext(Dispatchers.IO) { Engine.diagnose(ctx) }
                            } else onSetup()
                        }
                    }
                    diag?.let {
                        Spacer(Modifier.height(9.dp))
                        MonoBlock(it, maxLines = 10)
                    }
                    if (tools.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            tools.forEach { (name, on) ->
                                StatusPill(name, if (on) Pal.mint else Pal.faint, filled = on)
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------ termux userland
            SectionRow("TERMUX USERLAND", if (termux) "installed" else "optional")
            TCard(Modifier.fillMaxWidth(), radius = Tok.rL,
                glow = if (termux) Pal.mint else Pal.cyan) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(contentAlignment = Alignment.Center) {
                            Ring(if (termux) 1f else 0.04f, if (termux) Pal.mint else Pal.cyan, 44.dp)
                            Text("❯", color = if (termux) Pal.mint else Pal.cyan, fontSize = 13.sp,
                                fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (termux) "Termux installed" else "Add the real Termux userland",
                                color = Pal.text, fontSize = 13.5.sp, fontWeight = FontWeight.Black)
                            Text(
                                if (termux) "${TermuxUserland.installedSizeMb(ctx)} MB · ${TermuxUserland.buildFor(ctx).arch} · runs natively"
                                else "Termux's own bootstrap, inside Hakatron. bash, git, python, apt — no other app.",
                                color = Pal.dim, fontSize = 10.sp, lineHeight = 14.sp
                            )
                        }
                    }
                    if (installing) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
                            color = Pal.cyan, trackColor = Pal.surface3)
                        Text(phase, color = Pal.cyan, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Spacer(Modifier.height(11.dp))
                        GlowButton(
                            if (termux) "Re-install / update" else "Install Termux userland (~${TermuxUserland.buildFor(ctx).approxMb} MB)",
                            from = Pal.cyan, to = Pal.mint
                        ) {
                            if (!ready) { tlog = "Set the engine up first — the unpack runs inside it."; return@GlowButton }
                            installing = true
                            progress = 0f
                            tlog = null
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) {
                                    TermuxUserland.install(
                                        ctx,
                                        run = { c -> Engine.exec(ctx, c, 1_800_000) },
                                        onProgress = { p ->
                                            phase = p.phase
                                            progress = if (p.total > 0) (p.done.toFloat() / p.total) else 0f
                                        }
                                    )
                                }
                                tlog = msg
                                installing = false
                                termux = TermuxUserland.isInstalled(ctx)
                                onTermuxChanged(termux)
                            }
                        }
                    }
                    tlog?.let {
                        Spacer(Modifier.height(9.dp))
                        MonoBlock(it, tone = if (it.startsWith("Termux userland installed")) Pal.mint else Pal.amber,
                            maxLines = 12)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Expected ~${TermuxUserland.buildFor(ctx).approxMb} MB download, ~200 MB on disk, " +
                        "fetched once from the Termux project's own release page. Hakatron never uploads anything.",
                        color = Pal.faint, fontSize = 9.sp, lineHeight = 13.sp)
                }
            }

            // ------------------------------------------------ storage
            SectionRow("STORAGE")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StatTile("FREE", "$freeMb MB", if (freeMb > 800) Pal.mint else Pal.amber,
                    Modifier.weight(1f))
                StatTile("GUEST", "$sizeMb MB", Pal.cyan, Modifier.weight(1f))
                StatTile("SHARED", if (access) "on" else "off",
                    if (access) Pal.mint else Pal.amber, Modifier.weight(1f))
            }
            Spacer(Modifier.height(9.dp))
            TCard(Modifier.fillMaxWidth().clickable {
                SharedFiles.requestAccess(ctx)
                access = SharedFiles.hasAccess(ctx)
            }) {
                Column(Modifier.padding(13.dp)) {
                    Text(if (access) "Shared folder is writable" else "Allow files access",
                        color = Pal.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(SharedFiles.DIR, color = Pal.faint, fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        StatusPill(if (access) "granted" else "tap to grant",
                            if (access) Pal.mint else Pal.amber, filled = true)
                        Box(Modifier.clip(RoundedCornerShape(50)).background(Pal.surface2)
                            .clickable { SharedFiles.seed(ctx) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("seed README", color = Pal.dim, fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ------------------------------------------------ about
            SectionRow("ABOUT")
            TCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp)) {
                    val v = remember {
                        runCatching {
                            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                        }.getOrDefault("")
                    }
                    Text("Hakatron $v", color = Pal.text, fontSize = 12.5.sp,
                        fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(3.dp))
                    Text("Kotlin · Jetpack Compose · ML Kit (on-device) · PRoot + Alpine · " +
                        "Termux bootstrap (optional). No source file of yours is ever edited by Devos.",
                        color = Pal.dim, fontSize = 9.5.sp, lineHeight = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Back to dashboard") { onBack() }
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}
