package dev.devstudio.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.devstudio.ide.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real terminal for the built-in Alpine guest. Keeps a live shell process so
 * `cd` and exported variables persist between commands (no daemon needed).
 */
@Composable
fun TerminalScreen(ready: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var cwd by remember { mutableStateOf("/root") }
    var live by remember { mutableStateOf<java.lang.Process?>(null) }
    val listState = rememberLazyListState()
    val history = remember { mutableListOf<String>() }
    var histAt by remember { mutableStateOf(-1) }

    fun shell(): java.lang.Process? {
        live?.let { if (it.isAlive) return it }
        val p = Engine.shell(ctx) ?: return null
        live = p
        return p
    }

    fun push(s: String) {
        s.split("\n").forEach { lines.add(it) }
        while (lines.size > 800) lines.removeAt(0)
    }

    LaunchedEffect(ready) {
        if (ready && lines.isEmpty()) {
            push("Hakatron shell · Alpine guest · type `help` for a few starters")
            push("")
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) runCatching { listState.animateScrollToItem(lines.size - 1) }
    }

    fun send(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty() || busy) return
        history.add(c)
        histAt = -1
        push("\$ " + c)
        input = ""
        if (c == "clear" || c == "cls") {
            lines.clear(); return
        }
        if (c == "help") {
            push("  ls, pwd, cd <dir>          browse files")
            push("  cat <file>                 read a file")
            push("  python3 -c 'print(1)'      run python")
            push("  gcc / node / javac         compilers included")
            push("  apk add <pkg>              install (needs network)")
            push("  clear                      empty the screen")
            return
        }
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                val p = shell() ?: return@withContext "shell not available — finish setup first"
                Engine.runIn(p, c, 8_000).replace("\u001b[", "\u0001[")
            }
            // real cwd, asked from the guest so `cd ..` and `cd ~` are honest
            if (c.startsWith("cd ") || c == "cd") {
                val p = withContext(Dispatchers.IO) { Engine.runIn(shell(), "pwd") }
                p.lineSequence().lastOrNull { it.trim().startsWith("/") }?.let { cwd = it.trim() }
            }
            val cd = Regex("^cd\\s+(\\S+)\$").find(c)
            if (cd != null) {
                val t = cd.groupValues[1]
                cwd = when {
                    t == "~" || t == "/root" -> "/root"
                    t.startsWith("/") -> t
                    else -> (cwd.trimEnd('/') + "/" + t)
                }
            }
            if (out.isNotBlank()) push(out.trimEnd())
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Dot(Color(0xFF5BD48A)); Text("  shell", color = Dim, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(cwd, color = Cyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, modifier = Modifier.clickable { send("ls") })
            Spacer(Modifier.width(10.dp))
            Text(if (busy) "running…" else "clear", color = if (busy) Amber else Dim, fontSize = 11.sp,
                modifier = Modifier.clickable { if (!busy) lines.clear() })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(lines) { l ->
                Text(cleanAnsi(l), color = if (l.startsWith("\$ ")) Color(0xFF7EE3A2) else Txt,
                    fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
            }
            item { Spacer(Modifier.height(6.dp)) }
        }
        Row(Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(12.dp))
            .border(1.dp, LineC, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("\$", color = Green, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                value = input, onValueChange = { input = it.replace("\n", "") },
                textStyle = TextStyle(color = Txt, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Cyan),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                modifier = Modifier.weight(1f))
            if (input.isNotBlank()) Text("run", color = Cyan, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.clickable { send(input) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            listOf("ls -la", "pwd", "cat /etc/os-release", "python3 -V", "df -h /", "uname -a")
                .forEach { q ->
                    Box(Modifier.padding(end = 6.dp).background(Surf, RoundedCornerShape(8.dp))
                        .border(1.dp, LineC, RoundedCornerShape(8.dp))
                        .clickable { send(q) }.padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Text(q, color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
        }
    }
}

private fun cleanAnsi(s: String): String =
    s.replace(Regex("\u0001\\[[0-9;]*[A-Za-z]"), "").replace("\u0001[", "")

@Composable
internal fun Dot(c: Color) = Box(Modifier.size(7.dp).background(c, RoundedCornerShape(99.dp)))
