package dev.panoterm.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide preferences (local only). */
class Settings(ctx: Context) {
    enum class HangulToggle(val label: String) {
        SHIFT_SPACE("Shift+Space"),
        RIGHT_ALT("오른쪽 Alt"),
        CTRL_SPACE("Ctrl+Space"),
        OFF("사용 안 함"),
    }

    data class Values(
        val biometricLock: Boolean = false,
        /** Ask for biometrics before opening a new SSH session (using a key). */
        val keyAuth: Boolean = false,
        val capsAsCtrl: Boolean = false,
        val volumeAsCtrl: Boolean = false,
        val hangulToggle: HangulToggle = HangulToggle.SHIFT_SPACE,
        val notifyOnBell: Boolean = true,
        val keepScreenOn: Boolean = true,
        val approvalOverlay: Boolean = true,
        val twoFingerEsc: Boolean = true,
        /** Autocomplete bubbles (Claude slash commands, paths, command names). */
        val autocomplete: Boolean = true,
        /** Terminal renderer: auto (WebGL on hardware GPU, else Canvas) | webgl | canvas | dom. */
        val renderer: String = "auto",
        val updateCheck: Boolean = true,
        /** Download and install new releases automatically (installs only when no session is open). */
        val autoUpdate: Boolean = true,
        val updateWifiOnly: Boolean = true,
        /** Key ids of the bottom key row, in order. Empty = default. */
        val keyBar: List<String> = emptyList(),
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
        hangulToggle = runCatching { HangulToggle.valueOf(prefs.getString("hangulToggle", null) ?: "") }
            .getOrDefault(HangulToggle.SHIFT_SPACE),
        notifyOnBell = prefs.getBoolean("notifyOnBell", true),
        keepScreenOn = prefs.getBoolean("keepScreenOn", true),
        approvalOverlay = prefs.getBoolean("approvalOverlay", true),
        twoFingerEsc = prefs.getBoolean("twoFingerEsc", true),
        autocomplete = prefs.getBoolean("autocomplete", true),
        renderer = prefs.getString("renderer", "auto")!!,
        updateCheck = prefs.getBoolean("updateCheck", true),
        autoUpdate = prefs.getBoolean("autoUpdate", true),
        updateWifiOnly = prefs.getBoolean("updateWifiOnly", true),
        keyBar = migrateKeyBar(prefs.getString("keyBar", "")!!.split(',').filter { it.isNotBlank() }),
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
        prefs.edit().putBoolean("keybar_v122", true).putBoolean("keybar_tab_v130", true).putString("keyBar", next.joinToString(",")).apply()
        return next
    }

    fun update(f: (Values) -> Values) {
        val v = f(_values.value)
        prefs.edit()
            .putBoolean("biometricLock", v.biometricLock)
            .putBoolean("keyAuth", v.keyAuth)
            .putBoolean("capsAsCtrl", v.capsAsCtrl)
            .putBoolean("volumeAsCtrl", v.volumeAsCtrl)
            .putString("hangulToggle", v.hangulToggle.name)
            .putBoolean("notifyOnBell", v.notifyOnBell)
            .putBoolean("keepScreenOn", v.keepScreenOn)
            .putBoolean("approvalOverlay", v.approvalOverlay)
            .putBoolean("twoFingerEsc", v.twoFingerEsc)
            .putBoolean("autocomplete", v.autocomplete)
            .putString("renderer", v.renderer)
            .putBoolean("updateCheck", v.updateCheck)
            .putBoolean("autoUpdate", v.autoUpdate)
            .putBoolean("updateWifiOnly", v.updateWifiOnly)
            .putString("keyBar", v.keyBar.joinToString(","))
            .apply()
        _values.value = v
    }
}
