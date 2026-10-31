package com.fitagent.ui.screens

import androidx.compose.animation.AnimatedVisibility
import com.fitagent.ui.LocalClock
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
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.ui.unit.dp
import com.fitagent.data.health.HealthAvailability
import com.fitagent.domain.Band
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
import com.fitagent.ui.components.StatTile
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
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(state, onRefresh) }
        if (!state.health.connected) item { ConnectHealthCard(state, onConnectHealth) }
        item { ReadinessCard(state) }
        item { RingsCard(state) }
        item { CoachCard(state, onStart) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val t = state.todayActivity
                StatTile(Icons.Rounded.Straighten, "Distance", formatKm(t.distanceMeters), "km", Fit.colors.steps, Modifier.weight(1f))
                StatTile(Icons.Rounded.Favorite, "Resting HR", t.restingHr?.toString() ?: "—", if (t.restingHr != null) "bpm" else "",
                    Fit.colors.heart, Modifier.weight(1f))
                StatTile(Icons.Rounded.Bedtime, "Sleep", t.sleepMinutes?.let { formatHours(it) } ?: "—", "",
                    Fit.colors.sleep, Modifier.weight(1f))
            }
        }
        item { WeekStrip(state) }
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
private fun Header(state: HomeState, onRefresh: () -> Unit) {
    val clock = LocalClock.current
    val hour = remember(clock) { LocalTime.now(clock).hour }
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.today.format(DateTimeFormatter.ofPattern("EEEE, d MMM")).uppercase(),
                style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted)
            Text(if (state.profile.name.isBlank()) greeting else "$greeting, ${state.profile.name.substringBefore(' ')}",
                style = MaterialTheme.typography.headlineMedium)
        }
        if (state.streak.current > 0) {
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
private fun ConnectHealthCard(state: HomeState, onConnect: () -> Unit) {
    AppCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Watch, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Track steps automatically", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    when (state.health.availability) {
                        HealthAvailability.AVAILABLE -> "Connect Health Connect to bring in steps, sleep and heart rate from your phone or watch."
                        HealthAvailability.UPDATE_REQUIRED -> "Health Connect needs an update before FitAgent can read your activity."
                        HealthAvailability.NOT_INSTALLED -> "Install Health Connect to sync steps, sleep and heart rate from your phone or watch."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.health.availability == HealthAvailability.AVAILABLE) "Connect" else "Get Health Connect")
        }
    }
}

@Composable
private fun ReadinessCard(state: HomeState) {
    val r = state.readiness
    val color = Fit.colors.band(r.band)
    var expanded by rememberSaveable { mutableStateOf(false) }
    AppCard(Modifier.clickable { expanded = !expanded }) {
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
                Text(
                    when (r.band) {
                        Band.PUSH -> "Your body is recovered. Today is a good day for a harder session."
                        Band.STEADY -> "Moderate effort today. Keep moving, but don't go all-out."
                        Band.RECOVER -> "Recovery is low. Prioritise sleep, hydration and gentle movement."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (expanded) "Hide details" else "Why?", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val names = mapOf("sleep" to "Sleep", "resting_hr" to "Resting heart rate", "load" to "Training load")
                r.components.forEach { (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(names[k] ?: k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(130.dp))
                        com.fitagent.ui.components.LinearMeter(v / 100f, color, Modifier.weight(1f), 6.dp)
                        Text("$v", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp).padding(start = 8.dp))
                    }
                }
                r.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted) }
                if (r.components.size == 1) {
                    Text("Wear a watch to bed to add sleep and resting heart rate to your score.",
                        style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
                }
            }
        }
    }
}

@Composable
private fun RingsCard(state: HomeState) {
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
