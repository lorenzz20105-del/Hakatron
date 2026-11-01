package dev.devstudio.ide

import dev.devstudio.ide.core.TermuxUserland
import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * InternalEngine — a REAL Linux running entirely inside LinuxDesk.
 *
 * No Termux required. How it works:
 *  - The APK ships PRoot (Debian arm64 build) plus its glibc loader and
 *    libraries in jniLibs/arm64-v8a as lib*.so — the native library
 *    directory is the ONLY place Android (targetSdk 34) allows an app to
 *    execute binaries from, so PRoot is executed through its own loader:
 *        libldlinux.so --library-path <nativeLibDir> libproot.so …
 *  - On first setup, the bundled Alpine minirootfs (asset, ~3.8 MB) is
 *    extracted into the app sandbox and becomes the guest system.
 *  - Commands run as fake-root inside the guest via PRoot. apk inside the
 *    guest installs real Alpine packages (xfce4, tigervnc, …) from the
 *    Alpine mirrors — the same way proot-distro guests work in Termux,
 *    except everything lives inside this app.
 *
 * Honest limits: PRoot is ptrace-based (slower than a VM), the guest is
 * Alpine (musl) rather than Debian, and a handful of guest binaries that
 * need real kernel features may misbehave. The Termux engine remains the
 * heavy-duty path; this one needs nothing outside the app.
 */
object Engine {

    fun baseDir(ctx: Context): File = File(ctx.filesDir, "internal-linux").apply { mkdirs() }
    fun rootfs(ctx: Context): File = File(baseDir(ctx), "rootfs")
    fun isReady(ctx: Context): Boolean {
        // NOTE: guest /bin/sh is an ABSOLUTE symlink (-> /bin/busybox). On the
        // host that target does not exist, so File.exists() lies and reports
        // a perfectly good rootfs as broken. Check busybox + the link itself.
        val root = rootfs(ctx)
        if (File(root, "bin/busybox").exists()) return true
        val sh = File(root, "bin/sh")
        return try { java.nio.file.Files.isSymbolicLink(sh.toPath()) } catch (ex: Exception) { sh.exists() }
    }
    fun freeSpaceMb(ctx: Context): Long = baseDir(ctx).usableSpace / (1024 * 1024)

