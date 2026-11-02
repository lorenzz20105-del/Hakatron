package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Devos predictions — what will break, said before it breaks.
 */
class PredictTest {

    private val planner = Planner(run = { "" })

    private fun predictions(structure: String, parts: String) =
        planner.plan("app", structure, parts, "").predictions.joinToString("\n") {
            "${it.sev} ${it.where}: ${it.what} → ${it.fix}"
        }

    @Test
    fun `a quoted include that was not pasted is predicted`() {
        val out = predictions("app/\n├── main.c\n└── Makefile",
            "=== main.c ===\n#include \"util.h\"\nint main(void){return 0;}")
        assertTrue(out.contains("util.h"), out)
    }

    @Test
    fun `an include that was pasted is not predicted`() {
        val out = predictions("app/\n├── main.c\n├── util.h\n└── Makefile",
            "=== main.c ===\n#include \"util.h\"\nint main(void){return 0;}\n\n=== util.h ===\nint f(void);")
        assertTrue(!out.contains("util.h"), out)
    }

    @Test
    fun `a system include is never predicted`() {
        val out = predictions("app/\n├── main.c\n└── Makefile",
            "=== main.c ===\n#include <stdio.h>\nint main(void){return 0;}")
        assertTrue(!out.contains("stdio.h"), out)
    }

    @Test
    fun `go without a sum file warns that modules will be downloaded`() {
        val out = predictions("app/\n├── go.mod\n└── main.go",
            "=== go.mod ===\nmodule app\n\ngo 1.22\n\n=== main.go ===\npackage main\nfunc main(){}")
        assertTrue(out.contains("go.sum"), out)
    }

    @Test
    fun `node without a lockfile says the install needs the network`() {
        val out = predictions("web/\n├── package.json\n└── index.js",
            "=== package.json ===\n{\"name\":\"w\",\"dependencies\":{\"express\":\"4.19.2\"}}\n\n" +
                "=== index.js ===\nconsole.log(1)")
        assertTrue(out.contains("lockfile"), out)
    }

    @Test
    fun `a Dockerfile is written but never built, and says so`() {
        val out = predictions("app/\n├── Dockerfile\n└── app.py",
            "=== Dockerfile ===\nFROM python:3.12\n\n=== app.py ===\nprint(1)")
        assertTrue(out.contains("docker cannot run"), out)
    }
}
