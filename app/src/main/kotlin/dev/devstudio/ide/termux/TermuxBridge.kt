package dev.devstudio.ide.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/** Verdict on the installed Termux. Only genuine F-Droid / GitHub builds are accepted —
 *  Android requires the same signing key across Termux and anything using RUN_COMMAND. */
data class TermuxVerdict(
    val installed: Boolean,
    val genuine: Boolean,
    val source: String,
    val version: String,
    val certSha256: String,
    val problems: List<String>,
    val advice: String
)

object TermuxBridge {
    const val PKG = "com.termux"
    const val PERM = "com.termux.permission.RUN_COMMAND"

    // Official signing cert SHA-256 (termux-app signing docs)
    private const val CERT_FDROID = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42"
    private const val CERT_GITHUB = "B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1"
    private const val CERT_PLAY = "738F0A30A04D3C8A1BE304AF18D0779BCF3EA88FB60808F657A3521861C2EBF9"

    fun verdict(ctx: Context): TermuxVerdict {
        val pm = ctx.packageManager
        val info = try {
            pm.getPackageInfo(PKG, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: Exception) {
            return TermuxVerdict(false, false, "Not installed", "-", "", listOf("Termux is not installed"),
                "Install Termux from F-Droid or from github.com/termux/termux-app/releases. " +
                    "The Play Store build is abandoned and will not work.")
        }
        val version = info.versionName ?: "?"
        val sig: Signature? = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        else @Suppress("DEPRECATION") info.signatures?.firstOrNull()
        val cert = sig?.let { sha256(it.toByteArray()) } ?: ""
        val problems = ArrayList<String>()
        val (genuine, source) = when (cert) {
            CERT_FDROID -> true to "F-Droid (genuine)"
            CERT_GITHUB -> true to "GitHub release (genuine)"
            CERT_PLAY -> {
                problems.add("Play Store build detected — abandoned (≈0.108), incompatible with modern plugins.")
                false to "Google Play (rejected)"
            }
            else -> {
                problems.add("Unknown signing certificate — possibly a fake/modded Termux.")
                false to "Unknown source (rejected)"
            }
        }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (code in 1 until 118) problems.add("Termux $version is old (need 0.118+).")
        if (!hasRunCommand(ctx)) problems.add("RUN_COMMAND permission not granted yet.")
        val advice = when {
            genuine && problems.isEmpty() -> "Genuine Termux $version — ready for one-tap installs."
            genuine -> "Genuine Termux, but fix the notes above first."
            else -> "Uninstall it, then install the real Termux from F-Droid or GitHub. " +
                "Never mix stores — Termux and its plugins must share one signature."
        }
        return TermuxVerdict(true, genuine, source, version, cert, problems, advice)
    }

    fun hasRunCommand(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    fun launchIntent(ctx: Context): Intent? = ctx.packageManager.getLaunchIntentForPackage(PKG)

    /**
     * Fire a command at Termux. Three delivery modes, best first:
     *  1. RUN_COMMAND_SERVICE with a background session  — runs without stealing focus
     *  2. RUN_COMMAND_SERVICE foreground session         — watch it happen
     *  3. opening Termux with the command on the clipboard (copy + paste hint)
     * Output is not streamed back: Termux lives in another UID, so its stdout is
     * not readable from this app. The UI says so instead of pretending.
     */
    fun send(ctx: Context, command: String, foreground: Boolean = true, session: String = "devstudio"): Boolean {
        val intent = Intent().apply {
            setClassName(PKG, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", !foreground)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", if (foreground) "0" else "1")
        }
        return try {
            ctx.startService(intent)
            true
        } catch (e: Exception) {
            // Termux <0.118 or allow-external-apps off: fall back to simply opening Termux
            try {
                launchIntent(ctx)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } catch (_: Exception) {
            }
            false
        }
    }

    /** One-tap package installs, all through Termux's own package manager. */
    val INSTALLS = listOf(
        "python3" to "pkg install -y python",
        "node" to "pkg install -y nodejs",
        "git" to "pkg install -y git",
        "clang (C/C++)" to "pkg install -y clang make",
        "rust" to "pkg install -y rust",
        "openjdk-17" to "pkg install -y openjdk-17",
        "proot-distro" to "pkg install -y proot-distro",
        "everything for DevStudio" to
            "pkg update -y && pkg install -y python nodejs git clang make rust openjdk-17 proot-distro"
    )

    /** Which Termux package provides each language, for the "install it in Termux" button. */
    fun installFor(stack: String): String = when (stack) {
        "python", "notebook" -> "pkg install -y python"
        "node" -> "pkg install -y nodejs"
        "go" -> "pkg install -y golang"
        "rust" -> "pkg install -y rust"
        "c", "cpp" -> "pkg install -y clang make"
        "jvm" -> "pkg install -y openjdk-17"
        "php" -> "pkg install -y php"
        "ruby" -> "pkg install -y ruby"
        else -> "pkg install -y python nodejs git"
    }

    /**
     * A script that runs an entire Build Studio plan inside Termux, printing a
     * summary at the end. Used when the user picks the Termux engine.
     */
    /** Shared folder both apps can reach; Termux writes its log here. */
    private const val BRIDGE = "/sdcard/Download/Hakatron"

    val DISTROS = listOf("ubuntu" to "Ubuntu", "debian" to "Debian", "kali" to "Kali",
        "alpine" to "Alpine", "archlinux" to "Arch")

    fun installDistroCmd(alias: String): String =
        "pkg install -y proot-distro && proot-distro install $alias 2>&1 | tee " +
            "\$HOME/.devstudio-distro.log; proot-distro list --installed > $BRIDGE/distros.txt 2>&1; " +
            "cat $BRIDGE/distros.txt"

    fun loginCmd(alias: String) = "proot-distro login $alias"

    fun refreshDistrosCmd() =
        "mkdir -p $BRIDGE && pkg install -y proot-distro >/dev/null 2>&1; " +
            "proot-distro list --installed > $BRIDGE/distros.txt 2>&1; cat $BRIDGE/distros.txt"

    fun scriptFor(project: String, steps: List<String>): String {
        val body = steps.joinToString("\n") { "echo \"\\e[38;5;81m▸ $it\\e[0m\"; $it || " +
            "{ echo \"\\e[38;5;214m✗ failed: $it\\e[0m\"; FAIL=1; }" }
        // everything is teed into the bridge folder, because Termux's own screen
        // is a different app — this file is the only way the IDE can show the log
        return """
mkdir -p $BRIDGE ~/devstudio/$project && cd ~/devstudio/$project
{
echo "Hakatron build in Termux · $project · \$(date)"
$body
if [ "${'$'}FAIL" = "1" ]; then echo "\\e[38;5;203mBuild finished with errors — see last-build.log\\e[0m";
else echo "\\e[38;5;114m✓ Build complete in Termux\\e[0m"; fi
} 2>&1 | tee "$BRIDGE/last-build.log"
""".trimIndent()
    }

    /** Banner + aliases for Termux, matching the app's identity. */
    fun bannerScript(): String = """
if ! grep -q DevStudio "${'$'}HOME/.bashrc" 2>/dev/null; then
cat >> "${'$'}HOME/.bashrc" <<'RC'
# DevStudio
export DEVSTUDIO=1
alias ds='cd ~/devstudio && ls'
alias dspy='cd ~/devstudio && python3'
alias dsfix='pkg update -y && pkg upgrade -y'
printf '\e[38;5;99m  </> DevStudio\e[0m · ~/devstudio · ds · dspy · dsfix\n'
RC
fi
mkdir -p ~/devstudio
echo "DevStudio aliases installed — open a new Termux session"
""".trimIndent()
}
