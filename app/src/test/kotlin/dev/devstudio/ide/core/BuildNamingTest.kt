package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Naming and splitting. 0.16.0 shipped a literal `=== paste_here.kt ===` header, so
 * Python source was planned as a Kotlin project and everything was one part.
 */
class BuildNamingTest {

    @Test
    fun `idle names a class after the class`() {
        val kotlin = "package com.exam.le.idebot.core\n\nclass VariableResolver {\n    fun go() = 1\n}"
        assertEquals("VariableResolver.kt", Parts.inferName(kotlin))
    }

    @Test
    fun `idle names a python function`() {
        assertEquals("clean.py", Parts.inferName("def clean(text):\n    return text.strip()\n"))
    }

    @Test
    fun `idle names a rust function`() {
        assertEquals("run.rs", Parts.inferName("fn run() -> i32 {\n    0\n}\n"))
    }

    @Test
    fun `main is not used as a file name twice`() {
        assertEquals("main.py", Parts.inferName("def main():\n    pass\n"))
    }

    @Test
    fun `a requirements list names itself`() {
        assertEquals("requirements.txt", Parts.inferName("flask==3.0.0\nrequests>=2.31\n"))
    }

    @Test
    fun `a dockerfile names itself`() {
        assertEquals("Dockerfile", Parts.inferName("FROM alpine:3.20\nRUN apk add build-base\n"))
    }

    @Test
    fun `an html document names itself`() {
        assertEquals("index.html", Parts.inferName("<!DOCTYPE html>\n<html><body>hi</body></html>"))
    }

    @Test
    fun `placeholder names are recognised`() {
        assertTrue(Parts.isPlaceholder("paste_here.kt"))
        assertTrue(Parts.isPlaceholder("untitled.py"))
        assertFalse(Parts.isPlaceholder("app.py"))
        assertFalse(Parts.isPlaceholder("core/VariableResolver.kt"))
    }

    @Test
    fun `language detection separates kotlin from java and python`() {
        assertEquals("kotlin", Parts.languageOf("package a.b\nfun main() { val x = 1 }"))
        assertEquals("java", Parts.languageOf("import java.time.LocalDate;\npublic class A {}"))
        assertEquals("python", Parts.languageOf("import os\nprint(os.getcwd())"))
    }
}
