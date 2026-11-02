package dev.devstudio.ide.core

import dev.devstudio.ide.ui.FileTree
import dev.devstudio.ide.ui.Lang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drawer's tree. The composable only renders what these functions return, so this
 * is where the layout of the explorer is actually decided.
 */
class ExplorerTreeTest {

    private val project = listOf(
        "/root/projects/Cheto/app/src/main/cpp/CMakeLists.txt",
        "/root/projects/Cheto/app/src/main/cpp/jni_bridge.cpp",
        "/root/projects/Cheto/app/src/main/cpp/physics.cpp",
        "/root/projects/Cheto/app/src/main/cpp/physics.hpp",
        "/root/projects/Cheto/app/src/main/java/com/cheto/pool/MainActivity.java",
        "/root/projects/Cheto/app/src/main/AndroidManifest.xml",
        "/root/projects/Cheto/app/build.gradle",
        "/root/projects/Cheto/settings.gradle"
    )

    @Test
    fun `the tree starts at the project, not at root`() {
        val rows = FileTree.rows(project, expanded = emptySet())
        assertEquals("app", rows.first().name)
        assertTrue(rows.none { it.name == "root" || it.name == "projects" })
    }

    @Test
    fun `a folded folder shows one row and nothing under it`() {
        val rows = FileTree.rows(project, expanded = emptySet())
        assertEquals(1, rows.count { it.name == "app" }, rows.map { it.name }.toString())
        assertTrue(rows.none { it.name == "src" }, "src must stay hidden while app is folded")
        assertTrue(rows.all { it.depth == 0 })
    }

    @Test
    fun `folding one level reveals exactly one level`() {
        val root = FileTree.commonRoot(project.map { it.split("/").filter { s -> s.isNotEmpty() } })
            .joinToString("/")
        val rows = FileTree.rows(project, expanded = setOf("$root/app"))
        val depths = rows.filter { it.folder }.map { it.name to it.depth }
        assertTrue(depths.contains("src" to 1), depths.toString())
        assertTrue(rows.none { it.name == "main" }, "main is two levels down and stays hidden")
    }

    @Test
    fun `folders come before files and both are alphabetical`() {
        val rows = FileTree.rows(project, expanded = emptySet())
        val names = rows.map { it.name }
        assertEquals(listOf("app", "settings.gradle"), names.take(2))
        // at the project root, the only folder precedes the only file
        val top = FileTree.rows(project, expanded = emptySet())
        assertTrue(top.first().folder, "a folder comes first")
        assertTrue(!top.last().folder, "the file comes after it")
    }

    @Test
    fun `a folded folder reports how many entries it holds`() {
        val rows = FileTree.rows(project, expanded = emptySet())
        val app = rows.first { it.name == "app" }
        assertTrue(app.children >= 2, "app holds src and build.gradle, saw ${app.children}")
    }

    @Test
    fun `the language mark matches the reference`() {
        assertEquals(Lang.Cpp, FileTree.lang("jni_bridge.cpp"))
        assertEquals(Lang.Header, FileTree.lang("scanner.hpp"))
        assertEquals(Lang.Java, FileTree.lang("MainActivity.java"))
        assertEquals(Lang.Xml, FileTree.lang("AndroidManifest.xml"))
        assertEquals(Lang.Text, FileTree.lang("CMakeLists.txt"))
        assertEquals(Lang.Gradle, FileTree.lang("build.gradle"))
        assertEquals("C++", Lang.Cpp.badge)
        assertEquals("</>", Lang.Xml.badge)
    }

    @Test
    fun `default expansion opens two levels and no deeper`() {
        val set = FileTree.defaultExpanded(project, keep = 2)
        assertTrue(set.any { it.endsWith("/app") }, set.toString())
        assertTrue(set.any { it.endsWith("/src") })
        assertTrue(set.none { it.contains("CMakeLists") }, "a file is never in the expanded set")
    }

    @Test
    fun `an empty project is not a crash`() {
        assertEquals(emptyList(), FileTree.rows(emptyList(), emptySet()))
        assertTrue(FileTree.defaultExpanded(emptyList()).isEmpty())
        assertEquals("no files yet", FileTree.summary(emptyList()))
    }

    @Test
    fun `the summary counts files, folders and the top languages`() {
        val line = FileTree.summary(project)
        assertTrue(line.startsWith("8 file(s)"), line)
        assertTrue(line.contains("C++×2"), line)
        assertTrue(line.contains("folder(s)"), line)
    }

    @Test
    fun `one file in one folder is still a tree`() {
        val rows = FileTree.rows(listOf("/root/projects/only/src/main.py"), emptySet())
        assertEquals(listOf("src"), rows.map { it.name }, "the file's own folder is the root row")
    }
}
