package dev.panoterm.ui

import androidx.compose.ui.res.stringResource
import dev.panoterm.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.panoterm.App
import dev.panoterm.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: App,
    biometricAvailable: Boolean,
    onLicenses: () -> Unit,
    onKeyBar: () -> Unit,
    onSnippets: () -> Unit,
    onBack: () -> Unit,
) {
    val v by app.settings.values.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    var backupMode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) } // "export" | "import"
    var pendingUri by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null) }
    var topBarEdit by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (topBarEdit) TopBarEditorDialog(app) { topBarEdit = false }

    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) { pendingUri = uri; backupMode = "export" } }
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) { pendingUri = uri; backupMode = "import" } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (android.os.Build.VERSION.SDK_INT >= 33) LanguagePicker()

            Section(stringResource(R.string.settings_security))
            Toggle(
                stringResource(R.string.settings_bio_app), if (biometricAvailable) stringResource(R.string.settings_bio_app_sub) else stringResource(R.string.settings_bio_unavailable),
                v.biometricLock && biometricAvailable, enabled = biometricAvailable,
            ) { b -> app.settings.update { it.copy(biometricLock = b) } }
            Toggle(
                stringResource(R.string.settings_bio_connect), stringResource(R.string.settings_bio_connect_sub),
                v.keyAuth && biometricAvailable, enabled = biometricAvailable,
            ) { b -> app.settings.update { it.copy(keyAuth = b) } }

            Section(stringResource(R.string.settings_backup))
            Text(
                stringResource(R.string.settings_backup_desc),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = { exportPicker.launch("panoterm-backup.ptbak") }) { Text(stringResource(R.string.settings_export)) }
                androidx.compose.material3.OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.settings_import)) }
            }

            Section(stringResource(R.string.settings_keyboard))
            TextButton(onClick = onKeyBar) { Text(stringResource(R.string.settings_edit_keybar)) }
            TextButton(onClick = { topBarEdit = true }) { Text(stringResource(R.string.settings_edit_topbar)) }
            TextButton(onClick = onSnippets) { Text(stringResource(R.string.settings_edit_snippets)) }
            Toggle(stringResource(R.string.settings_caps_ctrl), stringResource(R.string.settings_caps_ctrl_sub), v.capsAsCtrl) { b ->
                app.settings.update { it.copy(capsAsCtrl = b) }
            }
            Toggle(stringResource(R.string.settings_vol_ctrl), stringResource(R.string.settings_vol_ctrl_sub), v.volumeAsCtrl) { b ->
                app.settings.update { it.copy(volumeAsCtrl = b) }
            }
            Toggle(stringResource(R.string.settings_two_finger_esc), stringResource(R.string.settings_two_finger_esc_sub), v.twoFingerEsc) { b ->
                app.settings.update { it.copy(twoFingerEsc = b) }
            }
            Toggle(
                stringResource(R.string.settings_app_hangul),
                stringResource(R.string.settings_app_hangul_sub),
                v.appHangul,
            ) { b -> app.settings.update { it.copy(appHangul = b) } }
            if (v.appHangul) {
                Text(stringResource(R.string.settings_hangul_toggle), style = MaterialTheme.typography.bodyLarge)
                Settings.HangulToggle.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(hangulToggle = t) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = v.hangulToggle == t, onClick = { app.settings.update { it.copy(hangulToggle = t) } })
                        Text(stringResource(t.label))
                    }
                }
                Text(
                    stringResource(R.string.settings_hangul_toggle_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Claude Code")
            Toggle(stringResource(R.string.settings_autocomplete), stringResource(R.string.settings_autocomplete_sub), v.autocomplete) { b ->
                app.settings.update { it.copy(autocomplete = b) }
            }
            Toggle(stringResource(R.string.settings_agent_tabs), stringResource(R.string.settings_agent_tabs_sub), v.agentTabs) { b ->
                app.settings.update { it.copy(agentTabs = b) }
            }
            Toggle(stringResource(R.string.settings_approval), stringResource(R.string.settings_approval_sub), v.approvalOverlay) { b ->
                app.settings.update { it.copy(approvalOverlay = b) }
            }
            Toggle(stringResource(R.string.settings_bell), stringResource(R.string.settings_bell_sub), v.notifyOnBell) { b ->
                app.settings.update { it.copy(notifyOnBell = b) }
            }
            Toggle(stringResource(R.string.settings_remote_clip), stringResource(R.string.settings_remote_clip_sub), v.remoteClipboard) { b ->
                app.settings.update { it.copy(remoteClipboard = b) }
            }
            Toggle(stringResource(R.string.settings_keep_screen), null, v.keepScreenOn) { b ->
                app.settings.update { it.copy(keepScreenOn = b) }
            }
            Text(stringResource(R.string.settings_renderer), style = MaterialTheme.typography.bodyLarge)
            listOf(
                "auto" to stringResource(R.string.settings_renderer_auto),
                "webgl" to stringResource(R.string.settings_renderer_webgl),
                "dom" to stringResource(R.string.settings_renderer_dom),
            ).forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(renderer = id) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = v.renderer == id, onClick = { app.settings.update { it.copy(renderer = id) } })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Section(stringResource(R.string.settings_updates))
            Text(stringResource(R.string.settings_version, dev.panoterm.BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            UpdateCard(app)
            Toggle(stringResource(R.string.settings_check_updates), stringResource(R.string.settings_check_updates_sub), v.updateCheck) { b ->
                app.settings.update { it.copy(updateCheck = b) }
            }
            Toggle(
                stringResource(R.string.settings_auto_update), stringResource(R.string.settings_auto_update_sub),
                v.autoUpdate && v.updateCheck, enabled = v.updateCheck,
            ) { b -> app.settings.update { it.copy(autoUpdate = b) } }
            Toggle(
                stringResource(R.string.settings_wifi_only), stringResource(R.string.settings_wifi_only_sub),
                v.updateWifiOnly, enabled = v.autoUpdate && v.updateCheck,
            ) { b -> app.settings.update { it.copy(updateWifiOnly = b) } }
            Text(
                stringResource(R.string.settings_update_note),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                app.updates.check(force = true)
                android.widget.Toast.makeText(ctx, res.getString(R.string.settings_checking), android.widget.Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.settings_check_now)) }

            Section(stringResource(R.string.settings_about))
            TextButton(onClick = onLicenses) { Text(stringResource(R.string.settings_licenses)) }
        }
    }

    val mode = backupMode
    val uri = pendingUri
    if (mode != null && uri != null) {
        BackupPasswordDialog(
            export = mode == "export",
            onDismiss = { backupMode = null; pendingUri = null },
            onConfirm = { pw ->
                backupMode = null; pendingUri = null
                val msg = runCatching {
                    if (mode == "export") {
                        val (blob, s) = dev.panoterm.data.Backup.export(app.hosts, app.keys, app.knownHosts.path, app.snippets, pw)
                        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(blob) }
                        res.getString(R.string.backup_done, s.hosts, s.keys) + if (s.skippedHardwareKeys > 0) res.getString(R.string.backup_skipped_hw, s.skippedHardwareKeys) else ""
                    } else {
                        val blob = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        val s = dev.panoterm.data.Backup.import(blob, pw, app.hosts, app.keys, app.knownHosts, app.snippets)
                        res.getString(R.string.import_done, s.hosts, s.keys)
                    }
                }.getOrElse { res.getString(R.string.failed_with, it.message ?: it.toString()) }
                pw.fill(' ')
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            },
        )
    }
}

