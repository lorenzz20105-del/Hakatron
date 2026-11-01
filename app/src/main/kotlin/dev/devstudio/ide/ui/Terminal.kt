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
    var session by remember { mutableStateOf<ShellSession?>(null) }
    var tick by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf("starting") }
    var title by remember { mutableStateOf("terminal") }
    var dead by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // start once, and again whenever the user asks for a fresh shell
    LaunchedEffect(ready, tick) {
        if (!ready) return@LaunchedEffect
        val s = ShellSession(ctx)
        session = s
        s.start(
            onChange = {
                mode = s.mode
                title = s.screen.title
                if (!s.running) dead = true
            },
            onExit = { dead = true }
        )
        delay(120)
        mode = s.mode
    }
    DisposableEffect(Unit) { onDispose { session?.close() } }

    // repaint whenever the emulator's version counter moves
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(session, tick) {
        while (true) {
            val v = session?.version?.get() ?: 0
            if (v != version) version = v
            delay(70)
        }
    }

    val lines = remember(version, fontSize) { session?.screen?.lines() ?: emptyList() }
    var autoScroll by remember { mutableStateOf(true) }
    LaunchedEffect(lines.size, version) {
        if (autoScroll && lines.isNotEmpty())
            runCatching { listState.scrollToItem(lines.size - 1) }
    }

    fun send1(text: String) { session?.write(text) }

    Column(Modifier.fillMaxSize().background(Pal.ink)) {
        // ------------------------------------------------------------- header
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Dot(if (dead) Red else if (ready) Color(0xFF5BD48A) else Amber)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !ready -> "engine not set up"
                    dead -> "shell exited"
                    else -> title
                },
                color = if (dead) Red else Dim, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            // Truthful about what this shell actually is: which userland, and
            // whether it got a real pty. Never a vague "terminal ready" chip.
            if (ready) {
                val land = session?.userland ?: "alpine"
                StatusPill(land + " · " + (if (land == "termux") "native" else "proot"),
                    if (land == "termux") Cyan else Dim)
                Spacer(Modifier.width(5.dp))
                StatusPill(if (mode == "pty") "pty" else "pipes",
                    if (mode == "pty") Green else Amber, filled = true)
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("A-", color = Dim, fontSize = 11.sp, modifier = Modifier.clickable {
                fontSize = (fontSize - 1f).coerceIn(8f, 18f)
            })
            Text("A+", color = Dim, fontSize = 11.sp,
                modifier = Modifier.padding(start = 10.dp).clickable {
                    fontSize = (fontSize + 1f).coerceIn(8f, 18f)
                })
            Text(if (autoScroll) "⇣ on" else "⇣ off", color = if (autoScroll) Cyan else Dim,
                fontSize = 10.sp, modifier = Modifier.padding(start = 12.dp).clickable { autoScroll = !autoScroll })
            Text("↻", color = Dim, fontSize = 13.sp,
                modifier = Modifier.padding(start = 12.dp).clickable {
                    session?.close(); dead = false; tick++
                })
        }

        if (!ready) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.Center) {
                Text("The terminal runs inside the built-in Alpine guest.",
                    color = Txt, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("Set the engine up in System → and come back: it needs ~600 MB free, " +
                    "then this screen gives you a real pty, a real shell and a real kernel view.",
                    color = Dim, fontSize = 11.sp, lineHeight = 16.sp)
            }
            return@Column
        }

        // --------------------------------------------------------------- grid
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { runCatching { focus.requestFocus() }; keyboard?.show() } }
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                items(lines) { line ->
                    Text(
                        renderLine(line),
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.32f).sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        modifier = Modifier
                            .background(backgroundColorOf(line))
                            .padding(horizontal = 2.dp)
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }

        // ------------------------------------------------- invisible key sink
        BasicTextField(
            value = "",
            onValueChange = { typed ->
                // The IME hands us composed text; a terminal wants keystrokes.
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

        // -------------------------------------------------------- extra keys
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .background(Pal.ink)
            .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyCap("CTRL", active = ctrl) { ctrl = !ctrl }
            KeyCap("ESC", Amber) { session?.esc(); ctrl = false }
            KeyCap("TAB") { session?.tab() }
            KeyCap("^C", Red) { session?.interrupt() }
            KeyCap("^D", Red) { session?.eof() }
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
            KeyCap("⏎", Green) { send1("\r") }
        }

        // ------------------------------------------------- quick commands row
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ls -la", "pwd", "cat /etc/os-release", "df -h /", "apk update",
                "python3 -V", "top", "clear").forEach { q ->
                Box(Modifier.background(Surf, RoundedCornerShape(7.dp))
                    .border(1.dp, LineC, RoundedCornerShape(7.dp))
                    .clickable { send1(q + "\r") }
                    .padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(q, color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/** One key of the extra-keys row — a real button, so it can hold "armed" state. */
@Composable
private fun KeyCap(label: String, colour: Color = Dim, active: Boolean = false, onTap: () -> Unit) {
    Box(
        Modifier
            .background(if (active) Violet else Color(0xFF161922), RoundedCornerShape(6.dp))
            .border(1.dp, if (active) Violet else LineC, RoundedCornerShape(6.dp))
            .clickable { onTap() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (active) Color.White else colour,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
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
