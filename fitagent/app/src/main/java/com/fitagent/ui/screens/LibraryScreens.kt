package com.fitagent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitagent.domain.BodyArea
import com.fitagent.domain.Exercise
import com.fitagent.domain.ExerciseLibrary
import com.fitagent.domain.Routine
import com.fitagent.domain.Routines
import com.fitagent.domain.routineProgressAt
import com.fitagent.ui.LocalClock
import com.fitagent.ui.VoiceCoach
import com.fitagent.ui.charts.HorizontalStackBar
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.components.ExerciseAnimation
import com.fitagent.ui.components.LinearMeter
import com.fitagent.ui.components.Pill
import com.fitagent.ui.components.SectionTitle
import com.fitagent.ui.theme.Fit
import com.fitagent.ui.theme.color
import kotlinx.coroutines.delay

/** S3: dorm-friendly routines and an exercise library with animated demos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(onStartRoutine: (Routine) -> Unit, onBack: () -> Unit) {
    val fc = Fit.colors
    var quietOnly by rememberSaveable { mutableStateOf(false) }
    var area by rememberSaveable { mutableStateOf<BodyArea?>(null) }
    var open by remember { mutableStateOf<Exercise?>(null) }
    val routines = Routines.all.filter { !quietOnly || it.quiet }
    val exercises = ExerciseLibrary.all.filter { (!quietOnly || it.quiet) && (area == null || it.area == area) }
    DetailScaffold("Workout library", "${Routines.all.size} routines · ${ExerciseLibrary.all.size} exercises · no equipment", onBack) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(quietOnly, { quietOnly = !quietOnly }, label = { Text("🤫 Quiet (no jumping)") })
            }
        }
        item { SectionTitle("Routines") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(routines, key = { it.id }) { r -> RoutineCard(r) { onStartRoutine(r) } }
            }
        }
        item { SectionTitle("Exercises") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(area == null, { area = null }, label = { Text("All") })
                BodyArea.entries.forEach { a -> FilterChip(area == a, { area = if (area == a) null else a }, label = { Text(a.label) }) }
            }
        }
        exercises.chunked(2).forEach { row ->
            item(key = row.joinToString { it.id }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { e -> ExerciseTile(e, Modifier.weight(1f)) { open = e } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    open?.let { ExerciseSheet(it) { open = null } }
}

@Composable
private fun RoutineCard(r: Routine, onStart: () -> Unit) {
    val fc = Fit.colors
    val c = r.type.color(fc)
    Surface(onClick = onStart, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(220.dp)) {
        Column(Modifier.padding(16.dp)) {
            ExerciseAnimation(ExerciseLibrary.byId(r.steps.first().exerciseId), c, Modifier.fillMaxWidth().height(90.dp))
            Spacer(Modifier.height(8.dp))
            Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(r.description, style = MaterialTheme.typography.bodySmall, color = fc.muted, maxLines = 2, minLines = 2)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("${r.minutes} min", c)
                Pill(r.level.label, fc.muted)
                if (r.quiet) Pill("Quiet", fc.push)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalStackBar(r.steps.map { it.seconds.toFloat() to ExerciseLibrary.byId(it.exerciseId).area.tint() }, height = 6.dp)
        }
    }
}

@Composable
private fun BodyArea.tint() = when (this) {
    BodyArea.FULL -> Fit.colors.steps
    BodyArea.LEGS -> Fit.colors.move
    BodyArea.CORE -> Fit.colors.steady
    BodyArea.UPPER -> Fit.colors.workout
    BodyArea.MOBILITY -> Fit.colors.sleep
    BodyArea.CARDIO -> Fit.colors.heart
}

@Composable
private fun ExerciseTile(e: Exercise, modifier: Modifier, onClick: () -> Unit) {
    val c = e.area.tint()
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.3f).clip(MaterialTheme.shapes.small).background(c.copy(alpha = 0.08f))) {
                ExerciseAnimation(e, c, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(6.dp))
            Text(e.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text("${e.area.label} · ${e.level.label}${if (e.quiet) "" else " · jumps"}", style = MaterialTheme.typography.labelSmall,
                color = Fit.colors.muted, maxLines = 1)
        }
    }
}

@Composable
private fun ExerciseSheet(e: Exercise, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val fc = Fit.colors
    val c = e.area.tint()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(e.area.label, c); Pill(e.level.label, fc.muted); if (e.quiet) Pill("Quiet", fc.push)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(1.4f).padding(vertical = 12.dp).clip(MaterialTheme.shapes.large).background(c.copy(alpha = 0.08f))) {
                ExerciseAnimation(e, c, Modifier.fillMaxSize())
            }
            Text("How to", style = MaterialTheme.typography.titleMedium)
            e.cues.forEachIndexed { i, cue -> Text("${i + 1}. $cue", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(10.dp))
            Text("Avoid", style = MaterialTheme.typography.titleMedium)
            e.mistakes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = fc.muted) }
            Spacer(Modifier.height(10.dp))
            Text("Easier version", style = MaterialTheme.typography.titleMedium)
            Text(e.easier, style = MaterialTheme.typography.bodyMedium, color = fc.muted)
        }
    }
}

/**
 * Guided routine player: current move with its animation, a countdown, the next move during rests and spoken
 * cues. Pausing freezes time; finishing (or quitting after a minute) saves the workout.
 */
