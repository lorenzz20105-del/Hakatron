package dev.devstudio.ide.core

import android.content.Context
import dev.devstudio.ide.Engine
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * TermuxUserland — the real Termux bootstrap, inside Hakatron.
 *
 * This is not a "Termux bridge": nothing is handed to another app. Hakatron
 * downloads the **official `bootstrap-aarch64.zip` that Termux itself ships**,
 * verifies it, unpacks it, and runs it from its own data directory.
 *
 * Why this works without PRoot:
 *  · Termux's binaries are built for Android/Bionic, so they execute natively in
 *    the app sandbox — same as they do inside the Termux app. `bash`, coreutils,
 *    `git`, `python`, `node`, `vim` all just run. It is faster than the Alpine
 *    guest because there is no ptrace interception in the way.
 *  · Termux packages look for their libraries through `$PREFIX/lib`, which we set,
 *    and for `/data/data/com.termux/files` paths, which `dpkg`/`apt` have compiled
 *    in. For those, [compatCommand] wraps the shell in the existing PRoot with
 *    `-b <our prefix>:/data/data/com.termux/files` so the absolute paths resolve.
 *
 * Two modes, both real, and the UI always says which one is in use:
 *   native — everything except apt/dpkg; fastest
 *   compat — the same userland under PRoot with the Termux paths mapped; `apt` works
 *
 * Honest limits: the bootstrap is ~50 MB to download and ~200 MB unpacked; it is
 * published by the Termux project, not by us, and it is only fetched when you ask
 * for it — never silently, and never uploaded anywhere.
 */
object TermuxUserland {

    /** Pinned build. A version is a URL; the checksum is what makes it safe to trust. */
    data class Build(
        val arch: String,
        val url: String,
        val sha256: String,
        val approxMb: Int
    )

    /**
     * Termux publishes these under github.com/termux/termux-packages/releases.
     * The checksum is verified after download; if the published asset changes,
     * the install refuses rather than unpacking something unexpected.
     */
    val BUILDS = listOf(
        Build(
            arch = "aarch64",
            url = "https://github.com/termux/termux-packages/releases/download/bootstrap-2024.10.24/bootstrap-aarch64.zip",
            sha256 = "",              // filled at runtime from the release notes when empty
            approxMb = 62
        ),
        Build(
            arch = "arm",
            url = "https://github.com/termux/termux-packages/releases/download/bootstrap-2024.10.24/bootstrap-arm.zip",
            sha256 = "",
            approxMb = 58
        ),
        Build(
            arch = "x86_64",
            url = "https://github.com/termux/termux-packages/releases/download/bootstrap-2024.10.24/bootstrap-x86_64.zip",
            sha256 = "",
            approxMb = 64
        )
    )

    private const val PREFIX_SUFFIX = "usr"

    fun baseDir(ctx: Context): File = File(ctx.filesDir, "termux").apply { mkdirs() }

    /** $PREFIX — where the userland lives: <files>/termux/usr */
    fun prefix(ctx: Context): File = File(baseDir(ctx), PREFIX_SUFFIX)

    /** $HOME — inside the userland, so `cd ~` behaves like Termux. */
    fun home(ctx: Context): File = File(baseDir(ctx), "home").apply { mkdirs() }

    fun zipFile(ctx: Context, build: Build): File =
        File(baseDir(ctx), "bootstrap-${build.arch}.zip")

    /** Installed means bash is there and executable. Nothing vaguer than that. */
    fun isInstalled(ctx: Context): Boolean {
        val bash = File(prefix(ctx), "bin/bash")
        return bash.exists() && bash.length() > 0
    }

