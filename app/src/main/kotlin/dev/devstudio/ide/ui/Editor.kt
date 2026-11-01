package dev.devstudio.ide.ui

import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.devstudio.ide.Engine
import dev.devstudio.ide.core.Highlighter
import dev.devstudio.ide.core.Store
import dev.devstudio.ide.core.Vfs
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val Bg = Color(0xFF0B0C10)
internal val Surf = Color(0xFF15171E)
internal val Surf2 = Color(0xFF1D2029)
internal val LineC = Color(0xFF282C38)
internal val Txt = Color(0xFFE9EBF2)
internal val Dim = Color(0xFF9AA0B0)
internal val Violet = Color(0xFF8B5CF6)
internal val Cyan = Color(0xFF22D3EE)
internal val Green = Color(0xFF34D399)
internal val Amber = Color(0xFFFBBF24)
internal val Red = Color(0xFFF87171)
private val Gutter = Color(0xFF454B5C)

/** Guest file helpers — base64 both ways so quotes, $ and newlines survive the shell. */
object GuestFiles {
    fun list(run: (String) -> String, root: String = "~/projects"): List<String> {
        val out = run("find $root -type f -not -path '*/.git/*' -not -path '*/node_modules/*' " +
            "-not -path '*/build/*' -not -path '*/dist/*' -not -path '*/__pycache__/*' " +
            "2>/dev/null | head -400")
        return out.lines().map { it.trim() }
            .filter { it.startsWith("/") || it.startsWith("~") }
            .distinct()
    }

    fun read(run: (String) -> String, path: String): String {
        val out = run("base64 -w0 \"$path\" 2>/dev/null || echo MISSING")
        val b64 = out.lines().firstOrNull { !it.startsWith("[") && it.isNotBlank() } ?: return ""
        if (b64.contains("MISSING")) return ""
        return runCatching { String(Base64.decode(b64.trim(), Base64.DEFAULT)) }.getOrDefault("")
    }

    fun write(run: (String) -> String, path: String, content: String): Boolean {
        val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        val dir = path.substringBeforeLast('/', "")
        val out = run("mkdir -p \"$dir\" && echo '$b64' | base64 -d > \"$path\" && echo SAVED")
        return out.contains("SAVED")
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun mkdir(run: (String) -> String, path: String): Boolean =
        run("mkdir -p " + q(path) + " && echo MKDIR-OK").contains("MKDIR-OK")

    fun rename(run: (String) -> String, from: String, to: String): Boolean =
        run("mv " + q(from) + " " + q(to) + " && echo MOVED").contains("MOVED")

    fun delete(run: (String) -> String, path: String): Boolean =
        run("rm -rf " + q(path) + " && echo DELETED").contains("DELETED")

    fun cmdFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "py" -> "python3 \"$path\""
        "js", "mjs" -> "node \"$path\""
        "ts" -> "npx tsx \"$path\""
        "sh", "bash" -> "sh \"$path\""
        "c" -> "gcc -O1 \"$path\" -o /tmp/a.out && /tmp/a.out"
        "cpp" -> "g++ -O1 \"$path\" -o /tmp/a.out && /tmp/a.out"
        "go" -> "go run \"$path\""
        "rs" -> "cargo run"
        "java" -> "cd \"$(dirname \"$path\")\" && javac \"$(basename \"$path\")\" && " +
            "java \"$(basename \"$path\" .java)\""
        "rb" -> "ruby \"$path\""
        "php" -> "php \"$path\""
        "html" -> "cat \"$path\" | head -1"
        else -> "cat \"$path\""
    }
}

private const val LINE_H = 19f

/* ------------------------------------------------------------- code editor */

