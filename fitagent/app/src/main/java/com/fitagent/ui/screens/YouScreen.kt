package com.fitagent.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitagent.BuildConfig
import com.fitagent.data.health.HealthAvailability
import com.fitagent.domain.Achievement
import com.fitagent.domain.Goals
import com.fitagent.domain.Profile
import com.fitagent.domain.formatCount
import com.fitagent.domain.formatKm
import com.fitagent.ui.CloudState
import com.fitagent.ui.HomeState
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.components.BarChart
import com.fitagent.ui.components.LinearMeter
import com.fitagent.ui.components.SectionTitle
import com.fitagent.ui.theme.Fit
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun YouScreen(
    state: HomeState,
    cloud: CloudState,
    contentPadding: PaddingValues,
    onUpdateProfile: ((Profile) -> Profile) -> Unit,
    onManageHealth: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onServerUrl: (String) -> Unit,
    onPrivacy: () -> Unit,
) {
    var range by rememberSaveable { mutableStateOf(7) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp, contentPadding.calculateTopPadding() + 8.dp, 16.dp,
            contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ProfileHeader(state, cloud) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(range == 7, { range = 7 }, label = { Text("7 days") })
                FilterChip(range == 30, { range = 30 }, label = { Text("30 days") })
            }
        }
        item { ProgressCharts(state, range) }
        item { SectionTitle("Achievements", trailing = {
            Text("${state.achievements.count { it.unlocked }}/${state.achievements.size}",
                style = MaterialTheme.typography.labelMedium, color = Fit.colors.muted)
        }) }
        item { AchievementsGrid(state.achievements) }
        item { SectionTitle("Goals") }
        item { GoalsCard(state.profile.goals) { g -> onUpdateProfile { it.copy(goals = g) } } }
        item { SectionTitle("Body") }
        item { BodyCard(state.profile, onUpdateProfile) }
        item { SectionTitle("Settings") }
        item { RemindersCard(state.profile, onUpdateProfile) }
        item { HealthCard(state, onManageHealth) }
        item { AccountCard(cloud, onSignIn, onSignOut, onDeleteAccount, onServerUrl) }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = onPrivacy) { Text("Privacy policy") }
                Text("FitAgent ${BuildConfig.VERSION_NAME} · a fitness coach, not a medical device.",
                    style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ProfileHeader(state: HomeState, cloud: CloudState) {
    val name = state.profile.name.ifBlank { cloud.account?.displayName ?: "Athlete" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), Alignment.Center) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.headlineSmall)
            Text(cloud.account?.email ?: "Local profile", style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HeaderStat("${state.streak.current}", "Streak", Modifier.weight(1f))
        HeaderStat("${state.streak.best}", "Best streak", Modifier.weight(1f))
        HeaderStat("${state.workouts.size}", "Workouts", Modifier.weight(1f))
    }
}

@Composable
private fun HeaderStat(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
        }
    }
}

