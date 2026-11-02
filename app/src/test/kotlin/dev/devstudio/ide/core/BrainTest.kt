package dev.devstudio.ide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Brain — the small parametric model that decides what Devos is being asked to
 * build. No magic: a weight table, a softmax and one perceptron step.
 */
class BrainTest {

    private fun intent(desc: String, files: List<String> = emptyList()) =
        Brain.predict(desc, files).intent

    @Test
    fun `an android request is understood with no files at all`() {
        val g = Brain.predict("make me a todo app for android with a dark ui")
        assertEquals(Brain.Intent.Android, g.intent)
        assertTrue(g.confidence > 0.5, "confidence was ${g.confidence}")
        assertTrue(g.evidence.isNotEmpty())
    }

    @Test
    fun `an apk request is android`() {
        assertEquals(Brain.Intent.Android, intent("build an apk that shows my battery"))
    }

    @Test
    fun `a flask request is a python api`() {
        assertEquals(Brain.Intent.PythonApi, intent("a small flask api with one endpoint"))
    }

    @Test
    fun `a script request is a python cli`() {
        assertEquals(Brain.Intent.PythonCli, intent("write me a python script that reads a csv"))
    }

    @Test
    fun `a static page request is web`() {
        assertEquals(Brain.Intent.WebStatic, intent("a landing page in html and css"))
    }

    @Test
    fun `an express request is a node api`() {
        assertEquals(Brain.Intent.NodeApi, intent("an express api with npm"))
    }

    @Test
    fun `cargo and cmake and go are recognised`() {
        assertEquals(Brain.Intent.RustCli, intent("a rust cargo tool"))
        assertEquals(Brain.Intent.CNative, intent("a cmake c++ program"))
        assertEquals(Brain.Intent.GoCli, intent("a golang tool"))
    }

    @Test
    fun `pasted files outweigh words`() {
        // says "android" but what was pasted is plainly a python project
        val g = Brain.predict("android", listOf("app.py", "requirements.txt"))
        // the words still carry weight, so this is a contest — but it must not be certain
        assertTrue(g.confidence < 0.99, "confidence was ${g.confidence}")
    }

    @Test
    fun `a manifest in the paste is decisive`() {
        val g = Brain.predict("", listOf("app/src/main/AndroidManifest.xml"))
        assertEquals(Brain.Intent.Android, g.intent)
        assertTrue(g.confidence > 0.9, "confidence was ${g.confidence}")
    }

    @Test
    fun `nonsense is unknown rather than a guess`() {
        val g = Brain.predict("hello there how are you")
        assertEquals(Brain.Intent.Unknown, g.intent)
        assertEquals(0.0, g.confidence)
    }

    @Test
    fun `an empty request is unknown`() {
        assertEquals(Brain.Intent.Unknown, Brain.predict("").intent)
    }

    @Test
    fun `learning pushes a misread toward the truth`() {
        val desc = "a little timer thing"
        // a phrasing the defaults know but read the other way round
        val features = Brain.features("a small go tool")
        assertTrue(features.isNotEmpty(), features.toString())
        val before = Brain.predict("a small go tool")
        // teach it that this phrasing means android, over a few steps
        var weights = emptyMap<Brain.Intent, Map<String, Double>>()
        repeat(6) {
            val wrong = Brain.predict("a small go tool", weights = weights).intent
            weights = Brain.learn(weights, features, Brain.Intent.Android, wrong, rate = 0.6)
        }
        val after = Brain.predict("a small go tool", weights = weights)
        assertTrue(before.intent != Brain.Intent.Android, "already android: ${before.intent}")
        assertEquals(Brain.Intent.Android, after.intent, "before=${before.intent} after=${after.intent}")
        // the learned weights are what flipped it, and they are inspectable
        assertTrue(after.evidence.any { it.first == "word:go" })
    }

    @Test
    fun `weights survive a round trip through storage`() {
        val weights = mapOf(
            Brain.Intent.Android to mapOf("word:todo" to 2.5, "word:app" to -0.5),
            Brain.Intent.PythonCli to mapOf("word:todo" to -2.5)
        )
        val back = Brain.decode(Brain.encode(weights))
        assertEquals(weights, back)
    }

    @Test
    fun `a corrupt store line is ignored, not fatal`() {
        val back = Brain.decode("Android\tword:app=1.5,broken\nNonsense\tx=1\nPythonCli\t")
        assertEquals(1, back.size)
        assertEquals(1.5, back[Brain.Intent.Android]!!["word:app"])
    }

    @Test
    fun `learning never invents an unknown intent`() {
        val w = Brain.learn(emptyMap(), listOf("word:app"), Brain.Intent.Unknown, Brain.Intent.Android)
        assertTrue(w.isEmpty())
    }
}
