package dev.panoterm.net

import android.content.Context
import dev.panoterm.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer version (the app is distributed outside Play).
 * Only one anonymous GET to api.github.com, at most every 12 hours; nothing is sent.
 */
class UpdateChecker(ctx: Context) {
    data class Update(val version: String, val url: String)

    private val prefs = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val _available = MutableStateFlow<Update?>(cached())
    val available: StateFlow<Update?> = _available
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun cached(): Update? {
        val v = prefs.getString("version", null) ?: return null
        return if (isNewer(v, BuildConfig.VERSION_NAME)) Update(v, prefs.getString("url", RELEASES)!!) else null
    }

    fun check(force: Boolean = false) {
        val last = prefs.getLong("checkedAt", 0)
        if (!force && System.currentTimeMillis() - last < 12 * 3600_000L) return
        scope.launch {
            runCatching {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "PanoTerm")
                val body = c.inputStream.use { it.readBytes().decodeToString() }
                val o = JSONObject(body)
                val tag = o.optString("tag_name").removePrefix("v")
                val url = o.optString("html_url", RELEASES)
                prefs.edit().putLong("checkedAt", System.currentTimeMillis())
                    .putString("version", tag).putString("url", url).apply()
                _available.value = if (isNewer(tag, BuildConfig.VERSION_NAME)) Update(tag, url) else null
            }
        }
    }

    fun dismiss() { _available.value = null }

    companion object {
        const val REPO = "crossback2000/panoterm"
        const val API = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES = "https://github.com/$REPO/releases"

        /** Semver-ish comparison of "1.2.3" strings. */
        fun isNewer(remote: String, local: String): Boolean {
            fun parts(s: String) = s.split('.', '-').mapNotNull { it.toIntOrNull() }
            val r = parts(remote); val l = parts(local)
            for (i in 0 until maxOf(r.size, l.size)) {
                val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}
