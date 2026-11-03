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
import com.fitagent.ui.CloudState
import com.fitagent.ui.charts.RaceBars
import com.fitagent.ui.charts.RaceEntry
import com.fitagent.domain.formatCount
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
    onKudos: (challengeId: String, toUserId: String, emoji: String, remove: Boolean) -> Unit = { _, _, _, _ -> },
    onLoadCampus: (String) -> Unit = {},
    inviteLink: (String) -> String = { it },
    onClearInvite: () -> Unit = {},
) {
    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ChallengeRes?>(null) }
    LaunchedEffect(cloud.account) { if (cloud.account != null) { onLoad(); onLoadCampus(cloud.campusScope) } }
    LaunchedEffect(cloud.pendingJoinCode, cloud.account) { if (cloud.pendingJoinCode != null && cloud.account != null) showJoin = true }

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
        val joinable = cloud.official.filter { !it.joined }
        if (joinable.isNotEmpty()) {
            item { SectionTitle("From your college") }
            items(joinable, key = { "o" + it.id }) { o ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("🏛 ${o.name}", style = MaterialTheme.typography.titleMedium)
                            Text("${METRICS.first { it.first == o.metric }.second} · ${o.participants} joined · ends ${o.endDate}",
                                style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
                        }
                        Button(onClick = { onJoin(o.code) {} }) { Text("Join") }
                    }
                }
            }
        }
        val active = cloud.challenges.filter { it.status != "finished" }
        val finished = cloud.challenges.filter { it.status == "finished" }
        if (active.isNotEmpty()) item { SectionTitle("Active") }
        items(active, key = { it.id }) { ChallengeCard(it) { detail = it } }
        item { CampusCard(cloud, onLoadCampus) }
        if (finished.isNotEmpty()) item { SectionTitle("Finished") }
        items(finished, key = { it.id }) { ChallengeCard(it) { detail = it } }
    }

    if (showCreate) CreateDialog(onDismiss = { showCreate = false }) { name, metric, days ->
        onCreate(name, metric, days) { created -> showCreate = false; detail = created }
    }
    if (showJoin) JoinDialog(cloud.pendingJoinCode.orEmpty(), onDismiss = { showJoin = false; onClearInvite() }) { code ->
        onJoin(code) { showJoin = false; onClearInvite() }
    }
    detail?.let { c ->
        val fresh = cloud.challenges.firstOrNull { it.id == c.id } ?: c
        ChallengeDetail(fresh, inviteLink(fresh.code), onDismiss = { detail = null }, onLeave = { onLeave(fresh.id); detail = null },
            onKudos = { to, emoji, remove -> onKudos(fresh.id, to, emoji, remove) })
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

/** Full-screen challenge: race-bar leaderboard, today's kudos and an invite link. */
@Composable
fun ChallengeDetail(
    c: ChallengeRes,
    inviteUrl: String,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
    onKudos: (toUserId: String, emoji: String, remove: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val fc = Fit.colors
    var confirmLeave by remember { mutableStateOf(false) }
    val unit = metricUnit(c.metric)
    DetailScaffold(c.name, "${METRICS.first { it.first == c.metric }.second} · ${c.participants} people · ${statusText(c, LocalDate.now(LocalClock.current))}", onDismiss) {
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.myRank?.let { "You're #$it of ${c.participants}" } ?: "Not ranked yet", style = MaterialTheme.typography.titleLarge)
                        val me = c.leaderboard.firstOrNull { it.isMe }
                        val ahead = c.leaderboard.filter { it.rank < (me?.rank ?: 0) }.maxByOrNull { it.rank }
                        Text(
                            when {
                                me == null -> "Sync your activity to appear on the board."
                                ahead == null -> "You're leading — keep it up!"
                                else -> "${formatCount((ahead.value - me.value).toLong())} $unit behind ${ahead.displayName}"
                            },
                            style = MaterialTheme.typography.bodySmall, color = fc.muted,
                        )
                    }
                    c.myRank?.let { RankBadge(it) }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val text = "Join my FitAgent challenge “${c.name}”: $inviteUrl (or enter code ${c.code} in Squad → Join)"
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite friends"))
                    }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Invite") }
                    Pill("Code ${c.code}", MaterialTheme.colorScheme.primary, Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        item {
            ChartCard("Leaderboard", "Tap an emoji to cheer someone on — one of each per day") {
                RaceBars(c.leaderboard.sortedBy { it.rank }.take(10).map {
                    RaceEntry(if (it.isMe) "${it.displayName} (you)" else it.displayName, it.value.toFloat(), "${formatValue(it.value, c.metric)}", it.isMe)
                }, if (c.metric == "steps") fc.steps else if (c.metric == "workout_minutes") fc.workout else fc.push)
            }
        }
        items(c.leaderboard.sortedBy { it.rank }, key = { it.userId }) { row -> KudosRow(row, onKudos) }
        item {
            OutlinedButton(onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth()) { Text(if (c.isOwner) "Delete challenge" else "Leave challenge") }
        }
    }
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

private val KUDOS = listOf("👏", "🔥", "💪")

@Composable
private fun KudosRow(row: LeaderboardRowDto, onKudos: (String, String, Boolean) -> Unit) {
    val fc = Fit.colors
    AppCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${row.rank}", style = MaterialTheme.typography.titleMedium, color = fc.muted, modifier = Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(if (row.isMe) "${row.displayName} (you)" else row.displayName, style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (row.isMe) FontWeight.Bold else FontWeight.Medium)
                val got = row.kudos.orEmpty().entries.joinToString("  ") { "${it.key} ${it.value}" }
                if (got.isNotEmpty()) Text(got, style = MaterialTheme.typography.labelSmall, color = fc.muted)
            }
            if (!row.isMe) {
                KUDOS.forEach { e ->
                    val sent = e in row.myKudos.orEmpty()
                    FilterChip(sent, { onKudos(row.userId, e, sent) }, label = { Text(e) }, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

private fun formatValue(v: Double, metric: String): String = when (metric) {
    "steps" -> formatCount(v.toLong())
    "workout_minutes" -> "${v.toLong()} min"
    else -> "${v.toLong()} d"
}

/** Campus leaderboard (G4): hostels/colleges/batches by goal days per member. */
@Composable
private fun CampusCard(cloud: CloudState, onLoad: (String) -> Unit) {
    val fc = Fit.colors
    val campus = cloud.campus
    ChartCard("Campus leaderboard", "Goal days per member, last 7 days — small groups can win") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("hostel" to "Hostel", "college" to "College", "batch" to "Batch").forEach { (k, label) ->
                FilterChip(cloud.campusScope == k, { onLoad(k) }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            cloud.campusLoading && campus == null -> CircularProgressIndicator(Modifier.size(24.dp))
            campus == null || campus.scope != cloud.campusScope -> Text("Couldn't load the campus board.", color = fc.muted, style = MaterialTheme.typography.bodySmall)
            campus.myGroup.isNullOrBlank() -> Text("Add your ${cloud.campusScope} in You → Campus to compete for it.",
                style = MaterialTheme.typography.bodySmall, color = fc.muted)
            campus.rows.isEmpty() -> Text("No group has ${campus.minGroupSize}+ members yet — invite your ${cloud.campusScope}mates!",
                style = MaterialTheme.typography.bodySmall, color = fc.muted)
            else -> RaceBars(campus.rows.take(8).map { RaceEntry("${it.name} · ${it.members}", it.value.toFloat(), "%.1f".format(it.value), it.isMine) }, fc.steps)
        }
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
private fun JoinDialog(initial: String, onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var code by remember { mutableStateOf(initial) }
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
