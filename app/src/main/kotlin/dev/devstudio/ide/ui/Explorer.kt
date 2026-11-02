package dev.devstudio.ide.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.devstudio.ide.core.Highlighter

/*
 * The explorer.
 *
 * This is the panel from the reference: a full-height drawer whose header is the
 * project name with two actions, and under it the project as a *tree* — chevrons that
 * fold, warm folder glyphs, and a language mark on every file so the row tells you
 * what it is before you read the name. The editor sits behind it, so opening a file
 * is a tap and the drawer closes on its own.
 *
 * Two rules shaped the code:
 *
 *  1. The tree is a pure function of (paths, expanded). [FileTree.rows] is testable
 *     without a device, the composable only renders what it returns, and a folder's
 *     open state is one set of strings — no mutable node graph to get out of sync
 *     with the filesystem.
 *  2. Every row is 42dp and every row has a glyph. The eye finds a file by shape and
 *     colour first; the name confirms it. That is why the folder glyph is warm and the
 *     file marks are cool — a directory never looks like a document at a glance.
 */

// --------------------------------------------------------------------- model

/**
 * A language, as far as the explorer is concerned. Deliberately coarse: the badge is
 * 26dp wide and there is no room to be clever.
 */
enum class Lang(val badge: String, val tone: Color) {
    Java("Java", Color(0xFFE8A34A)),
    Kotlin("Kt", Color(0xFFA97BFF)),
    Cpp("C++", Color(0xFF6BA8E8)),
    C("C", Color(0xFF7FA8D6)),
    Header("h", Color(0xFF8FA0C0)),
    Python("Py", Color(0xFF5FB2D8)),
    Js("JS", Color(0xFFF0D264)),
    Ts("TS", Color(0xFF4FA3E0)),
    Json("{}", Color(0xFFD8B25E)),
    Xml("</>", Color(0xFF8FBFA0)),
    Html("</>", Color(0xFFE08A5A)),
    Css("#", Color(0xFF7FB6E8)),
    Markdown("M", Color(0xFF9AA6BE)),
    Gradle("G", Color(0xFF6BCB9B)),
    Shell("sh", Color(0xFF8ED08A)),
    Go("Go", Color(0xFF5FC8D8)),
    Rust("Rs", Color(0xFFD89070)),
    Sql("SQL", Color(0xFFD8A05E)),
    Yaml("yml", Color(0xFFC08ED0)),
    Text("txt", Color(0xFF9AA6BE)),
    None("", Color(0xFF9AA6BE))
}

/** One rendered row: either a directory that can fold, or a file that can open. */
data class Row(
    val path: String,
    val name: String,
    val depth: Int,
    val folder: Boolean,
    /** only meaningful for a folder — a file is either open in a tab or it is not */
    val open: Boolean = false,
    /** how many entries sit directly inside a folder, shown instead of a chevron tip */
    val children: Int = 0
)

/**
 * Builds the rows the drawer renders.
 *
 * The input is the flat list of absolute paths the guest reported. The tree is built
 * by splitting on `/`, and the *root* is the shortest shared prefix, so a project in
 * `~/projects/Cheto/app/src/main/...` shows as `Cheto → app → src → …` exactly like
 * the reference instead of as one deep indented column starting at `/home`.
 *
 * Folders sort before files, both alphabetically and case-insensitively, because that
 * is the order people look for a file in.
 */
object FileTree {

    fun rows(paths: List<String>, expanded: Set<String>): List<Row> {
        val clean = paths.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return emptyList()
        // every path here is a *file*; its parents exist because it exists
        val files = clean.map { it to it.split("/").filter { p -> p.isNotEmpty() } }
            .filter { it.second.isNotEmpty() }

        // The root is the shared *folder* of every file — the project itself. Using the
        // shared segments of the paths including the file names would push the tree down
        // one level whenever a project happens to have a single file in a single folder.
        val root0 = commonPrefix(files.map { it.second.dropLast(1) })
        // …unless that leaves nothing but files: then back off one level so the folder
        // they live in is still a row you can fold.
        val allDirect = files.all { it.second.size == root0.size + 1 }
        val root = if (allDirect && root0.isNotEmpty()) root0.dropLast(1) else root0
        val out = ArrayList<Row>()
        walk(files, root, 0, expanded, out, "")
        return out
    }

