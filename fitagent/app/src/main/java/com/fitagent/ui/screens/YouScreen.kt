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
import androidx.compose.material.icons.rounded.Share
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
import com.fitagent.domain.stepsTrendHeadline
import com.fitagent.ui.charts.Bars
import com.fitagent.ui.charts.ChartPeriod
import com.fitagent.ui.charts.Legend
import com.fitagent.ui.charts.LineChart
import com.fitagent.ui.charts.LineSeries
import com.fitagent.ui.charts.PeriodChips
import com.fitagent.ui.charts.sparseLabels
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
    onOpenDevices: () -> Unit = {},
    onOpenRecap: () -> Unit = {},
    onOpenRewards: () -> Unit = {},
) {
    var range by rememberSaveable { mutableStateOf(ChartPeriod.WEEK) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp, contentPadding.calculateTopPadding() + 8.dp, 16.dp,
            contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ProfileHeader(state, cloud) }
        item { PeriodChips(range, { range = it }) }
        item { ProgressCharts(state, range) }
        item {
            OutlinedButton(onClick = onOpenRecap, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Share my weekly recap")
            }
        }
        if (cloud.account != null) item {
            OutlinedButton(onClick = onOpenRewards, modifier = Modifier.fillMaxWidth()) { Text("🎁  Rewards & campus perks") }
        }
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
        item { LanguageCard(state.profile, onUpdateProfile) }
        item { HealthCard(state, onManageHealth, onOpenDevices) }
        if (cloud.account != null) item { CampusGroupsCard(state.profile, onUpdateProfile) }
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
private fun ProgressCharts(state: HomeState, range: ChartPeriod) {
    val days = state.history.takeLast(range.days)
    val previous = state.history.dropLast(range.days).takeLast(range.days)
    val labels = if (days.size <= 7) days.map { it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
    else sparseLabels(days.map { "${it.date.dayOfMonth}" }, if (days.size <= 31) 7 else 21)
    val g = state.profile.goals
    val fc = Fit.colors
    val dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
    fun change(now: Double, before: Double): String? =
        if (before <= 0) null else ((now - before) / before * 100).roundToInt().let { if (it >= 0) " · ▲ $it% vs previous" else " · ▼ ${-it}% vs previous" }

    val stepsAvg = days.map { it.steps }.average()
    ChartCard("Steps", stepsTrendHeadline(days, g.dailySteps) + (change(stepsAvg, previous.map { it.steps }.average().takeIf { previous.isNotEmpty() } ?: 0.0) ?: "")) {
        Bars(days.map { it.steps.toFloat() }, fc.steps, goal = g.dailySteps.toFloat(), labels = labels, highlight = setOf(days.lastIndex),
            maxBarWidth = if (days.size > 31) 6.dp else 28.dp,
            scrubLabel = { "${days[it].date.format(dayFmt)} · ${formatCount(days[it].steps)}" }, description = "Steps per day")
    }
    Spacer(Modifier.height(12.dp))
    ChartCard("Workout minutes", "${days.sumOf { it.workoutMinutes }} min over ${days.size} days" +
        (change(days.sumOf { it.workoutMinutes }.toDouble(), previous.sumOf { it.workoutMinutes }.toDouble()) ?: "")) {
        Bars(days.map { it.workoutMinutes.toFloat() }, fc.workout, goal = g.dailyWorkoutMinutes.toFloat(), labels = labels,
            maxBarWidth = if (days.size > 31) 6.dp else 28.dp,
            scrubLabel = { "${days[it].date.format(dayFmt)} · ${days[it].workoutMinutes} min" }, description = "Workout minutes per day")
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
    Spacer(Modifier.height(12.dp))
    ChartCard("Active calories", "Average ${days.map { it.activeKcal }.average().roundToInt()} kcal a day") {
        LineChart(
            listOf(LineSeries(days.map { it.activeKcal.toFloat() }, fc.move, fill = true),
                LineSeries(previous.map { it.activeKcal.toFloat() }.takeIf { it.size == days.size } ?: emptyList(), fc.muted, dashed = true)),
            goal = g.dailyActiveKcal.toFloat(), yMin = 0f, xLabels = labels,
            scrubLabel = { "${days[it].date.format(dayFmt)} · ${days[it].activeKcal} kcal" }, description = "Active calories per day",
        )
        if (previous.size == days.size) Legend(listOf("This period" to fc.move, "Previous period" to fc.muted))
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
private fun HealthCard(state: HomeState, onManage: () -> Unit, onOpenDevices: () -> Unit) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onManage, modifier = Modifier.weight(1f)) {
                Text(if (h.connected) "Permissions" else "Connect")
            }
            Button(onClick = onOpenDevices, modifier = Modifier.weight(1f)) { Text("Data health") }
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


/** G4: optional college / hostel / batch for campus leaderboards. Only group totals are ever shown. */
@Composable
private fun CampusGroupsCard(profile: Profile, onUpdate: ((Profile) -> Profile) -> Unit) {
    var college by remember(profile.college) { mutableStateOf(profile.college) }
    var hostel by remember(profile.hostel) { mutableStateOf(profile.hostel) }
    var batch by remember(profile.batch) { mutableStateOf(profile.batch) }
    val changed = college != profile.college || hostel != profile.hostel || batch != profile.batch
    AppCard {
        Text("Campus", style = MaterialTheme.typography.titleMedium)
        Text("Join your college, hostel and batch leaderboards. Only group averages are shown — never your own numbers.",
            style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(college, { college = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("College") }, singleLine = true)
        OutlinedTextField(hostel, { hostel = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Hostel") }, singleLine = true)
        OutlinedTextField(batch, { batch = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Batch, e.g. CSE '27") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("Count me in campus leaderboards", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(profile.showOnCampus, { on -> onUpdate { it.copy(showOnCampus = on) } })
        }
        if (changed) {
            Button(onClick = { onUpdate { it.copy(college = college.trim(), hostel = hostel.trim(), batch = batch.trim()) } },
                modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageCard(profile: Profile, onUpdate: ((Profile) -> Profile) -> Unit) {
    AppCard {
        Text(androidx.compose.ui.res.stringResource(com.fitagent.R.string.coach_language), style = MaterialTheme.typography.titleMedium)
        Text("Your AI coach replies in this language. App language: Android Settings → Apps → FitAgent → Language.",
            style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.fitagent.domain.COACH_LANGUAGES.forEach { (code, name) ->
                androidx.compose.material3.FilterChip(profile.language == code, { onUpdate { it.copy(language = code) } }, label = { Text(name) })
            }
        }
    }
}