    /** Size of the guest on disk, in MB — honest number, not a guess. */
    fun guestSizeMb(ctx: Context): Long = try {
        rootfs(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
    } catch (ex: Exception) { 0L }

    /** Which compilers actually exist in the guest right now. */
    fun tools(ctx: Context): List<Pair<String, Boolean>> {
        if (!isReady(ctx)) return emptyList()
        val probe = exec(ctx,
            "for t in gcc make python3 node javac rustc git; do " +
                "if command -v \$t >/dev/null 2>&1; then echo \"\$t=1\"; else echo \"\$t=0\"; fi; done",
            60_000)
        val found = probe.lines().mapNotNull { line ->
            val m = Regex("^\\s*([a-z0-9]+)=([01])\\s*$").find(line) ?: return@mapNotNull null
            m.groupValues[1] to (m.groupValues[2] == "1")
        }
        return listOf("gcc", "make", "python3", "node", "javac", "rustc", "git").map { name ->
            name to (found.firstOrNull { it.first == name }?.second ?: false)
        }
    }

    /** Installs guest packages and returns the tail of apk's own output. */
    fun install(ctx: Context, pkg: String, log: (String) -> Unit = {}): String {
        if (!isReady(ctx)) return "Set up the engine first."
        log("apk add $pkg …")
        val out = exec(ctx, "apk update >/dev/null 2>&1; apk add --no-cache $pkg 2>&1 | tail -25", 900_000)
        return out
    }

    // ---------------------------------------------------------------- setup
    /** Extracts the bundled Alpine rootfs. Returns a human-readable result. */
    fun setup(ctx: Context, log: (String) -> Unit): String {
        val root = rootfs(ctx)
        if (isReady(ctx)) return "Built-in Linux already set up (${root.absolutePath})"
        log("Extracting Alpine Linux rootfs inside LinuxDesk…")
        root.mkdirs()
        File(baseDir(ctx), "tmp").mkdirs()
        ctx.assets.open("alpine-rootfs.targz").use { raw ->
            GZIPInputStream(raw).use { gz -> extractTar(gz, root, log) }
        }
        // Guest networking, identity, apk mirrors and a host-shared folder
        runCatching {
            File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            File(root, "etc/hostname").writeText("linuxdesk\n")
            val repos = File(root, "etc/apk/repositories")
            if (!repos.exists() || repos.readText().isBlank()) {
                repos.parentFile?.mkdirs()
                repos.writeText("https://dl-cdn.alpinelinux.org/alpine/v3.20/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.20/community\n")
            }
            File(root, "root/.profile").appendText("\nexport PS1='linuxdesk:\\w\\$ '\n")
            File(root, "root/shared").mkdirs()
        }
        return if (isReady(ctx)) "Alpine Linux is ready. Try: linux cat /etc/os-release"
        else "Extraction finished but /bin/sh is missing — storage may be full (${freeSpaceMb(ctx)} MB free)"
    }

    /** Minimal ustar reader: files, dirs, symlinks, hardlinks, modes. */
    private fun extractTar(input: GZIPInputStream, dest: File, log: (String) -> Unit) {
        val buf = ByteArray(512)
        var count = 0
        while (true) {
            val read = fill(input, buf); if (read < 512) break
            if (buf.all { it == 0.toByte() }) break
            val name = cstr(buf, 0, 100)
            val prefix = cstr(buf, 345, 155)
            val full = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val mode = cstr(buf, 100, 8).trim().toIntOrNull(8) ?: 0
            val size = cstr(buf, 124, 12).trim().toLongOrNull(8) ?: 0L
            val type = buf[156].toInt().toChar()
            val link = cstr(buf, 157, 100)
            val target = File(dest, full.trimStart('/'))
            val isFile = type == '0' || type.code == 0
            when {
                type == '5' -> target.mkdirs()
                type == '2' || type == '1' -> { // symlink / hardlink
                    runCatching {
                        target.parentFile?.mkdirs()
                        if (target.exists()) target.delete()
                        Os.symlink(link, target.absolutePath)
                    }
                }
                isFile -> {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        var left = size
                        val chunk = ByteArray(8192)
                        while (left > 0) {
                            val n = input.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
                            if (n < 0) break
                            out.write(chunk, 0, n); left -= n
                        }
                    }
                    runCatching { Os.chmod(target.absolutePath, mode and 0xFFF) }
                    count++
                }
                else -> skipBytes(input, size) // e.g. GNU longname payloads
            }
            // Data blocks: files already consumed theirs above; everything
            // else with a size field (longnames) was skipped. Just padding.
            skipBytes(input, (512 - (size % 512)) % 512)
        }
        log("Rootfs extracted ($count files)")
    }

