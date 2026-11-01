package dev.devstudio.ide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.devstudio.ide.termux.TermuxBridge
import dev.devstudio.ide.termux.TermuxVerdict

/** Real Termux integration: detection, signature check, one-tap installs, custom commands. */
@Composable
fun TermuxScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var verdict by remember { mutableStateOf(TermuxBridge.verdict(ctx)) }
    var custom by remember { mutableStateOf("") }
    var lastSent by remember { mutableStateOf<String?>(null) }
    var foreground by remember { mutableStateOf(true) }

    var distros by remember { mutableStateOf(SharedFiles.distros()) }
    var log by remember { mutableStateOf(SharedFiles.buildLog()) }
    fun resend() {
        verdict = TermuxBridge.verdict(ctx)
        distros = SharedFiles.distros()
        log = SharedFiles.buildLog()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Termux", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("Run installs in the real Termux on your phone", color = Dim, fontSize = 11.sp)
            }
            TextButton(onClick = { resend() }) { Text("Refresh", color = Cyan, fontSize = 11.sp) }
            TextButton(onClick = onBack) { Text("Back", color = Dim, fontSize = 11.sp) }
        }
        Spacer(Modifier.height(10.dp))

        val statusColor = when {
            !verdict.installed -> Amber
            verdict.genuine -> Green
            else -> Red
        }
        Card(colors = CardDefaults.cardColors(containerColor = Surf), shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("TERMUX STATUS", color = Dim, fontSize = 9.sp, letterSpacing = 1.1.sp)
                Text(
                    when {
                        !verdict.installed -> "Not installed"
                        verdict.genuine -> "Genuine · ${verdict.version}"
                        else -> "Rejected · ${verdict.version}"
                    },
                    color = statusColor, fontSize = 17.sp, fontWeight = FontWeight.Black
                )
                Text(verdict.source + if (verdict.certSha256.isNotBlank())
                    " · ${verdict.certSha256.take(16)}…" else "", color = Dim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Text(verdict.advice, color = Color(0xFFC6CBD9), fontSize = 11.sp, lineHeight = 16.sp)
                verdict.problems.forEach {
                    Text("• $it", color = Amber, fontSize = 10.5.sp, lineHeight = 15.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (verdict.installed) {
                        Button(onClick = {
                            ctx.packageManager.getLaunchIntentForPackage(TermuxBridge.PKG)?.let {
                                ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                            Text("Open Termux", color = Color(0xFF06281C), fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = {
                            TermuxBridge.send(ctx, TermuxBridge.bannerScript())
                            lastSent = "banner + aliases (ds, dspy, dsfix)"
                        }) { Text("Install Hakatron aliases", color = Cyan, fontSize = 12.sp) }
                    }
                }
                if (!verdict.genuine) {
                    Spacer(Modifier.height(8.dp))
                    Text("Without a genuine Termux, RUN_COMMAND installs cannot work. Use the built-in " +
                        "Alpine engine instead — it needs no other app at all (System tab).",
                        color = Dim, fontSize = 10.5.sp, lineHeight = 15.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("INSTALL IN TERMUX", color = Dim, fontSize = 9.5.sp, letterSpacing = 1.1.sp)
            Spacer(Modifier.weight(1f))
            Text(if (foreground) "watch it run" else "background",
                color = if (foreground) Cyan else Dim, fontSize = 9.5.sp,
                modifier = Modifier.clickable { foreground = !foreground })
        }
        Spacer(Modifier.height(6.dp))
        // two-column list of install buttons
        Column {
            TermuxBridge.INSTALLS.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEach { (label, cmd) ->
                        Surface(shape = RoundedCornerShape(11.dp), color = Surf,
                            border = BorderStroke(1.dp, LineC),
                            modifier = Modifier.weight(1f).clickable {
                                val ok = TermuxBridge.send(ctx, cmd, foreground)
                                lastSent = if (ok) "$label — sent to Termux" else
                                    "Termux refused the intent — open Termux once, allow external apps, retry"
                            }) {
                            Column(Modifier.padding(10.dp)) {
                                Text(label, color = Txt, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                Text("pkg install", color = Dim, fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LINUX DISTROS (PROOT-DISTRO)", color = Dim, fontSize = 9.5.sp,
                letterSpacing = 1.1.sp)
            Spacer(Modifier.weight(1f))
            Text("refresh", color = Cyan, fontSize = 9.5.sp, modifier = Modifier.clickable {
                TermuxBridge.send(ctx, TermuxBridge.refreshDistrosCmd(), foreground)
                lastSent = "asked Termux for the installed distro list — give it a few seconds"
            })
        }
        Spacer(Modifier.height(6.dp))
        Text("One tap installs a full Ubuntu, Debian, Kali, Alpine or Arch inside Termux. " +
            "Then log in with proot-distro login <name>.", color = Dim, fontSize = 10.sp,
            lineHeight = 14.sp)
        Spacer(Modifier.height(7.dp))
        TermuxBridge.DISTROS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { (alias, label) ->
                    val installed = distros.any { it.contains(alias, true) }
                    Surface(shape = RoundedCornerShape(11.dp),
                        color = if (installed) Color(0xFF10251D) else Surf,
                        border = BorderStroke(1.dp, if (installed) Color(0xFF2E6B57) else LineC),
                        modifier = Modifier.weight(1f).clickable {
                            TermuxBridge.send(ctx, TermuxBridge.installDistroCmd(alias), foreground)
                            lastSent = "$label — Termux is downloading the rootfs. " +
                                "500 MB+ on first install; the list refreshes when it finishes."
                        }) {
                        Column(Modifier.padding(10.dp)) {
                            Text(label, color = if (installed) Color(0xFF7BF3BE) else Txt,
                                fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            Text(if (installed) "installed · tap to reinstall" else
                                "proot-distro install", color = Dim, fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (distros.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            distros.take(6).forEach {
                Text("· " + it, color = Color(0xFF7BF3BE), fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TERMUX OUTPUT", color = Dim, fontSize = 9.5.sp, letterSpacing = 1.1.sp)
            Spacer(Modifier.weight(1f))
            Text("read log", color = Cyan, fontSize = 9.5.sp, modifier = Modifier.clickable {
                log = SharedFiles.buildLog()
                if (log == null) lastSent = "No log yet. Builds sent to Termux write " +
                    SharedFiles.DIR + "/last-build.log"
            })
        }
        Spacer(Modifier.height(6.dp))
        Text("Termux has its own user account, so Hakatron cannot stream its screen. " +
            "Every build we send is teed into a log file in the shared folder — that is what " +
            "this panel reads.", color = Dim, fontSize = 10.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(7.dp))
        val l = log
        if (l == null) {
            Surface(shape = RoundedCornerShape(11.dp), color = Surf,
                border = BorderStroke(1.dp, LineC), modifier = Modifier.fillMaxWidth()) {
                Text("no log yet — run a build with the Termux engine, then read log",
                    color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp))
            }
        } else {
            Surface(shape = RoundedCornerShape(11.dp), color = Color(0xFF080A0E),
                border = BorderStroke(1.dp, LineC), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(l.takeLast(4000), color = Color(0xFF9FB2C9), fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("clear log", color = Amber, fontSize = 9.5.sp,
                        modifier = Modifier.clickable { SharedFiles.clearLog(); log = null })
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("CUSTOM COMMAND", color = Dim, fontSize = 9.5.sp, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(custom, { custom = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("pkg install -y ffmpeg", color = Color(0xFF5C6478), fontSize = 11.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, color = Txt),
            minLines = 2,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet,
                unfocusedBorderColor = LineC, focusedContainerColor = Color(0xFF0A0C11),
                unfocusedContainerColor = Color(0xFF0A0C11)))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(onClick = {
                if (custom.isNotBlank()) {
                    val ok = TermuxBridge.send(ctx, custom, foreground)
                    lastSent = if (ok) "sent: ${custom.take(60)}" else "Termux refused the intent"
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                Text("Send to Termux", fontSize = 12.sp)
            }
            OutlinedButton(onClick = {
                TermuxBridge.send(ctx, "cd ~/devstudio 2>/dev/null || mkdir -p ~/devstudio; cd ~/devstudio; " +
                    "ls -la; echo; echo 'Hakatron shell — type your commands'; exec bash -l", true)
                lastSent = "opened a Hakatron shell inside Termux"
            }) { Text("Open Hakatron shell", color = Cyan, fontSize = 12.sp) }
        }

        lastSent?.let {
            Spacer(Modifier.height(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2B22)),
                shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(it, color = Color(0xFF7BF3BE), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Termux runs it in its own app — its output stays in Termux, not here.",
                        color = Color(0xFFA9C7C0), fontSize = 9.5.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("HOW THE TWO ENGINES DIFFER", color = Dim, fontSize = 9.5.sp, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(6.dp))
        listOf(
            "Built-in engine (Alpine + PRoot): output comes back into Hakatron, Build Studio can read errors and fix them. Needs no other app.",
            "Termux engine: real Termux packages, faster CPU (no ptrace), and you can use the full Termux ecosystem — but Termux is a separate app, so Hakatron cannot read its output.",
            "Rule of thumb: Build Studio → built-in engine. Big toolchains or Termux-specific tools → Termux."
        ).forEach { Text("• $it", color = Color(0xFFC6CBD9), fontSize = 11.sp, lineHeight = 16.sp) }
        Spacer(Modifier.height(24.dp))
    }
}

/** Compact Termux card used on Home / System so the entry point is always one tap away. */
@Composable
fun TermuxCard(verdict: TermuxVerdict, onOpen: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surf), shape = RoundedCornerShape(15.dp),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("REAL TERMUX", color = Dim, fontSize = 9.sp, letterSpacing = 1.1.sp)
                Text(
                    when {
                        !verdict.installed -> "Not installed — tap for setup"
                        verdict.genuine -> "Genuine ${verdict.version} · one-tap installs"
                        else -> "Rejected build — tap to see why"
                    },
                    color = when {
                        !verdict.installed -> Amber
                        verdict.genuine -> Green
                        else -> Red
                    }, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("pkg install python · node · rust · openjdk-17 · proot-distro",
                    color = Dim, fontSize = 9.5.sp)
            }
            Text("›", color = Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
