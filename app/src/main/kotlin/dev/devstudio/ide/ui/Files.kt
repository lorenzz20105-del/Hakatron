package dev.devstudio.ide.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.devstudio.ide.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Files — the guest's filesystem, rebuilt.
 *
 * Layout follows the new system: a segmented root switch (guest / shared), a
 * toolbar of real actions, then one card of rows. Rows select on tap and reveal
 * actions inline, so nothing hides behind a ⋯ menu you have to discover.
 */
@Composable
fun FilesScreen(ready: Boolean, onOpenInEditor: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var root by remember { mutableStateOf(0) }          // 0 = guest, 1 = shared
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }   // "file" | "dir" | "rename"
    var nameField by remember { mutableStateOf("") }

    val run: (String) -> String = { c -> Engine.exec(ctx, c, 60_000) }

    fun load() {
        loading = true
        scope.launch {
            files = withContext(Dispatchers.IO) {
                runCatching {
                    if (root == 0) GuestFiles.list({ c -> Engine.exec(ctx, c, 30_000) })
                    else SharedFiles.list(ctx)
                }.getOrDefault(emptyList())
            }
            loading = false
        }
    }
    LaunchedEffect(root, ready) { load() }

    fun act(what: String, block: () -> Boolean) {
        val ok = runCatching(block).getOrDefault(false)
        note = if (ok) what else "$what — failed"
        selected = null
        load()
    }

    Screen(
        title = "Files",
        subtitle = if (root == 0) "Inside the Linux guest" else "Shared folder on your storage",
        accent = Pal.cyan,
        actions = {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Pal.surface2)
                    .clickable { load() }, contentAlignment = Alignment.Center
            ) { Text("↻", color = Pal.dim, fontSize = 14.sp) }
        }
    ) {
        // root switch
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Guest" to 0, "Shared" to 1).forEach { (label, idx) ->
                val on = root == idx
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50))
                        .background(if (on) Pal.cyanSoft else Pal.surface)
                        .then(
                            if (on) Modifier else Modifier
                        )
                        .clickable { root = idx }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (on) Pal.cyan else Pal.faint, fontSize = 11.5.sp,
                        fontWeight = if (on) FontWeight.Black else FontWeight.Normal)
                }
            }
        }

        // actions
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MiniAction("＋ file") { nameField = ""; dialog = "file" }
            MiniAction("＋ dir") { nameField = ""; dialog = "dir" }
            MiniAction("open") {
                selected?.let { onOpenInEditor(it) } ?: run { note = "pick a file first" }
            }
        }

        note?.let {
            Text(it, color = if (it.contains("failed")) Pal.rose else Pal.mint, fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
        }

        if (loading) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(6) { Skeleton(38.dp) }
            }
        } else if (files.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (ready) "Nothing here yet" else "Set the engine up first",
                    color = Pal.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(if (ready) "Create a file with ＋ file, or build a project and it lands here."
                     else "System → set up the engine, then come back.",
                    color = Pal.faint, fontSize = 10.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(files, key = { it }) { path ->
                    val name = path.trimEnd('/').substringAfterLast('/')
                    val isDir = path.endsWith("/")
                    val on = selected == path
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(Tok.rS))
                            .background(if (on) Pal.violetSoft else Pal.surface)
                            .clickable { selected = if (on) null else path }
                            .padding(horizontal = 11.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (isDir) "▸" else "·",
                            color = if (isDir) Pal.amber else Pal.cyan, fontSize = 13.sp)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = Pal.text, fontSize = 12.sp,
                                fontWeight = if (isDir) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1)
                            Text(path, color = Pal.faint, fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                        if (on && !isDir) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                InlineAction("edit", Pal.violet) { onOpenInEditor(path) }
                                InlineAction("rename", Pal.amber) {
                                    nameField = name; dialog = "rename|$path"
                                }
                                InlineAction("delete", Pal.rose) {
                                    if (root == 0) act("deleted") { GuestFiles.delete(run, path) }
                                    else act("deleted") { SharedFiles.delete(ctx, path) }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
    }

    // create / rename dialog
    dialog?.let { kind ->
        val isRename = kind.startsWith("rename")
        val old = if (isRename) kind.substringAfter("|") else ""
        AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = Pal.surface,
            title = {
                Text(
                    when {
                        isRename -> "Rename"
                        kind == "dir" -> "New folder"
                        else -> "New file"
                    },
                    color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
            },
            text = {
                BasicTextField(
                    value = nameField, onValueChange = { nameField = it },
                    textStyle = TextStyle(color = Pal.text, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Tok.rS))
                            .background(Pal.surface2)
                            .padding(horizontal = 10.dp, vertical = 10.dp)) {
                            if (nameField.isEmpty()) Text("name", color = Pal.faint, fontSize = 13.sp)
                            inner()
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = nameField.trim()
                    dialog = null
                    if (name.isEmpty()) return@TextButton
                    when {
                        isRename -> {
                            val dir = old.substringBeforeLast('/', "")
                            val dest = if (dir.isBlank()) name else "$dir/$name"
                            act("renamed") {
                                if (root == 0) GuestFiles.rename(run, old, dest)
                                else SharedFiles.rename(ctx, old, dest)
                            }
                        }
                        kind == "dir" -> act("folder created") {
                            if (root == 0) GuestFiles.mkdir(run, name) else SharedFiles.mkdir(ctx, name)
                        }
                        else -> act("file created") {
                            if (root == 0) GuestFiles.write(run, name, "")
                            else SharedFiles.write("${SharedFiles.DIR}/$name", "")
                        }
                    }
                }) { Text(if (isRename) "Rename" else "Create", color = Pal.violet) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("Cancel", color = Pal.dim) }
            }
        )
    }
}

@Composable
private fun MiniAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Pal.surface)
            .border(1.dp, Pal.line, RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text(label, color = Pal.text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun InlineAction(label: String, tone: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.16f))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) { Text(label, color = tone, fontSize = 9.5.sp, fontWeight = FontWeight.Bold) }
}
