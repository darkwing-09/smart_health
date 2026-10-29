package com.fitagent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fitagent.domain.IntervalConfig
import com.fitagent.domain.Workout
import com.fitagent.domain.WorkoutType
import com.fitagent.domain.daysLeftInWeek
import com.fitagent.ui.HomeState
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.components.SectionTitle
import com.fitagent.ui.components.WorkoutBadge
import com.fitagent.ui.theme.Fit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val QUICK_START = listOf(
    WorkoutType.WALK, WorkoutType.RUN, WorkoutType.CYCLE, WorkoutType.STRENGTH, WorkoutType.YOGA,
    WorkoutType.SWIM, WorkoutType.SPORTS, WorkoutType.DANCE, WorkoutType.OTHER,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainScreen(
    state: HomeState,
    contentPadding: PaddingValues,
    onStart: (WorkoutType, IntervalConfig?) -> Unit,
    onLog: (WorkoutType, Int, Int) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Workout?>(null) }
    val zone = ZoneId.systemDefault()
    val history = state.workouts.take(60).groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp, contentPadding.calculateTopPadding() + 8.dp, 16.dp,
            contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Train", style = MaterialTheme.typography.headlineLarge) }
        item { WeeklyGoalCard(state) }
        item { SectionTitle("Interval training") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IntervalPreset("Tabata", "20s / 10s × 8", "4 min", IntervalConfig.TABATA, Modifier.weight(1f), onStart)
                IntervalPreset("Classic", "40s / 20s × 10", "10 min", IntervalConfig.CLASSIC, Modifier.weight(1f), onStart)
                IntervalPreset("Endurance", "60s / 30s × 8", "12 min", IntervalConfig.ENDURANCE, Modifier.weight(1f), onStart)
            }
        }
        item { SectionTitle("Quick start") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 3) {
                QUICK_START.forEach { type ->
                    Surface(
                        onClick = { onStart(type, null) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(vertical = 16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            WorkoutBadge(type, 40.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(type.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { showLog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Log a workout you already did")
            }
        }
        item { SectionTitle("History") }
        if (history.isEmpty()) {
            item {
                Text("No workouts yet. Start one above — even 10 minutes counts.",
                    style = MaterialTheme.typography.bodyMedium, color = Fit.colors.muted)
            }
        }
        history.forEach { (date, list) ->
            item(key = "h$date") {
                Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.labelMedium,
                    color = Fit.colors.muted, modifier = Modifier.padding(top = 4.dp))
            }
            list.forEach { w ->
                item(key = w.id) {
                    WorkoutRow(w) {
                        IconButton(onClick = { pendingDelete = w }) {
                            Icon(Icons.Rounded.DeleteOutline, "Delete", tint = Fit.colors.muted)
                        }
                    }
                }
            }
        }
    }

    if (showLog) LogWorkoutDialog(onDismiss = { showLog = false }, onSave = { t, m, d -> onLog(t, m, d); showLog = false })
    pendingDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this workout?") },
            text = { Text("${w.type.label}, ${w.minutes} min. This also removes it from Health Connect and your cloud backup.") },
            confirmButton = { TextButton(onClick = { onDelete(w.id); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WeeklyGoalCard(state: HomeState) {
    val goal = state.profile.goals.weeklyWorkouts
    val done = state.weekWorkouts
    val left = daysLeftInWeek(state.today)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("WEEKLY GOAL", style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted)
                Text("$done of $goal workouts", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        done >= goal -> "Goal smashed. Anything more is a bonus!"
                        goal - done > left -> "${goal - done} to go with $left day${if (left == 1) "" else "s"} left — every session counts."
                        else -> "${goal - done} to go · $left day${if (left == 1) "" else "s"} left this week"
                    },
                    style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(maxOf(goal, done)) { i ->
                Box(Modifier.weight(1f).height(10.dp).clip(CircleShape)
                    .then(Modifier.padding(0.dp))) {
                    Surface(color = if (i < done) Fit.colors.workout else Fit.colors.track, modifier = Modifier.fillMaxWidth().height(10.dp)) {}
                }
            }
        }
    }
}

@Composable
private fun IntervalPreset(
    name: String, spec: String, total: String, config: IntervalConfig, modifier: Modifier,
    onStart: (WorkoutType, IntervalConfig?) -> Unit,
) {
    Surface(onClick = { onStart(WorkoutType.HIIT, config) }, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Icon(Icons.Rounded.Bolt, null, tint = Fit.colors.move)
            Spacer(Modifier.height(8.dp))
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(spec, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
            Text(total, style = MaterialTheme.typography.labelMedium, color = Fit.colors.move)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogWorkoutDialog(onDismiss: () -> Unit, onSave: (WorkoutType, Int, Int) -> Unit) {
    var type by remember { mutableStateOf(WorkoutType.WALK) }
    var minutes by remember { mutableFloatStateOf(30f) }
    var daysAgo by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a workout") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (QUICK_START + WorkoutType.HIIT).distinct().forEach {
                        FilterChip(selected = type == it, onClick = { type = it }, label = { Text(it.label) })
                    }
                }
                Text("Duration: ${minutes.toInt()} min", style = MaterialTheme.typography.titleSmall)
                Slider(value = minutes, onValueChange = { minutes = it }, valueRange = 5f..180f, steps = 34)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = daysAgo == 0, onClick = { daysAgo = 0 }, label = { Text("Today") })
                    FilterChip(selected = daysAgo == 1, onClick = { daysAgo = 1 }, label = { Text("Yesterday") })
                    FilterChip(selected = daysAgo == 2, onClick = { daysAgo = 2 }, label = { Text("2 days ago") })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(type, minutes.toInt(), daysAgo) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
