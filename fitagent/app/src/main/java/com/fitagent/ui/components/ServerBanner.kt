package com.fitagent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitagent.data.ServerStatus

/**
 * Shown on every tab while the FitAgent server can't be reached. Watch data, workouts and the on-phone PDF keep
 * working offline; coach, squad, cloud backup and WhatsApp delivery wait for the server.
 */
@Composable
fun ServerBanner(status: ServerStatus, serverUrl: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    AnimatedVisibility(status is ServerStatus.Offline, enter = expandVertically(), exit = shrinkVertically()) {
        val msg = (status as? ServerStatus.Offline)?.message.orEmpty()
        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudOff, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Not connected to the FitAgent server", style = MaterialTheme.typography.titleSmall)
                    Text("$msg Coach, squad and WhatsApp reports are paused. ($serverUrl)",
                        style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
                Column(horizontalAlignment = Alignment.End) {
                    val c = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onRetry, colors = c) { Text("Retry") }
                    TextButton(onClick = onSettings, colors = c) { Text("Server") }
                }
            }
        }
    }
}
