package com.healthos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthos.ui.components.FindingsBanner
import com.healthos.ui.components.HealthTrendChart
import com.healthos.ui.components.MetricRingCard
import com.healthos.ui.components.TrendPoint
import com.healthos.ui.theme.LocalHealthColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed interface SyncUiState {
    data object Idle : SyncUiState
    data object Queued : SyncUiState
    data class Syncing(val message: String = "Reading Health Connect & synchronizing...") : SyncUiState
    data object WaitingForConstraint : SyncUiState
    data class Success(
        val message: String,
        val recordsRead: Int = 0,
        val recordsStaged: Int = 0,
        val recordsSynced: Int = 0,
        val isOffline: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    ) : SyncUiState
    data class Error(
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : SyncUiState
}

data class VitalsSummary(
    val latestHeartRateBpm: Double? = null,
    val latestHeartRateRecordedAt: Long? = null,
    val todaySteps: Int = 0,
    val todayCaloriesKcal: Int = 0,
    val latestSleepMinutes: Double? = null,
    val latestSleepRecordedAt: Long? = null,
    val totalMeasurementsCount: Int = 0
)

data class DailyInsightUi(
    val headline: String = "Cardiovascular Stability Observed",
    val narrative: String = "Resting pulse and diurnal variance remain aligned with your established 30-day baseline curve. Physical exertion recovery is within optimal range.",
    val category: String = "RECOVERY",
    val recommendation: String? = "Maintain steady hydration and consider an evening wind-down routine to support consistent deep-sleep architecture.",
    val confidence: Double = 0.94,
    val isFallback: Boolean = false
)

@Composable
fun HealthDashboardScreen(
    isHealthConnectAvailable: Boolean,
    hasPermissions: Boolean,
    pendingQueueCount: Int,
    syncState: SyncUiState = SyncUiState.Idle,
    vitals: VitalsSummary = VitalsSummary(),
    trendPoints: List<TrendPoint> = emptyList(),
    baselineHrMean: Double? = null,
    findings: List<NotificationItemUi> = emptyList(),
    dailyInsight: DailyInsightUi? = DailyInsightUi(),
    onRequestPermissions: () -> Unit,
    onTriggerSync: () -> Unit,
    onAcknowledgeFinding: ((String) -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    val healthColors = LocalHealthColors.current

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            else -> "Good Evening"
        }
    }

    val todayDateStr = remember {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 1. Top Bar: Greeting & Sync Status Pill ─────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = todayDateStr,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Minimal Sync Status Pill
                SyncStatusPill(
                    syncState = syncState,
                    pendingCount = pendingQueueCount,
                    onSyncTap = onTriggerSync
                )
            }

            // ── 2. Action Required (Health Connect Permissions) ──────────────
            if (!hasPermissions) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("🔗", fontSize = 24.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Connect Health Data",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Grant Health Connect permissions to ingest biometric timelines and generate baseline insights.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Button(
                            onClick = onRequestPermissions,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Grant", fontSize = 12.sp)
                        }
                    }
                }
            }

            // ── 3. Readiness / Goal Score Card ──────────────────────────────
            HeroReadinessCard(
                vitals = vitals,
                baselineHrMean = baselineHrMean
            )

            // ── 4. Interactive 2×2 Vitals Grid ──────────────────────────────
            VitalsGrid(vitals = vitals)

            // ── 5. Heart Rate Trend Chart ────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    HealthTrendChart(
                        dataPoints = trendPoints,
                        baselineMean = baselineHrMean,
                        title = "7-Day Heart Rate Trajectory",
                        unit = "bpm"
                    )
                }
            }

            // ── 6. Clinical Observations & Alerts Banner ────────────────────
            FindingsBanner(
                findings = findings,
                onAcknowledge = onAcknowledgeFinding
            )

            // ── 7. AI Wellness Insights Card (Gemini) ───────────────────────
            dailyInsight?.let { insight ->
                AiWellnessInsightCard(insight = insight)
            }

            // ── 8. Footer Sync Action ───────────────────────────────────────
            val isSyncActive = syncState is SyncUiState.Syncing || syncState is SyncUiState.Queued
            Button(
                onClick = onTriggerSync,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isSyncActive
            ) {
                if (isSyncActive) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Synchronizing Health Timeline...")
                } else {
                    Text("Sync Health Timeline Now", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

// ── Supporting UI Components ────────────────────────────────────────────────

@Composable
private fun SyncStatusPill(
    syncState: SyncUiState,
    pendingCount: Int,
    onSyncTap: () -> Unit
) {
    val (label, dotColor) = when (syncState) {
        is SyncUiState.Idle -> {
            if (pendingCount > 0) "$pendingCount pending" to Color(0xFFFFA726)
            else "Synced" to Color(0xFF00E676)
        }
        is SyncUiState.Queued -> "Queued" to Color(0xFF42A5F5)
        is SyncUiState.Syncing -> "Syncing..." to Color(0xFF29B6F6)
        is SyncUiState.WaitingForConstraint -> "Offline" to Color(0xFFFFB74D)
        is SyncUiState.Success -> "Updated" to Color(0xFF00E676)
        is SyncUiState.Error -> "Sync Error" to Color(0xFFFF5252)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable { onSyncTap() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (syncState is SyncUiState.Syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = dotColor
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HeroReadinessCard(
    vitals: VitalsSummary,
    baselineHrMean: Double?
) {
    val healthColors = LocalHealthColors.current

    // Compute composite health score (0..100)
    val stepRatio = (vitals.todaySteps.toFloat() / 10000f).coerceIn(0f, 1f)
    val calRatio = (vitals.todayCaloriesKcal.toFloat() / 600f).coerceIn(0f, 1f)
    val score = ((stepRatio * 40) + (calRatio * 30) + 25).toInt().coerceIn(40, 98)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MetricRingCard(
                currentValue = score,
                goalValue = 100,
                label = "Score",
                unit = "/100",
                ringColor = if (score >= 75) healthColors.successGreen else healthColors.steps,
                ringSize = 76.dp,
                ringStroke = 7.dp
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (score >= 80) "Optimal Health Score" else "Daily Vitality Track",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                val baselineText = baselineHrMean?.let {
                    "Resting pulse steady against 30d baseline (${it.toInt()} bpm)."
                } ?: "Collecting continuous biometrics for baseline modeling."
                Text(
                    text = baselineText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VitalsGrid(vitals: VitalsSummary) {
    val healthColors = LocalHealthColors.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Heart Rate Card
            VitalMetricCard(
                modifier = Modifier.weight(1f),
                title = "Heart Rate",
                value = vitals.latestHeartRateBpm?.let { "${it.toInt()} bpm" } ?: "-- bpm",
                subtitle = vitals.latestHeartRateRecordedAt?.let {
                    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it))
                } ?: "Resting",
                icon = "❤️",
                accentColor = healthColors.heartRate
            )

            // Steps Card
            VitalMetricCard(
                modifier = Modifier.weight(1f),
                title = "Today's Steps",
                value = String.format(Locale.getDefault(), "%,d", vitals.todaySteps),
                subtitle = "Goal: 10,000",
                icon = "👟",
                accentColor = healthColors.steps
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Calories Card
            VitalMetricCard(
                modifier = Modifier.weight(1f),
                title = "Active Burn",
                value = "${vitals.todayCaloriesKcal} kcal",
                subtitle = "Goal: 600 kcal",
                icon = "🔥",
                accentColor = healthColors.calories
            )

            // Sleep Card
            val sleepText = vitals.latestSleepMinutes?.let { minutes ->
                val h = (minutes / 60).toInt()
                val m = (minutes % 60).toInt()
                "${h}h ${m}m"
            } ?: "--"

            VitalMetricCard(
                modifier = Modifier.weight(1f),
                title = "Sleep Session",
                value = sleepText,
                subtitle = vitals.latestSleepRecordedAt?.let {
                    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(it))
                } ?: "Restful sleep",
                icon = "😴",
                accentColor = healthColors.sleep
            )
        }
    }
}

@Composable
private fun VitalMetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    icon: String,
    accentColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(text = icon, fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AiWellnessInsightCard(insight: DailyInsightUi) {
    val healthColors = LocalHealthColors.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = healthColors.aiInsightContainer.copy(alpha = 0.35f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = healthColors.aiInsight.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("✨", fontSize = 16.sp)
                    Text(
                        text = "AI Wellness Insight",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = healthColors.aiInsight
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(healthColors.aiInsight.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = insight.category.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = healthColors.aiInsight
                    )
                }
            }

            Text(
                text = insight.headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = insight.narrative,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            insight.recommendation?.let { rec ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .padding(10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("💡", fontSize = 14.sp)
                        Text(
                            text = rec,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Text(
                text = "Personal Health OS observations are generated for wellness awareness and do not constitute clinical diagnosis.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 9.sp
            )
        }
    }
}
