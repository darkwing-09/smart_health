package com.fitagent.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fitagent.data.remote.dto.PlanRes
import com.fitagent.data.remote.dto.SessionDto
import com.fitagent.domain.WorkoutType
import com.fitagent.ui.ChatMessage
import com.fitagent.ui.CloudState
import com.fitagent.ui.HomeState
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.components.Pill
import com.fitagent.ui.components.WorkoutBadge
import com.fitagent.ui.theme.Fit
import java.time.LocalDate

private val PROMPTS = listOf(
    "What should I do today?",
    "A 15-min workout I can do in my room",
    "How was my week?",
    "How do I stay active during exams?",
    "How am I doing in my challenges?",
)

@Composable
fun CoachScreen(
    home: HomeState,
    cloud: CloudState,
    contentPadding: PaddingValues,
    onSignIn: () -> Unit,
    onLoad: () -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onGeneratePlan: () -> Unit,
    onStart: (WorkoutType) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Coach", style = MaterialTheme.typography.headlineLarge)
                Text("Personal advice grounded in your data", style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
            }
            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (cloud.account == null) {
            SignedOutCoach(home, onSignIn, onStart, Modifier.padding(bottom = contentPadding.calculateBottomPadding()))
            return
        }
        LaunchedEffect(cloud.account) { if (!cloud.coachLoaded) onLoad() }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Chat") })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Weekly plan") })
        }
        Box(Modifier.weight(1f).padding(bottom = contentPadding.calculateBottomPadding())) {
            if (tab == 0) Chat(cloud, onSend, onClear) else Plan(cloud, onGeneratePlan, onStart)
        }
    }
}

@Composable
private fun SignedOutCoach(home: HomeState, onSignIn: () -> Unit, onStart: (WorkoutType) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AppCard(color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface) {
                Text("Today's suggestion", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text(home.suggestion.headline, style = MaterialTheme.typography.titleLarge)
                Text(home.suggestion.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                home.suggestion.type?.let { t ->
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onStart(t) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Start ${t.label.lowercase()} · ${home.suggestion.minutes} min")
                    }
                }
            }
        }
        item {
            AppCard {
                Text("Chat with your AI coach", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text("Create a free account to ask questions, get a 7-day plan built around your readiness, and join " +
                    "challenges with friends. Your activity syncs securely; you can delete it any time.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in or create account") }
            }
        }
    }
}

@Composable
private fun Chat(cloud: CloudState, onSend: (String) -> Unit, onClear: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(cloud.messages.size) { if (cloud.messages.isNotEmpty()) list.animateScrollToItem(cloud.messages.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cloud.messages.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text("Ask me anything about your training", style = MaterialTheme.typography.titleMedium)
                        Text("I can see your steps, workouts, sleep and readiness.", style = MaterialTheme.typography.bodySmall,
                            color = Fit.colors.muted)
                    }
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = onClear) { Icon(Icons.Rounded.DeleteSweep, "Clear conversation", tint = Fit.colors.muted) }
                    }
                }
            }
            items(cloud.messages.size) { i -> Bubble(cloud.messages[i]) }
            cloud.coachError?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(PROMPTS) { p -> AssistChip(onClick = { onSend(p) }, label = { Text(p) }, enabled = !cloud.sending) }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { if (it.length <= 1000) input = it },
                placeholder = { Text("Message your coach") }, modifier = Modifier.weight(1f), maxLines = 4,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.size(8.dp))
            FilledIconButton(onClick = { onSend(input); input = "" }, enabled = input.isNotBlank() && !cloud.sending,
                modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send")
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = when {
                mine -> MaterialTheme.colorScheme.primary
                m.safety != null -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(20.dp, 20.dp, if (mine) 4.dp else 20.dp, if (mine) 20.dp else 4.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (m.safety != null && !mine) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.HealthAndSafety, null, Modifier.size(16.dp))
                        Text("  Safety first", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (m.pending) TypingDots() else Text(m.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse), label = "dot$i")
            Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
        }
    }
}

@Composable
private fun Plan(cloud: CloudState, onGenerate: () -> Unit, onStart: (WorkoutType) -> Unit) {
    val plan = cloud.plan
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AppCard(color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface) {
                Text(if (plan == null) "Your 7-day plan" else plan.plan.summary,
                    style = if (plan == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium)
                if (plan == null) {
                    Text("Built from your readiness, recent load and goals — then checked against safety rules " +
                        "(rest days, no back-to-back hard days, gradual progression).",
                        style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Readiness ${plan.readiness.score}", bandColor(plan))
                        Pill(if (plan.source == "llm") "AI-generated" else "Rule-based", Fit.colors.muted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onGenerate, enabled = !cloud.planBusy, modifier = Modifier.fillMaxWidth()) {
                    if (cloud.planBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(if (plan == null) "Generate my plan" else "Regenerate plan")
                }
            }
        }
        plan?.let { p ->
            val start = LocalDate.parse(p.startsOn)
            items(p.plan.sessions.size) { i -> SessionRow(p.plan.sessions[i], start.plusDays(i.toLong()), onStart) }
        }
    }
}

@Composable
private fun bandColor(p: PlanRes) = when (p.readiness.band) {
    "PUSH" -> Fit.colors.push
    "RECOVER" -> Fit.colors.recover
    else -> Fit.colors.steady
}

@Composable
private fun SessionRow(s: SessionDto, date: LocalDate, onStart: (WorkoutType) -> Unit) {
    val isToday = date == LocalDate.now()
    val rest = s.type == "REST"
    val type = WorkoutType.fromName(s.type)
    AppCard(padding = 14.dp, color = if (isToday) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(min = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.day, style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary else Fit.colors.muted)
                Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.size(10.dp))
            if (!rest) WorkoutBadge(type, 40.dp)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (rest) "Rest day" else "${type.label} · ${s.minutes} min", style = MaterialTheme.typography.titleMedium)
                Text(s.focus, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
            }
            if (!rest) {
                val c = when (s.intensity) { "hard" -> Fit.colors.move; "moderate" -> Fit.colors.steady; else -> Fit.colors.push }
                Column(horizontalAlignment = Alignment.End) {
                    Pill(s.intensity, c)
                    if (isToday) IconButton(onClick = { onStart(type) }) { Icon(Icons.Rounded.PlayArrow, "Start") }
                }
            }
        }
    }
}
