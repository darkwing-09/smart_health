package com.fitagent.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitagent.domain.WorkoutType
import com.fitagent.ui.theme.Fit
import com.fitagent.ui.theme.color

/** Rounded content card used throughout the app. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = color, contentColor = contentColor) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted,
            modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

data class Ring(val progress: Float, val color: Color)

/** Concentric goal rings (outermost first), animated on first show and on change. */
@Composable
fun ActivityRings(rings: List<Ring>, modifier: Modifier = Modifier, stroke: Dp = 16.dp, gap: Dp = 5.dp) {
    val inspection = LocalInspectionMode.current // previews & screenshot tests render the final frame
    val anims = rings.map { r ->
        val a = remember { Animatable(if (inspection) r.progress else 0f) }
        LaunchedEffect(r.progress) { a.animateTo(r.progress, tween(900, easing = FastOutSlowInEasing)) }
        a
    }
    val track = Fit.colors.track
    Canvas(modifier) {
        val strokePx = stroke.toPx()
        val gapPx = gap.toPx()
        rings.forEachIndexed { i, ring ->
            val inset = strokePx / 2 + i * (strokePx + gapPx)
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            if (arcSize.width <= 0) return@forEachIndexed
            val topLeft = Offset(inset, inset)
            drawArc(ring.color.copy(alpha = 0.18f), 0f, 360f, false, topLeft, arcSize, style = Stroke(strokePx))
            val p = anims[i].value
            if (p > 0f) {
                drawArc(ring.color, -90f, 360f * p.coerceAtMost(1f), false, topLeft, arcSize,
                    style = Stroke(strokePx, cap = StrokeCap.Round))
                if (p > 1f) { // second lap for over-achievement
                    drawArc(ring.color.copy(alpha = 0.55f), -90f, 360f * (p - 1f).coerceAtMost(1f), false, topLeft,
                        arcSize, style = Stroke(strokePx * 0.55f, cap = StrokeCap.Round))
                }
            }
        }
        if (rings.isEmpty()) drawCircle(track)
    }
}

/** 270° readiness gauge with the score in the middle. */
@Composable
fun ReadinessGauge(score: Int, color: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val inspection = LocalInspectionMode.current
    val a = remember { Animatable(if (inspection) score / 100f else 0f) }
    LaunchedEffect(score) { a.animateTo(score / 100f, tween(1100, easing = FastOutSlowInEasing)) }
    val track = Fit.colors.track
    Box(modifier.semantics { contentDescription = "Readiness $score out of 100" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 14.dp.toPx()
            val s = Size(size.width - w, size.height - w)
            val tl = Offset(w / 2, w / 2)
            drawArc(track, 135f, 270f, false, tl, s, style = Stroke(w, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * a.value, false, tl, s, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Simple bar chart with an optional dashed goal line. Values ≥ goal are drawn in full colour. */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    goal: Float? = null,
    highlightLast: Boolean = true,
) {
    val maxV = maxOf(values.maxOrNull() ?: 0f, goal ?: 0f, 1f) * 1.1f
    val muted = Fit.colors.muted
    val track = Fit.colors.track
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.56f).coerceAtMost(28.dp.toPx())
            values.forEachIndexed { i, v ->
                val h = size.height * (v / maxV)
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(track, Offset(x, 0f), Size(barW, size.height), CornerRadius(barW / 2))
                val full = goal == null || v >= goal || (highlightLast && i == values.lastIndex)
                if (h > 0) drawRoundRect(if (full) color else color.copy(alpha = 0.45f), Offset(x, size.height - h),
                    Size(barW, h), CornerRadius(barW / 2))
            }
            goal?.let {
                val y = size.height * (1 - it / maxV)
                drawLine(muted, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            }
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                labels.forEach {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun StatTile(icon: ImageVector, label: String, value: String, unit: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text(unit, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted,
                        modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            Text(label, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted, maxLines = 1)
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(CircleShape).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(14.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

fun WorkoutType.icon(): ImageVector = when (this) {
    WorkoutType.WALK -> Icons.AutoMirrored.Rounded.DirectionsWalk
    WorkoutType.RUN -> Icons.AutoMirrored.Rounded.DirectionsRun
    WorkoutType.CYCLE -> Icons.AutoMirrored.Rounded.DirectionsBike
    WorkoutType.STRENGTH -> Icons.Rounded.FitnessCenter
    WorkoutType.HIIT -> Icons.Rounded.Bolt
    WorkoutType.YOGA -> Icons.Rounded.SelfImprovement
    WorkoutType.SWIM -> Icons.Rounded.Pool
    WorkoutType.SPORTS -> Icons.Rounded.SportsSoccer
    WorkoutType.DANCE -> Icons.Rounded.MusicNote
    WorkoutType.OTHER -> Icons.Rounded.Timer
}

@Composable
fun WorkoutBadge(type: WorkoutType, size: Dp = 44.dp) {
    val c = type.color(Fit.colors)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(c.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(type.icon(), type.label, tint = c, modifier = Modifier.size(size * 0.55f)) }
}

@Composable
fun LinearMeter(progress: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val track = Fit.colors.track
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }
}
