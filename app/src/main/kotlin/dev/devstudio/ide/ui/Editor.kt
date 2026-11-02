package dev.devstudio.ide.ui

import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

/*
 * The legacy names below are kept as aliases, but they now point at the new
 * design tokens in Kit.kt. That is deliberate: the editor and the terminal are
 * large, working surfaces, and remapping their palette onto the rebuilt system
 * updates every pixel of them at once instead of restyling them file by file and
 * leaving half the app on the old greys.
 */
internal val Bg = Pal.ink
internal val Surf = Pal.surface
internal val Surf2 = Pal.surface2
internal val LineC = Pal.line
internal val Txt = Pal.text
internal val Dim = Pal.dim
internal val Violet = Pal.violet
internal val Cyan = Pal.cyan
internal val Green = Pal.mint
internal val Amber = Pal.amber
internal val Red = Pal.rose
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

/** The writing surface: one shade below the ink, so the text reads as a page. */
private val EditorSurface = Color(0xFF0A0C12)
private val GutterText = Color(0xFF3D4557)

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
    // -1 closed · 0 problems · 1 output
    var panelTab by remember { mutableStateOf(-1) }
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

    Column(Modifier.fillMaxSize().background(Pal.ink)) {

        // ------------------------------------------------------------- header
        Row(
            Modifier.fillMaxWidth().padding(start = 13.dp, end = 8.dp, top = 9.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(width = 4.dp, height = 26.dp).background(
                    Brush.verticalGradient(listOf(Pal.violet, Pal.violet.copy(alpha = 0.2f))),
                    RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Highlighter.titleOf(active ?: "Editor"), color = Pal.text,
                        fontSize = 16.sp, fontWeight = FontWeight.Black,
                        letterSpacing = (-0.2).sp, maxLines = 1)
                    if (active != null) {
                        Spacer(Modifier.width(6.dp))
                        StatusPill(if (dirty) "modified" else "saved",
                            if (dirty) Pal.amber else Pal.mint, filled = dirty)
                    }
                }
                Text(active?.substringBeforeLast('/', "") ?: "no file open",
                    color = Pal.faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            EditorPill("▤", if (showFiles) Pal.cyan else Pal.dim) { showFiles = !showFiles; refresh() }
            Spacer(Modifier.width(5.dp))
            EditorPill("⌕", Pal.dim) { showFind = true }
            Spacer(Modifier.width(5.dp))
            EditorPill("⑂", Pal.dim) { showGit = true }
            Spacer(Modifier.width(5.dp))
            EditorPill("＋", Pal.violet) { showNew = true }
        }

        // tabs — pill chips; the active one carries the accent and a dirty dot
        if (tabs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                tabs.forEach { path ->
                    val on = path == active
                    val unsaved = if (on) dirty
                    else drafts[path]?.text?.let { it != (savedText[path] ?: it) } ?: false
                    Pressable(onClick = { open(path) }, scaleDown = 0.95f) {
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) Pal.violetSoft else Pal.surface)
                                .border(
                                    if (on) 1.dp else 0.dp,
                                    if (on) Pal.violet.copy(alpha = 0.5f) else Color.Transparent,
                                    RoundedCornerShape(50)
                                )
                                .padding(start = 10.dp, end = if (on) 6.dp else 10.dp,
                                    top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (unsaved) {
                                Box(Modifier.size(5.dp).clip(RoundedCornerShape(50))
                                    .background(Pal.amber))
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(Highlighter.titleOf(path),
                                color = if (on) Pal.text else Pal.dim,
                                fontSize = 10.5.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1)
                            if (on) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier.size(16.dp).clip(RoundedCornerShape(50))
                                        .clickable {
                                            val dirtyTab = drafts[path]?.text
                                                ?.let { it != (savedText[path] ?: it) } ?: false
                                            if (dirtyTab) confirmClose = path else closeTab(path)
                                        },
                                    contentAlignment = Alignment.Center
                                ) { Text("×", color = Pal.dim, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }

        // ----------------------------------------------------------- explorer
        //
        // The old "Files" sheet was a flat list under a search box. This is the
        // panel an editor actually has: a header with a new-file button, indented
        // paths so folder depth is visible, and the open file marked.
        if (showFiles) {
            TCard(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                tone = Pal.surface, radius = Tok.rM, glow = Pal.cyan
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 11.dp, end = 8.dp, top = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("EXPLORER", color = Pal.faint, fontSize = 7.5.sp,
                            letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("${files.size} file(s)", color = Pal.faint, fontSize = 8.5.sp)
                        Spacer(Modifier.width(7.dp))
                        Box(
                            Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                                .background(Pal.violetSoft).clickable { showNew = true },
                            contentAlignment = Alignment.Center
                        ) { Text("＋", color = Pal.violet, fontSize = 11.sp) }
                    }
                    BasicTextField(
                        value = search, onValueChange = { search = it }, singleLine = true,
                        textStyle = TextStyle(fontSize = 11.sp, color = Pal.text,
                            fontFamily = FontFamily.Monospace),
                        decorationBox = { inner ->
                            Box(
                                Modifier.fillMaxWidth().padding(8.dp)
                                    .clip(RoundedCornerShape(Tok.rS)).background(Pal.ink)
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                if (search.isEmpty()) Text("find a file…", color = Pal.faint,
                                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                                inner()
                            }
                        }
                    )
                    LazyColumn(Modifier.heightIn(max = 250.dp).padding(bottom = 6.dp)) {
                        val shown = files.filter { search.isBlank() || it.contains(search, true) }
                        if (shown.isEmpty()) item {
                            Text(
                                if (files.isEmpty()) "no files yet — ＋ creates one, or build a project"
                                else "nothing matches \"$search\"",
                                color = Pal.faint, fontSize = 10.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                        items(shown) { f ->
                            val depth = (f.count { it == '/' } - 1).coerceIn(0, 4)
                            val on = f == active
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (on) Pal.violetSoft else Color.Transparent)
                                    .clickable { open(f); showFiles = false }
                                    .padding(start = (12 + depth * 9).dp, end = 10.dp,
                                        top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (on) "●" else "·",
                                    color = if (on) Pal.violet else Pal.faint, fontSize = 8.sp)
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(Highlighter.titleOf(f), color = if (on) Pal.text else Pal.text,
                                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1)
                                    Text(f.substringBeforeLast('/', ""), color = Pal.faint,
                                        fontSize = 8.sp, maxLines = 1,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }

        // breadcrumbs — the VS Code habit: always know where the caret lives
        val pathShown = active
        if (pathShown != null) Breadcrumbs(pathShown, compact = wrap)

        // ------------------------------------------------------------- editor
        Box(Modifier.weight(1f).fillMaxWidth().background(EditorSurface)) {
            // the activity rail sits on the leading edge, the minimap on the trailing
            if (active != null && !bigFile && !wrap) {
                Minimap(
                    lines = lines,
                    scroll = vs.value,
                    viewportPx = screenPx,
                    lineHeight = lineHeight,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
            if (active != null) {
                ActivityRail(
                    onFiles = { showFiles = !showFiles; refresh() },
                    onSearch = { showSearch = true },
                    onGit = { showGit = true },
                    onProblems = { panelTab = 0 },
                    onOutput = { panelTab = 1 },
                    onRun = { save(andRun = true) },
                    filesOpen = showFiles,
                    panelTab = panelTab,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }
            if (active == null) {
                Column(
                    Modifier.fillMaxSize().padding(22.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(54.dp).clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(Pal.violet, Pal.cyan))),
                        contentAlignment = Alignment.Center
                    ) { Text("⌘", color = Pal.ink, fontSize = 22.sp, fontWeight = FontWeight.Black) }
                    Spacer(Modifier.height(14.dp))
                    Text("Nothing open", color = Pal.text, fontSize = 16.sp,
                        fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    Text("Open a file from the guest, or start a new one from a template.",
                        color = Pal.faint, fontSize = 10.5.sp, textAlign = TextAlign.Center,
                        lineHeight = 15.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            ActionTile("▤", "Open a file", "${files.size} in the guest", Pal.cyan) {
                                showFiles = true; refresh()
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            ActionTile("＋", "New file", "from a template", Pal.violet) { showNew = true }
                        }
                    }
                }
            } else {
                val hs = rememberScrollState()
                val caretLine = doc.text.take(doc.selection.start).count { it == '\n' }
                Column(Modifier.fillMaxSize().padding(start = 38.dp, end = 26.dp)
                    .verticalScroll(vs)) {
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
                                        color = if (here) Pal.violet else GutterText,
                                        fontSize = fontSp,
                                        fontWeight = if (here) FontWeight.Black else FontWeight.Normal,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = lineHeight.sp,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.fillMaxWidth().padding(end = 6.dp))
                                }
                                Spacer(Modifier.height(((lines.size - lastVis) * lineHeight).dp))
                            }
                            Box(Modifier.width(1.dp).height((lines.size * lineHeight + 8).dp)
                                .background(Pal.lineSoft))
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

        // --------------------------------------------------------- bottom panel
        //
        // Problems and output used to be an inline strip plus a drawer that shoved
        // the editor around. One panel, two tabs, closed by default — the code keeps
        // the space until you ask for the detail.
        if (panelTab >= 0) {
            Column(Modifier.fillMaxWidth().background(Pal.surface)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    PanelTab("Problems", diagnostics.size, 0, panelTab) { panelTab = 0 }
                    Spacer(Modifier.width(5.dp))
                    PanelTab("Output", null, 1, panelTab) { panelTab = 1 }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                            .background(Pal.surface2).clickable { panelTab = -1 },
                        contentAlignment = Alignment.Center
                    ) { Text("×", color = Pal.dim, fontSize = 11.sp) }
                }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    if (panelTab == 0) {
                        if (diagnostics.isEmpty()) {
                            Text("no problems found", color = Pal.mint, fontSize = 10.sp)
                        } else {
                            diagnostics.forEach { d ->
                                Text("⚠  $d", color = Pal.amber, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                            }
                        }
                    } else {
                        Text(
                            output.ifBlank { "nothing has run yet — Save & run puts the output here" },
                            color = if (output.startsWith("saved")) Pal.mint else Pal.dim,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------- rails
        Column(Modifier.fillMaxWidth().background(Pal.surface).padding(vertical = 5.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                EditorPill("⤺", if (undoStack.isEmpty()) Pal.faint else Pal.text) { undo() }
                EditorPill("⤼", if (redoStack.isEmpty()) Pal.faint else Pal.text) { redo() }
                RailDivider()
                EditorPill("Ln", Pal.dim) { showGoto = true }
                EditorPill("⌕", Pal.dim) { showSearch = true }
                EditorPill("Dup", Pal.dim) { withLines { l, n -> l.add(n + 1, l[n]); n + 1 to 0 } }
                EditorPill("↑", Pal.dim) {
                    withLines { l, n -> if (n > 0) { swap(l, n, n - 1); n - 1 to 0 } else n to 0 }
                }
                EditorPill("↓", Pal.dim) {
                    withLines { l, n -> if (n < l.size - 1) { swap(l, n, n + 1); n + 1 to 0 } else n to 0 }
                }
                EditorPill("#", Pal.dim) {
                    withLines { l, n ->
                        val t = l[n].trimStart()
                        l[n] = if (t.startsWith("#") || t.startsWith("//"))
                            l[n].replaceFirst(Regex("^(\\s*)(#|//)\\s?"), "$1")
                        else {
                            val pre = if (Highlighter.langOf(active ?: "") in
                                listOf("py", "sh", "rb", "yaml")) "# " else "// "
                            l[n].replaceFirst(Regex("^(\\s*)"), "$1$pre")
                        }
                        n to 0
                    }
                }
                RailDivider()
                EditorPill(if (wrap) "wrap" else "nowrap", if (wrap) Pal.cyan else Pal.faint) {
                    wrap = !wrap; store.setWordWrap(wrap)
                }
                EditorPill("A-", Pal.dim) {
                    fontSp = (fontSp.value - 1f).coerceAtLeast(9f).sp
                    store.setEditorFont(fontSp.value.toInt())
                }
                EditorPill("A+", Pal.dim) {
                    fontSp = (fontSp.value + 1f).coerceAtMost(22f).sp
                    store.setEditorFont(fontSp.value.toInt())
                }
                if ((active ?: "").endsWith(".html"))
                    EditorPill("◎", Pal.cyan) {
                        onPreview("file://" + (active ?: "").replace("~", "/root"))
                    }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Pressable(
                    onClick = { save() }, enabled = active != null && dirty,
                    modifier = Modifier.weight(1f), scaleDown = 0.97f
                ) {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                            .background(
                                if (active != null && dirty)
                                    Brush.horizontalGradient(listOf(Pal.mint, Pal.cyan))
                                else Brush.horizontalGradient(listOf(Pal.surface2, Pal.surface2))
                            )
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (active == null) "Save" else if (dirty) "Save changes" else "Saved",
                            color = if (active != null && dirty) Pal.ink else Pal.faint,
                            fontSize = 11.5.sp, fontWeight = FontWeight.Black
                        )
                    }
                }
                Pressable(
                    onClick = { save(andRun = true) }, enabled = active != null && !running,
                    modifier = Modifier.weight(1f), scaleDown = 0.97f
                ) {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                            .background(
                                if (active != null && !running)
                                    Brush.horizontalGradient(listOf(Pal.violet, Pal.cyan))
                                else Brush.horizontalGradient(listOf(Pal.surface2, Pal.surface2))
                            )
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (running) "running…" else "Save & run",
                            color = if (active != null && !running) Pal.ink else Pal.faint,
                            fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }

        // --------------------------------------------------------- symbol keys
        Column(Modifier.fillMaxWidth().background(Pal.surface)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.lineSoft))
            SymbolsRow(listOf("\t", "{", "}", "(", ")", "[", "]", "<", ">", "=", "+", "-", "*", "/",
                "\"", "'", ":", ";", ",", ".", "_", "#", "$", "&", "|", "!")) { insertAtCursor(it) }
            SymbolsRow(listOf("->", "=>", "==", "!=", "::", "&&", "||", "  ", "\n")) {
                insertAtCursor(if (it == "  ") "  " else it)
            }
        }

        // ----------------------------------------------------------- status bar
        Row(
            Modifier.fillMaxWidth().background(Pal.ink).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val caret = doc.selection.end
            val line = doc.text.take(caret).count { it == '\n' } + 1
            val col = caret - doc.text.take(caret).lastIndexOf('\n')
            StatusPill(Highlighter.langOf(active ?: "").ifBlank { "text" }, Pal.violet)
            Spacer(Modifier.width(6.dp))
            Text("Ln $line, Col $col", color = Pal.dim, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text("Spaces: 4", color = Pal.faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text("UTF-8 · LF", color = Pal.faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            if (bigFile) {
                StatusPill("large · highlight off", Pal.amber)
                Spacer(Modifier.width(6.dp))
            }
            val problems = diagnostics.size
            Row(Modifier.clickable { panelTab = 0 }, verticalAlignment = Alignment.CenterVertically) {
                Text("⚠", color = if (problems > 0) Pal.rose else Pal.faint, fontSize = 9.sp)
                Spacer(Modifier.width(3.dp))
                Text("$problems", color = if (problems > 0) Pal.rose else Pal.faint,
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(8.dp))
            Text("${lines.size} lines", color = Pal.faint, fontSize = 9.sp)
            Spacer(Modifier.width(8.dp))
            Text("${doc.text.length} chars", color = Pal.faint, fontSize = 9.sp)
            Spacer(Modifier.width(8.dp))
            Text(if (panelTab >= 0) "panel" else "output",
                color = if (panelTab >= 0) Pal.cyan else Pal.faint,
                fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { panelTab = if (panelTab >= 0) -1 else 1 })
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50))
                .background(if (dirty) Pal.amber else Pal.mint))
            Spacer(Modifier.width(4.dp))
            Text(if (dirty) "modified" else "saved",
                color = if (dirty) Pal.amber else Pal.mint, fontSize = 9.sp)
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
        HkDialog(
            title = "Unsaved changes",
            sub = Highlighter.titleOf(p),
            onDismiss = { confirmClose = null },
            accent = Pal.amber,
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            confirmTone = Pal.rose,
            onConfirm = { closeTab(p); confirmClose = null }
        ) {
            Text("${Highlighter.titleOf(p)} has edits that are not saved yet.",
                color = Pal.dim, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
    if (showGoto) {
        var target by remember { mutableStateOf("") }
        HkDialog(
            title = "Go to line",
            sub = "this file has ${lines.size} lines",
            onDismiss = { showGoto = false },
            accent = Pal.cyan,
            confirmLabel = "Go",
            confirmTone = Pal.cyan,
            onConfirm = {
                val n = target.trim().toIntOrNull()
                if (n != null && n >= 1) {
                    val at = offsetOfLine(doc.text, n)
                    doc = TextFieldValue(doc.text,
                        androidx.compose.ui.text.TextRange(at.coerceIn(0, doc.text.length)))
                }
                showGoto = false
            }
        ) {
            HkField(target, { target = it }, hint = "line number (1-${lines.size})")
            Text("jump inside the open file", color = Pal.faint, fontSize = 9.5.sp)
        }
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

/** One square action on the editor header. */
/**
 * The activity rail — the thing that makes an editor read as an IDE at a glance.
 * Six destinations, no labels, one accent for the thing that is open.
 */
@Composable
private fun ActivityRail(
    onFiles: () -> Unit,
    onSearch: () -> Unit,
    onGit: () -> Unit,
    onProblems: () -> Unit,
    onOutput: () -> Unit,
    onRun: () -> Unit,
    filesOpen: Boolean,
    panelTab: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.width(34.dp).fillMaxHeight().background(Pal.ink)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        RailItem("▤", "Explorer", filesOpen, Pal.cyan, onFiles)
        RailItem("⌕", "Search", false, Pal.violet, onSearch)
        RailItem("⑂", "Source control", false, Pal.amber, onGit)
        RailItem("▷", "Run", false, Pal.mint, onRun)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(18.dp).height(1.dp).background(Pal.lineSoft))
        Spacer(Modifier.height(6.dp))
        RailItem("⚠", "Problems", panelTab == 0, Pal.rose, onProblems)
        RailItem("❯", "Output", panelTab == 1, Pal.dim, onOutput)
    }
}

@Composable
private fun RailItem(glyph: String, label: String, on: Boolean, tone: Color, onClick: () -> Unit) {
    Pressable(onClick = onClick, scaleDown = 0.9f) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                .background(if (on) tone.copy(alpha = 0.14f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(glyph, color = if (on) tone else Pal.faint, fontSize = 13.sp,
                fontWeight = if (on) FontWeight.Black else FontWeight.Normal)
        }
    }
}

/**
 * Breadcrumbs — folder segments, then the file. Tapping a segment is not wired to
 * a picker yet, so the segments are text, not fake buttons.
 */
@Composable
private fun Breadcrumbs(path: String, compact: Boolean) {
    val parts = path.trimStart('/', '~').split('/').filter { it.isNotBlank() }
    Row(
        Modifier.fillMaxWidth().background(Pal.surface)
            .padding(start = 12.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        parts.forEachIndexed { i, segment ->
            val last = i == parts.lastIndex
            Text(
                segment,
                color = if (last) Pal.text else Pal.faint,
                fontSize = if (compact) 8.5.sp else 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
            if (!last) {
                Text(" › ", color = Pal.faint, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        if (!compact) Text("${parts.size - 1} folder(s) deep", color = Pal.faint, fontSize = 8.sp)
    }
}

/**
 * A minimap, drawn from the text itself: one mark per non-blank line, wide marks
 * where a line is long. The viewport box tracks the real scroll, so it is a map,
 * not decoration.
 */
@Composable
private fun Minimap(
    lines: List<String>,
    scroll: Int,
    viewportPx: Float,
    lineHeight: Float,
    modifier: Modifier = Modifier
) {
    val total = lines.size.coerceAtLeast(1)
    Canvas(modifier.width(22.dp).fillMaxHeight().background(Pal.ink.copy(alpha = 0.6f))) {
        val h = size.height
        val w = size.width
        val step = h / total
        val mark = step.coerceAtLeast(1f)
        lines.forEachIndexed { i, line ->
            if (i >= total) return@forEachIndexed
            val t = line.trim()
            if (t.isEmpty()) return@forEachIndexed
            val x0 = 2.dp.toPx()
            val len = (t.length / 70f).coerceIn(0.08f, 1f)
            drawRect(
                color = when {
                    t.startsWith("//") || t.startsWith("#") || t.startsWith("/*") ->
                        Pal.faint.copy(alpha = 0.5f)
                    t.contains("fun ") || t.contains("class ") || t.contains("def ") ||
                        t.contains("fn ") || t.contains("func ") -> Pal.violet.copy(alpha = 0.75f)
                    else -> Pal.dim.copy(alpha = 0.45f)
                },
                topLeft = androidx.compose.ui.geometry.Offset(x0, i * step),
                size = androidx.compose.ui.geometry.Size((w - x0 - 2.dp.toPx()) * len, mark)
            )
        }
        // viewport
        val top = (scroll / lineHeight) * step
        val vh = (viewportPx / lineHeight) * step
        drawRect(
            color = Pal.text.copy(alpha = 0.08f),
            topLeft = androidx.compose.ui.geometry.Offset(0f, top.coerceIn(0f, h)),
            size = androidx.compose.ui.geometry.Size(w, vh.coerceAtMost(h))
        )
        drawLine(Pal.violet.copy(alpha = 0.5f),
            androidx.compose.ui.geometry.Offset(0f, top.coerceIn(0f, h)),
            androidx.compose.ui.geometry.Offset(w, top.coerceIn(0f, h)), strokeWidth = 1f)
    }
}

@Composable
private fun EditorPill(label: String, tone: Color, onClick: () -> Unit) {
    Pressable(onClick = onClick, scaleDown = 0.9f) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                .background(tone.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) { Text(label, color = tone, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1) }
    }
}

/** Hairline between groups inside the action rail. */
@Composable
private fun RailDivider() {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(Pal.line))
}

/** One tab of the bottom panel. */
@Composable
private fun PanelTab(label: String, count: Int?, index: Int, current: Int, onPick: () -> Unit) {
    val on = current == index
    Pressable(onClick = onPick, scaleDown = 0.95f) {
        Row(
            Modifier.clip(RoundedCornerShape(50))
                .background(if (on) Pal.cyanSoft else Pal.surface2)
                .padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = if (on) Pal.cyan else Pal.dim, fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold)
            if (count != null) {
                Spacer(Modifier.width(5.dp))
                Text("$count", color = if (count > 0) Pal.amber else Pal.mint, fontSize = 9.sp,
                    fontWeight = FontWeight.Black)
            }
        }
    }
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
    val count = remember(find, doc.text) {
        if (find.isBlank()) 0 else doc.text.windowed(find.length, 1, true).count { it == find }
    }
    HkDialog(
        title = "Find & replace",
        sub = if (find.isBlank()) "type something to search for" else "$count match(es) in this file",
        onDismiss = onClose,
        accent = Pal.cyan,
        confirmLabel = "Replace all",
        dismissLabel = "Close",
        confirmTone = Pal.mint,
        onConfirm = {
            if (find.isBlank()) note = "type something to find"
            else {
                onApply(TextFieldValue(doc.text.replace(find, repl)))
                note = "replaced $count occurrence(s)"
            }
        }
    ) {
        HkField(find, { find = it }, hint = "find")
        HkField(repl, { repl = it }, hint = "replace with")
        if (note.isNotBlank()) Text(note, color = Pal.mint, fontSize = 10.sp)
        GhostButton("Find next") {
            if (find.isBlank()) note = "type something to find"
            else {
                val idx = doc.text.indexOf(find, doc.selection.end)
                if (idx < 0) note = "not found after the caret"
                else {
                    onApply(TextFieldValue(doc.text,
                        androidx.compose.ui.text.TextRange(idx, idx + find.length)))
                    note = "found at character $idx"
                }
            }
        }
    }
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
    HkDialog(
        title = "New file",
        sub = "created in ~/projects/scratch",
        onDismiss = onClose,
        accent = Pal.violet,
        confirmLabel = "Cancel",
        dismissLabel = null,
        onConfirm = onClose
    ) {
        Column(
            Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            templates.forEach { t ->
                Pressable(onClick = { onCreate("~/projects/scratch/${t.path}", t.body) }) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tok.rS))
                            .background(Pal.surface2)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                                .background(Pal.violetSoft),
                            contentAlignment = Alignment.Center
                        ) { Text("＋", color = Pal.violet, fontSize = 12.sp, fontWeight = FontWeight.Black) }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, color = Pal.text, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            Text(t.path, color = Pal.faint, fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                        Text("›", color = Pal.faint, fontSize = 13.sp)
                    }
                }
            }
        }
    }
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

    HkDialog(
        title = "Git", sub = dir, onDismiss = onClose, accent = Pal.mint,
        confirmLabel = "Close", dismissLabel = null, onConfirm = onClose
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("init" to Pal.cyan, "status" to Pal.cyan, "commit" to Pal.mint).forEach { (label, tone) ->
                Pressable(onClick = {
                    when (label) {
                        "init" -> go("git init")
                        "status" -> go("git status --short")
                        else -> go("git add -A && git -c user.email=dev@hakatron " +
                            "-c user.name=Hakatron commit -m 'Hakatron commit' && git log --oneline -3")
                    }
                }, scaleDown = 0.94f) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.16f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text(label, color = tone, fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        MonoBlock(if (busy) "working…" else log, maxLines = 12)
        Text("git installs itself on first use if it is missing.", color = Pal.faint, fontSize = 9.sp)
    }
}

/* ----------------------------------------------------------------- preview */

@Composable
fun PreviewScreen(url: String, onClose: () -> Unit) {
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    Screen(
        title = "Preview", subtitle = url, accent = Pal.cyan,
        actions = {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Pal.surface2)
                    .clickable { web?.reload() },
                contentAlignment = Alignment.Center
            ) { Text("↻", color = Pal.dim, fontSize = 12.sp) }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Pal.surface2)
                    .clickable { onClose() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text("Close", color = Pal.dim, fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
        }
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp),
            factory = { c ->
                android.webkit.WebView(c).apply {
                    webViewClient = android.webkit.WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    loadUrl(url)
                    web = this
                }
            },
            update = { w -> web = w })
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

    HkDialog(
        title = "Search the project",
        sub = if (scanned.isBlank()) "grep across ~/projects" else scanned,
        onDismiss = onClose,
        accent = Pal.violet,
        confirmLabel = if (busy) "searching…" else "Search",
        dismissLabel = "Close",
        onConfirm = { if (!busy) go() }
    ) {
        HkField(q, { q = it }, hint = "text to find")
        if (hits.isNotEmpty()) {
            Column(
                Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                hits.forEach { (path, line, body) ->
                    Pressable(onClick = { onOpen(path, line) }) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(Tok.rS))
                                .background(Pal.surface2)
                                .padding(horizontal = 9.dp, vertical = 7.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(path, color = Pal.cyan, fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1,
                                    modifier = Modifier.weight(1f))
                                Text(":$line", color = Pal.faint, fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                            Text(body, color = Pal.dim, fontSize = 9.sp, maxLines = 1,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        } else if (!busy && scanned.isNotBlank()) {
            Text("no matches", color = Pal.faint, fontSize = 10.sp)
        }
    }
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
