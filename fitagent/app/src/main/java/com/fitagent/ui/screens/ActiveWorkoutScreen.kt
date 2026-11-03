package com.fitagent.ui.screens

import com.fitagent.ui.LocalClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitagent.domain.ActiveSession
import com.fitagent.domain.PhaseKind
import com.fitagent.domain.Workout
import com.fitagent.domain.formatClock
import com.fitagent.data.ble.BleState
import com.fitagent.domain.ZONE_NAMES
import com.fitagent.domain.zoneOf
import com.fitagent.ui.charts.ArcGauge
import com.fitagent.ui.charts.Sparkline
import com.fitagent.domain.RoutePoint
import com.fitagent.domain.cleanRoute
import com.fitagent.domain.formatPace
import com.fitagent.domain.kmSplits
import com.fitagent.domain.routeDistance
import com.fitagent.domain.segmentPaces
import com.fitagent.ui.charts.Bars
import com.fitagent.ui.charts.HorizontalStackBar
import com.fitagent.ui.charts.RouteTrace
import com.fitagent.domain.intervalPhaseAt
import com.fitagent.domain.voiceCue
import com.fitagent.ui.VoiceCoach
import com.fitagent.domain.workoutKcal
import com.fitagent.ui.components.LinearMeter
import com.fitagent.ui.components.WorkoutBadge
import com.fitagent.ui.theme.Fit
import com.fitagent.ui.theme.color
import kotlinx.coroutines.delay

