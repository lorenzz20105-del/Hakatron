package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `Planner.coalesceInstalls` — one apk call instead of one per rule. */
class InstallMergeTest {

    private fun step(kind: String, cmd: String) = Planner.Step(kind, cmd, "test")

    @Test
    fun `two apk steps become one, in order, without duplicates`() {
        val merged = Planner(run = { "" }).coalesceInstalls(listOf(
            step("install", "apk add --no-cache cmake build-base samurai"),
            step("build", "cmake -B build"),
            step("install", "apk add --no-cache build-base git")))
        val installs = merged.filter { it.kind == "install" }
        assertEquals(1, installs.size, merged.joinToString { it.cmd })
        assertEquals("apk add --no-cache cmake build-base samurai git", installs[0].cmd)
    }

    @Test
    fun `the merged install sits before the step that needs it`() {
        val merged = Planner(run = { "" }).coalesceInstalls(listOf(
            step("install", "apk add --no-cache git"),
            step("build", "make"),
            step("install", "apk add --no-cache nodejs")))
        assertEquals("install", merged[0].kind)
        assertEquals("apk add --no-cache git nodejs", merged[0].cmd)
    }

    @Test
    fun `pip and npm steps are never merged into apk`() {
        val merged = Planner(run = { "" }).coalesceInstalls(listOf(
            step("install", "apk add --no-cache python3"),
            step("install", "pip install --break-system-packages flask"),
            step("install", "npm install")))
        assertEquals(3, merged.size, merged.joinToString { it.cmd })
    }

    @Test
    fun `a plan with no installs is returned untouched`() {
        val steps = listOf(step("build", "make"), step("run", "./app"))
        assertEquals(steps, Planner(run = { "" }).coalesceInstalls(steps))
    }

    @Test
    fun `a real plan ends up with a single apk call`() {
        val plan = Planner(run = { "" }).plan("app",
            "app/\n├── CMakeLists.txt\n├── main.cpp\n└── Makefile",
            "=== CMakeLists.txt ===\ncmake_minimum_required(VERSION 3.20)\nproject(x CXX)\n\n" +
                "=== main.cpp ===\nint main(){return 0;}", "Tools needed first: git")
        val apk = plan.steps.filter { it.cmd.startsWith("apk add") }
        assertTrue(apk.size <= 1, plan.steps.joinToString { it.cmd })
        if (apk.isNotEmpty()) assertTrue("git" in apk[0].cmd, apk[0].cmd)
    }
}
