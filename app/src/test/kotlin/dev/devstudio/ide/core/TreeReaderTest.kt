package dev.devstudio.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TreeReader — the screenshot → structure parser.
 *
 * The four renderings below are the same project: as it appears in a chat answer,
 * with OCR-dropped box characters, with pixel jitter, and with all spacing gone.
 * The first three must produce the identical tree; the last must degrade honestly
 * instead of inventing depth.
 */
class TreeReaderTest {

    private val reference = """
        IDEbotAutomation/
        ├── settings.gradle.kts
        ├── build.gradle.kts
        └── app/
            ├── build.gradle.kts
            └── src/
                └── main/
                    ├── AndroidManifest.xml
                    ├── res/values/{strings.xml, themes.xml}
                    └── java/com/example/idebot/
                        ├── App.kt
                        └── di/AppModule.kt
    """.trimIndent()

    /** every line's name → its indent width, so assertions read like the picture */
    private fun indents(structure: String): List<Pair<String, Int>> =
        structure.lines().filter { it.isNotBlank() }
            .map { it.trim() to (it.length - it.trimStart().length) }

    private fun indentOf(structure: String, name: String, nth: Int = 0): Int =
        indents(structure).filter { it.first == name }.map { it.second }.getOrElse(nth) { -1 }

    @Test
    fun `stripPrefix counts bars and indentation separately`() {
        assertEquals("src/" to 2, TreeReader.stripPrefix("│   ├── src/"))
        assertEquals("app.py" to 1, TreeReader.stripPrefix("├── app.py"))
        assertEquals("main/" to 3, TreeReader.stripPrefix("        └── main/"))
        // one bar column + one connector = two levels, exactly as drawn
        assertEquals("x.kt" to 2, TreeReader.stripPrefix("│       ├── x.kt"))
    }

    @Test
    fun `reads the reference project with the right root`() {
        val r = TreeReader.parse(reference)
        assertTrue("nothing parsed", r.ok)
        assertEquals("IDEbotAutomation", r.root)
        assertTrue(r.structure.startsWith("IDEbotAutomation/"))
    }

    @Test
    fun `nesting follows the picture`() {
        val s = TreeReader.parse(reference).structure
        assertEquals(4, indentOf(s, "settings.gradle.kts"))      // child of the root
        assertEquals(4, indentOf(s, "app/"))
        assertEquals(8, indentOf(s, "build.gradle.kts", 1))       // child of app/
        assertEquals(12, indentOf(s, "main/"))
        assertEquals(16, indentOf(s, "res/values/"))
        assertEquals(20, indentOf(s, "strings.xml"))              // child of res/values/
        assertEquals(20, indentOf(s, "themes.xml"))
        assertEquals(20, indentOf(s, "App.kt"))                   // child of java/com/example/idebot/
    }

    @Test
    fun `brace expansion makes a real folder with two children`() {
        val s = TreeReader.parse(reference).structure
        assertTrue(s.contains("res/values/"))
        assertEquals(20, indentOf(s, "strings.xml"))
        assertEquals(20, indentOf(s, "themes.xml"))
        assertTrue("braces left in a filename", !s.contains("{"))
    }

    @Test
    fun `prose between trees is dropped, not turned into files`() {
        val noisy = """
            Project Tree
            Copy
            IDEbotAutomation/
            ├── settings.gradle.kts
            └── app/
            Thought for 6 seconds
        """.trimIndent()
        val r = TreeReader.parse(noisy)
        assertTrue(r.structure.lines().none { it.trim() == "Project Tree" })
        assertTrue(r.structure.lines().none { it.trim() == "Copy" })
        assertTrue("something was ignored", r.ignored > 0)
        assertEquals("IDEbotAutomation", r.root)
    }

    @Test
    fun `ocr dropping every box character still nests correctly`() {
        val dropped = """
            IDEbotAutomation/
                settings.gradle.kts
                build.gradle.kts
                app/
                    build.gradle.kts
                    src/
                        main/
                            AndroidManifest.xml
                            res/values/{strings.xml, themes.xml}
                            java/com/example/idebot/
                                App.kt
                                di/AppModule.kt
        """.trimIndent()
        val a = TreeReader.parse(reference).structure
        val b = TreeReader.parse(dropped).structure
        assertEquals(a, b)
    }

    @Test
    fun `pixel left edges are used when OCR gives them`() {
        // 32 px per level, as measured on the real 720 px screenshot; ±3 px jitter
        val rows = listOf(
            "IDEbotAutomation/" to 20f, "├── settings.gradle.kts" to 23f,
            "├── build.gradle.kts" to 18f, "└── app/" to 21f,
            "    ├── build.gradle.kts" to 52f, "    └── src/" to 49f,
            "        └── main/" to 84f, "            ├── AndroidManifest.xml" to 116f,
            "            ├── res/values/{strings.xml, themes.xml}" to 113f,
            "            └── java/com/example/idebot/" to 116f,
            "                ├── App.kt" to 148f,
            "                └── di/AppModule.kt" to 151f
        )
        val r = TreeReader.parse(rows.map { TreeReader.OcrLine(it.first, it.second) })
        assertTrue(r.ok)
        val s = r.structure
        assertEquals(8, indentOf(s, "build.gradle.kts", 1))
        assertEquals(16, indentOf(s, "res/values/"))
        assertEquals(20, indentOf(s, "App.kt"))
    }

    @Test
    fun `a long wrapped line cannot shift the levels below it`() {
        val rows = reference.lines().toMutableList()
        rows.add(rows.size - 1, "                    some very long wrapped continuation line")
        val s = TreeReader.parse(rows.joinToString("\n")).structure
        assertEquals(20, indentOf(s, "App.kt"))
    }

    @Test
    fun `empty and junk input is refused instead of crashing`() {
        assertEquals(0, TreeReader.parse("").entries)
        assertEquals(0, TreeReader.parse("hello there\nno tree here").entries)
        assertNotNull(TreeReader.parse("\u0000\u0001 random \u0000"))
    }
}
