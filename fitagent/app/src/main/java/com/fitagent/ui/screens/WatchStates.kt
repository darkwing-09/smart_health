package com.fitagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Favorite
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
                else sharingHint(androidx.compose.ui.platform.LocalContext.current),
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
        is WatchState.Connected -> fc.push to "${watchName(watch.label, watch.device)} · ${agoText(watch.lastDataMs, now)}"
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

/** Step-by-step for the watch app actually installed on this phone (checked through the manifest's <queries>). */
internal fun sharingHint(context: android.content.Context): String {
    val installed = com.fitagent.data.health.AppLabels.WATCH_COMPANIONS.filter { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }
    return when {
        installed.any { it.startsWith("com.noisefit") || it == "com.crrepa.band.noise" || it == "com.zjw.noisefitsync" } ->
            "NoiseFit: tap the watch icon (top right) → Integrations → Google fit → Connect and finish the Google sign-in " +
                "(the row must stop saying “Pair”). Keep the watch connected, then sync."
        "com.coveiot.android.boat" in installed ->
            "boAt Crest: Profile → Connected apps → Health Connect → allow all, then sync the watch."
        else -> "In your watch app (NoiseFit, boAt Crest, Samsung Health, Fitbit…) open settings → Health Connect " +
            "(or Google Fit) → allow. Then sync the watch."
    }
}

/** Latest heart rate the watch shared today, how fresh it is, and today's range. Real readings only. */
@Composable
fun HeartNowCard(buckets: List<com.fitagent.domain.HrBucket>, onOpen: () -> Unit) {
    val fc = Fit.colors
    val now = LocalClock.current.millis()
    val last = buckets.maxBy { it.startMs }
    com.fitagent.ui.components.AppCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(androidx.compose.material.icons.Icons.Rounded.Favorite, null, tint = fc.move)
            Spacer(Modifier.width(8.dp))
            Text("Heart rate", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(agoText(last.startMs, now), style = MaterialTheme.typography.labelMedium, color = fc.muted)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${last.avg}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(" bpm", style = MaterialTheme.typography.titleMedium, color = fc.muted, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("Today ${buckets.minOf { it.min }}–${buckets.maxOf { it.max }} bpm", style = MaterialTheme.typography.bodySmall, color = fc.muted)
                Text("${buckets.size} reading${if (buckets.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = fc.muted)
            }
        }
        if (buckets.size > 1) {
            Spacer(Modifier.height(8.dp))
            com.fitagent.ui.charts.Sparkline(buckets.sortedBy { it.startMs }.map { it.avg.toFloat() }, fc.move,
                Modifier.fillMaxWidth().height(40.dp))
        }
    }
}

/** "NoiseFit · ColorFit Pro 5", or just the app when the record's device is this phone (Google Fit tags the phone). */
internal fun watchName(app: String, device: String?): String {
    val phone = android.os.Build.MODEL.orEmpty()
    return if (device.isNullOrBlank() || (phone.isNotBlank() && device.contains(phone, ignoreCase = true))) app else device
}
