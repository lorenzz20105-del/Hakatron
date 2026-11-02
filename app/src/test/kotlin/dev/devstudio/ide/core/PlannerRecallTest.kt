package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Devos warm start — `Planner.recall`.
 */
class PlannerRecallTest {

    private val planner = Planner(run = { "" })

    private fun plan(name: String = "app") = planner.plan(
        name, "myapp/\n├── app.py\n└── requirements.txt",
        "=== app.py ===\nimport yaml\nprint('hi')", ""
    )

    private fun steps(p: Planner.Plan) = p.steps.joinToString(" | ") { "${it.kind}=${it.cmd}" }

    @Test
    fun `the commands that worked here replace the inferred ones`() {
        val p = plan()
        assertTrue(p.steps.isNotEmpty(), "fixture produced no steps")
        val warm = planner.recall(p, listOf(
            Planner.Recall(p.name, p.stack, p.steps.map { "${it.kind}|echo proven-${it.kind}" })))
        assertTrue(warm.steps.all { it.cmd.startsWith("echo proven-") }, steps(warm))
        assertEquals(p.name, warm.recalledFrom)
    }

    @Test
    fun `a warm plan is more confident but never more than certain`() {
        val p = plan().copy(confidence = 0.5)
        val past = listOf(Planner.Recall(p.name, p.stack, listOf("${p.steps.first().kind}|pip install pyyaml")))
        assertTrue(planner.recall(p, past).confidence > p.confidence)
        assertEquals(1.0, planner.recall(p.copy(confidence = 0.99), past).confidence)
    }

    @Test
    fun `a different stack is left untouched`() {
        val p = plan()
        val past = listOf(Planner.Recall(p.name, "rust", listOf("build|cargo build --release")))
        assertSame(p, planner.recall(p, past))
        assertNull(planner.recall(p, past).recalledFrom)
    }

    @Test
    fun `no recipes means no change at all`() {
        val p = plan()
        assertSame(p, planner.recall(p, emptyList()))
    }

    @Test
    fun `recipe steps the plan does not have are not added`() {
        val p = plan()
        val past = listOf(Planner.Recall(p.name, p.stack,
            p.steps.map { "${it.kind}|proven ${it.kind}" } + "deploy|rsync -a . /srv/"))
        val warm = planner.recall(p, past)
        assertEquals(p.steps.size, warm.steps.size, steps(warm))
        assertTrue(warm.steps.none { it.kind == "deploy" }, steps(warm))
    }

    @Test
    fun `a broken recipe line is ignored rather than half-applied`() {
        val p = plan()
        assertSame(p, planner.recall(p, listOf(Planner.Recall(p.name, p.stack, listOf("", "|", "install")))))
    }

    @Test
    fun `the same stack under another name still warms the build`() {
        val p = plan()
        val warm = planner.recall(p, listOf(
            Planner.Recall(p.name + "-old", p.stack, listOf("run|python3 app.py --port 3000"))))
        assertNotNull(warm.recalledFrom)
        assertTrue(warm.steps.any { it.cmd.contains("--port 3000") }, steps(warm))
    }

    @Test
    fun `an exact name match wins over an older project of the same stack`() {
        val p = plan()
        val warm = planner.recall(p, listOf(
            Planner.Recall("other", p.stack, listOf("run|python3 other.py")),
            Planner.Recall(p.name, p.stack, listOf("run|python3 app.py"))))
        assertTrue(warm.steps.any { it.cmd == "python3 app.py" }, steps(warm))
        assertTrue(warm.steps.none { it.cmd == "python3 other.py" }, steps(warm))
    }
}
