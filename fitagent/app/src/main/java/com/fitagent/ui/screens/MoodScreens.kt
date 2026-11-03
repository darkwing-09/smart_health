package com.fitagent.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitagent.domain.BreathPattern
import com.fitagent.domain.HrBucket
import com.fitagent.domain.LOW_MOOD_SUPPORT_DAYS
import com.fitagent.domain.MOOD_EMOJI
import com.fitagent.domain.MOOD_LABELS
import com.fitagent.domain.MOOD_TAGS
import com.fitagent.domain.MoodEntry
import com.fitagent.domain.breathPhase
import com.fitagent.domain.lowMoodStreak
import com.fitagent.domain.stressHeadline
import com.fitagent.domain.stressTimeline
import com.fitagent.ui.HomeState
import com.fitagent.ui.LocalClock
import com.fitagent.ui.charts.CalendarHeatmap
import com.fitagent.ui.charts.LineChart
import com.fitagent.ui.charts.LineSeries
import com.fitagent.ui.charts.Scatter
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.theme.Fit
import kotlinx.coroutines.delay
import java.time.ZoneId
import kotlin.math.roundToInt

/** Today-tab check-in: one tap, optional tags. Shows a compact summary once done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodCheckInCard(today: MoodEntry?, onSave: (Int, Set<String>) -> Unit, onOpen: () -> Unit) {
    val fc = Fit.colors
    var picked by remember(today) { mutableStateOf(today?.score) }
    var tags by remember(today) { mutableStateOf(today?.tags.orEmpty()) }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (today == null) "How are you feeling?" else "Feeling ${MOOD_LABELS[today.score - 1].lowercase()} today",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Row(Modifier.clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                Text("Stress & mood", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            MOOD_EMOJI.forEachIndexed { i, e ->
                val score = i + 1
                val selected = picked == score
                Surface(
                    onClick = { picked = score; onSave(score, tags) },
                    shape = CircleShape,
                    color = if (selected) fc.mood[i].copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(52.dp),
                ) { Box(contentAlignment = Alignment.Center) { Text(e, fontSize = 24.sp) } }
            }
        }
        if (picked != null) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MOOD_TAGS.forEach { t ->
                    FilterChip(t in tags, {
                        tags = if (t in tags) tags - t else tags + t
                        onSave(picked!!, tags)
                    }, label = { Text(t) })
                }
            }
        }
    }
}

@Composable
fun MoodScreen(
    state: HomeState,
    moods: List<MoodEntry>,
    heartToday: List<HrBucket>,
    hourly: List<Long>,
    onBreathe: () -> Unit,
    onBack: () -> Unit,
) {
    val fc = Fit.colors
    val zone = ZoneId.systemDefault()
    val byDate = moods.associateBy { it.date }
    val lowStreak = lowMoodStreak(moods, state.today)
    DetailScaffold("Stress & mood", "Private to this phone", onBack) {
        if (lowStreak >= LOW_MOOD_SUPPORT_DAYS) {
            item {
                AppCard(color = fc.sleep.copy(alpha = 0.14f)) {
                    Text("You've had a tough few days", style = MaterialTheme.typography.titleMedium)
                    Text("Talking to someone helps. Tele-MANAS (free, 24×7, India): 14416 or 1-800-891-4416 · iCall: 9152987821 · " +
                        "your campus counselling centre. In an emergency call 112.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            ChartCard("Need a reset?", "Slow breathing lowers heart rate within minutes.") {
                Button(onClick = onBreathe, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Air, null); Spacer(Modifier.width(6.dp)); Text("Start a breathing break")
                }
            }
        }
        item {
            val stress = stressTimeline(heartToday, state.todayActivity.restingHr ?: state.history.dropLast(1).lastOrNull { it.restingHr != null }?.restingHr,
                hourly, zone)
            val start = state.today.atStartOfDay(zone).toInstant().toEpochMilli()
            val series = MutableList<Float?>(24 * 6) { null }
            heartToday.forEachIndexed { i, b -> val slot = ((b.startMs - start) / 600_000).toInt(); if (slot in series.indices) series[slot] = stress[i]?.toFloat() }
            ChartCard("Stress estimate today", stressHeadline(stress)) {
                LineChart(
                    listOf(LineSeries(series, fc.steady, fill = true, width = 2.dp)),
                    yMin = 0f, yMax = 100f, height = 120.dp,
                    xLabels = listOf("00", "", "06", "", "12", "", "18", ""),
                    scrubLabel = { i -> "%02d:%02d · %s".format(i / 6, i % 6 * 10, series[i]?.roundToInt()?.toString() ?: "moving / no data") },
                    description = "Stress estimate from heart rate while still",
                )
                Text("From heart rate above resting while you're still. Movement is excluded. An estimate, not a medical measure.",
                    style = MaterialTheme.typography.labelSmall, color = fc.muted)
            }
        }
        item {
            val from = state.today.minusWeeks(11).with(java.time.DayOfWeek.MONDAY)
            val logged = moods.count { !it.date.isBefore(from) }
            ChartCard("Mood calendar", if (logged == 0) "Check in on Today to start your mood calendar." else "$logged check-ins in the last 12 weeks") {
                CalendarHeatmap(from, state.today, intensity = { d -> byDate[d]?.score?.let { it / 5f } }, color = fc.push,
                    colorFor = { d, _ -> fc.mood[(byDate.getValue(d).score - 1).coerceIn(0, 4)] },
                    description = "Mood per day over 12 weeks")
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MOOD_EMOJI.forEachIndexed { i, e -> Text("$e", color = fc.mood[i], style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        item {
            val sleepByDate = state.history.associate { it.date to it.sleepMinutes }
            val points = moods.mapNotNull { m -> sleepByDate[m.date]?.let { it / 60f to m.score.toFloat() } }
            ChartCard("Mood vs sleep", if (points.size < 7) "After a week of check-ins you'll see how sleep and mood move together." else "Each dot is a day: hours slept → mood") {
                Scatter(points, fc.sleep, xLabel = "hours slept →", yLabel = "mood", description = "Mood against hours slept")
            }
        }
        item {
            val counts = moods.filter { !it.date.isBefore(state.today.minusDays(29)) }.flatMap { it.tags }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
            if (counts.isNotEmpty()) {
                ChartCard("What's on your mind", "Most tagged in the last 30 days") {
                    com.fitagent.ui.charts.RaceBars(counts.take(5).map { com.fitagent.ui.charts.RaceEntry(it.key, it.value.toFloat(), "${it.value}×") }, fc.sleep)
                }
            }
        }
    }
}

/** Guided breathing: an expanding/contracting circle, phase text and a gentle haptic on each phase change. */
@Composable
fun BreatheScreen(onBack: () -> Unit) {
    val fc = Fit.colors
    var pattern by remember { mutableStateOf(BreathPattern.BOX) }
    var minutes by remember { mutableStateOf(3) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    val clock = LocalClock.current
    val inspection = LocalInspectionMode.current
    var now by remember { mutableLongStateOf(clock.millis()) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(startedAt) { while (startedAt != null && !inspection) { now = clock.millis(); delay(50) } }
    val elapsed = startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0L
    val done = startedAt != null && elapsed >= minutes * 60_000L
    val (label, progress, phaseIndex) = breathPhase(pattern, elapsed)
    var lastPhase by remember { mutableStateOf(-1) }
    LaunchedEffect(phaseIndex, startedAt) {
        if (startedAt != null && phaseIndex != lastPhase) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); lastPhase = phaseIndex }
    }
    // Circle grows while breathing in, stays during holds, shrinks while breathing out.
    val target = when {
        startedAt == null || done -> 0.55f
        label.startsWith("Breathe in") -> 0.55f + 0.45f * progress
        label.startsWith("Breathe out") -> 1f - 0.45f * progress
        else -> if (pattern.phases.getOrNull(phaseIndex - 1)?.first?.startsWith("Breathe in") == true) 1f else 0.55f
    }
    val scale by animateFloatAsState(target, tween(120, easing = FastOutSlowInEasing), label = "breath")

    DetailScaffold("Breathe", "${pattern.label} · ${pattern.description}", onBack) {
        item {
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                val c = fc.workout
                Canvas(Modifier.size(280.dp)) {
                    drawCircle(c.copy(alpha = 0.10f), radius = size.minDimension / 2)
                    drawCircle(c.copy(alpha = 0.35f), radius = size.minDimension / 2 * scale)
                    drawCircle(c, radius = size.minDimension / 2 * scale * 0.35f)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when { done -> "Well done"; startedAt == null -> "Ready"; else -> label },
                        style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    if (startedAt != null && !done) {
                        val left = (minutes * 60_000L - elapsed) / 1000
                        Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.titleMedium, color = fc.muted)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BreathPattern.entries.forEach { p -> FilterChip(p == pattern, { if (startedAt == null) pattern = p }, label = { Text(p.label) }) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(1, 3, 5).forEach { m -> FilterChip(m == minutes, { if (startedAt == null) minutes = m }, label = { Text("$m min") }) }
                Spacer(Modifier.weight(1f))
                if (startedAt == null || done) Button(onClick = { startedAt = clock.millis(); now = clock.millis() }) { Text(if (done) "Again" else "Start") }
                else OutlinedButton(onClick = { startedAt = null }) { Text("Stop") }
            }
        }
        item {
            Text("Breathe through your nose, let your belly rise, and keep shoulders relaxed. Stop if you feel dizzy.",
                style = MaterialTheme.typography.bodySmall, color = fc.muted)
        }
    }
}

