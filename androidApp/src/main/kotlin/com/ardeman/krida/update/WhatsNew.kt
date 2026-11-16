package com.ardeman.krida.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Once-per-marketing-version “What’s New” on the home menu. Compares the install’s
 * `versionName` to the last dismissed version in prefs — build-number-only bumps do
 * not re-prompt.
 */
object WhatsNew {
    private const val PREFS = "whats_new"
    private const val KEY_SEEN = "seen_version"

    fun shouldShow(context: Context): Boolean {
        val current = marketingVersion(context)
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SEEN, null)
        if (seen.isNullOrEmpty()) return true
        return compareVersions(seen, current) < 0
    }

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SEEN, marketingVersion(context))
            .apply()
    }

    fun marketingVersion(context: Context): String {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return info.versionName ?: "0"
    }

    /** Segment-wise numeric compare: `"1.0.3" < "1.0.4"`. Non-numeric tails sort after. */
    internal fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(left.size, right.size)
        for (i in 0 until n) {
            val d = (left.getOrElse(i) { 0 }) - (right.getOrElse(i) { 0 })
            if (d != 0) return d.coerceIn(-1, 1)
        }
        return 0
    }
}