    /**
     * The segments every *folder* shares — compared in full, because a folder list has
     * no trailing file name to protect, unlike [commonRoot].
     */
    fun commonPrefix(dirs: List<List<String>>): List<String> {
        if (dirs.isEmpty()) return emptyList()
        val first = dirs.first()
        var n = 0
        while (n < first.size && dirs.all { it.size > n && it[n] == first[n] }) n++
        return first.take(n)
    }

    /** The segments every path shares — the project itself, not `/` and not `home`. */
    fun commonRoot(segments: List<List<String>>): List<String> {
        if (segments.isEmpty()) return emptyList()
        val first = segments.first()
        var n = 0
        while (n < first.size - 1 &&
            segments.all { it.size > n + 1 && it[n] == first[n] }) n++
        return first.take(n)
    }

    private fun walk(
        files: List<Pair<String, List<String>>>,
        prefix: List<String>,
        depth: Int,
        expanded: Set<String>,
        out: MutableList<Row>,
        base: String
    ) {
        val here = files.filter { it.second.size > prefix.size && it.second.take(prefix.size) == prefix }
        if (here.isEmpty()) return

        // group the next segment: a directory when any path is deeper than prefix+1
        val dirs = LinkedHashMap<String, MutableList<Pair<String, List<String>>>>()
        val leaves = LinkedHashMap<String, String>()          // name -> full path
        for ((full, segs) in here) {
            val next = segs[prefix.size]
            if (segs.size > prefix.size + 1) {
                dirs.getOrPut(next) { ArrayList() }.add(full to segs)
            } else {
                leaves[next] = full
            }
        }

        for (name in dirs.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)) {
            val childBase = if (base.isEmpty()) name else "$base/$name"
            val fullDirPath = (prefix + name).joinToString("/")
            val isOpen = fullDirPath in expanded
            out.add(Row(fullDirPath, name, depth, folder = true, open = isOpen,
                children = dirs[name]!!.size))
            if (isOpen) {
                walk(dirs[name]!!, prefix + name, depth + 1, expanded, out, childBase)
            }
        }
        for (name in leaves.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)) {
            out.add(Row(leaves[name]!!, name, depth, folder = false))
        }
    }

    /**
     * The folder paths that should start open: [keep] levels *below the project root*.
     *
     * Counting from the project and not from `/` is what makes this useful — with the
     * absolute depth, `~/projects/Cheto/app/src` would already be three levels deep and
     * the drawer would open as a single folded row.
     */
    fun defaultExpanded(paths: List<String>, keep: Int = 2): Set<String> {
        val all = paths.map { it.split("/").filter { s -> s.isNotEmpty() } }
        if (all.isEmpty()) return emptySet()
        val rootDepth = commonRoot(all).size
        val set = LinkedHashSet<String>()
        for (segs in all) {
            val last = (segs.size - 1).coerceAtMost(rootDepth + keep)
            for (i in rootDepth until last) set.add(segs.take(i + 1).joinToString("/"))
        }
        return set
    }

    /** The language mark for a file name. */
    fun lang(name: String): Lang {
        val base = name.substringAfterLast('/')
        val ext = base.substringAfterLast('.', "").lowercase()
        val lower = base.lowercase()
        return when {
            lower == "cmakelists.txt" -> Lang.Text
            lower == "dockerfile" -> Lang.Shell
            lower == "makefile" -> Lang.Shell
            lower == "gradlew" -> Lang.Shell
            ext == "java" -> Lang.Java
            ext == "kt" || ext == "kts" -> Lang.Kotlin
            ext == "cpp" || ext == "cc" || ext == "cxx" -> Lang.Cpp
            ext == "c" -> Lang.C
            ext == "h" || ext == "hpp" || ext == "hh" -> Lang.Header
            ext == "py" -> Lang.Python
            ext == "js" || ext == "mjs" || ext == "cjs" -> Lang.Js
            ext == "ts" || ext == "tsx" || ext == "jsx" -> Lang.Ts
            ext == "json" -> Lang.Json
            ext == "xml" || ext == "plist" -> Lang.Xml
            ext == "html" || ext == "htm" -> Lang.Html
            ext == "css" || ext == "scss" -> Lang.Css
            ext == "md" || ext == "markdown" -> Lang.Markdown
            ext == "gradle" -> Lang.Gradle
            ext == "sh" || ext == "bash" || ext == "zsh" -> Lang.Shell
            ext == "go" -> Lang.Go
            ext == "rs" -> Lang.Rust
            ext == "sql" -> Lang.Sql
            ext == "yml" || ext == "yaml" -> Lang.Yaml
            ext == "txt" || ext == "properties" || ext == "kt" -> Lang.Text
            ext.isEmpty() -> Lang.Text
            else -> Lang.None
        }
    }

    /** What one line of the drawer's footer says about the project. */
    fun summary(paths: List<String>): String {
        if (paths.isEmpty()) return "no files yet"
        val folders = paths.map { it.substringBeforeLast('/', "") }.distinct().size
        val langs = paths.map { lang(it) }.filter { it != Lang.Text && it != Lang.None }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)
        val tail = if (langs.isEmpty()) "" else
            " · " + langs.joinToString(" ") { "${it.key.badge}×${it.value}" }
        return "${paths.size} file(s) · $folders folder(s)$tail"
    }
}

