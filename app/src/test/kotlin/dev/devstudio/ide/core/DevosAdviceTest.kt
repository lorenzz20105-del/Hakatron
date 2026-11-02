package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Devos honesty — `Planner.advice` and the same-error guard.
 */
class DevosAdviceTest {

    private val planner = Planner(run = { "" })

    @Test
    fun `a successful build needs no advice`() = assertNull(planner.advice(null, null))

    @Test
    fun `a syntax stop points at the line and refuses to edit source`() {
        val a = planner.advice("syntax error: expected ;", 42)!!
        assertTrue(a.contains("line 42") && a.contains("not edit your source"), a)
    }

    @Test
    fun `an unknown error says so instead of inventing a fix`() {
        val a = planner.advice("unknown error", null)!!
        assertTrue(a.startsWith("Devos has no rule") && a.contains("Build notes"), a)
    }

    @Test
    fun `a fix that changed nothing is reported as wrong for this project`() {
        assertTrue(planner.advice("fix made no difference", null)!!.contains("wrong"))
        assertTrue(planner.advice("the same error came back", null)!!.contains("unchanged"))
    }

    @Test
    fun `exhausted retries blame the command, not the user`() {
        val a = planner.advice("step retries exhausted", null)!!
        assertTrue(a.contains("does not fit this project"), a)
    }

    @Test
    fun `a failed repair command names the likely reason`() {
        assertTrue(planner.advice("fix command failed", null)!!.contains("Alpine"))
    }

    @Test
    fun `an empty paste is answered with what is missing`() {
        assertTrue(planner.advice("no file contents were pasted", null)!!.contains("Paste"))
    }

    @Test
    fun `the same failure twice ends the attempt instead of repeating the fix`() {
        val commands = ArrayList<String>()
        val live = Planner(run = { cmd -> commands.add(cmd)
            when {
                cmd.contains("apk add") -> "installed\n[exit 0]"
                cmd.contains("python3 app.py") -> "sh: python3: not found\n[exit 127]"
                else -> "[exit 0]"
            }
        })
        val full = live.plan("app", "myapp/\n├── app.py\n└── requirements.txt",
            "=== app.py ===\nimport yaml\nprint('hi')", "")
        val plan = full.copy(steps = full.steps.filter { it.kind == "run" })
        val res = live.build(plan, maxAttempts = 4)

        assertTrue(!res.ok, "the build should have stopped")
        assertTrue(res.stopped!!.contains("same error came back"), "stopped=${res.stopped}")
        assertNotNull(res.advice)
        assertEquals(2, commands.count { it.contains("cd ~/projects/app && python3 app.py") },
            commands.joinToString("\n"))
    }

    @Test
    fun `a stop always carries advice or an honest null`() {
        val plan = planner.plan("project", "app/\n└── x.py", "", "")
        val res = planner.build(plan)
        if (!res.ok) assertEquals(res.advice, planner.advice(res.stopped, res.gotoLine))
    }
}
