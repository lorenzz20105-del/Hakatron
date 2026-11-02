package dev.devstudio.ide.core

/**
 * Base64, the boring kind.
 *
 * `core` used `android.util.Base64` to ship a whole project into the guest in one
 * command. That made the planner impossible to unit-test on a plain JVM ("Method
 * encodeToString in android.util.Base64 not mocked"), so the one piece of the
 * build loop that writes files could never be exercised without a device.
 * `java.util.Base64` is not an option either: it is API 26 and minSdk is 24.
 */
object B64 {

    private const val T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(T[n ushr 18 and 63]).append(T[n ushr 12 and 63])
                .append(T[n ushr 6 and 63]).append(T[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(T[n ushr 18 and 63]).append(T[n ushr 12 and 63]).append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or
                    ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(T[n ushr 18 and 63]).append(T[n ushr 12 and 63])
                    .append(T[n ushr 6 and 63]).append('=')
            }
        }
        return sb.toString()
    }

    /** Text → base64, UTF-8, which is what every caller here needs. */
    fun encode(text: String): String = encode(text.toByteArray(Charsets.UTF_8))
}
