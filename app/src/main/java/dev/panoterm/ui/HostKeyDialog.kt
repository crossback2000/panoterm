package dev.panoterm.ui

import androidx.compose.ui.res.stringResource
import dev.panoterm.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.panoterm.term.HostKeyPrompt

@Composable
fun HostKeyDialog(p: HostKeyPrompt) {
    AlertDialog(
        onDismissRequest = { p.result.complete(false) },
        title = { Text(stringResource(if (p.changed) R.string.hostkey_changed else R.string.hostkey_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.hostPort)
                Text(p.keyType, style = MaterialTheme.typography.bodySmall)
                Text(p.fingerprint, fontFamily = FontFamily.Monospace)
                if (p.changed) {
                    Text(
                        stringResource(R.string.hostkey_changed_warn),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(stringResource(R.string.hostkey_verify_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { p.result.complete(true) }) { Text(stringResource(if (p.changed) R.string.hostkey_replace else R.string.hostkey_trust)) }
        },
        dismissButton = { TextButton(onClick = { p.result.complete(false) }) { Text(stringResource(R.string.cancel)) } },
    )
}
