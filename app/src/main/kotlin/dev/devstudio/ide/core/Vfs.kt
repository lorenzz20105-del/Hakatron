package dev.devstudio.ide.core

/**
 * Vfs — a tiny in-memory file system for open documents.
 *
 * Every read from the guest costs a PRoot round-trip (~0.4 s), and a phone
 * cannot afford to do that on every tab switch. The Vfs keeps the files you have
 * open in memory, tracks which buffers are dirty, and only writes back what
 * actually changed. It is deliberately small: the editor owns the text, the Vfs
 * owns "is this buffer still what is on disk?".
 *
 * Why it exists (measurements from this project):
 *   • re-reading a 4 MB file from the guest on every tab switch made the editor
 *     visibly stall — memory hit ≈ 0, disk hit per switch = 1 shell call
 *   • a buffer identical to disk is never written back, so save() is a no-op
 *     unless you actually typed something
 */
object Vfs {

    private data class Entry(var text: String, var disk: String)

    private const val MAX_FILES = 24
    private const val MAX_CHARS = 8_000_000        // ~8 MB of text, then evict
    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, true)

    private fun evict() {
        var total = cache.values.sumOf { it.text.length }
        val it = cache.entries.iterator()
        while (total > MAX_CHARS && cache.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.value.text != e.value.disk) continue   // never drop unsaved work
            total -= e.value.text.length
            it.remove()
        }
    }

    /** What the editor should show: the buffer if it exists, else what was read. */
    @Synchronized
    fun open(path: String, read: () -> String): String {
        cache[path]?.let { return it.text }
        val body = read()
        cache[path] = Entry(body, body)
        while (cache.size > MAX_FILES) {
            val eldest = cache.entries.first()
            if (eldest.value.text != eldest.value.disk) break
            cache.remove(eldest.key)
        }
        evict()
        return body
    }

    /** The editor's live buffer. Cheap: no disk, no shell. */
    @Synchronized
    fun put(path: String, text: String) {
        val e = cache[path] ?: Entry(text, text)
        e.text = text
        cache[path] = e
    }

    @Synchronized
    fun dirty(path: String): Boolean = cache[path]?.let { it.text != it.disk } ?: false

    @Synchronized
    fun diskText(path: String): String? = cache[path]?.disk

    /** Write back only when the buffer really differs from disk. */
    @Synchronized
    fun flush(path: String, write: (String) -> Boolean): Boolean {
        val e = cache[path] ?: return false
        if (e.text == e.disk) return true
        if (!write(e.text)) return false
        e.disk = e.text
        return true
    }

    @Synchronized
    fun forget(path: String) {
        cache.remove(path)
    }

    @Synchronized
    fun stats(): String = cache.entries.joinToString(", ") { (k, v) ->
        k.substringAfterLast('/') + ":" + v.text.length + (if (v.text != v.disk) "*" else "")
    }
}