@Composable
fun EditorScreen(onPreview: (String) -> Unit = {}, initialPath: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store(ctx) }
    val run: (String) -> String = { cmd -> Engine.exec(ctx, cmd, 240_000) }

    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var tabs by remember { mutableStateOf<List<String>>(emptyList()) }
    var active by remember { mutableStateOf<String?>(null) }
    var doc by remember { mutableStateOf(TextFieldValue("")) }
    var saved by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var showFiles by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showGit by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var wrap by remember { mutableStateOf(store.wordWrap()) }
    var fontSp by remember { mutableStateOf(store.editorFont().sp) }
    var running by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }
    var showGoto by remember { mutableStateOf(false) }
    val drafts = remember { HashMap<String, TextFieldValue>() }
    val savedText = remember { HashMap<String, String>() }
    var confirmClose by remember { mutableStateOf<String?>(null) }
    val undoStack = remember { ArrayDeque<TextFieldValue>() }
    val redoStack = remember { ArrayDeque<TextFieldValue>() }

    val dirty = doc.text != saved
    val lineHeight = fontSp.value + 7f
    // splitting the whole file on every frame is O(n) per keystroke
    val lines = remember(doc.text) { doc.text.split("\n") }
    // a 5,000-line file must not lay out 5,000 line numbers
    val vs = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenPx = with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val linePx = with(density) { lineHeight.sp.toPx() }
    // very large files: highlighting is a per-frame cost, so it is switched off
    val bigFile = lines.size > 1500

    fun setDoc(v: TextFieldValue) {
        doc = v
        active?.let {
            drafts[it] = v
            Vfs.put(it, v.text)
        }
    }

    fun refresh() {
        scope.launch { files = withContext(Dispatchers.IO) { GuestFiles.list(run) } }
    }

    fun open(path: String, line: Int? = null) {
        scope.launch {
            val body = withContext(Dispatchers.IO) {
                Vfs.open(path) {
                    if (path.startsWith("/sdcard") || path.startsWith("/storage"))
                        SharedFiles.read(path) else GuestFiles.read(run, path)
                }
            }
            active = path
            val caret = if (line != null) offsetOfLine(body, line) else 0
            savedText[path] = body
            saved = body
            // a tab you come back to keeps its unsaved edits
            val draft = if (line != null) null else drafts[path]
            setDoc(draft ?: TextFieldValue(body,
                androidx.compose.ui.text.TextRange(caret.coerceIn(0, body.length))))
            output = ""
            diagnostics = emptyList()
            undoStack.clear(); redoStack.clear()
            if (path !in tabs) tabs = tabs + path
            store.addRecent(path)
        }
    }

    fun closeTab(p: String) {
        if (!Vfs.dirty(p)) Vfs.forget(p)
        drafts.remove(p)
        savedText.remove(p)
        tabs = tabs - p
        if (active == p) {
            val nxt = tabs.lastOrNull()
            if (nxt != null) open(nxt) else { active = null; doc = TextFieldValue(""); saved = "" }
        }
    }

    fun record(before: TextFieldValue) {
        if (undoStack.size >= 200) undoStack.removeFirst()
        undoStack.addLast(before)
        redoStack.clear()
    }

    fun edit(next: TextFieldValue) {
        val prev = doc
        if (next == prev) return
        record(prev)
        setDoc(smartType(prev, next))
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(doc)
        setDoc(undoStack.removeLast())
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(doc)
        setDoc(redoStack.removeLast())
    }

    fun insertAtCursor(s: String) {
        record(doc)
        val sel = doc.selection
        val text = doc.text.replaceRange(sel.start, sel.end, s)
        setDoc(TextFieldValue(text, androidx.compose.ui.text.TextRange(sel.start + s.length)))
    }

    fun withLines(block: (MutableList<String>, Int) -> Unit) {
        val list = lines.toMutableList()
        val caretLine = doc.text.take(doc.selection.start).count { it == '\n' }
        val (a, b) = block(list, caretLine) as Pair<Int, Int>
        record(doc)
        val newText = list.joinToString("\n")
        val caret = newText.split("\n").take(a).joinToString("\n").length + if (a > 0) 1 else 0
        setDoc(TextFieldValue(newText,
            androidx.compose.ui.text.TextRange((caret + b).coerceIn(0, newText.length))))
    }

    fun save(andRun: Boolean = false) {
        val p = active ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                Vfs.flush(p) { text ->
                    if (p.startsWith("/sdcard") || p.startsWith("/storage")) SharedFiles.write(p, text)
                    else GuestFiles.write(run, p, text)
                }
            }
            saved = if (ok) doc.text else saved
            if (ok) savedText[p] = doc.text
            output = if (ok) "saved $p" else "save failed"
            diagnostics = QuickReview.check(p, doc.text)
            if (ok && andRun) {
                running = true
                output = withContext(Dispatchers.IO) {
                    "$ " + GuestFiles.cmdFor(p) + "\n" + run("cd $(dirname \"$p\") && " + GuestFiles.cmdFor(p))
                }
                running = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        val kept = store.editorTabs()
        if (kept.isNotEmpty()) {
            tabs = kept
            val last = store.editorActive()
            if (last != null && last in kept) open(last)
        }
    }

    LaunchedEffect(tabs, active) {
        store.setEditorTabs(tabs)
        store.setEditorActive(active)
    }

    Column(Modifier.fillMaxSize().background(Bg)) {

        // ---------------------------------------------------------- top bar
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Highlighter.titleOf(active ?: "Code"), color = Txt, fontSize = 15.sp,
                    fontWeight = FontWeight.Black, maxLines = 1)
                Text(active ?: "no file open", color = Dim, fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            IconText("Files") { showFiles = !showFiles; refresh() }
            IconText("Find") { showFind = true }
            IconText("Git") { showGit = true }
            IconText("＋") { showNew = true }
        }

        // tabs
        if (tabs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                tabs.forEach { p ->
                    val on = p == active
                    Surface(shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp), color = if (on) Surf2 else Bg,
                        border = if (on) BorderStroke(1.dp, Violet) else null,
                        modifier = Modifier.clickable { open(p) }) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val mark = if (on) dirty
                            else drafts[p]?.text?.let { it != (savedText[p] ?: it) } ?: false
                            Text(Highlighter.titleOf(p) + if (mark) " •" else "",
                                color = if (on) Txt else Dim, fontSize = 10.5.sp)
                            if (on) {
                                Spacer(Modifier.width(7.dp))
                                Text("×", color = Dim, fontSize = 12.sp, modifier = Modifier.clickable {
                                    val unsaved = drafts[p]?.text?.let { it != (savedText[p] ?: it) } ?: false
                                    if (unsaved) confirmClose = p else closeTab(p)
                                })
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------- file sheet
        if (showFiles) {
            Surface(color = Surf, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).heightIn(max = 230.dp)) {
                Column {
                    OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().padding(8.dp),
                        placeholder = { Text("find file…", color = Dim, fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, color = Txt),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet,
                            unfocusedBorderColor = LineC))
                    LazyColumn(Modifier.padding(bottom = 6.dp)) {
                        val shown = files.filter { search.isBlank() || it.contains(search, true) }
                        if (shown.isEmpty()) item {
                            Text("no files — build a project or create one",
                                color = Dim, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                        }
                        items(shown) { f ->
                            Row(Modifier.fillMaxWidth().clickable { open(f); showFiles = false }
                                .padding(horizontal = 12.dp, vertical = 7.dp)) {
                                Text(Highlighter.titleOf(f), color = Txt, fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1)
                                Spacer(Modifier.weight(1f))
                                Text(f.substringBeforeLast('/'), color = Dim, fontSize = 8.5.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------- editor
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0D0F14))) {
            if (active == null) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No file open", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Files to open one · ＋ to create one", color = Dim, fontSize = 11.5.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showNew = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                        Text("New file from template", fontSize = 12.sp)
                    }
                }
            } else {
                val hs = rememberScrollState()
                val caretLine = doc.text.take(doc.selection.start).count { it == '\n' }
                Column(Modifier.fillMaxSize().verticalScroll(vs)) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 60.dp)) {
                        // numbers only make sense when one line is one row: with wrap on the
                        // status bar carries Ln/Col instead of a drifting gutter
                        if (!wrap) {
                            // virtualised gutter: only the numbers you can actually see
                            val firstVis = ((vs.value / linePx).toInt() - 12).coerceAtLeast(0)
                            val lastVis = (((vs.value + screenPx) / linePx).toInt() + 12)
                                .coerceAtMost(lines.size)
                            Column(Modifier.width(38.dp)) {
                                Spacer(Modifier.height((firstVis * lineHeight).dp))
                                for (i in firstVis until lastVis) {
                                    val here = i == caretLine
                                    Text("${i + 1}",
                                        color = if (here) Cyan else Gutter,
                                        fontSize = fontSp,
                                        fontWeight = if (here) FontWeight.Bold else FontWeight.Normal,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = lineHeight.sp,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.fillMaxWidth().padding(end = 6.dp))
                                }
                                Spacer(Modifier.height(((lines.size - lastVis) * lineHeight).dp))
                            }
                            Box(Modifier.width(1.dp).height((lines.size * lineHeight + 8).dp)
                                .background(LineC))
                        }
                        val lang = if (bigFile) "" else Highlighter.langOf(active!!)
                        if (wrap) {
                            CodeField(doc, { edit(it) }, fontSp.value, lineHeight, lang,
                                Modifier.weight(1f).padding(start = 10.dp))
                        } else {
                            Box(Modifier.horizontalScroll(hs)) {
                                CodeField(doc, { edit(it) }, fontSp.value, lineHeight, lang,
                                    Modifier.widthIn(min = 340.dp).padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }

        // diagnostics strip (from the same review used by Build Studio)
        if (diagnostics.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF2A1A14)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("⚠ ${diagnostics.first()}", color = Amber, fontSize = 10.sp, maxLines = 1)
                if (diagnostics.size > 1) {
                    Spacer(Modifier.weight(1f))
                    Text("+${diagnostics.size - 1} more", color = Dim, fontSize = 10.sp)
                }
            }
        }

        // --------------------------------------------------- quick action bar
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Surf2)
            .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            QAction("⤺", accent = if (undoStack.isEmpty()) Dim else Txt) { undo() }
            QAction("⤼", accent = if (redoStack.isEmpty()) Dim else Txt) { redo() }
            QAction("Ln") { showGoto = true }
            QAction("Find") { showSearch = true }
            QAction("Dup") { withLines { l, n -> l.add(n + 1, l[n]); n + 1 to 0 } }
            QAction("↑") { withLines { l, n -> if (n > 0) { swap(l, n, n - 1); n - 1 to 0 } else n to 0 } }
            QAction("↓") { withLines { l, n -> if (n < l.size - 1) { swap(l, n, n + 1); n + 1 to 0 } else n to 0 } }
            QAction("#") { withLines { l, n ->
                val t = l[n].trimStart()
                l[n] = if (t.startsWith("#") || t.startsWith("//"))
                    l[n].replaceFirst(Regex("^(\\s*)(#|//)\\s?"), "$1") else {
                    val pre = if (Highlighter.langOf(active ?: "") in listOf("py", "sh", "rb", "yaml")) "# " else "// "
                    l[n].replaceFirst(Regex("^(\\s*)"), "$1$pre")
                }
                n to 0
            } }
            QAction(if (wrap) "Wrap on" else "Wrap off",
                accent = if (wrap) Cyan else Dim) { wrap = !wrap; store.setWordWrap(wrap) }
            QAction("A-") { fontSp = (fontSp.value - 1f).coerceAtLeast(9f).sp; store.setEditorFont(fontSp.value.toInt()) }
            QAction("A+") { fontSp = (fontSp.value + 1f).coerceAtMost(22f).sp; store.setEditorFont(fontSp.value.toInt()) }
            Spacer(Modifier.width(4.dp))
            QAction("Save", accent = Green) { save() }
            QAction(if (running) "…" else "Run", accent = Violet) { save(andRun = true) }
            if ((active ?: "").endsWith(".html")) QAction("Preview", accent = Cyan) {
                onPreview("file://" + (active ?: "").replace("~", "/root"))
            }
        }

        // ------------------------------------------------------ symbol row(s)
        Column(Modifier.fillMaxWidth().background(Surf2)) {
            SymbolsRow(listOf("\t", "{", "}", "(", ")", "[", "]", "<", ">", "=", "+", "-", "*", "/",
                "\"", "'", ":", ";", ",", ".", "_", "#", "$", "&", "|", "!")) { insertAtCursor(it) }
            SymbolsRow(listOf("->", "=>", "==", "!=", "::", "&&", "||", "  ", "\n")) {
                insertAtCursor(if (it == "  ") "  " else it)
            }
        }

        // --------------------------------------------------------- status bar
        Row(Modifier.fillMaxWidth().background(Color(0xFF080A0E)).padding(horizontal = 10.dp, vertical = 3.dp)) {
            val caret = doc.selection.end
            val line = doc.text.take(caret).count { it == '\n' } + 1
            val col = caret - doc.text.take(caret).lastIndexOf('\n')
            Text(Highlighter.langOf(active ?: "").ifBlank { "text" }.uppercase(), color = Dim, fontSize = 8.5.sp)
            Spacer(Modifier.weight(1f))
            Text("Ln $line, Col $col", color = Dim, fontSize = 8.5.sp)
            Spacer(Modifier.width(10.dp))
            Text("${lines.size} lines", color = Dim, fontSize = 8.5.sp)
            Spacer(Modifier.width(10.dp))
            Text(if (bigFile) "large · highlight off" else "${doc.text.length} chars",
                color = if (bigFile) Amber else Dim, fontSize = 8.5.sp)
            Spacer(Modifier.width(10.dp))
            Text(if (dirty) "● modified" else "saved", color = if (dirty) Amber else Green, fontSize = 8.5.sp)
        }

        // output drawer
        if (output.isNotBlank()) {
            Surface(color = Color(0xFF080A0E), modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
                Column(Modifier.padding(10.dp).verticalScroll(rememberScrollState())) {
                    Text(output, color = Color(0xFF9FB2C9), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                }
            }
        }
    }

    // ------------------------------------------------------------- dialogs
    if (showFind) {
        FindReplaceDialog(doc, onApply = { record(doc); setDoc(smartType(doc, it)) },
            onClose = { showFind = false })
    }
    if (showNew) {
        NewFileDialog(onClose = { showNew = false }) { path, body ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    GuestFiles.write(run, path, body)
                    if (path.endsWith(".py")) run("apk add --no-cache python3 >/dev/null 2>&1; true")
                }
                refresh()
                open(path)
                showNew = false
            }
        }
    }
    if (showGit) {
        GitSheet(run, active ?: "", onClose = { showGit = false })
    }
    confirmClose?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmClose = null },
            containerColor = Surf,
            title = { Text("Unsaved changes", color = Txt, fontSize = 15.sp) },
            text = { Text("${Highlighter.titleOf(p)} has edits that are not saved yet.",
                color = Dim, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = { closeTab(p); confirmClose = null }) {
                    Text("Discard", color = Red, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = null }) {
                    Text("Keep editing", color = Cyan, fontSize = 12.sp)
                }
            }
        )
    }
    if (showGoto) {
        var target by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGoto = false },
            containerColor = Surf,
            title = { Text("Go to line", color = Txt, fontSize = 15.sp) },
            text = {
                Column {
                    OutlinedTextField(target, { target = it }, singleLine = true,
                        label = { Text("line number (1-${lines.size})", fontSize = 11.sp) },
                        textStyle = TextStyle(fontSize = 13.sp, color = Txt,
                            fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet,
                            unfocusedBorderColor = LineC))
                    Text("jump inside the open file", color = Dim, fontSize = 10.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = target.trim().toIntOrNull()
                    if (n != null && n >= 1) {
                        val at = offsetOfLine(doc.text, n)
                        doc = TextFieldValue(doc.text,
                            androidx.compose.ui.text.TextRange(at.coerceIn(0, doc.text.length)))
                    }
                    showGoto = false
                }) { Text("Go", color = Cyan, fontSize = 12.sp) }
            },
            dismissButton = { TextButton(onClick = { showGoto = false }) { Text("Cancel", color = Dim) } }
        )
    }
    if (showSearch) {
        ProjectSearchDialog(run, onOpen = { path, line ->
            showSearch = false
            open("~/projects/$path", line)
        }, onClose = { showSearch = false })
    }
}

