package dev.panoterm.net

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.panoterm.App
import dev.panoterm.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-updater for GitHub-distributed builds.
 *
 *  check()  → GET releases/latest (anonymous, nothing about the user is sent)
 *  download → APK into cacheDir, verify SHA-256 (GitHub asset digest) and that the APK is
 *             signed with the SAME certificate as the installed app
 *  install  → PackageInstaller session with USER_ACTION_NOT_REQUIRED: after the first
 *             confirmed self-update Android 12+ lets the app update itself silently.
 *
 * Installing restarts the app and drops SSH sessions, so automatic installs wait until
 * no session is open.
 */
class UpdateChecker(private val ctx: Context) {
    data class Update(val version: String, val url: String, val apkUrl: String = "", val apkSize: Long = 0, val sha256: String = "")

    sealed interface State {
        data object Idle : State
        data class Available(val u: Update) : State
        data class Downloading(val u: Update, val percent: Int) : State
        data class Ready(val u: Update, val file: File) : State
        data class NeedsPermission(val u: Update, val file: File) : State
        data class Installing(val u: Update) : State
        data class Failed(val u: Update?, val message: String) : State
    }

    private val prefs = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(ctx.cacheDir, "updates")

    init {
        // Off the main thread: verify() hashes the whole APK, and this runs during App.onCreate.
        scope.launch {
            val u = cached()
            // Keep only the APK of the pending update (exact name: "1.3.1" must not keep "1.3.10").
            val keep = u?.let { apkFile(it).name }
            dir.listFiles()?.forEach { if (it.name != keep) it.delete() }
            if (u != null) {
                val f = apkFile(u)
                val st = if (f.exists() && verify(u, f) == null) State.Ready(u, f) else State.Available(u)
                _state.compareAndSet(State.Idle, st)
            }
        }
    }

    private fun apkFile(u: Update) = File(dir, "PanoTerm-${u.version}.apk")

    private fun cached(): Update? {
        val v = prefs.getString("version", null) ?: return null
        if (!isNewer(v, BuildConfig.VERSION_NAME)) return null
        return Update(
            v, prefs.getString("url", RELEASES)!!, prefs.getString("apkUrl", "")!!,
            prefs.getLong("apkSize", 0), prefs.getString("sha256", "")!!,
        )
    }

    private val settings get() = App.instance.settings.current