/** Full-screen workout player. Colour floods WORK/REST phases so they're readable mid-burpee. */
@Composable
fun ActiveWorkoutScreen(
    session: ActiveSession,
    weightKg: Double,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onMinimize: () -> Unit,
    voiceEnabled: Boolean = false,
    onToggleVoice: () -> Unit = {},
    heartRate: BleState = BleState.Off,
    liveHr: List<Int> = emptyList(),
    hrMax: Int = 200,
    onScanHr: () -> Unit = {},
    onConnectHr: (String) -> Unit = {},
    onDisconnectHr: () -> Unit = {},
) {
    val clock = LocalClock.current
    var now by remember { mutableLongStateOf(clock.millis()) }
    LaunchedEffect(session) {
        while (true) { now = clock.millis(); delay(250) }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = onMinimize)

    val activeSec = session.activeMs(now) / 1000
    val phase = session.interval?.let { intervalPhaseAt(it, activeSec) }
    val fc = Fit.colors
    val bg by animateColorAsState(
        when (phase?.kind) {
            PhaseKind.WORK -> fc.move.copy(alpha = 0.22f)
            PhaseKind.REST -> fc.workout.copy(alpha = 0.18f)
            PhaseKind.DONE -> fc.push.copy(alpha = 0.18f)
            null -> session.type.color(fc).copy(alpha = 0.10f)
        }, tween(400), label = "phase",
    )
    PhaseHaptics(phase?.kind, phase?.round)
    VoiceCues(voiceEnabled && !session.isPaused, session, activeSec)

    var confirmDiscard by remember { mutableStateOf(false) }

    // Surface (not a plain Box) so text and icons get the theme's on-background content colour.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize().background(bg).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMinimize) { Icon(Icons.Rounded.ExpandMore, "Minimise") }
                Spacer(Modifier.weight(1f))
                WorkoutBadge(session.type, 36.dp)
                Spacer(Modifier.width(8.dp))
                Text(session.type.label, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleVoice) {
                    Icon(if (voiceEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                        if (voiceEnabled) "Mute voice cues" else "Turn on voice cues")
                }
                IconButton(onClick = { confirmDiscard = true }) { Icon(Icons.Rounded.Close, "Discard") }
            }

            Spacer(Modifier.weight(1f))
            if (phase != null) {
                val label = when (phase.kind) { PhaseKind.WORK -> "WORK"; PhaseKind.REST -> "REST"; PhaseKind.DONE -> "DONE!" }
                val c = when (phase.kind) { PhaseKind.WORK -> fc.move; PhaseKind.REST -> fc.workout; PhaseKind.DONE -> fc.push }
                Text(label, style = MaterialTheme.typography.displayMedium, color = c)
                if (phase.kind != PhaseKind.DONE) {
                    Text("${phase.secondsLeft}", fontSize = 132.sp, fontWeight = FontWeight.Black, color = c,
                        fontFamily = FontFamily.Default, lineHeight = 132.sp)
                    Text("Round ${phase.round} of ${session.interval!!.rounds}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    LinearMeter(1f - phase.secondsLeft / phase.phaseSeconds.toFloat(), c, Modifier.width(220.dp))
                } else {
                    Text("All rounds complete. Cool down, then tap finish.", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(28.dp))
                Text("Total ${formatClock(activeSec)}", style = MaterialTheme.typography.titleMedium, color = fc.muted)
            } else {
                Text(formatClock(activeSec), fontSize = 88.sp, fontWeight = FontWeight.Black, letterSpacing = (-3).sp)
                Text(if (session.isPaused) "PAUSED" else "ACTIVE TIME", style = MaterialTheme.typography.labelSmall,
                    color = if (session.isPaused) fc.steady else fc.muted)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Metric("${workoutKcal(session.type, weightKg, activeSec).toInt()}", "kcal")
                Metric("${activeSec / 60}", "min")
            }
            Spacer(Modifier.height(20.dp))
            HeartRatePanel(heartRate, liveHr, hrMax, onScanHr, onConnectHr, onDisconnectHr)
            Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                FilledIconButton(
                    onClick = onTogglePause, modifier = Modifier.size(84.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(if (session.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        if (session.isPaused) "Resume" else "Pause", Modifier.size(42.dp))
                }
                Button(
                    onClick = onFinish, modifier = Modifier.height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Icon(Icons.Rounded.Stop, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Finish", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
      }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard workout?") },
            text = { Text("This session won't be saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDiscard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun Metric(value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(unit.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted)
    }
}

@Composable
private fun PhaseHaptics(kind: PhaseKind?, round: Int?) {
    val context = LocalContext.current
    var last by remember { mutableStateOf<Pair<PhaseKind?, Int?>?>(null) }
    LaunchedEffect(kind, round) {
        val current = kind to round
        if (last != null && last != current && kind != null) {
            val vibrator = context.getSystemService(Vibrator::class.java)
            val pattern = if (kind == PhaseKind.WORK) longArrayOf(0, 250, 120, 250) else longArrayOf(0, 400)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
        last = current
    }
}

/** Post-workout celebration. */
/** Post-workout summary: time, calories, heart-rate zones (with a sensor) and the GPS route with km splits. */
@Composable
fun WorkoutSummaryDialog(workout: Workout, route: List<RoutePoint> = emptyList(), onDismiss: () -> Unit) {
    val fc = Fit.colors
    val clean = remember(route) { cleanRoute(route, workout.type) }
    val distance = remember(clean) { routeDistance(clean) }
    val splits = remember(clean) { kmSplits(clean) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { WorkoutBadge(workout.type, 56.dp) },
        title = { Text("${workout.type.label} complete!") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Metric(formatClock(workout.activeSeconds), "time")
                    Metric("${workout.kcal.toInt()}", "kcal")
                    workout.avgHr?.let { Metric("$it", "avg bpm") }
                    if (distance >= 100) Metric("%.2f".format(distance / 1000), "km")
                }
                workout.zoneSeconds?.takeIf { z -> z.any { it > 0 } }?.let { z ->
                    val top = z.indices.maxBy { z[it] }
                    Text("Mostly ${ZONE_NAMES[top].lowercase()} zone · max ${workout.maxHr ?: "—"} bpm", style = MaterialTheme.typography.bodySmall, color = fc.muted)
                    HorizontalStackBar(z.mapIndexed { i, s -> s.toFloat() to fc.zones[i] })
                }
                if (clean.size >= 2) {
                    RouteTrace(clean, segmentPaces(clean), fc.push, fc.move, Modifier.fillMaxWidth().height(160.dp),
                        description = "Route, %.2f km".format(distance / 1000))
                    if (splits.isNotEmpty()) {
                        Text("Splits · avg ${formatPace(splits.average().toInt())}", style = MaterialTheme.typography.labelMedium)
                        Bars(splits.map { it.toFloat() }, fc.workout, height = 70.dp, labels = splits.indices.map { "${it + 1}" },
                            scrubLabel = { formatPace(splits[it]) }, description = "Time per kilometre")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Nice!") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
fun MiniSessionBar(session: ActiveSession, onOpen: () -> Unit) {
    val clock = LocalClock.current
    var now by remember { mutableLongStateOf(clock.millis()) }
    LaunchedEffect(session) { while (true) { now = clock.millis(); delay(1000) } }
    androidx.compose.material3.Surface(
        onClick = onOpen, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(session.type.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (session.isPaused) "Paused · ${formatClock(session.activeMs(now) / 1000)}" else formatClock(session.activeMs(now) / 1000),
                style = MaterialTheme.typography.titleMedium, color = Color.Unspecified)
        }
    }
}


/** Speaks phase changes, countdowns and time checks (X2). */
@Composable
private fun VoiceCues(enabled: Boolean, session: ActiveSession, activeSec: Long) {
    if (LocalInspectionMode.current) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val coach = remember { VoiceCoach(context) }
    DisposableEffect(Unit) { onDispose { coach.shutdown() } }
    var last by remember(session.startedAtMs) { mutableLongStateOf(activeSec - 1) }
    LaunchedEffect(activeSec, enabled) {
        if (enabled) voiceCue(session.interval, last, activeSec)?.let(coach::say)
        last = activeSec
    }
}


/** Live heart rate + zone from a Bluetooth sensor, or a button to connect one (R3, D3). */
@Composable
private fun HeartRatePanel(
    state: BleState,
    samples: List<Int>,
    hrMax: Int,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val fc = Fit.colors
    var picker by remember { mutableStateOf(false) }
    when (state) {
        is BleState.Connected -> {
            val bpm = state.bpm
            val zone = bpm?.let { zoneOf(it, hrMax) } ?: 0
            val c = if (zone > 0) fc.zones[zone - 1] else fc.heart
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArcGauge(((bpm ?: 0) / hrMax.toFloat()).coerceIn(0f, 1f), c, Modifier.size(84.dp), thickness = 8.dp,
                    description = "Heart rate ${bpm ?: "—"}") {
                    Text(bpm?.toString() ?: "—", style = MaterialTheme.typography.headlineSmall, color = c)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(if (zone > 0) "Zone $zone · ${ZONE_NAMES[zone - 1]}" else "Below zones", style = MaterialTheme.typography.titleMedium, color = c)
                    Sparkline(samples.map { it.toFloat() }, c, Modifier.width(140.dp).height(28.dp))
                    Text(state.name, style = MaterialTheme.typography.labelSmall, color = fc.muted, modifier = Modifier.clickable { onDisconnect() })
                }
            }
        }
        is BleState.Connecting -> Text("Connecting to ${state.name}…", style = MaterialTheme.typography.bodyMedium, color = fc.muted)
        else -> TextButton(onClick = { picker = true; onScan() }) {
            Icon(Icons.Rounded.Favorite, null, tint = fc.heart); Spacer(Modifier.width(6.dp)); Text("Connect heart-rate sensor")
        }
    }
    if (picker && state !is BleState.Connected && state !is BleState.Connecting) {
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("Heart-rate sensors") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (state) {
                        is BleState.Scanning -> {
                            if (state.devices.isEmpty()) Text("Searching… Turn on “broadcast heart rate” on your watch, or wear your chest strap.")
                            state.devices.forEach { (address, name) ->
                                TextButton(onClick = { onConnect(address); picker = false }) { Text(name) }
                            }
                        }
                        BleState.NoPermission -> Text("Allow Nearby devices to find heart-rate sensors.")
                        is BleState.Error -> Text(state.message)
                        else -> Text("Searching…")
                    }
                    Text("Works with chest straps and watches that broadcast heart rate (Garmin, Amazfit, Polar…). Noise and boAt watches don't broadcast it.",
                        style = MaterialTheme.typography.bodySmall, color = fc.muted)
                }
            },
            confirmButton = { TextButton(onClick = onScan) { Text("Scan again") } },
            dismissButton = { TextButton(onClick = { picker = false }) { Text("Close") } },
        )
    }
}