// ---------------------------------------------------------------- components

/** How wide the drawer is: most of the screen, with the editor still visible behind. */
private const val DRAWER_FRACTION = 0.78f

/**
 * The drawer itself.
 *
 * It is a card, not a full-bleed column: the rounded outer corners are what make it
 * read as *on top of* the editor rather than as a second page.
 */
@Composable
fun ExplorerDrawer(
    project: String,
    paths: List<String>,
    expanded: Set<String>,
    active: String?,
    isDirty: (String) -> Boolean,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onSettings: () -> Unit,
    onFileActions: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = remember(paths, expanded) { FileTree.rows(paths, expanded) }
    val state = rememberLazyListState()

    Column(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topEnd = Tok.rXL, bottomEnd = Tok.rXL))
            .background(
                Brush.verticalGradient(listOf(Pal.surface2, Pal.surface))
            )
    ) {
        DrawerHeader(
            project = project,
            count = paths.size,
            onNewFile = onNewFile,
            onNewFolder = onNewFolder,
            onSettings = onSettings
        )

        if (rows.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(Tok.l)) {
                Text("This project is empty.", color = Pal.text, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "＋ writes a file, the folder button makes a directory, and Build " +
                        "Studio can write a whole project from one sentence.",
                    color = Pal.faint, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
            return@Column
        }

        LazyColumn(state = state, modifier = Modifier.weight(1f)) {
            items(rows, key = { it.path }) { row ->
                TreeRow(
                    row = row,
                    active = row.path == active,
                    dirty = !row.folder && isDirty(row.path),
                    onClick = { if (row.folder) onToggle(row.path) else onOpen(row.path) },
                    onLongClick = { if (!row.folder) onFileActions(row.path) }
                )
            }
            item { Spacer(Modifier.height(Tok.s)) }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = Tok.m, end = Tok.m, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(FileTree.summary(paths), color = Pal.faint, fontSize = 9.sp, maxLines = 1,
                fontFamily = FontFamily.Monospace, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text("${rows.size} row(s)", color = Pal.faint, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace)
        }
    }
}

/** Project name, entry count, and the two actions the reference puts on that line. */
@Composable
private fun DrawerHeader(
    project: String,
    count: Int,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onSettings: () -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = Tok.l + 2.dp, end = Tok.s + 2.dp, top = Tok.m),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(project, color = Pal.text, fontSize = 20.sp, fontWeight = FontWeight.Black,
                    letterSpacing = (-0.4).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (count == 0) "empty" else "$count file(s)",
                    color = Pal.faint, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace
                )
            }
            HeaderButton(glyph = "⚙", tag = "editor settings", onClick = onSettings)
            Spacer(Modifier.width(4.dp))
            HeaderButton(glyph = "＋", tag = "new file", onClick = onNewFile)
            Spacer(Modifier.width(4.dp))
            HeaderButton(glyph = "▤", tag = "new folder", onClick = onNewFolder)
        }
        Spacer(Modifier.height(Tok.s + 2.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.lineSoft))
    }
}

