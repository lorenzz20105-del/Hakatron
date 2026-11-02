package dev.devstudio.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.devstudio.ide.core.ShellSession
import dev.devstudio.ide.core.VtScreen
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * TerminalScreen — a real terminal, not a text box.
 *
 * The grid in [ShellSession.screen] is the source of truth: escape sequences are
 * interpreted (colours, cursor movement, alternate screen), output arrives as raw
 * bytes from a live shell that owns a pseudo-terminal, and keystrokes go straight
 * to it — so `vi`, `top`, `less`, `apk` and interactive Python all work the way
 * they do in Termux, with no Termux installed.
 */
@Composable
fun TerminalScreen(ready: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    var ctrl by remember { mutableStateOf(false) }
    var fontSize by remember { mutableStateOf(11f) }
    var tick by remember { mutableStateOf(0) }

    // Every tab is its own live ShellSession — a real second shell, not a view of
    // the first. State that belongs to a shell lives on the tab, so switching tabs
    // never restarts anything and never shows another shell's output.
    class ShellTab(val s: ShellSession, val n: Int) {
        var mode by mutableStateOf("starting")
        var title by mutableStateOf("shell $n")
        var dead by mutableStateOf(false)
        var version by mutableStateOf(0)
    }

    val tabsState = remember { androidx.compose.runtime.mutableStateListOf<ShellTab>() }
    var cur by remember { mutableStateOf(0) }
    var nextN by remember { mutableStateOf(1) }

    fun newTab() {
        if (!ready) return
        val s = ShellSession(ctx)
        val tab = ShellTab(s, nextN)
        nextN++
        tabsState.add(tab)
        cur = tabsState.lastIndex
        s.start(
            onChange = {
                tab.mode = s.mode
                tab.title = s.screen.title.ifBlank { tab.title }
                if (!s.running) tab.dead = true
            },
            onExit = { tab.dead = true }
        )
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(ready, tick) {
        if (!ready) return@LaunchedEffect
        if (tabsState.isEmpty()) newTab() else delay(120)
        delay(120)
        tabsState.getOrNull(cur)?.let { it.mode = it.s.mode }
    }

    DisposableEffect(Unit) { onDispose { tabsState.forEach { runCatching { it.s.close() } } } }

    val tab = tabsState.getOrNull(cur)
    val session = tab?.s
    val mode = tab?.mode ?: "starting"
    val title = tab?.title ?: "shell"
    val dead = tab?.dead ?: false

    // repaint whenever any tab's emulator moves
    LaunchedEffect(tabsState.size, tick) {
        while (true) {
            tabsState.forEach { t ->
                val v = t.s.version.get()
                if (v != t.version) t.version = v
            }
            delay(70)
        }
    }

    val version = tab?.version ?: 0
    val lines = remember(version, fontSize) { session?.screen?.lines() ?: emptyList() }
    var autoScroll by remember { mutableStateOf(true) }
    LaunchedEffect(lines.size, version) {
        if (autoScroll && lines.isNotEmpty())
            runCatching { listState.scrollToItem(lines.size - 1) }
    }

    fun send1(text: String) { session?.write(text) }

    Column(Modifier.fillMaxSize().background(Pal.ink)) {

        // ------------------------------------------------------------- header
        Row(
            Modifier.fillMaxWidth().padding(start = 13.dp, end = 8.dp, top = 9.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // a live dot: green while the shell is alive, rose when it exits
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            !ready -> Pal.surface3
                            dead -> Pal.roseSoft
                            else -> Pal.mintSoft
                        }
                    ),
                contentAlignment = Alignment.Center
            ) { Text("❯", color = when { !ready -> Pal.faint; dead -> Pal.rose; else -> Pal.mint },
                fontSize = 13.sp, fontWeight = FontWeight.Black) }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !ready -> "Shell"
                        dead -> "shell exited"
                        else -> title.ifBlank { "shell" }
                    },
                    color = Pal.text, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val land = session?.userland ?: "alpine"
                    Text(
                        when {
                            !ready -> "engine not set up"
                            land == "termux" -> "termux · native binaries"
                            else -> "alpine · proot guest"
                        },
                        color = Pal.faint, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1
                    )
                }
            }
            // truthful chips: which userland, and pty vs pipes
            if (ready) {
                StatusPill(if (mode == "pty") "pty" else "pipes",
                    if (mode == "pty") Pal.mint else Pal.amber, filled = true)
                Spacer(Modifier.width(5.dp))
                StatusPill(if (dead) "exited" else "live",
                    if (dead) Pal.rose else Pal.mint)
            }
        }

        // toolbar: font, follow-tail, restart — the three things you actually touch
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TermAction("A-") { fontSize = (fontSize - 1f).coerceIn(8f, 20f) }
            Spacer(Modifier.width(5.dp))
            TermAction("A+") { fontSize = (fontSize + 1f).coerceIn(8f, 20f) }
            Spacer(Modifier.width(5.dp))
            TermAction(if (autoScroll) "follow on" else "follow off",
                if (autoScroll) Pal.mint else Pal.faint) { autoScroll = !autoScroll }
            Spacer(Modifier.weight(1f))
            TermAction("restart", Pal.cyan) {
                tab?.let { t -> runCatching { t.s.close() }; tabsState.remove(t); tick++ }
            }
        }

        if (!ready) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(26.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("The shell runs inside the built-in Linux guest.",
                    color = Pal.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Set the engine up in System and come back: you get a real pty, a real shell " +
                        "and a real kernel view, with nothing installed outside this app.",
                    color = Pal.faint, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
            return@Column
        }

        // tab strip — one tab per live shell, exactly like a terminal in an IDE
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            tabsState.forEachIndexed { i, t ->
                val on = i == cur
                Pressable(onClick = { cur = i }, scaleDown = 0.95f) {
                    Row(
                        Modifier.clip(RoundedCornerShape(7.dp))
                            .background(if (on) Pal.surface3 else Pal.surface)
                            .border(1.dp,
                                if (on) Pal.violet.copy(alpha = 0.45f) else Pal.line,
                                RoundedCornerShape(7.dp))
                            .padding(start = 9.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(5.dp).clip(RoundedCornerShape(50))
                            .background(if (t.dead) Pal.rose else Pal.mint))
                        Spacer(Modifier.width(6.dp))
                        Text(t.title.take(18), color = if (on) Pal.text else Pal.dim,
                            fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Text("×", color = Pal.faint, fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                runCatching { t.s.close() }
                                tabsState.remove(t)
                                cur = cur.coerceIn(0, (tabsState.size - 1).coerceAtLeast(0))
                            })
                    }
                }
            }
            Pressable(onClick = { newTab() }, scaleDown = 0.9f) {
                Box(
                    Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Pal.violetSoft),
                    contentAlignment = Alignment.Center
                ) { Text("＋", color = Pal.violet, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }

        // --------------------------------------------------------------- grid
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures {
                        runCatching { focus.requestFocus() }
                        keyboard?.show()
                    }
                }
        ) {
            LazyColumn(state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(lines) { line ->
                    Text(
                        renderLine(line),
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.32f).sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        modifier = Modifier
                            .background(backgroundColorOf(line))
                            .padding(horizontal = 1.dp)
                    )
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
            // jump to the bottom when you have scrolled away and output arrived
            if (!autoScroll) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp)
                        .clip(RoundedCornerShape(50)).background(Pal.violet)
                        .clickable {
                            autoScroll = true
                            scope.launch { runCatching { listState.scrollToItem(
                                (lines.size - 1).coerceAtLeast(0)) } }
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) { Text("↓ latest", color = Pal.ink, fontSize = 10.sp, fontWeight = FontWeight.Black) }
            }
        }

        // ------------------------------------------------- invisible key sink
        BasicTextField(
            value = "",
            onValueChange = { typed ->
                typed.forEach { ch ->
                    when (ch) {
                        '\n' -> send1("\r")
                        else -> {
                            if (ctrl) {
                                val code = ch.uppercaseChar().code
                                send1(((code - 64).coerceIn(0, 31)).toChar().toString())
                                ctrl = false
                            } else send1(ch.toString())
                        }
                    }
                }
            },
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false
            ),
            keyboardActions = KeyboardActions(onSend = { send1("\r") }),
            modifier = Modifier.height(1.dp).fillMaxWidth().focusRequester(focus)
        )

        // ------------------------------------------------------- extra keys
        Column(Modifier.fillMaxWidth().background(Pal.surface)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.lineSoft))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                KeyCap("CTRL", Pal.violet, active = ctrl) { ctrl = !ctrl }
                KeyCap("ESC", Pal.amber) { session?.esc(); ctrl = false }
                KeyCap("TAB") { session?.tab() }
                KeyCap("^C", Pal.rose) { session?.interrupt() }
                KeyCap("^D", Pal.rose) { session?.eof() }
                KeyCap("↑") { session?.arrow(true) }
                KeyCap("↓") { session?.arrow(false) }
                KeyCap("←") { session?.arrowLR(false) }
                KeyCap("→") { session?.arrowLR(true) }
                KeyCap("|") { send1("|") }
                KeyCap("-") { send1("-") }
                KeyCap("/") { send1("/") }
                KeyCap("~") { send1("~") }
                KeyCap("$") { send1("$") }
                KeyCap("\"") { send1("\"") }
                KeyCap("'") { send1("'") }
                KeyCap("⏎", Pal.mint) { send1("\r") }
            }
        }

        // ---------------------------------------------------- quick commands
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .background(Pal.ink).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ls -la", "pwd", "cat /etc/os-release", "df -h /", "apk update",
                "python3 -V", "top", "clear").forEach { q ->
                Pressable(onClick = { send1(q + "\r") }, scaleDown = 0.94f) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Pal.surface2)
                            .border(1.dp, Pal.lineSoft, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(q, color = Pal.dim, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/** A small toolbar button in the terminal header. */
@Composable
private fun TermAction(label: String, tone: Color = Pal.dim, onClick: () -> Unit) {
    Pressable(onClick = onClick, scaleDown = 0.93f) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) { Text(label, color = tone, fontSize = 9.5.sp, fontWeight = FontWeight.Bold) }
    }
}