/** Offset of the first character of a 1-based line. */
private fun offsetOfLine(text: String, line: Int): Int {
    if (line <= 1) return 0
    var seen = 1
    text.forEachIndexed { i, c -> if (c == '\n') { seen++; if (seen == line) return i + 1 } }
    return text.length
}

private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')

/**
 * smartType — the small things a phone keyboard can't do for you:
 *   ( → ()          with the caret between them
 *   ) → steps over  the closing bracket that is already there
 *   ⏎ → keeps the current indentation, and indents one more level after { ( [
 * It only ever reacts to one character typed at a collapsed caret, so paste,
 * multi-line edits and IME composition are left exactly as the keyboard sent them.
 */
private fun smartType(prev: TextFieldValue, next: TextFieldValue): TextFieldValue {
    if (!prev.selection.collapsed || !next.selection.collapsed) return next
    if (next.text.length != prev.text.length + 1) return next
    val at = prev.selection.start
    if (at < 0 || at >= next.text.length) return next
    if (next.text.removeRange(at, at + 1) != prev.text) return next
    val ch = next.text[at]

    // a closing character that is already waiting there: step over it
    if (ch in ")]}\"'" && ch != '`' && prev.text.getOrNull(at) == ch)
        return TextFieldValue(prev.text, androidx.compose.ui.text.TextRange(at + 1))

    val closer = PAIRS[ch]
    if (closer != null) {
        val text = next.text.substring(0, at + 1) + closer + next.text.substring(at + 1)
        return TextFieldValue(text, androidx.compose.ui.text.TextRange(at + 1))
    }

    if (ch == '\n') {
        val lineStart = prev.text.lastIndexOf('\n', at - 1) + 1
        val head = prev.text.substring(lineStart, at)
        val indent = head.takeWhile { it == ' ' || it == '\t' }
        val trimmed = head.trimEnd()
        val extra = if (trimmed.endsWith("{") || trimmed.endsWith("(") ||
            trimmed.endsWith("[") || trimmed.endsWith(":")) "    " else ""
        val pad = indent + extra
        if (pad.isEmpty()) return next
        val text = next.text.substring(0, at + 1) + pad + next.text.substring(at + 1)
        return TextFieldValue(text, androidx.compose.ui.text.TextRange(at + 1 + pad.length))
    }
    return next
}

