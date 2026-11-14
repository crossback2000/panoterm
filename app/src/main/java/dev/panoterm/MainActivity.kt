package dev.panoterm

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.panoterm.data.Host
import dev.panoterm.term.Prompts
import dev.panoterm.term.TerminalInputView
import dev.panoterm.ui.HostEditScreen
import dev.panoterm.ui.HostKeyDialog
import dev.panoterm.ui.HostListScreen
import dev.panoterm.ui.KeysScreen
import dev.panoterm.ui.LicensesScreen
import dev.panoterm.ui.TerminalScreen

sealed interface Screen {
    data object Hosts : Screen
    data object Keys : Screen
    data object Licenses : Screen
    data class EditHost(val id: String?, val prefill: Host? = null) : Screen
    data class Terminal(val sessionId: String) : Screen
}

class MainActivity : ComponentActivity() {
    /** Set while the terminal screen is visible; receives hardware (K380) keys. */
    var keyTarget: TerminalInputView? = null
    private val consumedDown = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    AppRoot(this@MainActivity)
                }
            }
        }
    }

    /**
     * Hardware keys are taken here, before Compose focus handling, so Tab / arrows /
     * Esc never move UI focus and Shift+Tab etc. reach the terminal.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val t = keyTarget
        if (t != null && t.isAttachedToWindow) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (t.handleHardwareKey(event)) {
                    consumedDown.add(event.keyCode)
                    return true
                }
                KeyEvent.ACTION_UP -> if (consumedDown.remove(event.keyCode)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun AppRoot(activity: MainActivity) {
    val app = App.instance
    var screen by remember { mutableStateOf<Screen>(Screen.Hosts) }
    val prompt by Prompts.hostKey.collectAsState()

    BackHandler(enabled = screen != Screen.Hosts) { screen = Screen.Hosts }

    when (val s = screen) {
        Screen.Hosts -> HostListScreen(
            app = app,
            onOpenSession = { screen = Screen.Terminal(it.id) },
            onEdit = { id, prefill -> screen = Screen.EditHost(id, prefill) },
            onKeys = { screen = Screen.Keys },
            onLicenses = { screen = Screen.Licenses },
        )
        Screen.Keys -> KeysScreen(app = app, onBack = { screen = Screen.Hosts })
        Screen.Licenses -> LicensesScreen(onBack = { screen = Screen.Hosts })
        is Screen.EditHost -> HostEditScreen(app = app, hostId = s.id, prefill = s.prefill, onDone = { screen = Screen.Hosts })
        is Screen.Terminal -> {
            val session = app.sessions.get(s.sessionId)
            if (session == null) LaunchedEffect(s) { screen = Screen.Hosts }
            else TerminalScreen(app = app, session = session, activity = activity, onBack = { screen = Screen.Hosts })
        }
    }

    prompt?.let { HostKeyDialog(it) }
}
