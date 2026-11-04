package com.fitagent.ui.screens

import com.fitagent.ui.LocalClock
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fitagent.R
import com.fitagent.data.health.HealthAvailability
import com.fitagent.domain.MoodEntry
import com.fitagent.domain.SleepSegment
import com.fitagent.domain.activeHourHeadline
import com.fitagent.domain.bandFor
import com.fitagent.domain.readinessHeadline
import com.fitagent.domain.sleepHeadline
import com.fitagent.ui.charts.Bars
import com.fitagent.ui.charts.HorizontalStackBar
import com.fitagent.ui.charts.Legend
import com.fitagent.domain.DayActivity
import com.fitagent.domain.WorkoutType
import com.fitagent.domain.formatCount
import com.fitagent.domain.formatHours
import com.fitagent.domain.formatKm
import com.fitagent.ui.HomeState
import com.fitagent.ui.components.ActivityRings
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.components.Pill
import com.fitagent.ui.components.ReadinessGauge
import com.fitagent.ui.components.Ring
import com.fitagent.ui.components.SectionTitle
import com.fitagent.ui.components.WorkoutBadge
import com.fitagent.ui.theme.Fit
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun TodayScreen(
    state: HomeState,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onConnectHealth: () -> Unit,
    onStart: (WorkoutType) -> Unit,
    hourly: List<Long> = List(24) { 0L },
    lastNight: List<SleepSegment> = emptyList(),
    onOpen: (Detail) -> Unit = {},
    todayMood: MoodEntry? = null,
    onSaveMood: (Int, Set<String>) -> Unit = { _, _ -> },
    foods: List<com.fitagent.domain.FoodEntry> = emptyList(),
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(state, onRefresh, onOpen) }
        val watch = state.watch
        if (watch == com.fitagent.data.WatchState.None) {
            // No watch, no numbers: only the setup checklist plus things you enter yourself.
            item { WatchSetupCard(state.health, watch, onConnectHealth, onRefresh) { onOpen(Detail.DEVICES) } }
            item { SectionTitle("While you set up") }
            item { MoodCheckInCard(todayMood, onSaveMood) { onOpen(Detail.MOOD) } }
            item { FoodTodayCard(foods, state.today, state.profile.weightKg) { onOpen(Detail.FOOD) } }
        } else {
            if (watch is com.fitagent.data.WatchState.Stale) item { StaleWatchBanner(watch, onRefresh) }
            item { if (state.hasReadiness) ReadinessCard(state) { onOpen(Detail.READINESS) } else ReadinessPendingCard() }
            item { RingsCard(state, hourly) }
            item { CoachCard(state, onStart) }
            item { SectionTitle("Body") }
            item { Tiles(state, onOpen) }
            item { SleepCard(state, lastNight) { onOpen(Detail.SLEEP) } }
            item { SectionTitle("You") }
            item { MoodCheckInCard(todayMood, onSaveMood) { onOpen(Detail.MOOD) } }
            item { FoodTodayCard(foods, state.today, state.profile.weightKg) { onOpen(Detail.FOOD) } }
            item { WeekStrip(state) }
        }
        val todays = state.workouts.filter {
            java.time.Instant.ofEpochMilli(it.startMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == state.today
        }
        if (todays.isNotEmpty()) {
            item { SectionTitle("Today's workouts") }
            todays.forEach { w -> item(key = w.id) { WorkoutRow(w) } }
        }
    }
}

@Composable
private fun Header(state: HomeState, onRefresh: () -> Unit, onOpen: (Detail) -> Unit) {
    val clock = LocalClock.current
    val hour = remember(clock) { LocalTime.now(clock).hour }
    val greeting = stringResource(when (hour) { in 5..11 -> R.string.greeting_morning; in 12..16 -> R.string.greeting_afternoon; in 17..23 -> R.string.greeting_evening; else -> R.string.greeting_late })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.today.format(DateTimeFormatter.ofPattern("EEEE, d MMM")).uppercase(),
                style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted)
            Text(if (state.profile.name.isBlank()) greeting else "$greeting, ${state.profile.name.substringBefore(' ')}",
                style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            WatchChip(state.watch) { onOpen(Detail.DEVICES) }
        }
        if (state.watch.hasWatch && state.streak.current > 0) {
            Pill("${state.streak.current}", if (state.streak.todayDone) Fit.colors.move else Fit.colors.muted,
                icon = Icons.Rounded.LocalFireDepartment)
        }
        IconButton(onClick = onRefresh, enabled = !state.refreshing) {
            if (state.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Refresh, "Refresh")
        }
    }
}

