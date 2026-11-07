package com.fitagent.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.fitagent.domain.formatCount
import com.fitagent.domain.formatHours
import com.fitagent.ui.HomeState
import com.fitagent.ui.charts.Sparkline
import com.fitagent.ui.components.ActivityRings
import com.fitagent.ui.components.Ring
import com.fitagent.ui.theme.Fit
import kotlinx.coroutines.launch
import java.io.File
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** G3: a story-sized weekly recap image. Health metrics (sleep, heart) are off unless the user turns them on. */
@Composable
fun RecapScreen(state: HomeState, onBack: () -> Unit) {
    var showHealth by rememberSaveable { mutableStateOf(false) }
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    DetailScaffold("Weekly recap", "Share your week — you choose what's on it", onBack) {
        if (!state.watch.hasWatch && !state.profile.usePhoneSteps) {
            item {
                com.fitagent.ui.components.AppCard {
                    Text("No recap yet", style = MaterialTheme.typography.titleMedium)
                    Text("Your weekly recap is built from your watch's data. Connect your watch and it appears after your first synced day.",
                        style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
                }
            }
            return@DetailScaffold
        }
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }) {
                RecapCard(state, showHealth)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include sleep", style = MaterialTheme.typography.bodyMedium)
                    Text("Off by default — your health data, your call.", style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
                }
                Switch(showHealth, { showHealth = it })
            }
        }
        item {
            Button(onClick = { scope.launch { shareBitmap(context, layer.toImageBitmap().asAndroidBitmap()) } }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Share image")
            }
        }
    }
}

@Composable
fun RecapCard(state: HomeState, showHealth: Boolean) {
    val fc = Fit.colors
    val week = state.history.takeLast(7)
    val g = state.profile.goals
    val steps = week.sumOf { it.steps }
    val minutes = week.sumOf { it.workoutMinutes }
    val best = week.maxByOrNull { it.steps }
    val goalDays = week.count { it.goalMet(g) }
    val fmt = DateTimeFormatter.ofPattern("d MMM")
    Column(
        Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2A10), Color(0xFF0A0D12), Color(0xFF0E2A30))))
            .padding(22.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("MY WEEK", color = fc.steps, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 3.sp)
            Text("${week.firstOrNull()?.date?.format(fmt) ?: ""} – ${week.lastOrNull()?.date?.format(fmt) ?: ""}", color = Color(0xFF8A94A3), fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ActivityRings(listOf(
                Ring(goalDays / 7f, fc.steps),
                Ring(minutes / (g.dailyWorkoutMinutes * 7f).coerceAtLeast(1f), fc.workout),
            ), Modifier.size(150.dp), stroke = 16.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$goalDays/7", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("goal days", color = Color(0xFF8A94A3), fontSize = 11.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RecapStat("${formatCount(steps)}", "steps", fc.steps, week.map { it.steps.toFloat() })
            RecapStat("$minutes min", "workouts", fc.workout, week.map { it.workoutMinutes.toFloat() })
            if (showHealth) {
                val sleep = week.mapNotNull { it.sleepMinutes }
                if (sleep.isNotEmpty()) RecapStat(formatHours(sleep.average().roundToInt()), "avg sleep", fc.sleep, week.map { it.sleepMinutes?.toFloat() })
            }
            Row {
                Text("🔥 ${state.streak.current}-day streak", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                best?.let { Text("Best: ${it.date.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase)}", color = Color(0xFF8A94A3), fontSize = 12.sp) }
            }
        }
        Text("FitAgent · move more, together", color = Color(0xFF8A94A3), fontSize = 11.sp)
    }
}

@Composable
private fun RecapStat(value: String, label: String, color: Color, trend: List<Float?>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Color(0xFF8A94A3), fontSize = 11.sp)
        }
        Sparkline(trend, color, Modifier.width(90.dp).height(34.dp))
    }
}

private fun shareBitmap(context: Context, bitmap: Bitmap) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "fitagent-week.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share your week"))
}