@Composable
private fun HeaderButton(glyph: String, tag: String, onClick: () -> Unit) {
    Pressable(onClick = onClick, scaleDown = 0.9f) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(Tok.rS))
                .background(Pal.surface3),
            contentAlignment = Alignment.Center
        ) {
            Text(glyph, color = Pal.dim, fontSize = 13.sp)
        }
    }
}

/**
 * One row.
 *
 * Depth is expressed as leading space, exactly as in the reference: 16dp per level, and
 * the chevron/glyph column is fixed so names line up in a column no matter how deep the
 * folder is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    row: Row,
    active: Boolean,
    dirty: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bg = when {
        active -> Pal.violetSoft
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(Tok.rS))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = (8 + row.depth * 16).dp, end = 10.dp)
            .height(42.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (row.folder) {
            Chevron(row.open)
            Spacer(Modifier.width(2.dp))
            FolderGlyph(open = row.open)
            Spacer(Modifier.width(9.dp))
            Text(
                row.name,
                color = if (active) Pal.text else Pal.text.copy(alpha = 0.92f),
                fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (!row.open && row.children > 0) {
                Text("${row.children}", color = Pal.faint, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
            }
        } else {
            // a file row keeps the same glyph column as a folder, so the tree is a grid
            Spacer(Modifier.width(22.dp))
            LangMark(FileTree.lang(row.name))
            Spacer(Modifier.width(9.dp))
            Text(
                row.name,
                color = if (active) Pal.text else Pal.text.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (dirty) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Pal.amber))
            }
        }
    }
}

/** The fold arrow. It rotates, unless the OS asked for no motion. */
@Composable
private fun Chevron(open: Boolean) {
    val motion = rememberAnimationsEnabled()
    val angle by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = Motion.spatial(),
        label = "folder-chevron"
    )
    Canvas(
        Modifier.size(12.dp).rotate(if (motion) angle else if (open) 90f else 0f)
    ) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.32f, h * 0.20f)
            lineTo(w * 0.68f, h * 0.50f)
            lineTo(w * 0.32f, h * 0.80f)
        }
        drawPath(p, color = Pal.dim, style = Stroke(width = h * 0.13f))
    }
}

/**
 * A folder, drawn rather than typed.
 *
 * The two-tone front and back is what makes it read as a folder at 18dp; a single flat
 * rounded rectangle reads as a square.
 */
@Composable
private fun FolderGlyph(open: Boolean, size: Dp = 19.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val tabW = w * 0.42f
        val tabH = h * 0.16f
        // back: the tabbed sheet
        val back = Path().apply {
            moveTo(w * 0.06f, h * 0.26f)
            lineTo(tabW, h * 0.26f)
            lineTo(tabW + w * 0.10f, h * 0.36f)
            lineTo(w * 0.94f, h * 0.36f)
            lineTo(w * 0.94f, h * 0.86f)
            lineTo(w * 0.06f, h * 0.86f)
            close()
        }
        drawPath(back, color = Pal.folderDeep)
        // front: the page that lifts when the folder is open
        val top = if (open) h * 0.34f else h * 0.28f
        val front = Path().apply {
            moveTo(w * 0.06f, top + tabH)
            lineTo(w * 0.94f, top + tabH)
            lineTo(w * 0.94f, h * 0.92f)
            lineTo(w * 0.06f, h * 0.92f)
            close()
        }
        drawPath(front, color = if (open) Pal.folder else Pal.folderDeep.copy(alpha = 0.92f))
        // the fold line, so the front does not look like a pasted rectangle
        drawLine(
            color = Pal.surface2.copy(alpha = 0.35f),
            start = Offset(w * 0.10f, top + tabH),
            end = Offset(w * 0.90f, top + tabH),
            strokeWidth = h * 0.05f
        )
    }
}

