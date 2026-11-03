package com.fitagent.wear

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.delay

private val LIME = Color(0xFFC6F432)
private val CORAL = Color(0xFFFF6B4A)
private val CYAN = Color(0xFF3DD9EB)
private val ZONES = listOf(Color(0xFF8FA3BF), Color(0xFF3DA5FF), Color(0xFF2EE59D), Color(0xFFFFC93C), Color(0xFFFF5C5C))

/** Watch app (W1): readiness at a glance, start a workout, live HR/zone/time, HIIT with wrist haptics. */
class WearActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SummaryStore.load(this)
        val needed = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 36) add("android.permission.health.READ_HEART_RATE")
        }
        permissions.launch(needed.toTypedArray())
        val recorder = WorkoutRecorder.get(this)
        setContent {
            MaterialTheme {
                val live by recorder.live.collectAsStateWithLifecycle()
                val summary by SummaryStore.summary.collectAsStateWithLifecycle()
                val w = live
                if (w == null) Home(summary, onStart = { t, i -> recorder.start(t, i) })
                else LiveScreen(w, onPause = { recorder.togglePause() }, onFinish = { recorder.finish() })
            }
        }
    }
}

@Composable
private fun Home(summary: PhoneSummary?, onStart: (WatchWorkout, Intervals?) -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(Modifier.fillMaxSize(), state = state, horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(summary?.score?.toString() ?: "—", fontSize = 40.sp, fontWeight = FontWeight.Black, color = bandColor(summary?.band))
                    Text(if (summary == null) "Open FitAgent on your phone" else "Readiness · ${summary.steps} steps",
                        style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
                }
            }
            item {
                Chip(onClick = { onStart(WatchWorkout.HIIT, Intervals.TABATA) }, label = { Text("Tabata 4 min") },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = CORAL), modifier = Modifier.fillMaxWidth())
            }
            item {
                Chip(onClick = { onStart(WatchWorkout.HIIT, Intervals.CLASSIC) }, label = { Text("Intervals 10 min") },
                    colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
            }
            items(WatchWorkout.entries.filter { it != WatchWorkout.HIIT }) { t ->
                Chip(onClick = { onStart(t, null) }, label = { Text(t.label) }, colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LiveScreen(w: LiveWorkout, onPause: () -> Unit, onFinish: () -> Unit) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(w.paused) { while (!w.paused) { delay(1000); tick++ } }
    val zone = w.bpm?.let { zoneOf(it, hrMax(20)) } ?: 0
    val color = if (zone > 0) ZONES[zone - 1] else LIME
    val phase = w.intervals?.let { phaseAt(it, w.activeSeconds) }
    Scaffold(timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = ((w.bpm ?: 0) / 200f).coerceIn(0f, 1f), modifier = Modifier.fillMaxSize().padding(4.dp),
                indicatorColor = color, startAngle = 135f, endAngle = 45f, strokeWidth = 6.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (phase != null) {
                    Text(phase.phase.name, color = if (phase.phase == Phase.WORK) CORAL else CYAN, fontWeight = FontWeight.Bold)
                    Text("${phase.secondsLeft}", fontSize = 40.sp, fontWeight = FontWeight.Black)
                    Text("Round ${phase.round}/${w.intervals.rounds}", style = MaterialTheme.typography.caption2)
                } else {
                    Text("%d:%02d".format(w.activeSeconds / 60, w.activeSeconds % 60), fontSize = 34.sp, fontWeight = FontWeight.Black)
                }
                Text("${w.bpm ?: "—"} bpm" + if (zone > 0) " · Z$zone" else "", color = color)
                Text("${w.kcal.toInt()} kcal" + if (w.distanceM > 50) " · %.2f km".format(w.distanceM / 1000) else "",
                    style = MaterialTheme.typography.caption2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = onPause, colors = ButtonDefaults.secondaryButtonColors()) { Text(if (w.paused) "▶" else "❚❚") }
                    Button(onClick = onFinish, colors = ButtonDefaults.primaryButtonColors(backgroundColor = LIME)) { Text("■", color = Color.Black) }
                }
            }
        }
    }
}

fun bandColor(band: String?): Color = when (band) {
    "PUSH" -> Color(0xFF2EE59D)
    "RECOVER" -> Color(0xFFFF5C5C)
    else -> Color(0xFFFFC93C)
}
