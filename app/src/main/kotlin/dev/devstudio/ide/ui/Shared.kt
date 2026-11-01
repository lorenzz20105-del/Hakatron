package dev.devstudio.ide.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * SharedFiles — the folder Termux and the IDE both use.
 *
 * Termux cannot write into another app's private data, so the meeting point is
 * shared storage: /sdcard/Download/Hakatron. After `termux-setup-storage` in
 * Termux (or with "All files access" granted here) both sides read and write:
 *
 *   shared/          files you and Termux exchange — the editor opens them too
 *   last-build.log   full output of the last build handed to Termux
 *   distros.txt      `proot-distro list --installed`, so the app can show them
 *
 * Nothing is uploaded anywhere; this is a plain folder on your own phone.
 */
object SharedFiles {
    const val DIR = "/sdcard/Download/Hakatron"
    const val SHARED = "$DIR/shared"
    private const val LOG = "$DIR/last-build.log"
    private const val DISTROS = "$DIR/distros.txt"

    /** Android 11+ needs MANAGE_EXTERNAL_STORAGE for a folder like this one. */
    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun requestAccess(ctx: Context) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun ensure(ctx: Context): Boolean = runCatching {
        File(SHARED).mkdirs()
        File(DIR).exists()
    }.getOrDefault(false)

    /** Every file under shared/, newest first — the same list Termux sees. */
    fun list(ctx: Context): List<String> = runCatching {
        File(SHARED).listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }?.map { it.absolutePath } ?: emptyList()
    }.getOrDefault(emptyList())

    fun read(path: String): String =
        runCatching { File(path).takeIf { it.exists() }?.readText() }.getOrNull() ?: ""

    fun write(path: String, text: String): Boolean = runCatching {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(text)
        true
    }.getOrDefault(false)

    fun put(name: String, text: String, ctx: Context? = null): Boolean {
        if (ctx != null) ensure(ctx)
        return write("$SHARED/$name", text)
    }

    /** Output of the last build handed to Termux (Termux tees it here). */
    fun buildLog(): String? =
        runCatching { File(LOG).takeIf { it.exists() }?.readText()?.takeLast(6000) }.getOrNull()

    fun clearLog(): Boolean = runCatching { File(LOG).delete() }.getOrDefault(false)

    /** Distros proot-distro reports as installed. */
    fun distros(): List<String> = runCatching {
        File(DISTROS).takeIf { it.exists() }?.readLines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("[") && !it.startsWith("Installed") } ?: emptyList()
    }.getOrDefault(emptyList())

    fun seed(ctx: Context? = null): Boolean =
        put("README.txt", ctx = ctx, text =
            "Shared folder between Hakatron and Termux.\n" +
            "In Termux:  ln -s /sdcard/Download/Hakatron/shared ~/shared\n" +
            "Files written here open in Hakatron → Files → Shared.\n")
}