    private fun fill(input: GZIPInputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun skipBytes(input: GZIPInputStream, count: Long) {
        var left = count
        val chunk = ByteArray(8192)
        while (left > 0) {
            val n = input.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }

    private fun cstr(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.UTF_8).trim()
    }

    // ----------------------------------------------------------------- exec
    private fun prootCommand(ctx: Context, guestCmd: String): List<String> {
        val lib = ctx.applicationInfo.nativeLibraryDir
        val root = rootfs(ctx).absolutePath
        File(baseDir(ctx), "tmp").mkdirs()
        return listOf(
            "$lib/libldlinux.so", "--library-path", lib, "$lib/libproot.so"
        ) + currentRung().args + listOf(
            "-0", "-r", root,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/pts",
            "-b", File(baseDir(ctx), "shared").apply { mkdirs() }.absolutePath + ":/root/shared",
            "-w", "/root",
            "/bin/sh", "-c", guestCmd
        )
    }

    private fun prootEnv(ctx: Context): Map<String, String> = mapOf(
        // Android's own seccomp filter kills PRoot's seccomp acceleration
        // with SIGSYS (exit 159 on several OEM ROMs). Ptrace-only mode is
        // slightly slower and works everywhere. This is THE fix for 159.
        "PROOT_NO_SECCOMP" to "1",
        "PROOT_TMPDIR" to File(baseDir(ctx), "tmp").absolutePath,
        "HOME" to "/root",
        "TERM" to "xterm-256color",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    ) + currentRung().env + hostEnv

    // ---------------------------------------------------- launch compatibility
    /**
     * Devices differ in how the kernel treats PRoot's syscall interception. Instead
     * of a single hard-coded incantation, the launch is a small ladder: if a rung
     * dies with SIGSYS (exit 159 — Android's seccomp filter rejecting `process_vm_*`
     * or `ptrace` acceleration), the next rung is tried and **remembered**, so the
     * device only pays for the retry once.
     */
    private data class Rung(val name: String, val env: Map<String, String>, val args: List<String>)

    private val ladder = listOf(
        Rung("default", emptyMap(), emptyList()),
        Rung("no-seccomp", mapOf("PROOT_NO_SECCOMP" to "1"), emptyList()),
        Rung("kill-on-exit", mapOf("PROOT_NO_SECCOMP" to "1"), listOf("--kill-on-exit")),
        Rung("link2symlink", mapOf("PROOT_NO_SECCOMP" to "1"), listOf("--kill-on-exit", "--link2symlink")),
        Rung("no-kernel-release", mapOf("PROOT_NO_SECCOMP" to "1"),
            listOf("--kill-on-exit", "--link2symlink", "-v", "0"))
    )

    @Volatile private var rungIndex = 0
    @Volatile var launchMode: String = "default"; private set

    /** Extra environment that must be set on the HOST process, not inside the guest. */
    private val hostEnv = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Some "fixes" are host-side: `export PROOT_NO_SECCOMP=1` inside the guest does
     * nothing at all, because the variable has to be in PRoot's own environment.
     * The planner's fix table asked for the guest command; this routes it to where
     * it actually works.
     */
    fun applyHostFix(cmd: String): Boolean {
        var applied = false
        Regex("(PROOT_[A-Z_]+|ANDROID_[A-Z_]+)=([^\\s;]+)").findAll(cmd).forEach { m ->
            hostEnv[m.groupValues[1]] = m.groupValues[2]
            applied = true
        }
        if (applied) rungIndex = maxOf(rungIndex, 1)
        return applied
    }

    private fun currentRung(): Rung = ladder[rungIndex.coerceIn(0, ladder.size - 1)]

    /** Where the stack died, in words a user can act on. */
    fun diagnose(ctx: Context): String {
        if (!isReady(ctx)) return "No guest yet — set the engine up first."
        val lib = File(ctx.applicationInfo.nativeLibraryDir)
        val missing = listOf("libldlinux.so", "libproot.so", "libglibc.so", "libtalloc.so")
            .filterNot { File(lib, it).exists() }
        if (missing.isNotEmpty())
            return "The APK is missing ${missing.joinToString(", ")} for this device's CPU (" +
                "native dir ${lib.absolutePath}). That is a build problem, not a project problem."
        val probe = exec(ctx, "echo PROBE-OK; uname -m", 30_000)
        return "launch mode: ${currentRung().name}\n$probe"
    }

    /** Runs a command inside the built-in Linux and returns its output. */
    fun exec(ctx: Context, guestCmd: String, timeoutMs: Long = 120_000): String {
        if (!isReady(ctx)) return "Built-in Linux is not set up yet — run: linux-setup"
        var out = execOnce(ctx, guestCmd, timeoutMs)
        // 159 = 128 + SIGSYS: the kernel refused PRoot's interception. Climb the ladder.
        while (Regex("\\[exit 159").containsMatchIn(out) && rungIndex < ladder.size - 1) {
            rungIndex++
            launchMode = currentRung().name
            val retry = execOnce(ctx, guestCmd, timeoutMs)
            out = if (Regex("\\[exit 159").containsMatchIn(retry))
                retry + "\n[retried in ${currentRung().name} mode too]"
            else retry + "\n[built-in Linux recovered in \u201c${currentRung().name}\u201d mode]"
        }
        if (Regex("\\[exit 159").containsMatchIn(out) && rungIndex == ladder.size - 1) {
            out += "\n[This device's kernel blocks PRoot's syscall interception (SIGSYS). " +
                "Every launch mode was tried. Use the Termux engine, or run the project on a " +
                "device where PRoot is allowed.]"
        }
        return out
    }

    private fun execOnce(ctx: Context, guestCmd: String, timeoutMs: Long): String {
        return try {
            val pb = ProcessBuilder(prootCommand(ctx, guestCmd)).redirectErrorStream(true)
            pb.environment().putAll(prootEnv(ctx))
            val proc = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                proc.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } }
            }
            reader.start()
            val done = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!done) { proc.destroyForcibly(); return out.toString().trimEnd() + "\n[timeout after ${timeoutMs / 1000}s — command killed]" }
            reader.join(2000)
            val text = out.toString().trimEnd()
            (if (text.isBlank()) "(no output)" else text) +
                "\n[exit ${proc.exitValue()} · built-in Linux · ${currentRung().name} · no Termux]"
        } catch (e: Exception) {
            "Built-in Linux error: ${e.message}"
        }
    }

    // ------------------------------------------------------- persistent shell
    /**
     * Starts ONE long-lived guest shell. Keeping the process alive is what makes
     * `cd`, exported variables and `apk add` state survive between commands
     * without any daemon. The reader thread inside the UI drains stdout; PRoot
     * is line-buffered through the pipe, and every command we send is followed
     * by a ";\n" marker so the UI knows the prompt came back.
     */
    fun shell(ctx: Context): Process? {
        if (!isReady(ctx)) return null
        return try {
            val lib = ctx.applicationInfo.nativeLibraryDir
            val root = rootfs(ctx).absolutePath
            File(baseDir(ctx), "tmp").mkdirs()
            val cmd = listOf(
                "$lib/libldlinux.so", "--library-path", lib, "$lib/libproot.so",
                "-0", "-r", root,
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/dev/pts",
                "-b", File(baseDir(ctx), "shared").apply { mkdirs() }.absolutePath + ":/root/shared",
                "-b", "/sdcard", "-w", "/root",
                "/bin/sh", "-i"
            )
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().putAll(prootEnv(ctx))
            val proc = pb.start()
            // Interactive sh without a tty still needs a prompt-ish handshake.
            proc.outputStream.write("export PS1='' ; echo ready\n".toByteArray())
            proc.outputStream.flush()
            proc
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------- interactive session
    /** How the last [sessionProcess] call attached the shell: "pty" or "pipe". */
    @Volatile var lastSessionMode: String = "pipe"; private set

    /**
     * Starts an INTERACTIVE shell for the terminal screen.
     *
     * `script(1)` is bundled inside the Alpine rootfs (67 KB, musl only), so the
     * shell can be given a REAL pseudo-terminal: job control, `isatty`, prompts,
     * `vi`, `top` and `less` behave as they do in Termux — with nothing outside
     * the app. When that binary is missing we fall back to piped stdio and say so.
     */
    /** "termux" when the shell is the real Termux userland, "alpine" for the bundled guest. */
    @Volatile var lastSessionUserland: String = "alpine"; private set

    /** "native" when binaries run directly, "proot" when the Alpine guest runs them. */
    @Volatile var lastSessionRuntime: String = "proot"; private set

    /**
     * Starts a shell in the REAL Termux userland: Termux's own bootstrap, running
     * as Bionic binaries inside this app. Nothing is handed to the Termux app.
     *
     * A real pseudo-terminal is used when the userland has `script(1)` (Termux's
     * util-linux); otherwise stdio is piped and the terminal says so. Both are
     * honest — the UI never claims a pty it does not have.
     */
    fun termuxSession(ctx: Context): Process? {
        val bash = TermuxUserland.bash(ctx) ?: return null
        TermuxUserland.missingDirs(ctx)
        val script = TermuxUserland.script(ctx)
        val argv = if (script != null)
            listOf(script.absolutePath, "-q", "-e", "-f", "-c",
                "\"${bash.absolutePath}\" -l -i", "/dev/null")
        else listOf("sh", "-c", "exec \"${bash.absolutePath}\" -l -i")
        return try {
            val pb = ProcessBuilder(argv)
            pb.directory(TermuxUserland.home(ctx))
            pb.environment().putAll(TermuxUserland.env(ctx))
            pb.redirectErrorStream(true)
            val p = pb.start()
            lastSessionUserland = "termux"
            lastSessionRuntime = "native"
            lastSessionMode = if (script != null) "pty" else "pipe"
            p
        } catch (e: Exception) {
            null
        }
    }

    fun sessionProcess(ctx: Context, cols: Int, rows: Int, preferPty: Boolean = true): Process? {
        if (!isReady(ctx)) return null
        val lib = ctx.applicationInfo.nativeLibraryDir
        val root = rootfs(ctx).absolutePath
        File(baseDir(ctx), "tmp").mkdirs()

        fun base(guestArgv: List<String>): List<String> = listOf(
            "$lib/libldlinux.so", "--library-path", lib, "$lib/libproot.so",
            "-0", "-r", root,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/dev/pts",
            "-b", File(baseDir(ctx), "shared").apply { mkdirs() }.absolutePath + ":/root/shared",
            "-b", "/sdcard", "-w", "/root"
        ) + guestArgv

        val env = HashMap(prootEnv(ctx))
        env["TERM"] = "xterm-256color"
        env["LINES"] = rows.toString()
        env["COLUMNS"] = cols.toString()
        // an interactive ash picks PS1 up from the environment: a coloured prompt
        // without having to type `export PS1=…` into the user's session
        env["PS1"] = "\u001b[1;36mhakatron\u001b[0m:\\w\\$ "
        env["ENV"] = "/root/.hakatron_init"

        val pty = preferPty && hasScript(ctx)
        val argv = if (pty) {
            // -q quiet, -e return the child's exit status, -f flush after every write
            base(listOf("/bin/script", "-q", "-e", "-f", "-c", "sh -i", "/dev/null"))
        } else {
            base(listOf("/bin/sh", "-i"))
        }
        return try {
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            lastSessionMode = if (pty) "pty" else "pipe"
            lastSessionUserland = "alpine"
            lastSessionRuntime = "proot"
            // first-run shell conveniences, sourced only if it exists
            runCatching {
                val init = File(rootfs(ctx), "root/.hakatron_init")
                if (!init.exists()) init.writeText(
                    "# Hakatron terminal init\n" +
                        "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                        "export PS1='\\033[1;36mhakatron\\033[0m:\\w\\$ '\n" +
                        "alias ll='ls -la'\n" +
                        "echo -e '\\033[2mHakatron terminal — Alpine Linux, PRoot, on-device.'\n" +
                        "echo -e '\\033[2mTry: apk add python3  ·  vi /root/notes.txt  ·  exit\\033[0m'\n"
                )
            }
            p
        } catch (e: Exception) {
            null
        }
    }

    /** Is `script(1)` inside the guest? Probed once and remembered. */
    @Volatile private var scriptSeen: Boolean? = null
    fun hasScript(ctx: Context): Boolean {
        scriptSeen?.let { return it }
        val found = exec(ctx, "command -v script >/dev/null 2>&1 && echo YES || echo NO", 30_000).contains("YES")
        scriptSeen = found
        return found
    }

    /** Sends one command to a live shell and waits (bounded) for its output. */
    fun runIn(shell: Process?, cmd: String, waitMs: Long = 8_000): String {
        if (shell == null || !shell.isAlive) return "shell not running"
        return try {
            val marker = "__DS" + System.nanoTime().toString().takeLast(6) + "__"
            shell.outputStream.write((cmd + "\necho " + marker + "\n").toByteArray())
            shell.outputStream.flush()
            val sb = StringBuilder()
            val start = System.currentTimeMillis()
            var tail = ""
            while (System.currentTimeMillis() - start < waitMs) {
                if (shell.inputStream.available() > 0) {
                    val buf = ByteArray(shell.inputStream.available())
                    val n = shell.inputStream.read(buf)
                    if (n > 0) {
                        tail += String(buf, 0, n)
                        sb.append(String(buf, 0, n))
                        if (tail.contains(marker)) break
                        if (tail.length > 400) tail = tail.takeLast(400)
                    }
                } else Thread.sleep(30)
                if (sb.length > 200_000) break
            }
            sb.toString().substringBefore(marker).trimEnd()
        } catch (e: Exception) {
            "error: " + e.message
        }
    }

    /** One-time desktop install inside the guest (XFCE + TigerVNC). */
    fun desktopInstallCommand(): String =
        "apk update && apk add --no-cache xfce4 xfce4-terminal tigervnc dbus adwaita-icon-theme faenza-icon-theme xorg-server-xvfb 2>/dev/null || apk add --no-cache xfce4 xfce4-terminal tigervnc dbus adwaita-icon-theme; " +
        "mkdir -p /root/.vnc && printf 'linuxdes\\nlinuxdes\\nn\\n' | vncpasswd /root/.vnc/passwd >/dev/null 2>&1; " +
        "printf '#!/bin/sh\\nunset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS\\ndbus-launch --exit-with-session startxfce4 &\\n' > /root/.vnc/xstartup && chmod +x /root/.vnc/xstartup; " +
        "echo DESKTOP-INSTALLED"

    /** Starts TigerVNC (Xvnc :1) in the background; viewer connects to 5901. */
    fun startDesktop(ctx: Context, geometry: String): String {
        if (!isReady(ctx)) return "Built-in Linux is not set up yet — Desktop tab > Built-in Linux > Set up"
        val hasVnc = File(rootfs(ctx), "usr/bin/Xvnc").exists() || File(rootfs(ctx), "usr/bin/vncserver").exists()
        if (!hasVnc)
            return "Desktop packages not installed yet — run the desktop install first (it downloads XFCE + TigerVNC inside the guest)"
        return try {
            val startCmd = "vncserver -kill :1 >/dev/null 2>&1; rm -f /tmp/.X1-lock /root/.vnc/*.log; " +
                "Xvnc :1 -geometry $geometry -depth 24 -rfbport 5901 -PasswordFile /root/.vnc/passwd -xstartup /root/.vnc/xstartup >/root/.vnc/xvnc.log 2>&1 & " +
                "sleep 4; if pgrep Xvnc >/dev/null 2>&1; then echo XVNC-RUNNING; else echo XVNC-DIED; fi; tail -5 /root/.vnc/xvnc.log 2>/dev/null"
            exec(ctx, startCmd, 60_000)
        } catch (e: Exception) { "Desktop start failed: ${e.message}" }
    }

    fun stopDesktop(ctx: Context): String = exec(ctx, "vncserver -kill :1 2>/dev/null; pkill Xvnc 2>/dev/null; echo DESKTOP-STOPPED", 30_000)

    /** End-to-end proof the engine works: loader > PRoot > guest shell. */
    fun selfTest(ctx: Context): String {
        if (!isReady(ctx)) return "Built-in Linux not set up yet (Desktop tab > Built-in Linux > Set up)"
        val lib = File(ctx.applicationInfo.nativeLibraryDir)
        val bins = listOf("libldlinux.so", "libproot.so", "libglibc.so", "libtalloc.so").joinToString(", ") { n ->
            "$n=" + if (File(lib, n).exists()) "ok" else "MISSING"
        }
        val probe = exec(ctx, "echo GUEST-OK; uname -m; head -2 /etc/os-release; echo shared-folder:; ls /root/shared >/dev/null && echo ok", 60_000)
        return "Engine files: $bins\n$probe"
    }
}