@Composable
private fun BackupPasswordDialog(export: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    // Secure fields: no copy/cut, password keyboard, nothing learned by the IME.
    val p1 = androidx.compose.foundation.text.input.rememberTextFieldState()
    val p2 = androidx.compose.foundation.text.input.rememberTextFieldState()
    val ok = if (export) p1.text.length >= 8 && p1.text.contentEquals(p2.text) else p1.text.isNotEmpty()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (export) R.string.backup_pw_set else R.string.backup_pw_enter)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (export) Text(stringResource(R.string.backup_pw_note), style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedSecureTextField(p1, label = { Text(stringResource(R.string.password)) })
                if (export) androidx.compose.material3.OutlinedSecureTextField(p2, label = { Text(stringResource(R.string.password_confirm)) })
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                // Straight into a CharArray (no immutable String copy); the caller wipes it.
                val t = p1.text
                onConfirm(CharArray(t.length) { t[it] })
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Toggle(title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** App language: follow the system, English or Korean (per-app locale, Android 13+). */
@androidx.annotation.RequiresApi(33)
@Composable
private fun LanguagePicker() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lm = ctx.getSystemService(android.app.LocaleManager::class.java)
    val current = lm.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language ?: ""
    Section(stringResource(R.string.settings_language))
    listOf(
        "" to stringResource(R.string.lang_system),
        "en" to "English",
        "ko" to "한국어",
    ).forEach { (tag, label) ->
        fun pick() {
            if (tag != current) lm.applicationLocales = if (tag.isEmpty()) android.os.LocaleList.getEmptyLocaleList() else android.os.LocaleList.forLanguageTags(tag)
        }
        Row(Modifier.fillMaxWidth().clickable { pick() }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = current == tag, onClick = { pick() })
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
