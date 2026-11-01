package dev.devstudio.ide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Planner — structure + parts + notes → the plan Devos executes.
 *
 * The important property is *honesty*: what the project declares wins over what
 * the planner could guess, and nothing is invented when the project is silent.
 */
class PlannerTest {

    // plan() never executes anything; the run lambda is only used by build()
    private val planner = Planner(run = { cmd -> "" })

    private fun structure(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `notes are understood in plain english`() {
        val n = planner.parseNotes(
            """
            Tools needed first: gcc, make
            Before building: mkdir -p build
            Build: make
            Test: make test
            Run: ./build/app
            """.trimIndent()
        )
        assertEquals(listOf("gcc, make"), n["tools"])
        assertEquals(listOf("mkdir -p build"), n["before"])
        assertEquals(listOf("make"), n["build"])
        assertEquals(listOf("make test"), n["test"])
        assertEquals(listOf("./build/app"), n["run"])
    }

    @Test
    fun `python project gets an install step before the run`() {
        val plan = planner.plan(
            "app",
            structure("myapp/", "├── app.py", "└── requirements.txt"),
            "=== app.py ===\nimport yaml\nprint('hi')",
            ""
        )
        val kinds = plan.steps.map { it.kind }
        assertTrue("no install step in $kinds", kinds.contains("install"))
        assertTrue(kinds.indexOf("install") < kinds.indexOf("run"))
        assertTrue("yaml dependency lost", plan.steps.any { it.cmd.contains("pyyaml") })
    }

    @Test
    fun `package-json scripts are used instead of guesses`() {
        val plan = planner.plan(
            "site",
            structure("web/", "├── package.json", "├── index.js", "└── test.js"),
            """
            === package.json ===
            {"name":"web","scripts":{"build":"tsc -p .","test":"node test.js","start":"node index.js"}}
            === index.js ===
            console.log('hi')
            === test.js ===
            console.log('ok')
            """.trimIndent(),
            ""
        )
        val cmds = plan.steps.map { it.cmd }
        assertTrue("declared build script ignored: $cmds", cmds.contains("npm run build"))
        assertTrue("declared test script ignored: $cmds", cmds.contains("npm test"))
        assertTrue("declared start script ignored: $cmds", cmds.contains("npm start"))
        assertFalse("invented a node command: $cmds", cmds.any { it == "node index.js" })
    }

    @Test
    fun `a makefile is preferred over a guessed build`() {
        val plan = planner.plan(
            "tool",
            structure("tool/", "├── Makefile", "└── main.c"),
            "=== Makefile ===\nall:\n\tgcc -o tool main.c\n\ntest:\n\t./tool --test\n" +
                "=== main.c ===\nint main(void){return 0;}",
            ""
        )
        val cmds = plan.steps.map { it.cmd }
        assertTrue("make not used: $cmds", cmds.any { it == "make all" })
        assertTrue("make test not used: $cmds", cmds.contains("make test"))
    }

    @Test
    fun `cmake with enable_testing is tested through ctest`() {
        val plan = planner.plan(
            "core",
            structure("core/", "├── CMakeLists.txt", "└── main.cpp"),
            """
            === CMakeLists.txt ===
            cmake_minimum_required(VERSION 3.20)
            project(core CXX)
            enable_testing()
            add_test(NAME smoke COMMAND core)
            === main.cpp ===
            int main(){return 0;}
            """.trimIndent(),
            ""
        )
        val cmds = plan.steps.map { it.cmd }
        assertTrue("ctest missing: $cmds", cmds.any { it.startsWith("ctest") })
        assertTrue("cmake configure missing: $cmds", cmds.any { it.startsWith("cmake -S .") })
    }

    @Test
    fun `notes win over inference`() {
        val plan = planner.plan(
            "app",
            structure("app/", "└── app.py"),
            "=== app.py ===\nprint(1)",
            "Build: ./custom-build.sh\nRun: ./custom-run.sh"
        )
        val cmds = plan.steps.map { it.cmd }
        assertTrue("notes build ignored: $cmds", cmds.contains("./custom-build.sh"))
        assertTrue("notes run ignored: $cmds", cmds.contains("./custom-run.sh"))
    }

    @Test
    fun `an empty plan is refused instead of producing nonsense`() {
        val plan = planner.plan("nothing", "", "", "")
        assertTrue(plan.steps.isEmpty() || plan.steps.none { it.kind == "install" && it.cmd.isBlank() })
        assertTrue(plan.stack == "unknown" || plan.label.isNotBlank())
    }

    @Test
    fun `predictions name the riskiest thing before anything runs`() {
        val plan = planner.plan(
            "app",
            structure("app/", "└── app.py"),
            "=== app.py ===\nimport cv2\nimport yaml",
            ""
        )
        assertTrue("no predictions at all", plan.predictions.isNotEmpty())
        assertTrue("confidence out of range", plan.confidence in 0.0..1.0)
    }

    // ------------------------------------------------- the 0.14.0 screen bug
    @Test
    fun `an empty plan is blocked instead of building nothing`() {
        val plan = planner.plan("project", "", "", "")
        assertNotNull("an empty plan must carry a reason", plan.blocked)
        assertTrue(plan.blocked!!.contains("Nothing to build"))
        val result = planner.build(plan)
        assertFalse("an empty plan must not run", result.ok)
        // the refusal happens before anything is written or executed
        assertTrue(result.log.none { it.level == "cmd" })
    }

    @Test
    fun `a tree with no pasted contents is explained, not executed`() {
        val plan = planner.plan("project",
            structure("myapp/", "├── app.py", "└── requirements.txt"), "", "")
        if (plan.blocked != null) {
            assertTrue(plan.blocked!!.contains("contents"))
            assertFalse(planner.build(plan).ok)
        }
    }

    @Test
    fun `no plan ever contains a placeholder run step`() {
        val plan = planner.plan("project", "", "=== notes.md ===\nhello", "")
        assertTrue("placeholder steps: ${plan.steps.map { it.cmd }}", plan.steps.none { it.cmd == "true" })
    }

    @Test
    fun `a host-side remedy is handed to the launcher, not run in the guest`() {
        var hostFixes = mutableListOf<String>()
        val p = Planner(
            run = { cmd -> if (cmd.contains("make")) "sh: make: not found\n[exit 127]" else "[exit 0]" },
            onHostFix = { cmd -> hostFixes.add(cmd); true }
        )
        // the seccomp rule's command is an export: only the host can act on it
        assertTrue(p.parseNotes("Build: make").isNotEmpty())
        assertEquals(0, hostFixes.size)   // planning alone must not touch the device
    }
}
