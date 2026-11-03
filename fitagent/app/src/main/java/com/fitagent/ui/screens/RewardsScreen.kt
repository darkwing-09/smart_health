package com.fitagent.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitagent.data.remote.dto.PerkDto
import com.fitagent.ui.CloudState
import com.fitagent.ui.charts.Bars
import com.fitagent.ui.charts.BulletBar
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.components.Pill
import com.fitagent.ui.theme.Fit
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** G5: points for consistency (measured by your watch or phone), spent on campus partner perks. */
@Composable
fun RewardsScreen(cloud: CloudState, onLoad: () -> Unit, onRedeem: (String) -> Unit, onBack: () -> Unit) {
    val fc = Fit.colors
    LaunchedEffect(Unit) { onLoad() }
    val r = cloud.rewards
    var confirm by remember { mutableStateOf<PerkDto?>(null) }
    DetailScaffold("Rewards", "Earned by moving — tracked days only", onBack) {
        if (r == null) {
            item { if (cloud.rewardsLoading) CircularProgressIndicator() else Text("Sign in to earn rewards.", color = fc.muted) }
            return@DetailScaffold
        }
        item {
            AppCard(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Text("${r.balance}", style = MaterialTheme.typography.displayMedium)
                Text("points to spend · ${r.earnedTotal} earned in total", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("+10 each day you hit your step goal (tracked by your watch or phone) · +25 for 7 days in a row · +100 for 30 · podium finishes 50/30/20",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            val labels = r.weeks.map { runCatching { LocalDate.parse(it.weekStart).format(DateTimeFormatter.ofPattern("d/M")) }.getOrDefault("") }
            ChartCard("Points per week", "Last 8 weeks") {
                Bars(r.weeks.map { it.points.toFloat() }, fc.steps, labels = labels, highlight = setOf(r.weeks.lastIndex),
                    scrubLabel = { "${labels[it]} · ${r.weeks[it].points} pts" }, description = "Points earned per week")
            }
        }
        item { Text("PERKS", style = MaterialTheme.typography.labelSmall, color = fc.muted) }
        if (r.perks.isEmpty()) {
            item { Text("No perks at your college yet — ask your sports committee to add some.", style = MaterialTheme.typography.bodySmall, color = fc.muted) }
        }
        r.perks.forEach { perk ->
            item(key = perk.id) {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CardGiftcard, null, tint = fc.move, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(perk.title, style = MaterialTheme.typography.titleMedium)
                            Text("${perk.partner} · ${perk.stock} left", style = MaterialTheme.typography.bodySmall, color = fc.muted)
                        }
                        Button(onClick = { confirm = perk }, enabled = r.balance >= perk.cost) { Text("${perk.cost}") }
                    }
                    if (r.balance < perk.cost) {
                        Spacer(Modifier.height(8.dp))
                        BulletBar(r.balance.toFloat(), perk.cost.toFloat(), fc.steps, max = perk.cost.toFloat())
                        Text("${perk.cost - r.balance} more points", style = MaterialTheme.typography.labelSmall, color = fc.muted)
                    }
                }
            }
        }
        if (r.redemptions.isNotEmpty()) {
            item { Text("YOUR CODES", style = MaterialTheme.typography.labelSmall, color = fc.muted) }
            r.redemptions.forEach { red ->
                item(key = red.code) {
                    AppCard(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(red.perkTitle, style = MaterialTheme.typography.titleSmall)
                                Text("Show this code to the partner", style = MaterialTheme.typography.labelSmall, color = fc.muted)
                            }
                            Pill(red.code, fc.push)
                        }
                    }
                }
            }
        }
    }
    confirm?.let { perk ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Redeem ${perk.title}?") },
            text = { Text("${perk.cost} points will be spent. You'll get a code to show at ${perk.partner}.") },
            confirmButton = { TextButton(onClick = { onRedeem(perk.id); confirm = null }) { Text("Redeem") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

