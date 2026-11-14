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
        val capsAsCtrl: Boolean = false,
        val hangulToggle: HangulToggle = HangulToggle.SHIFT_SPACE,
        val notifyOnBell: Boolean = true,
        val keepScreenOn: Boolean = true,
        val approvalOverlay: Boolean = true,
    )

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(load())
    val values: StateFlow<Values> = _values
    val current: Values get() = _values.value

    private fun load() = Values(
        biometricLock = prefs.getBoolean("biometricLock", false),
        capsAsCtrl = prefs.getBoolean("capsAsCtrl", false),
        hangulToggle = runCatching { HangulToggle.valueOf(prefs.getString("hangulToggle", null) ?: "") }
            .getOrDefault(HangulToggle.SHIFT_SPACE),
        notifyOnBell = prefs.getBoolean("notifyOnBell", true),
        keepScreenOn = prefs.getBoolean("keepScreenOn", true),
        approvalOverlay = prefs.getBoolean("approvalOverlay", true),
    )

    fun update(f: (Values) -> Values) {
        val v = f(_values.value)
        prefs.edit()
            .putBoolean("biometricLock", v.biometricLock)
            .putBoolean("capsAsCtrl", v.capsAsCtrl)
            .putString("hangulToggle", v.hangulToggle.name)
            .putBoolean("notifyOnBell", v.notifyOnBell)
            .putBoolean("keepScreenOn", v.keepScreenOn)
            .putBoolean("approvalOverlay", v.approvalOverlay)
            .apply()
        _values.value = v
    }
}
