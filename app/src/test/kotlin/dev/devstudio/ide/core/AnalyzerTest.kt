package dev.devstudio.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Analyzer — structure parsing, bundle parsing and stack detection.
 *
 * These are the pieces every other part of Devos sits on: if `readStructure`
 * attaches a file to the wrong directory, the build runs the wrong command.
 */
class AnalyzerTest {

    private val fourSpace = """
        myapp/
            app.py
            requirements.txt
            templates/
                index.html
            static/
                style.css
    """.trimIndent()

    @Test
    fun `four-space trees nest correctly`() {
        val p = Analyzer.readStructure(fourSpace)
        assertTrue(p.dirs.contains("myapp"))
        assertTrue(p.dirs.contains("myapp/templates"))
        assertTrue(p.dirs.contains("myapp/static"))
        assertTrue(p.files.contains("myapp/templates/index.html"))
        assertTrue(p.files.contains("myapp/static/style.css"))
        assertTrue(p.files.contains("myapp/app.py"))
    }

    @Test
    fun `indentation rank, not a fixed divisor, decides depth`() {
        // 3-space and 6-space trees describe the same project as the 4-space one
        val threeSpace = "myapp/\n   app.py\n   templates/\n      index.html\n"
        val sixSpace = "myapp/\n      app.py\n      templates/\n            index.html\n"
        listOf(threeSpace, sixSpace, fourSpace).forEach { text ->
            val p = Analyzer.readStructure(text)
            assertTrue("index.html not under templates in:\n$text",
                p.files.contains("myapp/templates/index.html"))
        }
    }

    @Test
    fun `box-drawing trees from a chat answer are understood`() {
        val glyphs = """
            myapp/
            ├── app.py
            ├── requirements.txt
            └── templates/
                └── index.html
        """.trimIndent()
        val p = Analyzer.readStructure(glyphs)
        assertTrue(p.dirs.contains("myapp/templates"))
        assertTrue(p.files.contains("myapp/templates/index.html"))
        assertTrue(p.files.contains("myapp/requirements.txt"))
    }

    @Test
    fun `bundle headers split one paste into files`() {
        val bundle = """
            === app.py ===
            from flask import Flask
            === requirements.txt ===
            flask==3.0.0
        """.trimIndent()
        val m = Analyzer.readBundle(bundle)
        assertEquals(setOf("app.py", "requirements.txt"), m.keys)
        assertTrue(m["app.py"]!!.contains("Flask"))
        assertTrue(m["requirements.txt"]!!.contains("flask==3.0.0"))
    }

    @Test
    fun `other header styles are accepted too`() {
        val bundle = """
            # FILE: main.go
            package main
            ```go server.js
            console.log(1)
        """.trimIndent()
        val m = Analyzer.readBundle(bundle)
        assertTrue(m.containsKey("main.go"))
        assertTrue("fenced header not understood: ${m.keys}", m.containsKey("server.js"))
    }

    @Test
    fun `python is detected and its imports become pip names`() {
        val files = listOf("app.py", "requirements.txt")
        val plan = Analyzer.analyze(files, emptyList(), mapOf(
            "app.py" to "import yaml\nfrom PIL import Image\nimport os\n"
        ))
        assertEquals("python", plan.stack)
        assertTrue("pip list was ${plan.pip}", plan.pip.contains("pyyaml"))
        assertTrue("pillow missing from ${plan.pip}", plan.pip.contains("pillow"))
        assertTrue("stdlib leaked into pip: ${plan.pip}", !plan.pip.contains("os"))
    }

    @Test
    fun `node project is detected from package json`() {
        val plan = Analyzer.analyze(
            listOf("package.json", "index.js"), emptyList(),
            mapOf("package.json" to "{\"dependencies\":{\"express\":\"^4\"}}",
                "index.js" to "const express = require('express')\napp.listen(3000)")
        )
        assertEquals("node", plan.stack)
        assertEquals(3000, plan.port)
    }

    @Test
    fun `local modules are not mistaken for packages`() {
        val mods = Analyzer.localModules(listOf("src/utils.py", "app.py"))
        assertTrue(mods.contains("utils"))
        assertNotNull(mods)
    }
}