private fun swap(l: MutableList<String>, a: Int, b: Int) {
    val t = l[a]; l[a] = l[b]; l[b] = t
}

@Composable
private fun IconText(label: String, onClick: () -> Unit) {
    Text(label, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 7.dp, vertical = 4.dp))
}

@Composable
private fun QAction(label: String, accent: Color = Dim, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(7.dp), color = Surf, border = BorderStroke(1.dp, LineC),
        modifier = Modifier.clickable { onClick() }) {
        Text(label, color = accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

@Composable
private fun SymbolsRow(keys: List<String>, onKey: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 5.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        keys.forEach { k ->
            Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF23262E),
                modifier = Modifier.clickable { onKey(k) }) {
                Text(if (k == "\t") "⇥" else if (k == "\n") "⏎" else k, color = Txt, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
            }
        }
    }
}

/** Simple but useful review — the same rules Build Studio uses. */
object QuickReview {
    fun check(path: String, text: String): List<String> {
        val out = ArrayList<String>()
        text.lines().forEachIndexed { i, l ->
            if (path.endsWith(".py")) {
                if (Regex("^\\s*except\\s*:").containsMatchIn(l)) out.add("line ${i + 1}: bare except")
                if (Regex("==\\s*None").containsMatchIn(l)) out.add("line ${i + 1}: == None (use 'is None')")
                if (Regex("\\beval\\(|\\bexec\\(").containsMatchIn(l)) out.add("line ${i + 1}: eval/exec")
                if (Regex("(?i)(api[_-]?key|secret|token)\\s*=\\s*['\"][^'\"]{8,}").containsMatchIn(l))
                    out.add("line ${i + 1}: hardcoded secret")
            }
            if (path.endsWith(".js") || path.endsWith(".ts")) {
                if (Regex("[^=!<>]==[^=]").containsMatchIn(l)) out.add("line ${i + 1}: use ===")
            }
        }
        return out
    }
}

