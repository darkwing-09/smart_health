package com.fitagent.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.fitagent.container
import com.fitagent.domain.Band
import com.fitagent.domain.computeReadiness
import com.fitagent.domain.formatCount
import com.fitagent.domain.suggestToday
import com.fitagent.ui.MainActivity
import java.time.LocalDate
import java.time.ZoneId

/** What the widget shows; computed from the same on-phone data as the Today screen. */
data class WidgetData(
    val score: Int,
    val band: Band,
    val steps: Long,
    val stepGoal: Int,
    val kcal: Int,
    val kcalGoal: Int,
    val workoutMin: Int,
    val workoutGoal: Int,
    val coachLine: String,
)

/** Home-screen widget (X1): readiness + rings + today's coach line, without opening the app. */
class FitAgentWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = load(context)
        provideContent { GlanceTheme { Content(data) } }
    }

    private suspend fun load(context: Context): WidgetData? = runCatching {
        val c = context.container
        val (days, workouts) = c.fitness.snapshot()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val t = days.lastOrNull { it.date == today } ?: return@runCatching null
        val goals = c.profiles.profile.value.goals
        val r = computeReadiness(days, workouts, today, zone)
        WidgetData(r.score, r.band, t.steps, goals.dailySteps, t.activeKcal, goals.dailyActiveKcal, t.workoutMinutes,
            goals.dailyWorkoutMinutes, suggestToday(t, r, workouts, goals, today, zone).headline)
    }.getOrNull()

    @Composable
    private fun Content(d: WidgetData?) {
        val small = LocalSize.current.width < MEDIUM.width
        val bandColor = when (d?.band) { Band.PUSH -> PUSH; Band.RECOVER -> RECOVER; else -> STEADY }
        Column(
            GlanceModifier.fillMaxSize().background(BG).cornerRadius(24.dp).padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (d == null) {
                Text("Open FitAgent to connect your watch", style = TextStyle(color = ColorProvider(TEXT), fontSize = 13.sp))
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${d.score}", style = TextStyle(color = ColorProvider(bandColor), fontSize = if (small) 34.sp else 40.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.width(8.dp))
                Column {
                    Text("READINESS", style = TextStyle(color = ColorProvider(MUTED), fontSize = 10.sp))
                    Text(d.band.label, style = TextStyle(color = ColorProvider(TEXT), fontSize = 13.sp, fontWeight = FontWeight.Medium))
                }
            }
            if (!small) {
                Spacer(GlanceModifier.height(8.dp))
                Meter("Steps ${formatCount(d.steps)}", d.steps / d.stepGoal.toFloat(), STEPS)
                Meter("Move ${d.kcal} kcal", d.kcal / d.kcalGoal.toFloat(), MOVE)
                Meter("Workout ${d.workoutMin} min", d.workoutMin / d.workoutGoal.toFloat(), WORKOUT)
                Spacer(GlanceModifier.height(4.dp))
                Text(d.coachLine, style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp), maxLines = 2)
            } else {
                Spacer(GlanceModifier.height(6.dp))
                LinearProgressIndicator((d.steps / d.stepGoal.toFloat()).coerceIn(0f, 1f), GlanceModifier.fillMaxWidth().height(6.dp),
                    color = ColorProvider(STEPS), backgroundColor = ColorProvider(TRACK))
                Text("${formatCount(d.steps)} steps", style = TextStyle(color = ColorProvider(MUTED), fontSize = 11.sp))
            }
        }
    }

    @Composable
    private fun Meter(label: String, progress: Float, color: Color) {
        Column(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(label, style = TextStyle(color = ColorProvider(TEXT), fontSize = 11.sp))
            LinearProgressIndicator(progress.coerceIn(0f, 1f), GlanceModifier.fillMaxWidth().height(6.dp),
                color = ColorProvider(color), backgroundColor = ColorProvider(TRACK))
        }
    }

    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val MEDIUM = DpSize(220.dp, 140.dp)
        private val BG = Color(0xFF10151B)
        private val TEXT = Color(0xFFEDF1F5)
        private val MUTED = Color(0xFF8A94A3)
        private val TRACK = Color(0x33FFFFFF)
        private val STEPS = Color(0xFFC6F432)
        private val MOVE = Color(0xFFFF6B4A)
        private val WORKOUT = Color(0xFF3DD9EB)
        private val PUSH = Color(0xFF2EE59D)
        private val STEADY = Color(0xFFFFC93C)
        private val RECOVER = Color(0xFFFF5C5C)

        /** Re-renders every placed widget; cheap when none are placed. */
        suspend fun refresh(context: Context) {
            runCatching { FitAgentWidget().updateAll(context) }
        }
    }
}

class FitAgentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FitAgentWidget()
}