@Composable
private fun ProgressCharts(state: HomeState, range: Int) {
    val days = state.days.takeLast(range)
    val labels = if (range == 7) days.map { it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
    else days.mapIndexed { i, d -> if (i % 5 == 0 || i == days.lastIndex) "${d.date.dayOfMonth}" else "" }
    val g = state.profile.goals
    AppCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Steps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("avg ${formatCount(days.map { it.steps }.average().roundToInt())}/day",
                style = MaterialTheme.typography.labelMedium, color = Fit.colors.muted)
        }
        Spacer(Modifier.height(12.dp))
        BarChart(days.map { it.steps.toFloat() }, labels, Fit.colors.steps, goal = g.dailySteps.toFloat())
    }
    Spacer(Modifier.height(12.dp))
    AppCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Workout minutes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${days.sumOf { it.workoutMinutes }} min total", style = MaterialTheme.typography.labelMedium, color = Fit.colors.muted)
        }
        Spacer(Modifier.height(12.dp))
        BarChart(days.map { it.workoutMinutes.toFloat() }, labels, Fit.colors.workout, goal = g.dailyWorkoutMinutes.toFloat())
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Row {
            MiniStat("${formatKm(days.sumOf { it.distanceMeters })} km", "distance", Modifier.weight(1f))
            MiniStat("${days.count { it.goalMet(g) }}/${days.size}", "goal days", Modifier.weight(1f))
            val sleep = days.mapNotNull { it.sleepMinutes }
            MiniStat(if (sleep.isEmpty()) "—" else "${(sleep.average() / 60 * 10).roundToInt() / 10.0} h", "avg sleep", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AchievementsGrid(items: List<Achievement>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 3) {
        items.sortedByDescending { it.progress }.forEach { a ->
            Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
                Column(Modifier.padding(12.dp).alpha(if (a.unlocked) 1f else 0.75f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (a.unlocked) Icons.Rounded.EmojiEvents else Icons.Rounded.Lock, null,
                        tint = if (a.unlocked) Fit.colors.steady else Fit.colors.muted, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(a.title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1)
                    Text(a.description, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted,
                        textAlign = TextAlign.Center, maxLines = 2, minLines = 2)
                    if (!a.unlocked) {
                        Spacer(Modifier.height(6.dp))
                        LinearMeter(a.progress, Fit.colors.steady, height = 4.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalsCard(goals: Goals, onChange: (Goals) -> Unit) {
    AppCard {
        Stepper("Daily steps", formatCount(goals.dailySteps), Fit.colors.steps,
            { onChange(goals.copy(dailySteps = (goals.dailySteps - 500).coerceAtLeast(2000))) },
            { onChange(goals.copy(dailySteps = (goals.dailySteps + 500).coerceAtMost(30000))) })
        Stepper("Daily move", "${goals.dailyActiveKcal} kcal", Fit.colors.move,
            { onChange(goals.copy(dailyActiveKcal = (goals.dailyActiveKcal - 50).coerceAtLeast(100))) },
            { onChange(goals.copy(dailyActiveKcal = (goals.dailyActiveKcal + 50).coerceAtMost(2000))) })
        Stepper("Daily workout", "${goals.dailyWorkoutMinutes} min", Fit.colors.workout,
            { onChange(goals.copy(dailyWorkoutMinutes = (goals.dailyWorkoutMinutes - 5).coerceAtLeast(10))) },
            { onChange(goals.copy(dailyWorkoutMinutes = (goals.dailyWorkoutMinutes + 5).coerceAtMost(180))) })
        Stepper("Workouts per week", "${goals.weeklyWorkouts}", MaterialTheme.colorScheme.primary,
            { onChange(goals.copy(weeklyWorkouts = (goals.weeklyWorkouts - 1).coerceAtLeast(1))) },
            { onChange(goals.copy(weeklyWorkouts = (goals.weeklyWorkouts + 1).coerceAtMost(14))) })
    }
}

@Composable
fun Stepper(label: String, value: String, color: Color, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus) { Icon(Icons.Rounded.Remove, "Decrease $label") }
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(84.dp), textAlign = TextAlign.Center)
        IconButton(onClick = onPlus) { Icon(Icons.Rounded.Add, "Increase $label") }
    }
}

@Composable
private fun BodyCard(p: Profile, onUpdate: ((Profile) -> Profile) -> Unit) {
    AppCard {
        Stepper("Age", "${p.ageYears}", Fit.colors.muted,
            { onUpdate { it.copy(ageYears = (it.ageYears - 1).coerceAtLeast(13)) } },
            { onUpdate { it.copy(ageYears = (it.ageYears + 1).coerceAtMost(99)) } })
        Stepper("Height", "${p.heightCm} cm", Fit.colors.muted,
            { onUpdate { it.copy(heightCm = (it.heightCm - 1).coerceAtLeast(120)) } },
            { onUpdate { it.copy(heightCm = (it.heightCm + 1).coerceAtMost(230)) } })
        Stepper("Weight", "${(p.weightKg * 2).roundToInt() / 2.0} kg", Fit.colors.muted,
            { onUpdate { it.copy(weightKg = (it.weightKg - 0.5).coerceAtLeast(30.0)) } },
            { onUpdate { it.copy(weightKg = (it.weightKg + 0.5).coerceAtMost(250.0)) } })
        Text("BMI ${(p.bmi * 10).roundToInt() / 10.0} · used only to estimate calories",
            style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
    }
}

@Composable
private fun RemindersCard(p: Profile, onUpdate: ((Profile) -> Profile) -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Notifications, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Daily goal reminder", style = MaterialTheme.typography.titleMedium)
                Text("Only if you haven't hit your goal yet", style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
            }
            Switch(p.remindersEnabled, { on -> onUpdate { it.copy(remindersEnabled = on) } })
        }
        if (p.remindersEnabled) {
            Stepper("Remind me at", String.format(Locale.US, "%02d:00", p.reminderHour), Fit.colors.muted,
                { onUpdate { it.copy(reminderHour = (it.reminderHour + 23) % 24) } },
                { onUpdate { it.copy(reminderHour = (it.reminderHour + 1) % 24) } })
        }
    }
}

@Composable
private fun HealthCard(state: HomeState, onManage: () -> Unit) {
    val h = state.health
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Watch, null, tint = Fit.colors.workout)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Health Connect", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        h.availability == HealthAvailability.NOT_INSTALLED -> "Not installed"
                        h.availability == HealthAvailability.UPDATE_REQUIRED -> "Update required"
                        !h.connected -> "Not connected"
                        h.missing > 0 -> "Connected · ${h.missing} permission${if (h.missing > 1) "s" else ""} off"
                        else -> "Connected · all data types"
                    },
                    style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted,
                )
            }
            if (h.connected && h.missing == 0) Icon(Icons.Rounded.CheckCircle, null, tint = Fit.colors.push)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
            Text(if (h.connected) "Manage permissions" else "Connect")
        }
        Text("Works with Google Fit, Samsung Health, Fitbit, Noise, boAt and any app that writes to Health Connect.",
            style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AccountCard(
    cloud: CloudState, onSignIn: () -> Unit, onSignOut: () -> Unit, onDelete: () -> Unit, onServerUrl: (String) -> Unit,
) {
    var editServer by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Cloud, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Cloud account", style = MaterialTheme.typography.titleMedium)
                Text(cloud.account?.let { "Signed in as ${it.email}" } ?: "Optional — unlocks AI coach & challenges",
                    style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (cloud.account == null) {
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in or create account") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSignOut, modifier = Modifier.weight(1f)) { Text("Sign out") }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                    Text("Delete account", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        TextButton(onClick = { editServer = true }) {
            Text("Server: ${cloud.serverUrl}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
    if (editServer) {
        var url by remember { mutableStateOf(cloud.serverUrl) }
        AlertDialog(
            onDismissRequest = { editServer = false },
            title = { Text("FitAgent server") },
            text = {
                Column {
                    Text("Use your campus or self-hosted FitAgent server address.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(url, { url = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
            },
            confirmButton = { TextButton(onClick = { onServerUrl(url); editServer = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editServer = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your cloud account?") },
            text = { Text("This permanently erases your account, synced activity, coach conversations and challenges " +
                "from the server. Data on this phone stays.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) {
                Text("Delete forever", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
