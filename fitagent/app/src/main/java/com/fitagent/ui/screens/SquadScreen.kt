package com.fitagent.ui.screens

import android.content.Intent
import com.fitagent.ui.LocalClock
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.fitagent.data.remote.dto.ChallengeRes
import com.fitagent.data.remote.dto.LeaderboardRowDto
import com.fitagent.domain.formatCount
import com.fitagent.ui.CloudState
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.components.Pill
import com.fitagent.ui.components.SectionTitle
import com.fitagent.ui.theme.Fit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val METRICS = listOf("steps" to "Most steps", "workout_minutes" to "Workout minutes", "active_days" to "Goal days")

private fun metricUnit(metric: String) = when (metric) {
    "steps" -> "steps"; "workout_minutes" -> "min"; else -> "days"
}

@Composable
fun SquadScreen(
    cloud: CloudState,
    contentPadding: PaddingValues,
    onSignIn: () -> Unit,
    onLoad: () -> Unit,
    onCreate: (String, String, Int, (ChallengeRes) -> Unit) -> Unit,
    onJoin: (String, () -> Unit) -> Unit,
    onLeave: (String) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ChallengeRes?>(null) }
    LaunchedEffect(cloud.account) { if (cloud.account != null) onLoad() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp, contentPadding.calculateTopPadding() + 8.dp, 16.dp,
            contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Squad", style = MaterialTheme.typography.headlineLarge)
            Text("Challenge friends, your hostel or your whole class.", style = MaterialTheme.typography.bodySmall,
                color = Fit.colors.muted)
        }
        if (cloud.account == null) {
            item {
                AppCard {
                    Icon(Icons.Rounded.Groups, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Move more, together", style = MaterialTheme.typography.titleLarge)
                    Text("Create a challenge, share the 6-letter code, and climb the leaderboard. Pick “Goal days” so " +
                        "everyone competes on consistency, not just fitness level.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in to join challenges") }
                }
            }
            return@LazyColumn
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { showCreate = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("Create")
                }
                OutlinedButton(onClick = { showJoin = true }, modifier = Modifier.weight(1f)) { Text("Join with code") }
            }
        }
        if (cloud.challengesLoading && cloud.challenges.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
        }
        cloud.challengesError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (!cloud.challengesLoading && cloud.challenges.isEmpty() && cloud.challengesError == null) {
            item {
                Text("No challenges yet. Create one and share the code in your group chat!",
                    style = MaterialTheme.typography.bodyMedium, color = Fit.colors.muted)
            }
        }
        val active = cloud.challenges.filter { it.status != "finished" }
        val finished = cloud.challenges.filter { it.status == "finished" }
        if (active.isNotEmpty()) item { SectionTitle("Active") }
        items(active, key = { it.id }) { ChallengeCard(it) { detail = it } }
        if (finished.isNotEmpty()) item { SectionTitle("Finished") }
        items(finished, key = { it.id }) { ChallengeCard(it) { detail = it } }
    }

    if (showCreate) CreateDialog(onDismiss = { showCreate = false }) { name, metric, days ->
        onCreate(name, metric, days) { created -> showCreate = false; detail = created }
    }
    if (showJoin) JoinDialog(onDismiss = { showJoin = false }) { code -> onJoin(code) { showJoin = false } }
    detail?.let { c ->
        val fresh = cloud.challenges.firstOrNull { it.id == c.id } ?: c
        ChallengeDetail(fresh, onDismiss = { detail = null }, onLeave = { onLeave(fresh.id); detail = null })
    }
}

@Composable
private fun ChallengeCard(c: ChallengeRes, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleLarge)
                    Text("${METRICS.first { it.first == c.metric }.second} · ${c.participants} people · ${statusText(c, LocalDate.now(LocalClock.current))}",
                        style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
                }
                c.myRank?.let { RankBadge(it) }
            }
            Spacer(Modifier.height(12.dp))
            c.leaderboard.take(3).forEach { LeaderRow(it, c.metric, compact = true) }
        }
    }
}

private fun statusText(c: ChallengeRes, today: LocalDate): String {
    return when (c.status) {
        "upcoming" -> "starts ${LocalDate.parse(c.startDate)}"
        "finished" -> "ended ${LocalDate.parse(c.endDate)}"
        else -> {
            val left = ChronoUnit.DAYS.between(today, LocalDate.parse(c.endDate)) + 1
            "$left day${if (left == 1L) "" else "s"} left"
        }
    }
}

@Composable
private fun RankBadge(rank: Int) {
    val c = when (rank) { 1 -> Fit.colors.steady; 2 -> Fit.colors.muted; 3 -> Fit.colors.move; else -> MaterialTheme.colorScheme.primary }
    Row(Modifier.clip(CircleShape).background(c.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.EmojiEvents, null, tint = c, modifier = Modifier.size(18.dp))
        Text(" #$rank", color = c, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LeaderRow(r: LeaderboardRowDto, metric: String, compact: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .background(if (r.isMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${r.rank}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(28.dp), color = Fit.colors.muted)
        Text(if (r.isMe) "${r.displayName} (you)" else r.displayName, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (r.isMe) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), maxLines = 1)
        Text("${formatCount(r.value.toLong())} ${metricUnit(metric)}", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ChallengeDetail(c: ChallengeRes, onDismiss: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.name) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill("Code ${c.code}", MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(statusText(c, LocalDate.now(LocalClock.current)), style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                items(c.leaderboard, key = { it.userId }) { LeaderRow(it, c.metric) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val text = "Join my FitAgent challenge “${c.name}” — code ${c.code}"
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share code"))
            }) { Icon(Icons.Rounded.Share, null); Text(" Share code") }
        },
        dismissButton = {
            TextButton(onClick = { confirmLeave = true }) { Text(if (c.isOwner) "Delete" else "Leave") }
        },
    )
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (c.isOwner) "Delete challenge?" else "Leave challenge?") },
            text = { Text(if (c.isOwner) "You created this challenge, so it will be removed for everyone." else "You can rejoin later with the code.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CreateDialog(onDismiss: () -> Unit, onCreate: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var metric by remember { mutableStateOf("steps") }
    var days by remember { mutableIntStateOf(7) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New challenge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { if (it.length <= 60) name = it }, label = { Text("Name") },
                    placeholder = { Text("Hostel Step-Off") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Compete on", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    METRICS.forEach { (k, label) -> FilterChip(metric == k, { metric = k }, label = { Text(label) }) }
                }
                Text("Duration", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7, 14, 30).forEach { d -> FilterChip(days == d, { days = d }, label = { Text("$d days") }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, metric, days) }, enabled = name.trim().length >= 3) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun JoinDialog(onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a challenge") },
        text = {
            OutlinedTextField(code, { code = it.uppercase().filter(Char::isLetterOrDigit).take(8) }, label = { Text("Code") },
                singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
        },
        confirmButton = { TextButton(onClick = { onJoin(code) }, enabled = code.length >= 4) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
