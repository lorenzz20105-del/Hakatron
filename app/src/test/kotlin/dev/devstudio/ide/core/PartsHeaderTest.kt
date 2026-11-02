package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Header shapes — every way a chat window has ever marked a file. */
class PartsHeaderTest {

    @Test
    fun `bold file names cut a part`() {
        assertEquals(listOf("src/main.rs", "Cargo.toml"),
            Parts.split("**src/main.rs**\nfn main() {}\n\n**Cargo.toml**\n[package]\nname = \"x\"").files)
    }

    @Test
    fun `markdown headings with a path cut a part`() {
        assertEquals(listOf("app.py", "requirements.txt"),
            Parts.split("### File: app.py\nimport os\n\n### requirements.txt\nflask==3.0").files)
    }

    @Test
    fun `a plain label line cuts a part`() {
        assertEquals(listOf("index.js", "package.json"),
            Parts.split("File: index.js\nconsole.log(1)\n\nPath: package.json\n{\"a\":1}").files)
    }

    @Test
    fun `=== and --- still work`() {
        assertEquals(listOf("a.py", "b.py"),
            Parts.split("=== a.py ===\nx = 1\n\n--- b.py ---\ny = 2").files)
    }

    @Test
    fun `an extensionless file is a header too`() {
        assertEquals(listOf("app.py", "Dockerfile", "README.md"),
            Parts.split("**app.py**\nimport flask\n\n--- Dockerfile ---\nFROM python:3.12\n\n" +
                "### File: README.md\n# demo").files)
    }

    @Test
    fun `a C include is not a header`() {
        val s = Parts.split("#include <stdio.h>\n#include <stdlib.h>\nint main(void) { return 0; }")
        assertFalse(s.files.any { it.contains("stdio") }, s.files.toString())
        assertEquals(1, s.count)
    }

    @Test
    fun `bold emphasis in prose is not a header`() {
        val s = Parts.split("**Note**\nthis line explains the code below\n\n=== real.py ===\nprint(1)")
        assertTrue(s.files.contains("real.py"), s.files.toString())
        assertFalse(s.files.any { it.contains("Note", true) }, s.files.toString())
    }

    @Test
    fun `a header in the chat preamble is still found`() {
        val s = Parts.split("Here is the complete package.\nThought for 3 seconds\n\n" +
            "**server.js**\nconst http = require('http')", ocr = true)
        assertTrue(s.files.contains("server.js"), s.files.toString())
        assertEquals(1, s.count)
    }
}
