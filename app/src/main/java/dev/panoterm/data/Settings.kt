package dev.panoterm.data

import dev.panoterm.R
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.core.content.edit

/** App-wide preferences (local only). */
class Settings(ctx: Context) {
    enum class HangulToggle(@androidx.annotation.StringRes val label: Int) {
        SHIFT_SPACE(R.string.toggle_shift_space),
        RIGHT_ALT(R.string.toggle_right_alt),
        CTRL_SPACE(R.string.toggle_ctrl_space),
        OFF(R.string.toggle_off),
    }

    data class Values(
        val biometricLock: Boolean = false,
        /** Ask for biometrics before opening a new SSH session (using a key). */
        val keyAuth: Boolean = false,
        val capsAsCtrl: Boolean = false,
        val volumeAsCtrl: Boolean = false,
        /**
         * Compose Hangul in the app for hardware keys. Off: the phone keyboard composes (it does
         * for hardware keyboards too), so there is only one composer and one 한/영 state.
         */
        val appHangul: Boolean = false,
        val hangulToggle: HangulToggle = HangulToggle.SHIFT_SPACE,
        val notifyOnBell: Boolean = true,
        /**
         * Let the server set the phone clipboard (OSC 52, e.g. tmux set-clipboard). Off by default:
         * any output (`cat` of a downloaded file, a log) could silently replace the clipboard.
         */
        val remoteClipboard: Boolean = false,
        val keepScreenOn: Boolean = true,
        val approvalOverlay: Boolean = true,
        val twoFingerEsc: Boolean = true,
        /** Autocomplete bubbles (Claude slash commands, paths, command names). */
        val autocomplete: Boolean = true,
        /** Status tabs under the top bar: which session / tmux window is working or waiting. */
        val agentTabs: Boolean = true,
        /** Terminal renderer: auto (WebGL on hardware GPU, else DOM) | webgl | dom. */
        val renderer: String = "auto",
        val updateCheck: Boolean = true,
        /** Download and install new releases automatically (installs only when no session is open). */
        val autoUpdate: Boolean = true,
        val updateWifiOnly: Boolean = true,
        /** Key ids of the bottom key row, in order. Empty = default. */
        val keyBar: List<String> = emptyList(),
        /** Action ids shown as icons in the terminal's top bar, in order. Empty = default. */
        val topBar: List<String> = emptyList(),
    )

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(load())
    val values: StateFlow<Values> = _values
    val current: Values get() = _values.value

    private fun load() = Values(
        biometricLock = prefs.getBoolean("biometricLock", false),
        keyAuth = prefs.getBoolean("keyAuth", false),
        capsAsCtrl = prefs.getBoolean("capsAsCtrl", false),
        volumeAsCtrl = prefs.getBoolean("volumeAsCtrl", false),
        appHangul = prefs.getBoolean("appHangul", false),
        hangulToggle = runCatching { HangulToggle.valueOf(prefs.getString("hangulToggle", null) ?: "") }
            .getOrDefault(HangulToggle.SHIFT_SPACE),
        notifyOnBell = prefs.getBoolean("notifyOnBell", true),
        remoteClipboard = prefs.getBoolean("remoteClipboard", false),
        keepScreenOn = prefs.getBoolean("keepScreenOn", true),
        approvalOverlay = prefs.getBoolean("approvalOverlay", true),
        twoFingerEsc = prefs.getBoolean("twoFingerEsc", true),
        autocomplete = prefs.getBoolean("autocomplete", true),
        agentTabs = prefs.getBoolean("agentTabs", true),
        // "canvas" was removed with xterm.js 6: such a saved choice becomes auto.
        renderer = prefs.getString("renderer", "auto")!!.let { if (it == "canvas") "auto" else it },
        updateCheck = prefs.getBoolean("updateCheck", true),
        autoUpdate = prefs.getBoolean("autoUpdate", true),
        updateWifiOnly = prefs.getBoolean("updateWifiOnly", true),
        keyBar = migrateKeyBar(prefs.getString("keyBar", "")!!.split(',').filter { it.isNotBlank() }.distinct()),
        topBar = prefs.getString("topBar", "")!!.split(',').filter { it.isNotBlank() }.distinct(),
    )

    /** v1.2.2 slimmed the key row; drop the removed keys from a row the user had customised (once). */
    private fun migrateKeyBar(ids: List<String>): List<String> {
        if (ids.isEmpty()) return ids
        var next = ids
        if (!prefs.getBoolean("keybar_v122", false)) {
            val removed = setOf("stab", "esc2", "ctrlc", "tab", "ctrl", "alt", "at", "pipe", "tilde", "dash", "nl")
            next = next.filterNot { it in removed }
        }
        // v1.3.0: Tab goes back in, as the leftmost key.
        if (!prefs.getBoolean("keybar_tab_v130", false) && "tab" !in next) next = listOf("tab") + next
        prefs.edit {
            putBoolean("keybar_v122", true)
            putBoolean("keybar_tab_v130", true)
            putString("keyBar", next.joinToString(","))
        }
        return next
    }

    fun update(f: (Values) -> Values) {
        val v = f(_values.value)
        prefs.edit {
            putBoolean("biometricLock", v.biometricLock)
            putBoolean("keyAuth", v.keyAuth)
            putBoolean("capsAsCtrl", v.capsAsCtrl)
            putBoolean("volumeAsCtrl", v.volumeAsCtrl)
            putBoolean("appHangul", v.appHangul)
            putString("hangulToggle", v.hangulToggle.name)
            putBoolean("notifyOnBell", v.notifyOnBell)
            putBoolean("remoteClipboard", v.remoteClipboard)
            putBoolean("keepScreenOn", v.keepScreenOn)
            putBoolean("approvalOverlay", v.approvalOverlay)
            putBoolean("twoFingerEsc", v.twoFingerEsc)
            putBoolean("autocomplete", v.autocomplete)
            putBoolean("agentTabs", v.agentTabs)
            putString("renderer", v.renderer)
            putBoolean("updateCheck", v.updateCheck)
            putBoolean("autoUpdate", v.autoUpdate)
            putBoolean("updateWifiOnly", v.updateWifiOnly)
            putString("keyBar", v.keyBar.distinct().joinToString(","))
            putString("topBar", v.topBar.distinct().joinToString(","))
        }
        _values.value = v
    }
}
