package dev.panoterm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.panoterm.data.Host
import dev.panoterm.term.Prompts
import dev.panoterm.term.TerminalInputView
import dev.panoterm.ui.HostEditScreen
import dev.panoterm.ui.HostKeyDialog
import dev.panoterm.ui.HostListScreen
import dev.panoterm.ui.KeysScreen
import dev.panoterm.ui.LicensesScreen
import dev.panoterm.ui.SettingsScreen
import dev.panoterm.ui.TerminalScreen
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface Screen {
    data object Hosts : Screen
    data object Keys : Screen
    data object Settings : Screen
    data object Licenses : Screen
    data object KeyBarEditor : Screen
    data object Snippets : Screen
    data class EditHost(val id: String?, val prefill: Host? = null) : Screen
    data class Terminal(val sessionId: String) : Screen
}

class MainActivity : FragmentActivity() {
    /** Set while the terminal screen is visible; receives hardware (K380) keys. */
    var keyTarget: TerminalInputView? = null
    private val consumedDown = HashSet<Int>()

    /** Navigation requests from intents (notifications, launcher shortcuts). */
    val navRequest = MutableStateFlow<Screen?>(null)
    val locked = MutableStateFlow(false)
    private var unlockedOnce = false
    private var lastStop = 0L

    private val requestNotif = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    val biometricAvailable: Boolean
        get() = BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    /** Runs [ok] after a biometric check when "접속할 때 생체인증" is on; otherwise immediately. */
    fun requireAuth(title: String, ok: () -> Unit) {
        if (!App.instance.settings.current.keyAuth || !biometricAvailable) { ok(); return }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = ok()
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        )
    }

    /** Opens (or resumes) a session for [h], asking for biometrics first if configured. */
    fun connectHost(h: Host) {
        if (App.instance.sessions.sessions.value.any { it.host.id == h.id }) {
            navRequest.value = Screen.Terminal(App.instance.sessions.open(h).id)
            return
        }
        requireAuth("${h.title} 접속") { navRequest.value = Screen.Terminal(App.instance.sessions.open(h).id) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark: light status/navigation bar icons. The default follows the
        // system theme, which in light mode drew dark icons on our black bar (invisible).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        // The keyboard is left out here: the terminal moves with the keyboard
                        // animation itself (TerminalScreen); other screens pad for it in AppRoot.
                        .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
                ) {
                    val isLocked by locked.collectAsState()
                    if (isLocked) LockScreen { authenticate() } else AppRoot(this@MainActivity)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = App.instance
        val needLock = app.settings.current.biometricLock && biometricAvailable &&
            (!unlockedOnce || SystemClock.elapsedRealtime() - lastStop > 5 * 60_000)
        if (needLock) {
            locked.value = true
            authenticate()
        }
    }

    override fun onStop() {
        super.onStop()
        lastStop = SystemClock.elapsedRealtime()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val app = App.instance
        i?.getStringExtra(Notifier.EXTRA_SESSION)?.let { id ->
            if (app.sessions.get(id) != null) navRequest.value = Screen.Terminal(id)
        }
        i?.getStringExtra(Notifier.EXTRA_HOST)?.let { id ->
            val h = app.hosts.get(id) ?: return@let
            if (h.keyId != null) connectHost(h)
            else navRequest.value = Screen.EditHost(h.id)
        }
    }

    private fun authenticate() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlockedOnce = true
                locked.value = false
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("PanoTerm 잠금 해제")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        )
    }

    /**
     * Hardware keys are taken here, before Compose focus handling, so Tab / arrows /
     * Esc never move UI focus and Shift+Tab etc. reach the terminal.
     */
    override fun dispatchKeyEvent(original: KeyEvent): Boolean {
        val t = keyTarget
        if (t != null && t.isAttachedToWindow && !locked.value) {
            val event = backAsEscape(original) ?: original
            // Volume-down as a sticky Ctrl (optional).
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && App.instance.settings.current.volumeAsCtrl) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) t.toggleCtrl()
                return true
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (t.handleHardwareKey(event)) {
                    consumedDown.add(event.keyCode)
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    if (t.handleHardwareKeyUp(event)) return true
                    if (consumedDown.remove(event.keyCode)) return true
                }
            }
        }
        return super.dispatchKeyEvent(original)
    }

    /**
     * Samsung (and some keyboard layouts) deliver the physical Esc key as BACK, or Android turns
     * it into a fallback BACK, which would leave the terminal. While the terminal has the keys,
     * a BACK from an external hardware keyboard is Esc. The on-screen back gesture comes from a
     * virtual device and still navigates.
     */
    private fun backAsEscape(e: KeyEvent): KeyEvent? {
        if (e.keyCode != KeyEvent.KEYCODE_BACK) return null
        val dev = e.device
        val hardwareKeyboard = dev != null && !dev.isVirtual && dev.isExternal &&
            dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        if (!hardwareKeyboard && (e.flags and KeyEvent.FLAG_FALLBACK) == 0) return null
        return KeyEvent(
            e.downTime, e.eventTime, e.action, KeyEvent.KEYCODE_ESCAPE, e.repeatCount, e.metaState,
            e.deviceId, e.scanCode, e.flags and KeyEvent.FLAG_FALLBACK.inv(), e.source,
        )
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🔒 PanoTerm", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onUnlock) { Text("잠금 해제") }
    }
}

