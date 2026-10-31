package com.fitagent.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fitagent.data.DataSource
import com.fitagent.data.FitnessRepository
import com.fitagent.data.HealthStatus
import com.fitagent.data.SyncEvent
import com.fitagent.domain.PHONE_SENSOR_SOURCE
import com.fitagent.domain.formatCount
import com.fitagent.ui.CompatibilityState
import com.fitagent.ui.LocalClock
import com.fitagent.ui.charts.Legend
import com.fitagent.ui.charts.StackedBars
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.components.Pill
import com.fitagent.ui.theme.Fit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** R1 + R2: where the data comes from, whether it's arriving, and what each watch app actually shares. */
@Composable
fun DevicesScreen(
    health: HealthStatus,
    lastWatchSync: Long,
    lastCloudSync: Long,
    signedIn: Boolean,
    sources: List<DataSource>,
    log: List<SyncEvent>,
    compatibility: CompatibilityState,
    refreshing: Boolean,
    onSyncNow: () -> Unit,
    onRunCheck: () -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    val fc = Fit.colors
    val now = LocalClock.current.millis()
    DetailScaffold("Devices & data health", "Is your watch data arriving?", onBack) {
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        StatusDot(freshnessColor(lastWatchSync, now), "Watch → phone: ${agoText(lastWatchSync, now)}")
                        Spacer(Modifier.height(6.dp))
                        if (signedIn) StatusDot(freshnessColor(lastCloudSync, now), "Phone → cloud: ${agoText(lastCloudSync, now)}")
                        else StatusDot(fc.muted, "Cloud backup off (not signed in)")
                        Spacer(Modifier.height(6.dp))
                        StatusDot(if (health.backgroundRead) fc.push else fc.steady,
                            if (health.backgroundRead) "Updates every 15 min in the background" else "Updates when you open the app")
                    }
                    Button(onClick = onSyncNow, enabled = !refreshing) {
                        if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Sync, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Sync now")
                    }
                }
                if (!health.connected || health.missing > 0) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                        Text(if (!health.connected) "Connect Health Connect" else "Allow ${health.missing} more data type${if (health.missing > 1) "s" else ""}")
                    }
                }
            }
        }
        item { SectionTitleRow("Sources") }
        if (sources.isEmpty()) {
            item {
                AppCard {
                    Text("No data has arrived yet.", style = MaterialTheme.typography.titleMedium)
                    Text("Open your watch app (NoiseFit, boAt Crest, …), let it sync, and turn on Health Connect sharing in its settings. Then tap Sync now.",
                        style = MaterialTheme.typography.bodySmall, color = fc.muted)
                }
            }
        }
        sources.forEach { s -> item(key = s.id) { SourceCard(s, now) } }
        item { SyncTimeline(log, now) }
        item { CompatibilityCard(compatibility, onRunCheck) }
        item {
            AppCard(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text("How your data travels", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "1. Your watch syncs to its own app over Bluetooth.",
                    "2. The watch app writes to Health Connect (turn this on in its settings).",
                    "3. FitAgent reads Health Connect — every 15 min when allowed, and whenever you open the app.",
                    "4. If you sign in, daily summaries back up to the cloud. Heart-rate detail, sleep stages and routes stay on your phone.",
                ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = fc.muted) }
            }
        }
    }
}

@Composable
private fun SectionTitleRow(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SourceCard(s: DataSource, now: Long) {
    val fc = Fit.colors
    AppCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (s.id == PHONE_SENSOR_SOURCE) Icons.Rounded.PhoneAndroid else Icons.Rounded.Watch, null, tint = fc.workout)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.label, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(s.device, if (s.recordsReceived > 0) "${formatCount(s.recordsReceived)} records" else null).joinToString(" · ")
                    .ifEmpty { "Seen in Health Connect" }, style = MaterialTheme.typography.bodySmall, color = fc.muted)
            }
            Pill(if (s.lastDataMs > 0) agoText(s.lastDataMs, now) else "seen", freshnessColor(s.lastDataMs, now))
        }
        if (s.dataTypes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(s.dataTypes.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = fc.muted)
        }
    }
}

