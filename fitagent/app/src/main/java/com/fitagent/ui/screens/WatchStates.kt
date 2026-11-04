package com.fitagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WatchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fitagent.data.HealthStatus
import com.fitagent.data.WatchState
import com.fitagent.data.health.HealthAvailability
import com.fitagent.ui.LocalClock
import com.fitagent.ui.theme.Fit

/**
 * Shown on Today until a watch delivers data: a three-step checklist that reflects reality (Health Connect present,
 * FitAgent allowed to read, watch app sharing) — no sample numbers, ever.
 */
@Composable
fun WatchSetupCard(health: HealthStatus, watch: WatchState, onConnect: () -> Unit, onCheckAgain: () -> Unit, onDataHealth: () -> Unit) {
    val fc = Fit.colors
    val hcReady = health.availability == HealthAvailability.AVAILABLE
    val allowed = health.connected
    val watchSharing = watch.hasWatch
    Surface(
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(fc.workout.copy(alpha = 0.22f), MaterialTheme.colorScheme.surfaceContainer)))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(fc.workout.copy(alpha = 0.2f)), Alignment.Center) {
                    Icon(Icons.Rounded.Watch, null, tint = fc.workout)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Connect your watch", style = MaterialTheme.typography.titleLarge)
                    Text("FitAgent shows only what your watch measures — nothing appears until it does.",
                        style = MaterialTheme.typography.bodySmall, color = fc.muted)
                }
            }
            Spacer(Modifier.height(16.dp))
            SetupStep(1, "Health Connect on this phone",
                when (health.availability) {
                    HealthAvailability.AVAILABLE -> "Ready"
                    HealthAvailability.UPDATE_REQUIRED -> "Needs an update from the Play Store"
                    HealthAvailability.NOT_INSTALLED -> "Install it from the Play Store (built into Android 14+)"
                }, hcReady)
            SetupStep(2, "Allow FitAgent to read your data", if (allowed) "Allowed" else "Tap Connect and allow all", allowed)
            SetupStep(3, "Turn on sharing in your watch app",
                if (watchSharing) "Receiving from ${(watch as? WatchState.Connected)?.label ?: (watch as? WatchState.Stale)?.label}"
                else "In NoiseFit, boAt Crest, Samsung Health, Fitbit… open settings → Health Connect (or Google Fit) → allow. Then sync the watch.",
                watchSharing)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!allowed) Button(onClick = onConnect, modifier = Modifier.weight(1f)) { Text(if (hcReady) "Connect" else "Get Health Connect") }
                else Button(onClick = onCheckAgain, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Sync, null); Spacer(Modifier.width(6.dp)); Text("Check again") }
                if (allowed) OutlinedButton(onClick = onDataHealth, modifier = Modifier.weight(1f)) { Text("What's arriving?") }
            }
        }
    }
}

@Composable
private fun SetupStep(n: Int, title: String, detail: String, done: Boolean) {
    val fc = Fit.colors
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (done) fc.push else MaterialTheme.colorScheme.surfaceContainerHighest),
            Alignment.Center,
        ) {
            if (done) Icon(Icons.Rounded.Check, null, tint = Color(0xFF0A0D12), modifier = Modifier.size(16.dp))
            else Text("$n", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (done) fc.push else fc.muted)
        }
    }
}

/** The watch delivered data before but has gone quiet; what's shown below is its last real data. */
@Composable
fun StaleWatchBanner(watch: WatchState.Stale, onSync: () -> Unit) {
    val fc = Fit.colors
    val now = LocalClock.current.millis()
    Surface(shape = MaterialTheme.shapes.medium, color = fc.steady.copy(alpha = 0.14f), contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WatchOff, null, tint = fc.steady)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${watch.label} last synced ${agoText(watch.lastDataMs, now)}", style = MaterialTheme.typography.titleSmall)
                Text("Open the watch app so it syncs, then refresh. Below is your last synced data.",
                    style = MaterialTheme.typography.bodySmall, color = fc.muted)
            }
            TextButton(onClick = onSync) { Text("Refresh") }
        }
    }
}

/** Header chip: which watch feeds the app and how fresh its data is. */
@Composable
fun WatchChip(watch: WatchState, onClick: () -> Unit) {
    val fc = Fit.colors
    val now = LocalClock.current.millis()
    val (color, text) = when (watch) {
        WatchState.None -> fc.muted to "No watch connected"
        is WatchState.Stale -> fc.steady to "${watch.label} · ${agoText(watch.lastDataMs, now)}"
        is WatchState.Connected -> fc.push to "${watch.device ?: watch.label} · ${agoText(watch.lastDataMs, now)}"
    }
    Surface(onClick = onClick, shape = CircleShape, color = color.copy(alpha = 0.14f), contentColor = color) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (watch.hasWatch) Icons.Rounded.Watch else Icons.Rounded.WatchOff, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Readiness needs a real night of watch data; until then say so instead of showing a made-up score. */
@Composable
fun ReadinessPendingCard() {
    val fc = Fit.colors
    com.fitagent.ui.components.AppCard {
        Text("Readiness", style = MaterialTheme.typography.titleMedium)
        Text("Wear your watch to bed tonight. Your first readiness score appears once it shares sleep or resting heart rate.",
            style = MaterialTheme.typography.bodySmall, color = fc.muted)
    }
}