@Composable
private fun ReadinessCard(state: HomeState, onOpen: () -> Unit) {
    val r = state.readiness
    val color = Fit.colors.band(r.band)
    val base = MaterialTheme.colorScheme.surfaceContainer
    AppCard(
        Modifier.clip(MaterialTheme.shapes.large)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(color.copy(alpha = 0.18f), base, base)))
            .clickable(onClick = onOpen),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadinessGauge(r.score, color, Modifier.size(116.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${r.score}", style = MaterialTheme.typography.displayMedium, color = color)
                    Text("READINESS", style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Pill(r.band.label, color)
                Spacer(Modifier.height(8.dp))
                Text(readinessHeadline(r), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("See why", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
        if (r.components.size > 1) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                r.components.entries.take(4).forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        com.fitagent.ui.components.LinearMeter(v / 100f, Fit.colors.band(bandFor(v)), height = 6.dp)
                        Text(COMPONENT_SHORT_NAMES[k] ?: k, style = MaterialTheme.typography.labelSmall,
                            color = Fit.colors.muted, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Tiles(state: HomeState, onOpen: (Detail) -> Unit) {
    val t = state.todayActivity
    val last14 = state.history.takeLast(14)
    val fc = Fit.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SparkTile(Icons.Rounded.Straighten, "Distance", formatKm(t.distanceMeters), "km", fc.steps,
                last14.map { (it.distanceMeters / 1000).toFloat() }, Modifier.weight(1f))
            SparkTile(Icons.Rounded.Favorite, "Resting HR", t.restingHr?.toString() ?: "—", if (t.restingHr != null) "bpm" else "",
                fc.heart, last14.map { it.restingHr?.toFloat() }, Modifier.weight(1f)) { onOpen(Detail.HEART) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val hrv = t.night.hrvMs
            SparkTile(Icons.Rounded.MonitorHeart, "HRV", hrv?.toInt()?.toString() ?: "—", if (hrv != null) "ms" else "",
                fc.hrv, last14.map { it.night.hrvMs?.toFloat() }, Modifier.weight(1f)) { onOpen(Detail.HEART) }
            SparkTile(Icons.Rounded.Stairs, "Floors", t.floors?.toString() ?: "—", "", fc.move,
                last14.map { it.floors?.toFloat() }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SleepCard(state: HomeState, segments: List<SleepSegment>, onOpen: () -> Unit) {
    val t = state.todayActivity
    val fc = Fit.colors
    val previous = state.history.dropLast(1).takeLast(14).mapNotNull { it.sleepMinutes }
    AppCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bedtime, null, tint = fc.sleep, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sleep", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(t.sleepMinutes?.let { formatHours(it) } ?: "—", style = MaterialTheme.typography.titleLarge)
        }
        Text(sleepHeadline(t.sleepMinutes, previous), style = MaterialTheme.typography.bodySmall, color = fc.muted)
        val stages = t.sleepStages
        if (stages != null) {
            Spacer(Modifier.height(12.dp))
            HorizontalStackBar(listOf(stages.deep.toFloat() to fc.stageDeep, stages.light.toFloat() to fc.stageLight,
                stages.rem.toFloat() to fc.stageRem, stages.awake.toFloat() to fc.stageAwake))
            Legend(listOf("Deep ${stages.deep}m" to fc.stageDeep, "Light ${stages.light}m" to fc.stageLight,
                "REM ${stages.rem}m" to fc.stageRem, "Awake ${stages.awake}m" to fc.stageAwake))
        } else if (segments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Your watch reports sleep time but not stages.", style = MaterialTheme.typography.bodySmall, color = fc.muted)
        }
    }
}

@Composable
private fun RingsCard(state: HomeState, hourly: List<Long>) {
    val t = state.todayActivity
    val g = state.profile.goals
    val fc = Fit.colors
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActivityRings(
                listOf(
                    Ring(t.steps / g.dailySteps.toFloat(), fc.steps),
                    Ring(t.activeKcal / g.dailyActiveKcal.toFloat(), fc.move),
                    Ring(t.workoutMinutes / g.dailyWorkoutMinutes.toFloat(), fc.workout),
                ),
                Modifier.size(132.dp), stroke = 14.dp, gap = 4.dp,
            )
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RingLegend("Steps", formatCount(t.steps), "/ ${formatCount(g.dailySteps)}", fc.steps)
                RingLegend("Move" + if (t.kcalEstimated) " (est.)" else "", formatCount(t.activeKcal), "/ ${g.dailyActiveKcal} kcal", fc.move)
                RingLegend("Workout", "${t.workoutMinutes}", "/ ${g.dailyWorkoutMinutes} min", fc.workout)
            }
        }
        if (hourly.any { it > 0 }) {
            Spacer(Modifier.height(16.dp))
            Text(activeHourHeadline(hourly), style = MaterialTheme.typography.bodySmall, color = fc.muted)
            Spacer(Modifier.height(8.dp))
            val clock = LocalClock.current
            val now = remember(clock) { LocalTime.now(clock).hour }
            Bars(hourly.map { it.toFloat() }, fc.steps, height = 64.dp, highlight = setOf(now), maxBarWidth = 8.dp,
                labels = (0 until 24).map { if (it % 6 == 0) "%02d".format(it) else "" },
                scrubLabel = { "%02d:00 · %s steps".format(it, formatCount(hourly[it])) },
                description = "Steps per hour today")
        }
    }
}

@Composable
private fun RingLegend(label: String, value: String, goal: String, color: Color) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(4.dp))
            Text(goal, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
private fun CoachCard(state: HomeState, onStart: (WorkoutType) -> Unit) {
    val s = state.suggestion
    AppCard(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text("TODAY'S COACH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(s.headline, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(s.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.type != null) {
            Spacer(Modifier.height(14.dp))
            Button(onClick = { onStart(s.type) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Start ${s.type.label.lowercase()} · ${s.minutes} min", fontWeight = FontWeight.Bold)
            }
        }
        if (s.stepsRemaining > 0) {
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = { onStart(WorkoutType.WALK) }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors()) {
                Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("${formatCount(s.stepsRemaining)} steps to go · ~${s.walkMinutes} min walk")
            }
        }
    }
}

@Composable
private fun WeekStrip(state: HomeState) {
    val week = state.days.takeLast(7)
    val goals = state.profile.goals
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("This week", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${week.count { it.goalMet(goals) }}/7 goal days", style = MaterialTheme.typography.labelMedium,
                color = Fit.colors.muted)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            week.forEach { DayDot(it, goals, it.date == state.today) }
        }
    }
}

@Composable
private fun DayDot(day: DayActivity, goals: com.fitagent.domain.Goals, isToday: Boolean) {
    val fc = Fit.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            ActivityRings(
                listOf(Ring(day.steps / goals.dailySteps.toFloat(), fc.steps),
                    Ring(day.workoutMinutes / goals.dailyWorkoutMinutes.toFloat(), fc.workout)),
                Modifier.size(36.dp), stroke = 4.dp, gap = 2.dp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium,
            color = if (isToday) MaterialTheme.colorScheme.primary else fc.muted)
    }
}

@Composable
fun WorkoutRow(w: com.fitagent.domain.Workout, trailing: @Composable (() -> Unit)? = null) {
    AppCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WorkoutBadge(w.type)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(w.title ?: w.type.label, style = MaterialTheme.typography.titleMedium)
                val time = java.time.Instant.ofEpochMilli(w.startMs).atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm"))
                Text("$time${if (w.source == com.fitagent.domain.WorkoutSource.HEALTH_CONNECT) " · synced" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${w.minutes} min", style = MaterialTheme.typography.titleMedium)
                Text("${w.kcal.toInt()} kcal", style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
            }
            trailing?.invoke()
        }
    }
}