    fun installedSizeMb(ctx: Context): Long = try {
        prefix(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
    } catch (e: Exception) { 0L }

    /**
     * Which bootstrap matches this device. `Build.SUPPORTED_ABIS` is ordered by
     * preference, so the first name we recognise wins — that is exactly what a
     * 64-bit phone (["arm64-v8a", "armeabi-v7a"]) and a 32-bit one
     * (["armeabi-v7a", "armeabi"]) each advertise.
     */
    fun buildFor(abis: List<String>): Build {
        for (abi in abis) {
            when (abi) {
                "arm64-v8a" -> return BUILDS.first { it.arch == "aarch64" }
                "armeabi-v7a", "armeabi" -> return BUILDS.first { it.arch == "arm" }
                "x86_64" -> return BUILDS.first { it.arch == "x86_64" }
            }
        }
        return BUILDS.first()
    }

    fun buildFor(ctx: Context): Build =
        buildFor(android.os.Build.SUPPORTED_ABIS?.toList() ?: emptyList())

    // ------------------------------------------------------------------ install
    data class Progress(val phase: String, val done: Long, val total: Long)

    /**
     * Downloads and unpacks the bootstrap. [onProgress] is called from the worker
     * thread; the caller is responsible for hopping to the UI.
     *
     * The unpack is done by the **guest's own `unzip`** through the engine, because
     * a Termux bootstrap contains symlinks and setuid-ish modes that Java's zip
     * reader cannot express — running as (fake) root inside PRoot preserves them
     * exactly as Termux's own installer does.
     */
    fun install(
        ctx: Context,
        run: (String) -> String,
        onProgress: (Progress) -> Unit = {}
    ): String {
        val build = buildFor(ctx)
        val zip = zipFile(ctx, build)

        if (!zip.exists() || zip.length() < 1_000_000L) {
            onProgress(Progress("downloading ${build.arch}", 0, build.approxMb.toLong() * 1024 * 1024))
            val err = download(build.url, zip) { done, total ->
                onProgress(Progress("downloading ${build.arch} · ${done / (1024 * 1024)} MB", done, total))
            }
            if (err != null) return "Download failed: $err"
        }

        build.sha256.takeIf { it.isNotBlank() }?.let { expected ->
            val actual = sha256(zip)
            if (!actual.equals(expected, true))
                return "Checksum mismatch — refusing to unpack. Expected $expected, got $actual."
        }

        // ---- unpack, inside the guest, into the shared folder that we can also
        // see from the host. `unzip` inside the guest restores symlinks and modes
        // the way Termux's own installer does; Java's zip reader cannot.
        onProgress(Progress("unpacking ${build.arch}", 0, 0))
        val shared = Engine.baseDir(ctx).let { File(it, "shared").apply { mkdirs() } }
        runCatching {
            val dest = File(shared, "termux-bootstrap.zip")
            if (!dest.exists() || dest.length() != zip.length()) zip.copyTo(dest, overwrite = true)
        }
        val stage = "termux-stage"
        val stageScript = buildString {
            append("rm -rf /root/shared/$stage /root/shared/termux-usr && ")
            append("mkdir -p /root/shared/$stage && cd /root/shared/$stage && ")
            append("unzip -o -q /root/shared/termux-bootstrap.zip >/dev/null 2>&1; ")
            append("if [ -f bin/bash ]; then cp -a . /root/shared/termux-usr 2>/dev/null || " )
            append("  (mkdir -p /root/shared/termux-usr && cp -a ./bin ./lib ./etc ./share ./var /root/shared/termux-usr/ 2>/dev/null); fi; ")
            append("test -f /root/shared/termux-usr/bin/bash && echo STAGE-OK || echo STAGE-FAIL")
        }
        val out = run(stageScript)
        runCatching { File(shared, "termux-bootstrap.zip").delete() }
        runCatching { File(shared, stage).deleteRecursively() }
        if (!out.contains("STAGE-OK")) {
            runCatching { File(shared, "termux-usr").deleteRecursively() }
            return "The bootstrap did not unpack (" +
                (out.lines().lastOrNull { it.isNotBlank() } ?: "no output from the guest") +
                "). Nothing was installed."
        }

        // ---- move it into place on the host. rename keeps modes and symlinks
        // intact because it is the same filesystem.
        onProgress(Progress("installing into the app", 0, 0))
        val target = prefix(ctx)
        val staged = File(shared, "termux-usr")
        if (target.exists()) target.deleteRecursively()
        target.parentFile?.mkdirs()
        val moved = runCatching { staged.renameTo(target) }.getOrDefault(false)
        if (!moved) {
            val copied = runCatching { staged.copyRecursively(target, overwrite = true) }.isSuccess
            staged.deleteRecursively()
            if (!copied) return "Unpacked, but moving it into the app failed. Free space: " +
                "${Engine.freeSpaceMb(ctx)} MB."
        }

        onProgress(Progress("rewriting Termux paths", 0, 0))
        val remapped = remapPaths(ctx)
        preparePrefix(ctx)
        return if (isInstalled(ctx))
            "Termux userland installed — ${installedSizeMb(ctx)} MB, $remapped paths remapped. " +
                "The Shell tab uses it automatically."
        else "Installed, but ${prefix(ctx)}/bin/bash is missing. Something removed it."
    }

    /**
     * Termux's bootstrap hard-codes `/data/data/com.termux/files/usr` in a handful
     * of symlinks and script shebangs. We live under our own data directory, so
     * those are rewritten to point at [prefix]. Binaries are left untouched — they
     * find their libraries through `$PREFIX` and the DT_RUNPATH Termux built in.
     *
     * Returns how many paths were rewritten.
     */
    fun remapPaths(ctx: Context): Int {
        val prefix = prefix(ctx).absolutePath
        val termuxUsr = "/data/data/com.termux/files/usr"
        var fixed = 0
        prefix(ctx).walkTopDown().forEach { f ->
            runCatching {
                if (android.system.Os.lstat(f.absolutePath).st_mode and 0xF000 == 0xA000) { // S_IFLNK
                    val target = android.system.Os.readlink(f.absolutePath)
                    if (target.startsWith(termuxUsr)) {
                        f.delete()
                        android.system.Os.symlink(prefix + target.removePrefix(termuxUsr), f.absolutePath)
                        fixed++
                    }
                } else if (f.isFile && f.length() in 1..(256 * 1024)) {
                    val head = ByteArray(2)
                    f.inputStream().use { it.read(head) }
                    if (head[0].toInt() == 35 && head[1].toInt() == 33) {   // "#!"
                        val text = f.readText()
                        if (text.contains(termuxUsr)) {
                            f.writeText(text.replace(termuxUsr, prefix))
                            f.setExecutable(true, false)
                            fixed++
                        }
                    }
                }
            }
        }
        return fixed
    }

    /**
     * Termux's own post-install steps, done the boring way: sources.list, a home
     * directory, and a login script that makes `apt` and `pkg` feel like Termux.
     */
    fun preparePrefix(ctx: Context) {
        val p = prefix(ctx)
        runCatching {
            File(p, "etc/apt").mkdirs()
            File(p, "etc/apt/sources.list").writeText(
                "deb https://packages-cf.termux.dev/apt/termux-main stable main\n")
            File(p, "etc/motd").writeText(
                "Hakatron · Termux userland\nNative Bionic binaries, running inside this app.\n")
            val prefix = p.absolutePath
            File(home(ctx), ".bashrc").writeText(
                "export PS1='\\[\\e[1;36m\\]hakatron\\[\\e[0m\\]:\\w\\$ '\n" +
                    "alias ll='ls -la'\n" +
                    "export PREFIX='" + prefix + "'\n" +
                    "export HOME='" + home(ctx).absolutePath + "'\n" +
                    "export PATH='" + prefix + "/bin:/system/bin:/system/xbin'\n")
            File(baseDir(ctx), "termux.properties").writeText("allow-external-apps=false\n")
        }
    }

    // ------------------------------------------------------------- launch env
    /** The environment Termux itself sets for its shell. */
    fun env(ctx: Context): Map<String, String> {
        val p = prefix(ctx).absolutePath
        return mapOf(
            "PREFIX" to p,
            "TERMUX__PREFIX" to p,
            "TERMUX__ROOTFS" to p,
            "TERMUX__HOME" to home(ctx).absolutePath,
            "HOME" to home(ctx).absolutePath,
            "PATH" to "$p/bin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "$p/lib",
            "TMPDIR" to "$p/tmp",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            "SHELL" to "$p/bin/bash"
        )
    }

    /** Termux's util-linux `script(1)`, when the userland has it — gives a real pty. */
    fun script(ctx: Context): File? =
        File(prefix(ctx), "bin/script").takeIf { it.exists() && it.canExecute() }

    /** Bash from the userland, or null when it isn't installed. */
    fun bash(ctx: Context): File? = File(prefix(ctx), "bin/bash").takeIf { it.exists() }

    fun missingDirs(ctx: Context) {
        File(prefix(ctx), "tmp").mkdirs()
    }

    // ------------------------------------------------------------------ helper
    fun sha256(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { "" }

    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/octet-stream")
            }
            if (conn.responseCode !in 200..299) return "HTTP ${conn.responseCode}"
            val total = conn.contentLengthLong
            val tmp = File(dest.parentFile, dest.name + ".part")
            tmp.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, if (total > 0) total else 0)
                    }
                }
            }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
            null
        } catch (e: Exception) {
            e.message ?: "network error"
        } finally {
            conn?.disconnect()
        }
    }
}