    /** Checks at most every 6 hours unless [force]. */
    fun check(force: Boolean = false) {
        // Back from the "install unknown apps" settings screen: continue the install.
        (_state.value as? State.NeedsPermission)?.let { if (ctx.packageManager.canRequestPackageInstalls()) install() }
        val last = prefs.getLong("checkedAt", 0)
        if (!force && System.currentTimeMillis() - last < 6 * 3600_000L) { maybeAuto(); return }
        scope.launch {
            try {
                val c = URL(BuildConfig.UPDATE_API).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "PanoTerm")
                val o = JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
                val tag = o.optString("tag_name").removePrefix("v")
                var apkUrl = ""; var size = 0L; var sha = ""
                o.optJSONArray("assets")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url")
                            size = a.optLong("size")
                            sha = a.optString("digest").removePrefix("sha256:")
                            break
                        }
                    }
                }
                prefs.edit().putLong("checkedAt", System.currentTimeMillis())
                    .putString("version", tag).putString("url", o.optString("html_url", RELEASES))
                    .putString("apkUrl", apkUrl).putLong("apkSize", size).putString("sha256", sha).apply()
                val u = cached()
                if (u == null) { _state.value = State.Idle; return@launch }
                val cur = _state.value
                if (cur is State.Ready && cur.u.version == u.version) { maybeAuto(); return@launch }
                _state.value = State.Available(u)
                maybeAuto()
            } catch (e: Exception) {
                if (force) _state.value = State.Failed(null, "확인 실패: ${e.message}")
            }
        }
    }

    /** Auto mode: download (Wi-Fi only if configured), then install when no session is open. */
    fun maybeAuto() {
        if (!settings.autoUpdate) return
        when (val s = _state.value) {
            is State.Available -> if (!settings.updateWifiOnly || onUnmeteredNetwork()) download()
            is State.Ready -> if (App.instance.sessions.sessions.value.isEmpty()) install()
            else -> {}
        }
    }

    private fun onUnmeteredNetwork(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun download() {
        val u = when (val s = _state.value) {
            is State.Available -> s.u
            is State.Failed -> s.u ?: return
            else -> return
        }
        if (u.apkUrl.isBlank()) { _state.value = State.Failed(u, "릴리스에 APK가 없습니다"); return }
        scope.launch {
            val f = apkFile(u)
            // Already downloaded and intact (e.g. found again after a restart): no need to fetch it again.
            if (f.exists() && verify(u, f) == null) { _state.value = State.Ready(u, f); maybeAuto(); return@launch }
            val tmp = File(dir, f.name + ".part")
            try {
                dir.mkdirs()
                val c = URL(u.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "PanoTerm")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: u.apkSize
                // Never write more than the release says (or 300 MB without a size): a broken or
                // hostile response must not fill the phone's storage.
                val limit = if (u.apkSize > 0) u.apkSize else 300L shl 20
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done > limit) throw java.io.IOException("파일이 예상보다 큽니다")
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; _state.value = State.Downloading(u, pct) }
                        }
                    }
                }
                f.delete()
                if (!tmp.renameTo(f)) throw java.io.IOException("파일을 저장하지 못했습니다")
                verify(u, f)?.let { err -> f.delete(); _state.value = State.Failed(u, err); return@launch }
                _state.value = State.Ready(u, f)
                maybeAuto()
            } catch (e: Exception) {
                tmp.delete()
                _state.value = State.Failed(u, "다운로드 실패: ${e.message}")
            }
        }
    }

    /** Returns an error message, or null when the APK is safe to install. */
    private fun verify(u: Update, f: File): String? {
        if (u.sha256.isNotBlank()) {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(u.sha256, ignoreCase = true)) return "파일 해시가 맞지 않습니다 (손상되었거나 바뀐 파일)"
        }
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "APK를 읽을 수 없습니다"
        if (archive.packageName != ctx.packageName) return "다른 앱의 APK입니다"
        val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        // Compare SHA-256 digests of the whole certificates (not a 32-bit hashCode).
        fun certs(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners
            ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { x -> "%02x".format(x) } }
            ?.toSet()?.takeIf { it.isNotEmpty() }
        val a = certs(archive)
        val b = certs(mine)
        if (a == null || b == null || a != b) return "서명이 지금 앱과 다릅니다. 설치하지 않습니다."
        return null
    }

    fun install() {
        val (u, f) = when (val s = _state.value) {
            is State.Ready -> s.u to s.file
            is State.NeedsPermission -> s.u to s.file
            else -> return
        }
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(u, f)
            return
        }
        scope.launch {
            try {
                _state.value = State.Installing(u)
                val installer = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(ctx.packageName)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    setSize(f.length())
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, f.length()).use { out ->
                        f.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val pi = PendingIntent.getBroadcast(
                        ctx, id, Intent(ctx, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_STATUS),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                _state.value = State.Failed(u, "설치 실패: ${e.message}")
            }
        }
    }

    /** Called by [UpdateReceiver] with the installer's result. */
    internal fun onInstallResult(ok: Boolean, message: String?) {
        val u = (_state.value as? State.Installing)?.u
        if (!ok) _state.value = State.Failed(u, "설치 실패: ${message ?: "알 수 없는 오류"}")
    }

    fun dismiss() { _state.value = State.Idle }

    companion object {
        const val REPO = "crossback2000/panoterm"
        const val RELEASES = "https://github.com/$REPO/releases"

        /**
         * Semver-ish comparison of "1.2.3" strings. A pre-release ("1.3.10-rc1", a test build)
         * is older than the same version without a suffix, so it updates to the final release.
         */
        fun isNewer(remote: String, local: String): Boolean {
            fun parts(s: String) = s.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
            val r = parts(remote); val l = parts(local)
            for (i in 0 until maxOf(r.size, l.size)) {
                val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return !remote.contains('-') && local.contains('-')
        }
    }
}
