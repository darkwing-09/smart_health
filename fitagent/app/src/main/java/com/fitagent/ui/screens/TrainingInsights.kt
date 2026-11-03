package com.fitagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitagent.domain.WHO_WEEKLY_MINUTES
import com.fitagent.domain.WorkoutType
import com.fitagent.domain.whoHeadline
import com.fitagent.domain.whoMinutesFromWorkouts
import com.fitagent.ui.HomeState
import com.fitagent.ui.charts.BulletBar
import com.fitagent.ui.charts.CalendarHeatmap
import com.fitagent.ui.charts.Donut
import com.fitagent.ui.charts.PolarClock
import com.fitagent.ui.theme.Fit
import com.fitagent.ui.theme.color
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** Train tab charts: WHO minutes, workout mix, when you train, and the goal-day calendar. */
@Composable
fun TrainingInsights(state: HomeState) {
    val fc = Fit.colors
    val zone = ZoneId.systemDefault()
    val monday = state.today.with(DayOfWeek.MONDAY)
    fun dateOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val thisWeek = state.workouts.filter { !dateOf(it.startMs).isBefore(monday) }
    val month = state.workouts.filter { !dateOf(it.startMs).isBefore(state.today.minusDays(29)) }
    val who = whoMinutesFromWorkouts(thisWeek)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChartCard("Active minutes", whoHeadline(who)) {
            BulletBar(who.toFloat(), WHO_WEEKLY_MINUTES.toFloat(), fc.push, description = "$who of $WHO_WEEKLY_MINUTES WHO minutes this week")
            Text("$who / $WHO_WEEKLY_MINUTES this week · hard sessions count double", style = MaterialTheme.typography.labelSmall,
                color = fc.muted, modifier = Modifier.padding(top = 4.dp))
        }
        if (month.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val byType = month.groupBy { it.type }.mapValues { (_, l) -> l.sumOf { it.minutes } }.entries.sortedByDescending { it.value }
                ChartCard("Mix · 30 days", null, Modifier.weight(1f)) {
                    Donut(byType.map { it.value.toFloat() to it.key.color(fc) }, Modifier.size(110.dp).align(Alignment.CenterHorizontally),
                        thickness = 14.dp, description = byType.joinToString { "${it.key.label} ${it.value} min" }) {
                        Text("${month.size}", style = MaterialTheme.typography.headlineSmall)
                    }
                    Spacer(Modifier.padding(top = 8.dp))
                    byType.take(3).forEach { (type, min) -> TypeRow(type, min) }
                }
                val hours = MutableList(24) { 0f }
                month.forEach { w -> hours[Instant.ofEpochMilli(w.startMs).atZone(zone).hour] += w.minutes.toFloat() }
                val peak = hours.indices.maxBy { hours[it] }
                ChartCard("When you train", "Mostly around %02d:00".format(peak), Modifier.weight(1f)) {
                    PolarClock(hours, fc.workout, Modifier.fillMaxWidth().size(130.dp), description = "Workout minutes by hour of day")
                }
            }
        }
        val goals = state.profile.goals
        val byDate = state.history.associateBy { it.date }
        val from = state.today.minusWeeks(11).with(DayOfWeek.MONDAY)
        val met = state.history.count { !it.date.isBefore(from) && it.goalMet(goals) }
        ChartCard("Goal days", "$met goal days in the last 12 weeks · darker = closer to both goals") {
            CalendarHeatmap(
                from, state.today,
                intensity = { d ->
                    byDate[d]?.let { day ->
                        if (!day.hasData) null
                        else ((day.steps / goals.dailySteps.toFloat()).coerceAtMost(1f) + (day.workoutMinutes / goals.dailyWorkoutMinutes.toFloat()).coerceAtMost(1f)) / 2f
                    }
                },
                color = fc.steps,
                description = "$met goal days in the last 12 weeks",
            )
        }
    }
}

@Composable
private fun TypeRow(type: WorkoutType, minutes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val c = type.color(Fit.colors)
        androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(c) }
        Spacer(Modifier.width(6.dp))
        Text(type.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("$minutes m", style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted)
    }
}