/**
 * The language mark in front of a file name: a coffee cup for Java (as in the
 * reference), `</>` for markup, `{}` for JSON, and a short badge otherwise.
 */
@Composable
private fun LangMark(lang: Lang) {
    when (lang) {
        Lang.Java -> JavaCup()
        Lang.Json -> GlyphMark("{ }", lang.tone)
        Lang.Xml, Lang.Html -> GlyphMark("</>", lang.tone)
        Lang.Css -> GlyphMark("#", lang.tone)
        Lang.Markdown -> GlyphMark("M↓", lang.tone)
        Lang.None -> GlyphMark("·", lang.tone)
        else -> BadgeMark(lang.badge, lang.tone)
    }
}

/** A fixed-width badge, so a column of badges is a column and not a ragged edge. */
@Composable
private fun BadgeMark(text: String, tone: Color) {
    Box(Modifier.size(width = 26.dp, height = 18.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = tone,
            fontSize = if (text.length >= 3) 8.5.sp else 9.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** Markup and JSON get a glyph instead of a word: `</>` needs no translation. */
@Composable
private fun GlyphMark(text: String, tone: Color) {
    Box(Modifier.size(width = 26.dp, height = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, color = tone, fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/** A cup of coffee: the Java mark, drawn so it stays sharp at any density. */
@Composable
private fun JavaCup() {
    Canvas(Modifier.size(width = 26.dp, height = 18.dp)) {
        val w = this.size.width
        val h = this.size.height
        val tone = Lang.Java.tone
        val stroke = h * 0.075f
        // cup body
        val body = Path().apply {
            moveTo(w * 0.24f, h * 0.44f)
            lineTo(w * 0.30f, h * 0.82f)
            lineTo(w * 0.62f, h * 0.82f)
            lineTo(w * 0.68f, h * 0.44f)
            close()
        }
        drawPath(body, color = tone.copy(alpha = 0.85f))
        // handle
        val handle = Path().apply {
            moveTo(w * 0.68f, h * 0.50f)
            cubicTo(w * 0.84f, h * 0.48f, w * 0.84f, h * 0.70f, w * 0.66f, h * 0.70f)
        }
        drawPath(handle, color = tone, style = Stroke(width = stroke))
        // steam: two short curls, the part everyone recognises
        for ((i, x) in listOf(w * 0.38f, w * 0.55f).withIndex()) {
            val steam = Path().apply {
                moveTo(x, h * 0.34f)
                cubicTo(x - w * 0.06f, h * 0.26f, x + w * 0.06f, h * 0.22f, x, h * 0.14f)
            }
            drawPath(steam, color = tone.copy(alpha = if (i == 0) 0.9f else 0.65f),
                style = Stroke(width = stroke * 0.9f))
        }
        // saucer
        drawLine(tone, Offset(w * 0.20f, h * 0.88f), Offset(w * 0.72f, h * 0.88f),
            strokeWidth = stroke)
    }
}

/** A short "where am I" line for the editor's tab strip, e.g. `app/src/main`. */
fun folderCrumb(path: String, maxSegments: Int = 3): String {
    val segs = path.substringBeforeLast('/', "").split("/").filter { it.isNotEmpty() }
    if (segs.isEmpty()) return "/"
    return segs.takeLast(maxSegments).joinToString(" / ")
}

/** The nearest enclosing folder of a set of paths, for naming the project in the header. */
fun projectNameOf(paths: List<String>, fallback: String = "Project"): String {
    val root = FileTree.commonRoot(paths.map { it.split("/").filter { s -> s.isNotEmpty() } })
    return root.lastOrNull()
        ?: paths.firstOrNull()?.substringBeforeLast('/')?.substringAfterLast('/')
        ?: fallback
}

/**
 * How wide the drawer is: most of the screen, with a strip of editor still visible so
 * the drawer reads as *over* the page. Clamped, because a drawer that covers everything
 * hides the thing you are switching to, and one that is too narrow clips the tree.
 */
fun drawerWidth(fraction: Float = 0.78f): Float = fraction.coerceIn(0.6f, 0.92f)
