package dev.devstudio.ide.core

import android.content.Context
import dev.devstudio.ide.Engine
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * ShellSession — one live, interactive shell inside the built-in Alpine guest.
 *
 * This is the difference between "a command runner with a text box" and a terminal:
 *
 *  · **A real pty when the guest can give us one.** `script(1)` allocates a
 *    pseudo-terminal with `openpty` and runs the shell attached to it, so job
 *    control, `isatty()`, `stty`, prompts, `vi`, `top` and `less` all behave the
 *    way they do in Termux. Harkatron bundles `script` (67 KB, musl-only) into the
 *    Alpine rootfs, so this works with no network and no Termux app installed.
 *    If the binary is missing the session falls back to piped stdio and says so
 *    in [mode] instead of pretending.
 *  · **Raw bytes, not lines.** Output is fed straight into [VtScreen]; nothing is
 *    stripped, so colours and cursor addressing survive.
 *  · **One process for the whole session**, so `cd`, `export`, aliases and an
 *    `apk add` all stay in effect — no daemon, no re-entry per command.
 */
class ShellSession(
    private val ctx: Context,
    val cols: Int = 80,
    val rows: Int = 24
) {
    val screen = VtScreen(cols, rows, scrollbackMax = 3000)

    /** "pty" when a real pseudo-terminal was allocated, "pipe" otherwise. */
    @Volatile var mode: String = "starting"; private set

    /** Which userland this session is in: "termux" (real bootstrap) or "alpine". */
    @Volatile var userland: String = "alpine"; private set

    /** bumped on every chunk of output so the UI can recompose cheaply */
    val version = AtomicInteger(0)

    @Volatile var running = false; private set
    @Volatile var exitCode: Int? = null; private set

    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var reader: Thread? = null
    private val feed = Any()

    /** Starts the session; [onChange] fires from a background thread on new output. */
    fun start(onChange: () -> Unit, onExit: (Int?) -> Unit = {}) {
        if (running) return
        if (!Engine.isReady(ctx)) { mode = "no-engine"; return }

        // A real Termux userland wins when it is installed: native Bionic binaries,
        // no ptrace overhead. Otherwise the bundled Alpine guest, which always works.
        val p = Engine.termuxSession(ctx) ?: Engine.sessionProcess(ctx, cols, rows, preferPty = true)
        if (p == null) { mode = "failed"; return }
        userland = Engine.lastSessionUserland
        proc = p
        stdin = p.outputStream
        running = true
        mode = Engine.lastSessionMode

        reader = Thread({
            val input: InputStream = p.inputStream
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    synchronized(feed) {
                        screen.parse(buf, n)
                        version.incrementAndGet()
                    }
                    onChange()
                }
            } catch (_: Exception) {
            } finally {
                running = false
                exitCode = runCatching { p.exitValue() }.getOrNull()
                version.incrementAndGet()
                onChange()
                onExit(exitCode)
            }
        }, "hakatron-term-reader").apply { isDaemon = true; start() }

        // the pty reports its own size; make sure the guest agrees with our grid
        write("\u001b[2J\u001b[H")
        if (mode == "pty") write("stty rows $rows cols $cols 2>/dev/null; export TERM=xterm-256color; clear\r")
    }

    /** Sends raw text to the shell (keystrokes, pasted text, escape sequences). */
    fun write(text: String) {
        val s = stdin ?: return
        try {
            synchronized(feed) { s.write(text.toByteArray(Charsets.UTF_8)); s.flush() }
        } catch (_: Exception) {
        }
    }

    /** Sends one typed character, translating it for the terminal if needed. */
    fun key(ch: Char, ctrl: Boolean = false) {
        if (ctrl) {
            val code = ch.uppercaseChar().code
            write(((code - 64).coerceIn(0, 31)).toChar().toString())
        } else write(ch.toString())
    }

    fun enter() = write("\r")
    fun backspace() = write("\u007F")
    fun tab() = write("\t")
    fun esc() = write("\u001b")
    fun arrow(up: Boolean) = write(if (up) "\u001b[A" else "\u001b[B")
    fun arrowLR(right: Boolean) = write(if (right) "\u001b[C" else "\u001b[D")
    fun interrupt() = write("\u0003")
    fun eof() = write("\u0004")

    /** Asks the shell where it is, so the UI can show a real prompt path. */
    fun askCwd() = write("pwd\r")

    fun close() {
        running = false
        runCatching { stdin?.close() }
        runCatching { proc?.destroy() }
        reader?.interrupt()
    }
}