private class SyntaxTransform(private val lang: String) :
    androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString):
        androidx.compose.ui.text.input.TransformedText =
        androidx.compose.ui.text.input.TransformedText(
            Highlighter.highlight(text.text, lang),
            androidx.compose.ui.text.input.OffsetMapping.Identity)
}

/* --------------------------------------------------------------- dialogs */

@Composable
private fun FindReplaceDialog(doc: TextFieldValue, onApply: (TextFieldValue) -> Unit, onClose: () -> Unit) {
    var find by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Surf,
        title = { Text("Find & replace", color = Txt, fontSize = 15.sp) },
        text = {
            Column {
                OutlinedTextField(find, { find = it }, label = { Text("find", fontSize = 11.sp) },
                    singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(repl, { repl = it }, label = { Text("replace with", fontSize = 11.sp) },
                    singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet))
                if (note.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(note, color = Cyan, fontSize = 10.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        val idx = doc.text.indexOf(find, doc.selection.end)
                        if (find.isBlank()) { note = "type something to find"; return@TextButton }
                        if (idx < 0) note = "not found"
                        else {
                            onApply(TextFieldValue(doc.text,
                                androidx.compose.ui.text.TextRange(idx, idx + find.length)))
                            note = "found at $idx"
                        }
                    }) { Text("Find next", color = Cyan, fontSize = 12.sp) }
                    TextButton(onClick = {
                        if (find.isBlank()) { note = "type something to find"; return@TextButton }
                        val n = doc.text.windowed(find.length, 1, true).count { it == find }
                        onApply(TextFieldValue(doc.text.replace(find, repl)))
                        note = "replaced $n occurrence(s)"
                    }) { Text("Replace all", color = Green, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Dim) } }
    )
}

