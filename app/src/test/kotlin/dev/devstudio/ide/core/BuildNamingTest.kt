package dev.devstudio.ide.core

import dev.devstudio.ide.ui.renamePlaceholder
import dev.devstudio.ide.ui.suggestFileName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parts box names files. 0.16.0 shipped a literal `=== paste_here.kt ===`
 * header, so Python source was planned as a Kotlin project. These tests pin the
 * fix: the name is inferred from the project, and a stale placeholder is replaced.
 */
class BuildNamingTest {

    @Test
    fun `python tree suggests a python file`() {
        val tree = "myapp/\n├── app.py\n├── requirements.txt\n└── templates/index.html"
        assertEquals("main.py", suggestFileName(tree, ""))
    }

    @Test
    fun `cpp wins over plain c`() {
        assertEquals("main.cpp", suggestFileName("proj/\n├── main.cpp\n└── util.c", ""))
    }

    @Test
    fun `rust tree suggests rs`() {
        assertEquals("main.rs", suggestFileName("app/\n├── Cargo.toml\n└── src/main.rs", ""))
    }

    @Test
    fun `notes alone are enough`() {
        assertEquals("main.go", suggestFileName("", "Build: go build -o app main.go"))
    }

    @Test
    fun `unknown project falls back to an obviously generic name`() {
        assertEquals("main.txt", suggestFileName("proj/\n└── README", ""))
    }

    @Test
    fun `placeholder header is replaced with the inferred name`() {
        val parts = "=== paste_here.kt ===\nprint('hello')\n"
        val fixed = renamePlaceholder(parts, "main.py")
        assertEquals("=== main.py ===\nprint('hello')\n", fixed)
        assertTrue(!fixed.contains("paste_here"))
    }

    @Test
    fun `real file headers are left alone`() {
        val parts = "=== app.py ===\nfrom flask import Flask\n=== requirements.txt ===\nflask==3.0.0"
        assertEquals(parts, renamePlaceholder(parts, "main.py"))
    }
}
