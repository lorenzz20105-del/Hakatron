package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recipe — Devos writing a real project, including the parts it cannot finish here.
 */
class RecipeTest {

    @Test
    fun `a project name is taken from the request`() {
        assertEquals("focus", Recipe.projectName("make me a todo app called Focus"))
        assertEquals("focus", Recipe.projectName("build a \"Focus\" notes app"))
    }

    @Test
    fun `a project name falls back to the meaningful words`() {
        val n = Recipe.projectName("make me a weather dashboard please")
        assertTrue(n.contains("weather"), n)
        assertEquals("app", Recipe.projectName("make me a simple app"))
    }

    @Test
    fun `an android scaffold is a complete project`() {
        val s = Recipe.scaffold(Brain.Intent.Android, "a todo app for android called Focus")!!
        assertEquals("focus", s.name)
        val files = s.files.keys
        assertTrue(files.contains("settings.gradle.kts"), files.toString())
        assertTrue(files.contains("build.gradle.kts"))
        assertTrue(files.contains("app/build.gradle.kts"))
        assertTrue(files.contains("app/src/main/AndroidManifest.xml"))
        assertTrue(files.any { it.endsWith("MainActivity.kt") }, files.toString())
        assertTrue(files.any { it.startsWith(".github/workflows") }, files.toString())
    }

    @Test
    fun `the android scaffold says it cannot build an apk here`() {
        val s = Recipe.scaffold(Brain.Intent.Android, "an android app")!!
        assertEquals("", s.guestBuild, "the guest must not claim it can build an apk")
        assertNotNull(s.limit)
        assertTrue(s.limit!!.contains("Android SDK"), s.limit!!)
        assertNotNull(s.elsewhere)
    }

    @Test
    fun `the manifest and the activity agree on the package`() {
        val s = Recipe.scaffold(Brain.Intent.Android, "an android app called notes")!!
        val manifest = s.files["app/src/main/AndroidManifest.xml"]!!
        val activity = s.files.entries.first { it.key.endsWith("MainActivity.kt") }.value
        val ns = Regex("namespace = \"([^\"]+)\"").find(s.files["app/build.gradle.kts"]!!)!!.groupValues[1]
        assertTrue(activity.startsWith("package $ns"), activity.take(60))
        assertTrue(manifest.contains("android:name=\".MainActivity\""))
    }

    @Test
    fun `a python cli scaffold is runnable as written`() {
        val s = Recipe.scaffold(Brain.Intent.PythonCli, "a python script that repeats text")!!
        assertTrue(s.files.containsKey("${s.name}.py"), s.files.keys.toString())
        val py = s.files["${s.name}.py"]!!
        assertTrue(py.contains("def main(argv: list[str]) -> int"), py.take(120))
        assertTrue(py.contains("argparse"))
        assertNull(s.limit)
    }

    @Test
    fun `a flask scaffold pins a real version and has a health route`() {
        val s = Recipe.scaffold(Brain.Intent.PythonApi, "a flask api")!!
        assertTrue(s.files["requirements.txt"]!!.contains("flask=="), s.files["requirements.txt"]!!)
        assertTrue(s.files["app.py"]!!.contains("/health"))
    }

    @Test
    fun `a static site is three files with no build step`() {
        val s = Recipe.scaffold(Brain.Intent.WebStatic, "a landing page")!!
        assertEquals(setOf("index.html", "style.css", "app.js"), s.files.keys)
        assertTrue(s.guestBuild.contains("http.server"))
    }

    @Test
    fun `a node service uses the stdlib so npm install is optional`() {
        val s = Recipe.scaffold(Brain.Intent.NodeApi, "an express api")!!
        assertTrue(s.files["server.js"]!!.contains("node:http"))
        assertTrue(!s.files["package.json"]!!.contains("\"dependencies\""))
    }

    @Test
    fun `the c scaffold ships a Makefile whose recipe really builds`() {
        val s = Recipe.scaffold(Brain.Intent.CNative, "a c program")!!
        val mk = s.files["Makefile"]!!
        assertTrue(mk.contains("\$(CC) \$(CFLAGS) -o \$@ \$^"), mk)
        assertEquals("make", s.guestBuild)
    }

    @Test
    fun `an intent devos cannot scaffold returns nothing rather than a stub`() {
        assertNull(Recipe.scaffold(Brain.Intent.Unknown, "something"))
    }

    @Test
    fun `rust ships a cargo project and admits the toolchain is missing`() {
        val s = Recipe.scaffold(Brain.Intent.RustCli, "a rust tool called sortr")!!
        assertEquals("sortr", s.name)
        assertTrue(s.files["Cargo.toml"]!!.contains("name = \"sortr\""))
        assertTrue(s.files.containsKey("src/main.rs"), s.files.keys.toString())
        assertTrue(s.files["src/main.rs"]!!.contains("fn main()"))
        assertEquals("", s.guestBuild, "cargo is not in the guest")
        assertTrue(s.limit!!.contains("Rust"), s.limit!!)
    }

    @Test
    fun `go ships a module and a stdlib main`() {
        val s = Recipe.scaffold(Brain.Intent.GoCli, "a go tool called grab")!!
        assertEquals("grab", s.name)
        assertTrue(s.files["go.mod"]!!.startsWith("module dev.devos.grab"))
        assertTrue(s.files["main.go"]!!.contains("package main"))
        assertTrue(!s.files["main.go"]!!.contains("github.com/"), "no third-party deps")
        assertTrue(s.limit!!.contains("Go"), s.limit!!)
    }

    @Test
    fun `every scaffold names its project consistently`() {
        listOf(
            Brain.Intent.Android, Brain.Intent.PythonCli, Brain.Intent.PythonApi,
            Brain.Intent.WebStatic, Brain.Intent.NodeApi, Brain.Intent.CNative
        ).forEach { i ->
            val s = Recipe.scaffold(i, "a thing called widget")!!
            assertEquals("widget", s.name, "$i")
            assertTrue(s.files.size >= 2, "$i has ${s.files.size} files")
        }
    }
}
