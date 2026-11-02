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

/**
 * The screenshot-of-a-chat case: the OCR text starts with the model's preamble
 * ("V deepseek", "I need all in one go", "Thought for 6 seconds", "Project Tree",
 * "0 Copy") before the real files. 0.17.0 named that preamble `main.cs` and it was
 * the only part visible, so an eight-file paste looked like one block.
 */
class ChatPreambleTest {

    private val pasted = """
        V deepseek
        "I need all in one go
        Thought for 6 seconds >
        IDE Bot - Complete Package (One Drop)
        Everything: build files, all sources, tests, README. Copy these into a fresh project and it builds.
        Project Tree
        0 Copy
        text
        IDEBotAutomation/
        settings.gradle.kts
        build.gradle

        === app/src/main/kotlin/App.kt ===
        package com.exam.idebot
        import androidx.compose.runtime.Composable
        class App

        === editor/EditorSurface.kt ===
        package com.exam.idebot.editor
        class EditorSurface

        === model/Action.kt ===
        package com.exam.idebot.model
        data class Action(val id: String)

        === core/TriggerBus.kt ===
        package com.exam.idebot.core
        class TriggerBus

        === core/UndoManager.kt ===
        package com.exam.idebot.core
        class UndoManager

        === storage/RuleDao.kt ===
        package com.exam.idebot.storage
        class RuleDao

        === ui/RuleListScreen.kt ===
        package com.exam.idebot.ui
        class RuleListScreen
    """.trimIndent()

    @Test
    fun `the chat preamble is not a file`() {
        val s = Parts.split(pasted)
        assertTrue(s.files.none { it == "main.cs" }, s.files.toString())
        assertTrue(s.files.none { it.contains("paste") }, s.files.toString())
        assertTrue(s.dropped >= 1, "preamble was kept as a part")
    }

    @Test
    fun `all seven real files survive`() {
        val s = Parts.split(pasted)
        assertEquals(7, s.count, s.files.toString())
        assertTrue(s.files.contains("App.kt") || s.files.contains("app/src/main/kotlin/App.kt"),
            s.files.toString())
        assertTrue(s.files.any { it.endsWith("EditorSurface.kt") }, s.files.toString())
    }

    @Test
    fun `prose without a header is prose`() {
        assertTrue(Parts.isProse("V deepseek\nI need all in one go\nThought for 6 seconds >"))
        assertTrue(Parts.isProse("Everything: build files, all sources, tests, README."))
        assertTrue(!Parts.isProse("package a\nclass B { }"))
        assertTrue(!Parts.isProse("fn main() { println!(\"x\"); }"))
    }

    @Test
    fun `chat noise lines are recognised`() {
        assertTrue(Parts.isChatNoise("V deepseek"))
        assertTrue(Parts.isChatNoise("Thought for 6 seconds >"))
        assertTrue(Parts.isChatNoise("0 Copy"))
        assertTrue(Parts.isChatNoise("Project Tree"))
        assertTrue(!Parts.isChatNoise("class App"))
    }
}
