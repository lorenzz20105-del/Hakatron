package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every case here is a string the OCR actually produced on a real chat screenshot
 * (IDE Bot, DeepSeek, dark theme, 700 px wide).
 */
class OcrTextTest {

    @Test
    fun `word split in the middle is rejoined`() {
        assertEquals("IDEBotAutomation/", OcrText.repair("IDEBotAutomat ion/"))
    }

    @Test
    fun `space after a dot in a path is removed`() {
        assertEquals("Settings.gradle.kts", OcrText.repair("Settings. gradle.kts"))
    }

    @Test
    fun `hyphen broken by a space is rejoined`() {
        // the join is fixable; the missing leading "w" is not — inventing letters
        // would be guessing, so the fragment stays and the user can correct it
        assertEquals("rapper/gradle-wrapper.properties",
            OcrText.repair("rapper/gradle -wrapper.properties"))
    }

    @Test
    fun `real short words are not welded together`() {
        assertEquals("run all the tests", OcrText.repair("run all the tests"))
        assertEquals("build files for you", OcrText.repair("build files for you"))
    }

    @Test
    fun `space before punctuation is removed`() {
        assertEquals("int main(void) {", OcrText.repair("int main(void) {"))
        assertEquals("a, b, c", OcrText.repair("a , b , c"))
    }

    @Test
    fun `a connector read as a digit becomes a tree mark`() {
        assertTrue(OcrText.repair("0 src/").startsWith("└──"))
        assertTrue(OcrText.repair("1 app/").startsWith("├──"))
    }

    @Test
    fun `chat furniture is recognised`() {
        listOf("V deepseek", "deepseek", "Thought for 6 seconds", "0 Copy", "Copy",
               "text", "Project Tree", "I need all in one go", "Here is the project")
            .forEach { assertTrue(OcrText.isChatNoise(it), "should be noise: $it") }
    }

    @Test
    fun `real content is not mistaken for furniture`() {
        listOf("=== app.py ===", "package com.example", "index.html", "├── src/")
            .forEach { assertTrue(!OcrText.isChatNoise(it), "should be kept: $it") }
    }

    @Test
    fun `tree lines are recognised including substitutes`() {
        listOf("├── src/", "└── app.py", "|-- lib", "+-- main.c", "`-- test")
            .forEach { assertTrue(OcrText.isTreeLine(it), "should be a tree line: $it") }
        listOf("package a.b", "fs = require('fs')").forEach {
            assertTrue(!OcrText.isTreeLine(it), "should not be a tree line: $it")
        }
    }
}
