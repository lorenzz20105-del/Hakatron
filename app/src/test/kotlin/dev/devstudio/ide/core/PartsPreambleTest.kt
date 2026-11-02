package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bug this file pins: a screenshot of a chat answer contains the model's
 * preamble and an embedded tree, and *everything* used to land in the first part
 * (`=== main.cs ===`), so the parts box looked like one file of prose.
 */
class PartsPreambleTest {

    /** real OCR text, repaired, from the IDE Bot screenshot */
    private val ocr = """
        V deepseek
        I need all in one go
        Thought for 6 seconds
        IDE Bot - Complete Package (One Drop)
        Everything: build files, all sources, tests, README.
        Project Tree
        ├── IDEBotAutomation/
        ├── settings.gradle.kts
        ├── build.gradle
        └── wrapper/gradle-wrapper.properties
        text
        === App.kt ===
        package com.exam.le.idebot
        import java.time.LocalDate
        class App { fun start() = Unit }
        === core/TriggerBus.kt ===
        package com.exam.le.idebot.core
        class TriggerBus { fun fire() = Unit }
    """.trimIndent()

    @Test
    fun `preamble and chat furniture never become a part`() {
        val s = Parts.split(ocr, ocr = true)
        assertTrue(s.files.none { it.lowercase().startsWith("main.cs") },
            "the chat preamble was turned into a file: ${s.files}")
        assertTrue(s.dropped >= 6, "expected the preamble to be dropped, dropped=${s.dropped}")
    }

    @Test
    fun `the real files are the only parts`() {
        val s = Parts.split(ocr, ocr = true)
        assertEquals(listOf("App.kt", "core/TriggerBus.kt"), s.files)
    }

    @Test
    fun `the embedded tree is lifted out for step 1`() {
        val s = Parts.split(ocr, ocr = true)
        assertTrue(s.tree.contains("IDEBotAutomation/"), "tree not captured: ${s.tree}")
        assertTrue(s.tree.contains("wrapper/gradle-wrapper.properties"), "tree not captured")
        // and it is not inside any file body
        assertTrue(s.parts.none { it.body.contains("gradle-wrapper") })
    }

    @Test
    fun `a header with no body is not a file`() {
        val raw = """
            === src/ ===
            === app.py ===
            print(1)
        """.trimIndent()
        val s = Parts.split(raw)
        assertEquals(listOf("app.py"), s.files)
    }

    @Test
    fun `pasted code keeps its spacing and its name`() {
        // repair() is OCR-only: real code is never rewritten
        val out = Parts.split("=== a.py ===\nresult = a - b\n").toText()
        assertTrue(out.startsWith("=== a.py ==="), out)
        assertTrue(out.contains("result = a - b"), out)
    }
}
