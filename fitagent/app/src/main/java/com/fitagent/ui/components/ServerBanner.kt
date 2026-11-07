package com.fitagent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudOff, null, modifier = androidx.compose.ui.Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Offline — can't reach the FitAgent server", style = MaterialTheme.typography.titleSmall, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("Watch data still works. Coach, squad and WhatsApp reports wait. $serverUrl",
                        style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                val c = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = onRetry, colors = c) { Text("Retry") }
                TextButton(onClick = onSettings, colors = c) { Text("Fix") }
            }
        }
    }
}