/** One dot per sync run over the last 24 h (green ok, red failed), plus records per day by kind for a week. */
@Composable
private fun SyncTimeline(log: List<SyncEvent>, now: Long) {
    val fc = Fit.colors
    val dayMs = 24 * 3_600_000L
    val recent = log.filter { now - it.timeMs <= dayMs }
    val failures = recent.count { !it.ok }
    ChartCard("Sync timeline", when {
        recent.isEmpty() -> "No syncs in the last 24 hours."
        failures == 0 -> "${recent.size} syncs in the last 24 h, all successful."
        else -> "$failures of ${recent.size} syncs failed — latest: ${recent.firstOrNull { !it.ok }?.message ?: "unknown"}"
    }) {
        val ok = fc.push
        val bad = fc.recover
        val track = fc.track
        Canvas(Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = "Sync runs in the last 24 hours" }) {
            drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
            recent.forEach { e ->
                val x = size.width * (1f - (now - e.timeMs).toFloat() / dayMs)
                drawCircle(if (e.ok) ok else bad, if (e.kind == FitnessRepository.KIND_CLOUD) 4.dp.toPx() else 6.dp.toPx(),
                    Offset(x.coerceIn(6f, size.width - 6f), size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("24 h ago", style = MaterialTheme.typography.labelSmall, color = fc.muted, modifier = Modifier.weight(1f))
            Text("now", style = MaterialTheme.typography.labelSmall, color = fc.muted)
        }
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        fun count(d: LocalDate, kind: String) = log.filter { it.ok && it.kind == kind && Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() == d }
            .sumOf { it.records }.toFloat()
        if (log.any { it.records > 0 }) {
            Spacer(Modifier.height(14.dp))
            Text("Records received per day", style = MaterialTheme.typography.labelMedium)
            StackedBars(
                days.map { d -> listOf(count(d, FitnessRepository.KIND_WATCH), count(d, FitnessRepository.KIND_PHONE), count(d, FitnessRepository.KIND_CLOUD)) },
                listOf(fc.workout, fc.steps, fc.sleep), height = 90.dp,
                labels = days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()) },
            )
            Legend(listOf("Watch apps" to fc.workout, "Phone sensor" to fc.steps, "Sent to cloud" to fc.sleep))
        }
    }
}

@Composable
private fun CompatibilityCard(state: CompatibilityState, onRun: () -> Unit) {
    val fc = Fit.colors
    AppCard {
        Text("Compatibility check", style = MaterialTheme.typography.titleMedium)
        Text("Which data each watch app shared with Health Connect in the last 7 days.", style = MaterialTheme.typography.bodySmall, color = fc.muted)
        Spacer(Modifier.height(12.dp))
        when (state) {
            CompatibilityState.Idle -> Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) { Text("Run check") }
            CompatibilityState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Reading Health Connect…")
            }
            is CompatibilityState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRun) { Text("Try again") }
            }
            is CompatibilityState.Done -> {
                val apps = state.rows.flatMap { (row, names) -> row.countsByApp.keys.map { it to (names[it] ?: it) } }.distinct()
                if (apps.isEmpty()) {
                    Text("No app has shared any data in the last 7 days. Open your watch app and enable Health Connect in its settings.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (apps.isNotEmpty()) {
                        Row {
                            Spacer(Modifier.weight(1f))
                            apps.forEach { (_, name) ->
                                Text(name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(72.dp), maxLines = 2)
                            }
                        }
                    }
                    state.rows.forEach { (row, _) ->
                        HorizontalDivider(color = fc.track)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.type.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            apps.forEach { (pkg, _) ->
                                val n = row.countsByApp[pkg] ?: 0
                                Row(Modifier.width(72.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (n > 0) Icons.Rounded.CheckCircle else Icons.Rounded.RemoveCircleOutline, null,
                                        tint = if (n > 0) fc.push else fc.muted, modifier = Modifier.size(16.dp))
                                    if (n > 0) Text(" ${if (row.truncated && n >= 1000) "1k+" else formatCount(n)}",
                                        style = MaterialTheme.typography.labelSmall, color = fc.muted)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onRun) { Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Run again") }
            }
        }
    }
}
