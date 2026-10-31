package com.fitagent.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitagent.ui.theme.Fit

/*
 * FitAgent chart kit. Every chart:
 *  - animates in once from the baseline (skipped in previews and screenshot tests),
 *  - supports press-and-drag scrubbing with a value bubble and a haptic tick per point (when given a scrubLabel),
 *  - takes plain data so it renders the same on phone, widget previews and Paparazzi tests.
 * Design rationale: docs/phase2/01-design-and-charts.md.
 */

/** 0→1 entrance animation keyed on [key]; 1 immediately in inspection mode. */
@Composable
fun rememberReveal(key: Any?, durationMs: Int = 650): Float {
    val inspection = LocalInspectionMode.current
    val a = remember { Animatable(if (inspection) 1f else 0f) }
    LaunchedEffect(key) {
        if (!inspection) {
            a.snapTo(0f)
            a.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
        }
    }
    return a.value
}

@Composable
fun rememberScrub(): MutableState<Int?> = remember { mutableStateOf(null) }

/**
 * Press-and-drag (or tap) to select the nearest of [count] evenly spaced points. Horizontal drags select
 * immediately; vertical scrolling of the page still works because only horizontal drags are consumed.
 */
@Composable
fun Modifier.scrub(count: Int, state: MutableState<Int?>, enabled: Boolean = true): Modifier {
    if (!enabled || count == 0) return this
    val haptic = LocalHapticFeedback.current
    fun select(x: Float, width: Int) {
        val i = ((x / width) * count).toInt().coerceIn(0, count - 1)
        if (state.value != i) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        state.value = i
    }
    return this
        .pointerInput(count) {
            detectHorizontalDragGestures(
                onDragStart = { select(it.x, size.width) },
                onDragEnd = { state.value = null },
                onDragCancel = { state.value = null },
            ) { change, _ -> select(change.position.x, size.width) }
        }
        .pointerInput(count) {
            detectTapGestures(onPress = { select(it.x, size.width); tryAwaitRelease(); state.value = null })
        }
}

/** Centre x of slot [i] of [n] across [width]. */
fun slotCenter(i: Int, n: Int, width: Float): Float = width * (i + 0.5f) / n.coerceAtLeast(1)

/** Draws the scrub marker: a vertical guide at [x] and a rounded bubble with [text] near the top. */
fun DrawScope.drawScrubMarker(x: Float, text: String, measurer: TextMeasurer, guide: Color, bubble: Color, onBubble: Color) {
    drawLine(guide, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    val layout = measurer.measure(text, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = onBubble))
    val padH = 8.dp.toPx()
    val padV = 4.dp.toPx()
    val w = layout.size.width + padH * 2
    val h = layout.size.height + padV * 2
    val left = (x - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    drawRoundRect(bubble, Offset(left, 0f), Size(w, h), CornerRadius(h / 2))
    drawText(layout, topLeft = Offset(left + padH, padV))
}

/** Chart card header: small caps title, a plain-language headline, optional trailing control. */
@Composable
fun ChartHeader(title: String, headline: String?, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (headline != null) {
                Text(headline, style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
            }
        }
        trailing?.invoke()
    }
}

/** Colour key under a chart. */
@Composable
fun Legend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted)
            }
        }
    }
}

enum class ChartPeriod(val label: String, val days: Int) { WEEK("W", 7), MONTH("M", 30), QUARTER("3M", 90) }

/** `W · M · 3M` chips for trend cards. */
@Composable
fun PeriodChips(selected: ChartPeriod, onSelect: (ChartPeriod) -> Unit, options: List<ChartPeriod> = ChartPeriod.entries) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { p ->
            FilterChip(selected = p == selected, onClick = { onSelect(p) }, label = { Text(p.label) })
        }
    }
}

/** Axis label row under a chart: labels spread over equal slots. */
@Composable
fun AxisLabels(labels: List<String>, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    Row(modifier.fillMaxWidth().padding(top = 6.dp)) {
        labels.forEach {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Fit.colors.muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
        }
    }
}

/** Shows every [step]-th label (others blank) so dense axes stay readable. */
fun sparseLabels(labels: List<String>, step: Int): List<String> =
    labels.mapIndexed { i, l -> if (i % step == 0 || i == labels.lastIndex) l else "" }
