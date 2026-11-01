package com.fitagent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fitagent.ui.charts.ChartHeader
import com.fitagent.ui.charts.Sparkline
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.theme.Fit
import java.time.Duration

/** Screens opened from cards on Today / You. Shown over the tabs; back returns. */
enum class Detail { READINESS, SLEEP, HEART, DEVICES, TIMETABLE, MOOD, BREATHE, LIBRARY, RECAP, FOOD, REWARDS }

/** Full-screen detail page: back arrow, title, scrolling cards. */
@Composable
fun DetailScaffold(title: String, subtitle: String?, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

/** A card with a chart header (title + plain-language headline) and the chart below. */
@Composable
fun ChartCard(
    title: String,
    headline: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier) {
        ChartHeader(title, headline, trailing = trailing)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** Stat tile with a 7–14 day sparkline under the value. */
@Composable
fun SparkTile(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    tint: Color,
    trend: List<Float?>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier.then(if (onClick != null) Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = Fit.colors.muted, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text(unit, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Sparkline(trend, tint, Modifier.fillMaxWidth().height(28.dp))
        }
    }
}

/** Small coloured dot + text, e.g. "● Watch data 4 min ago". */
@Composable
fun StatusDot(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Fit.colors.muted)
    }
}

/** Green under 1 h, amber under 6 h, red after that or never — the Data health freshness rule. */
@Composable
fun freshnessColor(lastMs: Long, nowMs: Long): Color = when {
    lastMs <= 0 -> Fit.colors.recover
    nowMs - lastMs < Duration.ofHours(1).toMillis() -> Fit.colors.push
    nowMs - lastMs < Duration.ofHours(6).toMillis() -> Fit.colors.steady
    else -> Fit.colors.recover
}

fun agoText(lastMs: Long, nowMs: Long): String {
    if (lastMs <= 0) return "never"
    val m = Duration.ofMillis((nowMs - lastMs).coerceAtLeast(0)).toMinutes()
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / (24 * 60)} days ago"
    }
}

val COMPONENT_NAMES = mapOf(
    "sleep" to "Sleep",
    "sleep_quality" to "Sleep quality",
    "hrv" to "Heart rate variability",
    "resting_hr" to "Resting heart rate",
    "nightly" to "Overnight signals",
    "load" to "Training load",
)

/** Short labels for tight spaces (Today's contributor bars, chart legends). */
val COMPONENT_SHORT_NAMES = mapOf(
    "sleep" to "Sleep",
    "sleep_quality" to "Quality",
    "hrv" to "HRV",
    "resting_hr" to "Resting HR",
    "nightly" to "Night",
    "load" to "Load",
)
