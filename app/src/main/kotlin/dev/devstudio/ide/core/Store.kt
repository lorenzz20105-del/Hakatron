package dev.devstudio.ide.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Store — everything DevStudio learns stays on the device.
 *
 *  • fix memory: error signature -> the command that actually fixed it
 *  • recipes: a plan that worked, reusable on a similar project
 *  • recents: files opened in the editor
 *
 * Plain JSON in SharedPreferences; no network, no analytics, wipeable.
 */
class Store(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("devstudio", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ fix memory
    fun memory(): MutableMap<String, String> {
        val raw = prefs.getString("fix_memory", "{}") ?: "{}"
        val out = HashMap<String, String>()
        runCatching {
            val o = JSONObject(raw)
            o.keys().forEach { k -> out[k] = o.getString(k) }
        }
        return out
    }

    fun rememberFix(signature: String, cmd: String) {
        val m = memory()
        m[signature] = cmd
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("fix_memory", o.toString()).apply()
    }

    fun forgetFix(signature: String) {
        val m = memory()
        if (!m.containsKey(signature)) return
        m.remove(signature)
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("fix_memory", o.toString()).apply()
    }

    // ------------------------------------------------------------ brain weights
    /** The weight table Brain learned on this device. Empty on a fresh install. */
    fun brain(): Map<Brain.Intent, Map<String, Double>> =
        Brain.decode(prefs.getString("brain_weights", "") ?: "")

    fun saveBrain(weights: Map<Brain.Intent, Map<String, Double>>) {
        prefs.edit().putString("brain_weights", Brain.encode(weights)).apply()
    }

    fun forgetBrain() {
        prefs.edit().remove("brain_weights").apply()
    }

    /** How many weight entries the model holds — shown next to the fix count. */
    fun brainSize(): Int = brain().values.sumOf { it.size }

    fun forgetFixes() {
        prefs.edit().remove("fix_memory").apply()
    }

    fun fixCount(): Int = memory().size

    // --------------------------------------------------------------- recipes
    data class Recipe(val name: String, val stack: String, val steps: List<String>, val notes: String)

    fun recipes(): MutableList<Recipe> {
        val raw = prefs.getString("recipes", "[]") ?: "[]"
        val out = ArrayList<Recipe>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val steps = ArrayList<String>()
                val sa = o.optJSONArray("steps") ?: JSONArray()
                for (j in 0 until sa.length()) steps.add(sa.getString(j))
                out.add(Recipe(o.optString("name"), o.optString("stack"), steps, o.optString("notes")))
            }
        }
        return out
    }

    fun saveRecipe(name: String, stack: String, steps: List<String>, notes: String) {
        val list = recipes().filterNot { it.name == name }
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("name", r.name); put("stack", r.stack); put("notes", r.notes)
                put("steps", JSONArray(r.steps))
            })
        }
        arr.put(JSONObject().apply {
            put("name", name); put("stack", stack); put("notes", notes)
            put("steps", JSONArray(steps))
        })
        prefs.edit().putString("recipes", arr.toString()).apply()
    }

    fun deleteRecipe(name: String) {
        val arr = JSONArray()
        recipes().filterNot { it.name == name }.forEach { r ->
            arr.put(JSONObject().apply {
                put("name", r.name); put("stack", r.stack); put("notes", r.notes)
                put("steps", JSONArray(r.steps))
            })
        }
        prefs.edit().putString("recipes", arr.toString()).apply()
    }

    // ------------------------------------------------------------ preferences
    fun wordWrap(): Boolean = prefs.getBoolean("wrap", false)
    fun setWordWrap(v: Boolean) = prefs.edit().putBoolean("wrap", v).apply()
    fun editorFont(): Int = prefs.getInt("font", 12)
    fun setEditorFont(v: Int) = prefs.edit().putInt("font", v).apply()

    // ------------------------------------------------------------ open tabs
    /** The editor reopens where you left off, across app restarts. */
    fun editorTabs(): List<String> {
        val raw = prefs.getString("tabs", "[]") ?: "[]"
        val out = ArrayList<String>()
        runCatching { JSONArray(raw).let { a -> for (i in 0 until a.length()) out.add(a.getString(i)) } }
        return out
    }

    fun setEditorTabs(paths: List<String>) {
        prefs.edit().putString("tabs", JSONArray(paths.take(8)).toString()).apply()
    }

    fun editorActive(): String? = prefs.getString("active_tab", null)
    fun setEditorActive(path: String?) {
        if (path == null) prefs.edit().remove("active_tab").apply()
        else prefs.edit().putString("active_tab", path).apply()
    }

    // ---------------------------------------------------------------- recents
    fun recents(): MutableList<String> {
        val raw = prefs.getString("recents", "[]") ?: "[]"
        val out = ArrayList<String>()
        runCatching { JSONArray(raw).let { a -> for (i in 0 until a.length()) out.add(a.getString(i)) } }
        return out
    }

    fun addRecent(path: String) {
        val list = ArrayList(recents().filterNot { it == path })
        list.add(0, path)
        prefs.edit().putString("recents", JSONArray(list.take(12)).toString()).apply()
    }
}