/** One key of the extra-keys row — a real button, so it can hold "armed" state. */
@Composable
private fun KeyCap(label: String, tone: Color = Pal.dim, active: Boolean = false, onTap: () -> Unit) {
    Pressable(onClick = onTap, scaleDown = 0.9f) {
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (active) tone else Pal.surface2)
                .border(1.dp, if (active) tone else Pal.lineSoft, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 7.dp)
        ) {
            Text(
                label,
                color = if (active) Pal.ink else tone,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (active) FontWeight.Black else FontWeight.Normal
            )
        }
    }
}

/** xterm's default 256-colour palette, enough for every SGR sequence programs emit. */
private val PALETTE: IntArray = intArrayOf(
    0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x0FA8CD, 0xE5E5E5,
    0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF
)

private fun colourOf(index: Int, default: Color): Color {
    if (index < 0) return default
    if (index >= 0x1000000) return Color(0xFF000000.toInt() or index)
    if (index < 16) return Color(0xFF000000.toInt() or PALETTE[index])
    if (index < 232) {                                     // 6×6×6 cube
        val i = index - 16
        val r = if (i / 36 == 0) 0 else 55 + (i / 36) * 40
        val g = if ((i / 6) % 6 == 0) 0 else 55 + ((i / 6) % 6) * 40
        val b = if (i % 6 == 0) 0 else 55 + (i % 6) * 40
        return Color(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
    }
    val v = 8 + (index - 232) * 10                          // greyscale ramp
    return Color(0xFF000000.toInt() or (v shl 16) or (v shl 8) or v)
}

private fun renderLine(runs: List<VtScreen.Run>): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        val fg0 = if (run.attr.reverse) run.attr.bg else run.attr.fg
        val bg0 = if (run.attr.reverse) run.attr.fg else run.attr.bg
        val fg = colourOf(if (fg0 == -1 && run.attr.reverse) 0 else fg0, Color(0xFFD8DEE9))
        val bg = if (bg0 == -1) null else colourOf(bg0, Color(0xFF07080B))
        withStyle(
            SpanStyle(
                color = if (run.attr.hidden) Color.Transparent else fg,
                background = bg ?: Color.Unspecified,
                fontWeight = if (run.attr.bold) FontWeight.Bold else null,
                textDecoration = when {
                    run.attr.underline && run.attr.strike ->
                        androidx.compose.ui.text.style.TextDecoration.combine(
                            listOf(androidx.compose.ui.text.style.TextDecoration.Underline,
                                androidx.compose.ui.text.style.TextDecoration.LineThrough))
                    run.attr.underline -> androidx.compose.ui.text.style.TextDecoration.Underline
                    run.attr.strike -> androidx.compose.ui.text.style.TextDecoration.LineThrough
                    else -> null
                },
                fontStyle = if (run.attr.italic) androidx.compose.ui.text.font.FontStyle.Italic else null
            )
        ) { append(run.text.ifEmpty { " " }) }
    }
}

private fun backgroundColorOf(runs: List<VtScreen.Run>): Color {
    val bg = runs.firstOrNull { it.attr.bg >= 0 && !it.attr.reverse }?.attr?.bg ?: return Color.Transparent
    return colourOf(bg, Color.Transparent)
}

@Composable
internal fun Dot(c: Color) = Box(Modifier.size(7.dp).background(c, RoundedCornerShape(99.dp)))
