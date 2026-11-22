package dev.panoterm

import androidx.annotation.StringRes

/**
 * A UI string outside composition (callbacks, services, notifications, terminal notes).
 * Inside composables use stringResource so the text follows the Activity's locale.
 * Translations: res/values (English, default) and res/values-ko (Korean).
 */
fun str(@StringRes id: Int, vararg args: Any): String = App.instance.getString(id, *args)

/**
 * An error whose message is a string resource, for code that runs without an Android
 * context in unit tests (backup crypto). [message] resolves it when the app is running.
 */
class UiException(@StringRes val res: Int, vararg val args: Any) : Exception() {
    override val message: String
        get() = runCatching { str(res, *args) }.getOrDefault("error $res")
}
