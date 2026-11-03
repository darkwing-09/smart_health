package com.fitagent.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitagent.domain.FoodEntry
import com.fitagent.domain.FoodRef
import com.fitagent.domain.Meal
import com.fitagent.domain.dayNutrition
import com.fitagent.domain.mealFor
import com.fitagent.domain.proteinGoal
import com.fitagent.domain.proteinHeadline
import com.fitagent.domain.searchFoods
import com.fitagent.ui.FoodPhotoState
import com.fitagent.ui.HomeState
import com.fitagent.ui.LocalClock
import com.fitagent.ui.charts.BulletBar
import com.fitagent.ui.charts.Donut
import com.fitagent.ui.charts.Legend
import com.fitagent.ui.charts.StackedBars
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.components.Pill
import com.fitagent.ui.theme.Fit
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** S4: today's meals, macros, protein vs goal and food in vs. activity out — with photo or list entry. */
@Composable
fun FoodScreen(
    state: HomeState,
    foods: List<FoodEntry>,
    photo: FoodPhotoState,
    onTakePhoto: () -> Unit,
    onAdd: (List<FoodEntry>) -> Unit,
    onDelete: (Long) -> Unit,
    onDismissPhoto: () -> Unit,
    onBack: () -> Unit,
) {
    val fc = Fit.colors
    val today = state.today
    val todays = foods.filter { it.date == today }
    val n = dayNutrition(todays)
    val goal = proteinGoal(state.profile.weightKg)
    var addFromList by remember { mutableStateOf(false) }
    DetailScaffold("Food log", "Private to this phone · estimates, not exact", onBack) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onTakePhoto, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text("Photo") }
                OutlinedButton(onClick = { addFromList = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("From list") }
            }
        }
        item {
            ChartCard("Today", "${n.kcal} kcal · ${n.protein.roundToInt()} g protein") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Donut(listOf((n.protein * 4).toFloat() to fc.workout, (n.carbs * 4).toFloat() to fc.steady, (n.fat * 9).toFloat() to fc.move),
                        Modifier.size(110.dp), description = "Protein ${n.protein.roundToInt()} g, carbs ${n.carbs.roundToInt()} g, fat ${n.fat.roundToInt()} g") {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${n.kcal}", style = MaterialTheme.typography.titleLarge)
                            Text("kcal", style = MaterialTheme.typography.labelSmall, color = fc.muted)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Protein ${n.protein.roundToInt()} g", style = MaterialTheme.typography.bodyMedium, color = fc.workout)
                        Text("Carbs ${n.carbs.roundToInt()} g", style = MaterialTheme.typography.bodyMedium, color = fc.steady)
                        Text("Fat ${n.fat.roundToInt()} g", style = MaterialTheme.typography.bodyMedium, color = fc.move)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(proteinHeadline(n.protein, goal), style = MaterialTheme.typography.bodySmall, color = fc.muted)
                BulletBar(n.protein.toFloat(), goal.toFloat(), fc.workout, description = "Protein ${n.protein.roundToInt()} of $goal g")
                Text("Goal $goal g (1.2 g per kg for active students)", style = MaterialTheme.typography.labelSmall, color = fc.muted)
            }
        }
        item {
            val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val byDay = foods.groupBy { it.date }
            val activity = state.history.associateBy { it.date }
            ChartCard("Calories by meal", "Last 7 days — drag to compare with active calories burned") {
                StackedBars(
                    days.map { d -> Meal.entries.map { m -> byDay[d].orEmpty().filter { it.meal == m }.sumOf { it.kcal }.toFloat() } },
                    listOf(fc.steady, fc.steps, fc.sleep, fc.workout), height = 130.dp,
                    labels = days.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) },
                    scrubLabel = { i -> "${byDay[days[i]].orEmpty().sumOf { it.kcal }} in · ${activity[days[i]]?.activeKcal ?: 0} active" },
                )
                Legend(Meal.entries.mapIndexed { i, m -> m.label to listOf(fc.steady, fc.steps, fc.sleep, fc.workout)[i] })
                Text("Active kcal this week: ${days.sumOf { activity[it]?.activeKcal ?: 0 }} · eaten: ${foods.filter { it.date in days }.sumOf { it.kcal }}",
                    style = MaterialTheme.typography.labelSmall, color = fc.muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Meal.entries.forEach { meal ->
            val list = todays.filter { it.meal == meal }
            if (list.isNotEmpty()) {
                item(key = "meal-$meal") {
                    AppCard(padding = 14.dp) {
                        Row { Text(meal.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text("${list.sumOf { it.kcal }} kcal", color = fc.muted) }
                        list.forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name + if (e.fromPhoto) " 📷" else "", style = MaterialTheme.typography.bodyMedium)
                                    Text("${e.portion} · ${e.proteinG.roundToInt()} g protein", style = MaterialTheme.typography.labelSmall, color = fc.muted)
                                }
                                Text("${e.kcal}", style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = { onDelete(e.id) }) { Icon(Icons.Rounded.Close, "Remove") }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("Calorie numbers are estimates. FitAgent never sets a calorie limit — eat enough to fuel study and training.",
                style = MaterialTheme.typography.bodySmall, color = fc.muted)
        }
    }
    if (addFromList) FoodPickerDialog(today, onDismiss = { addFromList = false }) { onAdd(it); addFromList = false }
    when (photo) {
        FoodPhotoState.Idle -> Unit
        FoodPhotoState.Analysing -> AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Looking at your meal…") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("Estimating dishes and portions") } })
        is FoodPhotoState.Message -> AlertDialog(onDismissRequest = onDismissPhoto,
            title = { Text(if (photo.supportive) "We're here for you" else "Food photo") }, text = { Text(photo.text) },
            confirmButton = { TextButton(onClick = onDismissPhoto) { Text("OK") } })
        is FoodPhotoState.Review -> EstimateReview(photo, onDismissPhoto, onAdd)
    }
}

@Composable
private fun EstimateReview(review: FoodPhotoState.Review, onDismiss: () -> Unit, onSave: (List<FoodEntry>) -> Unit) {
    var items by remember(review) { mutableStateOf(review.items) }
    var meal by remember(review) { mutableStateOf(review.items.first().meal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check the estimate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Meal.entries.forEach { m -> FilterChip(m == meal, { meal = m }, label = { Text(m.label.take(5)) }) } }
                items.forEachIndexed { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${e.portion} · ${e.kcal} kcal · ${e.proteinG.roundToInt()} g protein", style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted)
                        }
                        TextButton(onClick = { items = items.toMutableList().also { it[i] = scale(e, 0.5) } }) { Text("½") }
                        TextButton(onClick = { items = items.toMutableList().also { it[i] = scale(e, 1.5) } }) { Text("1½") }
                        IconButton(onClick = { items = items.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Rounded.Close, "Remove") }
                    }
                }
                if (review.note.isNotBlank()) Text(review.note, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
                Text("Total ${items.sumOf { it.kcal }} kcal", style = MaterialTheme.typography.titleSmall)
            }
        },
        confirmButton = { Button(enabled = items.isNotEmpty(), onClick = { onSave(items.map { it.copy(meal = meal) }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Discard") } },
    )
}

private fun scale(e: FoodEntry, f: Double) = e.copy(
    portion = if (f < 1) "½ × ${e.portion}" else "1½ × ${e.portion}",
    kcal = (e.kcal * f).roundToInt(), proteinG = e.proteinG * f, carbsG = e.carbsG * f, fatG = e.fatG * f,
)

@Composable
private fun FoodPickerDialog(today: LocalDate, onDismiss: () -> Unit, onAdd: (List<FoodEntry>) -> Unit) {
    val clock = LocalClock.current
    var query by remember { mutableStateOf("") }
    var meal by remember { mutableStateOf(mealFor(LocalTime.now(clock).hour)) }
    var picked by remember { mutableStateOf<FoodRef?>(null) }
    var qty by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add food") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Meal.entries.forEach { m -> FilterChip(m == meal, { meal = m }, label = { Text(m.label.take(5)) }) } }
                OutlinedTextField(query, { query = it; picked = null }, Modifier.fillMaxWidth(), label = { Text("Search, e.g. dal, roti, egg") }, singleLine = true)
                val p = picked
                if (p == null) {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(searchFoods(query), key = { it.name }) { f ->
                            Row(Modifier.fillMaxWidth().clickable { picked = f; qty = 1 }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Restaurant, null, tint = Fit.colors.muted, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                                    Text("${f.portion} · ${f.kcal} kcal · ${f.protein.roundToInt()} g protein", style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted)
                                }
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                            }
                        }
                    }
                } else {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { if (qty > 1) qty-- }) { Text("−") }
                        Text("$qty × ${p.portion}", style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { if (qty < 10) qty++ }) { Text("+") }
                    }
                    Pill("${p.kcal * qty} kcal · ${(p.protein * qty).roundToInt()} g protein", Fit.colors.workout)
                }
            }
        },
        confirmButton = {
            Button(enabled = picked != null, onClick = {
                val p = picked!!
                onAdd(listOf(FoodEntry(0, today, meal, p.name, if (qty == 1) p.portion else "$qty × ${p.portion}", p.kcal * qty,
                    p.protein * qty, p.carbs * qty, p.fat * qty)))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = { if (picked != null) picked = null else onDismiss() }) { Text(if (picked != null) "Back" else "Cancel") } },
    )
}

/** Today-tab summary card. */
@Composable
fun FoodTodayCard(foods: List<FoodEntry>, today: LocalDate, weightKg: Double, onOpen: () -> Unit) {
    val n = dayNutrition(foods.filter { it.date == today })
    val goal = proteinGoal(weightKg)
    AppCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Restaurant, null, tint = Fit.colors.steady, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Food", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (n.kcal == 0) "Log a meal" else "${n.kcal} kcal", style = MaterialTheme.typography.titleMedium)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
        }
        Spacer(Modifier.height(8.dp))
        BulletBar(n.protein.toFloat(), goal.toFloat(), Fit.colors.workout, description = "Protein ${n.protein.roundToInt()} of $goal g")
        Text("Protein ${n.protein.roundToInt()} / $goal g", style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted)
    }
}

/** Reads a camera photo and returns a ≤ 1280 px JPEG (enough for dish recognition, small to upload). */
fun downscaleJpeg(file: File, maxSide: Int = 1280): ByteArray {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, opts)
    var sample = 1
    while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("not an image")
    val scale = minOf(1f, maxSide / maxOf(bmp.width, bmp.height).toFloat())
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
    return ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
}