@Composable
fun RoutinePlayer(routine: Routine, voiceEnabled: Boolean, onToggleVoice: () -> Unit, onFinish: (activeSeconds: Long) -> Unit, onQuit: () -> Unit) {
    val fc = Fit.colors
    val clock = LocalClock.current
    val inspection = LocalInspectionMode.current
    var accumulated by rememberSaveable { mutableLongStateOf(if (inspection) 52_000L else 0L) }
    var resumedAt by rememberSaveable { mutableStateOf<Long?>(if (inspection) null else clock.millis()) }
    var now by remember { mutableLongStateOf(clock.millis()) }
    LaunchedEffect(resumedAt) { while (resumedAt != null) { now = clock.millis(); delay(200) } }
    val elapsedMs = accumulated + (resumedAt?.let { now - it } ?: 0)
    val p = routineProgressAt(routine, elapsedMs / 1000)
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    BackHandler { onQuit() }

    val context = LocalContext.current
    val coach = remember { if (inspection) null else VoiceCoach(context) }
    DisposableEffect(Unit) { onDispose { coach?.shutdown() } }
    val key = "${p.index}-${p.resting}"
    LaunchedEffect(key) {
        if (!voiceEnabled || coach == null) return@LaunchedEffect
        when {
            p.index == null -> coach.say("Routine complete. Great work!")
            p.resting -> coach.say("Rest. Next: ${ExerciseLibrary.byId(routine.steps[p.index].exerciseId).name}")
            else -> coach.say(ExerciseLibrary.byId(routine.steps[p.index].exerciseId).name)
        }
    }
    LaunchedEffect(p.index) { if (p.index == null && !inspection) onFinish(routine.totalSeconds.toLong()) }

    val step = p.index?.let { routine.steps[it] }
    val ex = step?.let { ExerciseLibrary.byId(it.exerciseId) }
    val c = if (p.resting) fc.workout else ex?.area?.tint() ?: fc.push
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().background(c.copy(alpha = 0.10f)).safeDrawingPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(routine.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleVoice) {
                    Icon(if (voiceEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, if (voiceEnabled) "Mute" else "Voice on")
                }
                TextButton(onClick = { if (elapsedMs >= 60_000) onFinish(elapsedMs / 1000) else onQuit() }) {
                    Text(if (elapsedMs >= 60_000) "End & save" else "Quit")
                }
            }
            LinearMeter(1f - p.totalLeft / routine.totalSeconds.toFloat(), c, Modifier.padding(vertical = 8.dp))
            Text("${if (p.resting) "Next: " else ""}${(p.index ?: (routine.steps.size - 1)) + 1} of ${routine.steps.size}",
                style = MaterialTheme.typography.labelMedium, color = fc.muted)
            Spacer(Modifier.height(8.dp))
            if (ex != null) {
                Text(if (p.resting) "REST · up next" else "", style = MaterialTheme.typography.labelLarge, color = c)
                Text(ex.name, style = MaterialTheme.typography.headlineMedium)
                Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    ExerciseAnimation(ex, c, Modifier.fillMaxSize(), paused = resumedAt == null)
                }
                Text("${p.secondsLeft}", fontSize = 72.sp, fontWeight = FontWeight.Black, color = c)
                Text(ex.cues.first(), style = MaterialTheme.typography.bodyMedium, color = fc.muted)
            } else {
                Spacer(Modifier.weight(1f))
                Text("Done! 🎉", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = {
                    if (resumedAt == null) { resumedAt = clock.millis(); now = clock.millis() }
                    else { accumulated += clock.millis() - resumedAt!!; resumedAt = null }
                }, modifier = Modifier.size(72.dp)) {
                    Icon(if (resumedAt == null) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (resumedAt == null) "Resume" else "Pause", Modifier.size(36.dp))
                }
                IconButton(onClick = { accumulated += p.secondsLeft * 1000L }) { Icon(Icons.Rounded.SkipNext, "Skip") }
            }
        }
    }
}

/** Train-tab entry to the library. */
@Composable
fun LibraryCard(onOpen: () -> Unit, onStart: (Routine) -> Unit) {
    val fc = Fit.colors
    AppCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dorm workouts", style = MaterialTheme.typography.titleMedium)
                Text("${Routines.all.size} guided routines, no equipment — quiet options for hostels",
                    style = MaterialTheme.typography.bodySmall, color = fc.muted)
            }
            Button(onClick = onOpen) { Text("Browse") }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Routines.all.take(5), key = { it.id }) { r ->
                Surface(onClick = { onStart(r) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.padding(12.dp).width(120.dp)) {
                        ExerciseAnimation(ExerciseLibrary.byId(r.steps.first().exerciseId), r.type.color(fc), Modifier.fillMaxWidth().height(60.dp))
                        Text(r.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Text("${r.minutes} min", style = MaterialTheme.typography.labelSmall, color = fc.muted)
                    }
                }
            }
        }
    }
}