@Composable
private fun NewFileDialog(onClose: () -> Unit, onCreate: (String, String) -> Unit) {
    data class Tpl(val name: String, val path: String, val body: String)
    val templates = listOf(
        Tpl("Python script", "hello.py", "def greet(name: str) -> str:\n    return f\"Hello, {name}!\"\n\n\n" +
            "if __name__ == \"__main__\":\n    print(greet(\"DevStudio\"))\n"),
        Tpl("Python + requests", "fetch.py", "import urllib.request\n\n" +
            "with urllib.request.urlopen(\"https://example.com\") as r:\n    print(r.status, len(r.read()))\n"),
        Tpl("C program", "main.c", "#include <stdio.h>\n\nint main(void) {\n    printf(\"hello\\n\");\n" +
            "    return 0;\n}\n"),
        Tpl("HTML page", "index.html", "<!doctype html>\n<html><head><meta charset=\"utf-8\">" +
            "<title>DevStudio</title></head>\n<body style=\"font-family:system-ui;padding:24px\">\n" +
            "<h1>Hello from DevStudio</h1>\n<p>Served from real Linux.</p>\n</body></html>\n"),
        Tpl("Node script", "index.js", "const os = require('os');\nconsole.log('node', process.version, os.platform());\n"),
        Tpl("Shell script", "run.sh", "#!/bin/sh\necho \"hello from the guest\"\nuname -a\n"),
        Tpl("Java class", "Main.java", "public class Main {\n    public static void main(String[] args) {\n" +
            "        System.out.println(\"hello from the JVM\");\n    }\n}\n"),
        Tpl("Rust main", "main.rs", "fn main() {\n    println!(\"hello from cargo\");\n}\n")
    )
    AlertDialog(
        onDismissRequest = onClose, containerColor = Surf,
        title = { Text("New file", color = Txt, fontSize = 15.sp) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text("Created in ~/projects/scratch", color = Dim, fontSize = 10.5.sp)
                Spacer(Modifier.height(8.dp))
                templates.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable {
                        onCreate("~/projects/scratch/${t.path}", t.body)
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.name, color = Txt, fontSize = 12.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text(t.path, color = Dim, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel", color = Dim) } }
    )
}

@Composable
private fun GitSheet(run: (String) -> String, activeFile: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("git status will appear here") }
    var busy by remember { mutableStateOf(false) }
    val dir = activeFile.substringBeforeLast('/', "~/projects")

    fun go(cmd: String) {
        busy = true
        scope.launch {
            log = withContext(Dispatchers.IO) { "$ $cmd\n" + run("cd \"$dir\" && $cmd") }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onClose, containerColor = Surf,
        title = { Text("Git · $dir", color = Txt, fontSize = 13.sp, maxLines = 1) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { go("git init") }) { Text("init", color = Cyan, fontSize = 12.sp) }
                    TextButton(onClick = { go("git status --short") }) { Text("status", color = Cyan, fontSize = 12.sp) }
                    TextButton(onClick = {
                        go("git add -A && git -c user.email=dev@devstudio -c user.name=DevStudio " +
                            "commit -m 'DevStudio commit' && git log --oneline -3")
                    }) { Text("commit", color = Green, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(8.dp))
                Surface(color = Color(0xFF080A0E), shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(10.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        Text(if (busy) "working…" else log, color = Color(0xFF9FB2C9), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                    }
                }
                Text("git installs itself on first use if missing.", color = Dim, fontSize = 9.5.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Dim) } }
    )
}

/* ----------------------------------------------------------------- preview */

@Composable
fun PreviewScreen(url: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(url, color = Cyan, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = onClose) { Text("Close", color = Dim, fontSize = 11.sp) }
        }
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { c ->
                android.webkit.WebView(c).apply {
                    webViewClient = android.webkit.WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    loadUrl(url)
                }
            })
    }
}

