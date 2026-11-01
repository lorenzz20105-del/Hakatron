package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Splitting one paste into the files it contains.
 *
 * The cases below are real shapes: an explicit header list, the chat-transcript
 * "path then language then code" layout (which is exactly what the user pasted),
 * comment headers, and a whole project arriving as bare code with no headers at all.
 */
class PartsSplitTest {

    // ---------------------------------------------------------------- headers

    @Test
    fun `explicit headers become separate files`() {
        val raw = """
            === app.py ===
            from flask import Flask
            app = Flask(__name__)

            === requirements.txt ===
            flask==3.0.0
        """.trimIndent()
        val s = Parts.split(raw)
        assertEquals(2, s.count)
        assertEquals(listOf("app.py", "requirements.txt"), s.files)
        assertEquals(2, s.headerCount)
        assertTrue(s.toText().startsWith("=== app.py ==="))
    }

    @Test
    fun `the user paste splits at the path-then-language header`() {
        // 0.16.1 kept all of this in ONE `=== paste_here.kt ===` block
        val raw = """
            === paste_here.kt ===
            val regex = "^" + Regex.escape(pattern)
            return Regex(regex).matches(path)
            core/VariableResolver.kt
            kotlin
            package com.exam.le.idebot.core
            import java.time.LocalDate
            class VariableResolver {
                fun resolve(p: String) = p
            }
        """.trimIndent()
        val s = Parts.split(raw)
        assertEquals(2, s.count, "expected the snippet and core/VariableResolver.kt")
        assertTrue(s.files.contains("core/VariableResolver.kt"), s.files.toString())
        val resolver = s.parts.first { it.path == "core/VariableResolver.kt" }
        assertTrue(resolver.body.contains("package com.exam.le.idebot.core"))
        assertTrue(!resolver.body.contains("val regex"), "the header line leaked into the body")
        // the placeholder name is replaced from the code, never kept
        assertTrue(s.files.none { Parts.isPlaceholder(it) }, s.files.toString())
    }

    @Test
    fun `path then language alone is a header`() {
        val raw = """
            src/main.rs
            rust
            fn main() { println!("hi"); }

            app/main.py
            python
            print("hi")
        """.trimIndent()
        val s = Parts.split(raw)
        assertEquals(2, s.count)
        assertEquals(listOf("src/main.rs", "app/main.py"), s.files)
    }

    @Test
    fun `comment headers are honoured`() {
        val raw = """
            // src/index.js
            const express = require('express')
            const app = express()

            # tools/clean.py
            import sys
            print(sys.argv)
        """.trimIndent()
        val s = Parts.split(raw)
        assertEquals(2, s.count)
        assertTrue(s.files.contains("src/index.js"), s.files.toString())
        assertTrue(s.files.contains("tools/clean.py"), s.files.toString())
    }

    // ------------------------------------------------------------ no headers

    @Test
    fun `one bare file stays one file and is named from its code`() {
        val s = Parts.split("def clean(text):\n    return text.strip()\n")
        assertEquals(1, s.count)
        assertEquals("clean.py", s.files[0])
        assertEquals(0, s.headerCount)
        assertTrue(s.parts[0].inferred)
    }

    @Test
    fun `a language change after a blank line splits`() {
        val raw = """
            #include <stdio.h>
            int main(void) {
                printf("hi\n");
                return 0;
            }

            def main():
                print("hi")
        """.trimIndent()
        val s = Parts.split(raw)
        assertEquals(2, s.count, s.files.toString())
        assertEquals("main.c", s.files[0])
        assertEquals("main.py", s.files[1])
    }

    @Test
    fun `duplicate inferred names are made unique`() {
        val raw = """
            main.py
            python
            print(1)

            main.py
            python
            print(2)
        """.trimIndent()
        val s = Parts.split(raw)
        assertEquals(2, s.count)
        assertEquals(listOf("main.py", "main-2.py"), s.files)
    }

    @Test
    fun `empty input has no parts and does not crash`() {
        val s = Parts.split("   \n\n  ")
        assertEquals(0, s.count)
        assertEquals("no files detected", s.summary())
    }

    @Test
    fun `summary reads like a sentence`() {
        val s = Parts.split("=== a.py ===\nprint(1)\n\n=== b.py ===\nprint(2)\n")
        assertEquals("2 files · a.py, b.py", s.summary())
    }

    @Test
    fun `round trip is stable`() {
        val once = Parts.split("=== a.py ===\nprint(1)\n\n=== b.py ===\nprint(2)\n")
        val twice = Parts.split(once.toText())
        assertEquals(once.files, twice.files)
        assertEquals(once.toText(), twice.toText())
    }
}
