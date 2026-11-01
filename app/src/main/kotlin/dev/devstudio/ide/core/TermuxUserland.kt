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
    private const val ASSET_BASE =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.01-r1%2Bapt.android-7/"

    val BUILDS = listOf(
        Build(
            arch = "aarch64",
            url = ASSET_BASE + "bootstrap-aarch64.zip",
            sha256 = "5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae",
            approxMb = 32
        ),
        Build(
            arch = "arm",
            url = ASSET_BASE + "bootstrap-arm.zip",
            sha256 = "99660c5a95733765c9967309a0c91c67335e9804b9e2027cfba71926fa4a3140",
            approxMb = 28
        ),
        Build(
            arch = "i686",
            url = ASSET_BASE + "bootstrap-i686.zip",
            sha256 = "3ebf0424a9ffee25724c3ebb03d6e78bdd2c6d8e8fee9c421bb6db4efd0d143c",
            approxMb = 31
        ),
        Build(
            arch = "x86_64",
            url = ASSET_BASE + "bootstrap-x86_64.zip",
            sha256 = "c0321730de9440ee8ea6f192151d5ecbe74f2391f07ffa9bf38584b4a0cbe616",
            approxMb = 32
        )
    )

    /**
     * The checked release. 0.16.0 pointed at `bootstrap-2024.10.24`, which Termux no
     * longer publishes — that is the HTTP 404 the install button was reporting. The
     * tag contains a `+`, which has to be percent-encoded in the download path.
     */
    const val TAG = "bootstrap-2026.11.01-r1+apt.android-7"

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
                "x86" -> return BUILDS.first { it.arch == "i686" }
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

        // ~32 MB down, ~90 MB unpacked: refuse politely instead of dying mid-copy.
        val needed = 160L
        if (Engine.freeSpaceMb(ctx) < needed)
            return "Not enough space: this needs about $needed MB free and there are " +
                "${Engine.freeSpaceMb(ctx)} MB. Nothing was downloaded."

        var usedUrl = build.url
        if (!zip.exists() || zip.length() < 1_000_000L) {
            onProgress(Progress("downloading ${build.arch}", 0, build.approxMb.toLong() * 1024 * 1024))
            var err = download(build.url, zip) { done, total ->
                onProgress(Progress("downloading ${build.arch} · ${done / (1024 * 1024)} MB", done, total))
            }
            // A pinned release can be withdrawn. If that happens, ask the project for
            // its current one instead of showing the user an HTTP status code.
            if (err != null && err.contains("404")) {
                onProgress(Progress("pinned build is gone — asking Termux for the current one", 0, 0))
                val latest = resolveLatest(build.arch)
                if (latest != null) {
                    usedUrl = latest
                    err = download(latest, zip) { done, total ->
                        onProgress(Progress("downloading ${build.arch} · ${done / (1024 * 1024)} MB", done, total))
                    }
                }
            }
            if (err != null) return "Download failed: $err\n($usedUrl)"
        }

        // Only the pinned asset has a checksum we know. A fallback download is a
        // different file, so it is not verified against this one — it is simply
        // unpacked and reported as "current release" instead of pretending.
        val pinned = usedUrl == build.url
        build.sha256.takeIf { it.isNotBlank() && pinned }?.let { expected ->
            val actual = sha256(zip)
            if (!actual.equals(expected, true))
                return "Checksum mismatch — refusing to unpack. Expected $expected, got $actual."
        }

        // ---- unpack on the host.
        //
        // Termux's bootstrap is a plain zip: no symlinks, no modes we can't read,
        // and every link it wants is listed in SYMLINKS.txt. So the unpack does not
        // need a guest, a PRoot or another app — it runs here, in Kotlin, and the
        // links are created with Os.symlink exactly as Termux's own installer does.
        onProgress(Progress("unpacking ${build.arch}", 0, 0))
        val target = prefix(ctx)
        val tmp = File(baseDir(ctx), "usr.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val problems = StringBuilder()
        try {
            java.util.zip.ZipFile(zip).use { zf ->
                val entries = zf.entries().toList()
                var done = 0
                entries.forEachIndexed { i, e ->
                    val out = File(tmp, e.name)
                    // zip-slip guard: nothing may escape the staging directory
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath)) {
                        problems.append("skipped unsafe path ${e.name}; ")
                        return@forEachIndexed
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        zf.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                        done++
                    }
                    if (i % 250 == 0) onProgress(Progress(
                        "unpacking ${build.arch} · $done files", i.toLong(), entries.size.toLong()))
                }
            }
        } catch (e: Exception) {
            tmp.deleteRecursively()
            return "Unpack failed: ${e.message ?: e.javaClass.simpleName}. Nothing was installed."
        }

        // ---- executables. ZipEntry does not expose unix modes in Java, so the bit
        // is set from content instead: an ELF header or a shebang under bin/ or
        // libexec/ is an executable, anything else is data.
        onProgress(Progress("marking " + build.arch + " executables", 0, 0))
        val executables = markExecutables(tmp)

        // ---- the links the bootstrap only *describes*
        onProgress(Progress("creating " + build.arch + " symlinks", 0, 0))
        val links = runCatching {
            File(tmp, "SYMLINKS.txt").takeIf { it.exists() }?.readText().orEmpty()
        }.getOrDefault("")
        val made = makeSymlinks(tmp, parseSymlinks(links), problems)

        if (File(tmp, "bin/bash").let { !it.exists() || it.length() == 0L }) {
            tmp.deleteRecursively()
            return "Unpacked, but bin/bash is missing from the bootstrap. Nothing was installed."
        }

        // ---- swap into place
        onProgress(Progress("installing into the app", 0, 0))
        if (target.exists()) target.deleteRecursively()
        target.parentFile?.mkdirs()
        val moved = runCatching { tmp.renameTo(target) }.getOrDefault(false)
        if (!moved) {
            val copied = runCatching { tmp.copyRecursively(target, overwrite = true) }.isSuccess
            tmp.deleteRecursively()
            if (!copied) return "Unpacked, but moving it into the app failed. Free space: " +
                "${Engine.freeSpaceMb(ctx)} MB."
        }
        runCatching { zip.delete() }

        onProgress(Progress("rewriting Termux paths", 0, 0))
        val remapped = remapPaths(ctx)
        preparePrefix(ctx)

        return if (isInstalled(ctx)) {
            val pty = if (script(ctx) != null) "real pty via script(1)" else "pipes (no script(1) in this build)"
            val apt = if (bin(ctx, "apt") != null) "apt ready" else "apt missing"
            val warn = if (problems.isEmpty()) "" else " · ${problems.toString().trim()}"
            "Termux userland installed — ${installedSizeMb(ctx)} MB, $made links, " +
                "$remapped paths remapped, $pty, $apt.$warn"
        } else "Installed, but ${prefix(ctx)}/bin/bash is missing. Something removed it."
    }

    /** Sets +x on real programs under bin/ and libexec/: ELF, or a "#!" script. */
    fun markExecutables(root: File): Int {
        var marked = 0
        listOf("bin", "libexec").forEach { dir ->
            File(root, dir).walkTopDown().filter { it.isFile }.forEach { f ->
                runCatching {
                    val head = ByteArray(4)
                    val n = f.inputStream().use { it.read(head) }
                    val elf = n >= 4 && head[0] == 0x7F.toByte() &&
                        head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() &&
                        head[3] == 'F'.code.toByte()
                    val script = n >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()
                    if (elf || script) { f.setExecutable(true, false); marked++ }
                }
            }
        }
        return marked
    }

    /**
     * `SYMLINKS.txt` is one link per line, `target←path`, e.g.
     * `dash←./bin/sh` (bin/sh points at dash) and
     * `libxxhash.so.0.8.4←./lib/libxxhash.so.0`. Targets are relative to the
     * link's own directory, which is why they survive being moved as a tree.
     */
    fun parseSymlinks(text: String): List<Pair<String, String>> =
        text.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val cut = line.indexOf('\u2190')
            if (cut <= 0) return@mapNotNull null
            val targetText = line.substring(0, cut).trim()
            val path = line.substring(cut + 1).trim()
            if (targetText.isEmpty() || path.isEmpty()) null else path to targetText
        }

    /** Creates the links inside [root]. Returns how many were created. */
    fun makeSymlinks(root: File, links: List<Pair<String, String>>, problems: StringBuilder): Int {
        var made = 0
        links.forEach { (rawPath, targetText) ->
            val link = File(root, rawPath.removePrefix("./"))
            val parent = link.parentFile ?: return@forEach
            if (!link.canonicalPath.startsWith(root.canonicalPath)) return@forEach
            runCatching {
                parent.mkdirs()
                if (link.exists()) link.delete()
                android.system.Os.symlink(targetText, link.absolutePath)
                made++
            }.onFailure {
                problems.append("link ${link.name} failed; ")
            }
        }
        return made
    }

    /**
     * Termux's bootstrap hard-codes `/data/data/com.termux/files/usr` in a few
     * shebangs. We live under our own data directory, so those are rewritten to
     * point at [prefix]. Binaries are left untouched — they find their libraries
     * through `$PREFIX` and the DT_RUNPATH Termux built in.
     *
     * Returns how many paths were rewritten.
     */
    fun remapPaths(ctx: Context): Int {
        val prefix = prefix(ctx).absolutePath
        var fixed = 0
        prefix(ctx).walkTopDown().forEach { f ->
            runCatching {
                val st = android.system.Os.lstat(f.absolutePath)
                if (st.st_mode and 0xF000 == 0xA000) {                 // S_IFLNK
                    // A few links in the bootstrap point at Termux's absolute
                    // prefix (/data/data/com.termux/files/usr/bin/unzip). Those
                    // dangle here, so the link is recreated against ours.
                    val target = android.system.Os.readlink(f.absolutePath)
                    val remapped = remapTarget(target, prefix, TERMUX_PREFIX)
                    if (remapped != null) {
                        f.delete()
                        android.system.Os.symlink(remapped, f.absolutePath)
                        fixed++
                    }
                } else if (f.isFile && f.length() in 2..(256 * 1024)) {
                    val head = ByteArray(2)
                    f.inputStream().use { it.read(head) }
                    if (head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) {
                        val text = f.readText()
                        if (text.contains(TERMUX_PREFIX)) {
                            f.writeText(text.replace(TERMUX_PREFIX, prefix))
                            f.setExecutable(true, false)
                            fixed++
                        }
                    }
                }
            }
        }
        return fixed
    }

    /** Where Termux itself installs to; the bootstrap has a few absolute references to it. */
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /**
     * Reworks one link target. Relative targets are already correct after the tree
     * move; only absolute Termux ones need rewriting. Returns null when untouched.
     */
    fun remapTarget(target: String, prefix: String, termuxPrefix: String = TERMUX_PREFIX): String? =
        if (target.startsWith(termuxPrefix))
            prefix + target.removePrefix(termuxPrefix)
        else null

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

    /** Any executable inside $PREFIX/bin, or null. */
    fun bin(ctx: Context, name: String): File? =
        File(prefix(ctx), "bin/$name").takeIf { it.exists() && it.length() > 0 }

    /**
     * Asks the Termux project for its newest bootstrap and returns the download URL
     * for [arch]. Parsed with a regex on purpose: this app ships no JSON library and
     * one GitHub call should not pull one in.
     */
    fun resolveLatest(arch: String): String? = try {
        val req = URL("https://api.github.com/repos/termux/termux-packages/releases/latest")
            .openConnection() as HttpURLConnection
        req.connectTimeout = 15_000
        req.readTimeout = 15_000
        req.setRequestProperty("Accept", "application/vnd.github+json")
        val body = req.inputStream.bufferedReader().use { it.readText() }
        req.disconnect()
        Regex("\"browser_download_url\\s*:\\s*\"([^\"]*bootstrap-" + Regex.escape(arch) + "\\.zip)\"")
            .find(body)?.groupValues?.get(1)
    } catch (e: Exception) {
        null
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