/** Project-wide search: grep inside the guest, tap a hit to open it at that line. */
@Composable
private fun ProjectSearchDialog(run: (String) -> String,
                                onOpen: (String, Int) -> Unit,
                                onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Triple<String, Int, String>>>(emptyList()) }

    fun go() {
        if (q.isBlank() || busy) return
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                run("cd ~/projects 2>/dev/null && grep -rnI --exclude-dir=node_modules " +
                    "--exclude-dir=.git --exclude-dir=build --exclude-dir=dist " +
                    "--exclude-dir=target --exclude-dir=.gradle -e " + shellQuote(q) +
                    " . 2>/dev/null | head -120")
            }
            hits = out.lines().mapNotNull { line ->
                val a = line.indexOf(':')
                if (a < 0) return@mapNotNull null
                val b = line.indexOf(':', a + 1)
                if (b < 0) return@mapNotNull null
                val path = line.substring(0, a).removePrefix("./")
                val n = line.substring(a + 1, b).toIntOrNull() ?: return@mapNotNull null
                Triple(path, n, line.substring(b + 1).trim().take(110))
            }
            scanned = if (hits.isEmpty()) "no matches in ~/projects" else "${hits.size} match(es)"
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Surf,
        title = { Text("Search project", color = Txt, fontSize = 15.sp) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, label = { Text("text to find", fontSize = 11.sp) },
                    singleLine = true, textStyle = TextStyle(fontSize = 12.sp, color = Txt),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet,
                        unfocusedBorderColor = LineC),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { go() }) {
                        Text(if (busy) "searching…" else "Search", color = Cyan, fontSize = 12.sp)
                    }
                    if (scanned.isNotBlank()) Text(scanned, color = Dim, fontSize = 10.5.sp,
                        modifier = Modifier.padding(top = 12.dp))
                }
                if (hits.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(hits) { (path, line, body) ->
                            Column(Modifier.fillMaxWidth().clickable { onOpen(path, line) }
                                .padding(vertical = 6.dp)) {
                                Row {
                                    Text(Highlighter.titleOf(path), color = Txt, fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 1)
                                    Spacer(Modifier.width(6.dp))
                                    Text(":$line", color = Cyan, fontSize = 10.sp)
                                }
                                Text(body, color = Dim, fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 2)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Dim) } }
    )
}

/** single-quote a string for the guest shell. */
private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"


/** The code field: monospace, syntax-coloured, no autocorrect, no surprise capitalisation. */
@Composable
private fun CodeField(value: TextFieldValue, onEdit: (TextFieldValue) -> Unit,
                      fontSize: Float, lineHeight: Float, lang: String, modifier: Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onEdit,
        modifier = modifier,
        textStyle = TextStyle(color = Txt, fontSize = fontSize.sp,
            fontFamily = FontFamily.Monospace, lineHeight = lineHeight.sp),
        cursorBrush = SolidColor(Cyan),
        visualTransformation = if (lang.isBlank()) androidx.compose.ui.text.input.VisualTransformation.None
                               else SyntaxTransform(lang),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
    )
}