@Composable
private fun AppRoot(activity: MainActivity) {
    val app = App.instance
    var screen by remember { mutableStateOf<Screen>(Screen.Hosts) }
    val prompt by Prompts.hostKey.collectAsState()
    val nav by activity.navRequest.collectAsState()
    val hosts by app.hosts.hosts.collectAsState()

    LaunchedEffect(nav) {
        nav?.let { screen = it; activity.navRequest.value = null }
    }
    LaunchedEffect(hosts) { Shortcuts.refresh(activity, hosts) }

    BackHandler(enabled = screen != Screen.Hosts) {
        screen = when (screen) {
            Screen.Licenses, Screen.KeyBarEditor, Screen.Snippets -> Screen.Settings
            else -> Screen.Hosts
        }
    }

    Box(if (screen is Screen.Terminal) Modifier.fillMaxSize() else Modifier.fillMaxSize().imePadding()) {
    when (val s = screen) {
        Screen.Hosts -> HostListScreen(
            app = app,
            onOpenSession = { screen = Screen.Terminal(it.id) },
            onConnectHost = { activity.connectHost(it) },
            onEdit = { id, prefill -> screen = Screen.EditHost(id, prefill) },
            onKeys = { screen = Screen.Keys },
            onLicenses = { screen = Screen.Settings },
        )
        Screen.Keys -> KeysScreen(app = app, onBack = { screen = Screen.Hosts })
        Screen.Settings -> SettingsScreen(
            app = app,
            biometricAvailable = activity.biometricAvailable,
            onLicenses = { screen = Screen.Licenses },
            onKeyBar = { screen = Screen.KeyBarEditor },
            onSnippets = { screen = Screen.Snippets },
            onBack = { screen = Screen.Hosts },
        )
        Screen.Licenses -> LicensesScreen(onBack = { screen = Screen.Settings })
        Screen.KeyBarEditor -> dev.panoterm.ui.KeyBarEditorScreen(app) { screen = Screen.Settings }
        Screen.Snippets -> dev.panoterm.ui.SnippetsEditorScreen(app) { screen = Screen.Settings }
        is Screen.EditHost -> HostEditScreen(app = app, hostId = s.id, prefill = s.prefill, onDone = { screen = Screen.Hosts })
        is Screen.Terminal -> {
            val session = app.sessions.get(s.sessionId)
            if (session == null) LaunchedEffect(s) { screen = Screen.Hosts }
            // key(): a session switch must build a new screen. Reusing it left the new session's
            // input view off-screen (hardware keys ignored, phone keyboard typing into the old session).
            else key(session.id) {
                TerminalScreen(
                    app = app, session = session, activity = activity,
                    onBack = { screen = Screen.Hosts },
                    onSwitch = { screen = Screen.Terminal(it.id) },
                )
            }
        }
    }
    }

    prompt?.let { HostKeyDialog(it) }
}
