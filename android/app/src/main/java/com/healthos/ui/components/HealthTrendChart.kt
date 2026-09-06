package com.healthos.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthos.ui.theme.LocalHealthColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrendPoint(
    val timestamp: Long,
    val value: Double
)

@Composable
fun HealthTrendChart(
    dataPoints: List<TrendPoint>,
    baselineMean: Double? = null,
    modifier: Modifier = Modifier,
    title: String = "Heart Rate Trend",
    unit: String = "bpm",
    lineColor: Color = LocalHealthColors.current.chartLine,
    fillColor: Color = LocalHealthColors.current.chartFill,
    baselineColor: Color = LocalHealthColors.current.chartBaseline
) {
    val healthColors = LocalHealthColors.current

    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val animatedProgress by animateFloatAsState(
        targetValue = if (dataPoints.isNotEmpty()) 1f else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "chartProgress"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Header
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Selected point tooltip
        val tooltipText = selectedIndex?.let { idx ->
            if (idx in dataPoints.indices) {
                val point = dataPoints[idx]
                val timeStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    .format(Date(point.timestamp))
                "${point.value.toInt()} $unit  •  $timeStr"
            } else null
        }

        Text(
            text = tooltipText ?: if (dataPoints.isEmpty()) "No data available" else "Tap chart to inspect",
            style = MaterialTheme.typography.labelSmall,
            color = if (tooltipText != null) lineColor else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (dataPoints.size < 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Collecting data for trend...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val values = dataPoints.map { it.value }
            val minVal = (values.minOrNull() ?: 0.0) - 5.0
            val maxVal = (values.maxOrNull() ?: 100.0) + 5.0
            val range = (maxVal - minVal).coerceAtLeast(1.0)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .pointerInput(dataPoints) {
                        detectTapGestures { offset ->
                            val stepX = size.width.toFloat() / (dataPoints.size - 1).coerceAtLeast(1)
                            val idx = ((offset.x / stepX) + 0.5f).toInt()
                                .coerceIn(0, dataPoints.size - 1)
                            selectedIndex = if (selectedIndex == idx) null else idx
                        }
                    }
            ) {
                val canvasW = size.width
                val canvasH = size.height
                val paddingTop = 8f
                val paddingBottom = 20f
                val chartH = canvasH - paddingTop - paddingBottom
                val stepX = canvasW / (dataPoints.size - 1).coerceAtLeast(1)

                fun valueToY(v: Double): Float {
                    return paddingTop + chartH - ((v - minVal) / range * chartH).toFloat()
                }

                // Draw baseline dashed line
                if (baselineMean != null && baselineMean in minVal..maxVal) {
                    val baselineY = valueToY(baselineMean)
                    drawLine(
                        color = baselineColor,
                        start = Offset(0f, baselineY),
                        end = Offset(canvasW, baselineY),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                    )
                }

                // Build the line path
                val linePath = Path()
                val fillPath = Path()
                val points = dataPoints.mapIndexed { i, p ->
                    Offset(i * stepX, valueToY(p.value))
                }

                // Draw gradient fill below curve
                fillPath.moveTo(points.first().x, canvasH - paddingBottom)
                points.forEachIndexed { i, pt ->
                    val animX = pt.x * animatedProgress
                    val animY = paddingTop + (pt.y - paddingTop) * animatedProgress +
                            (canvasH - paddingBottom - paddingTop) * (1f - animatedProgress)
                    if (i == 0) {
                        linePath.moveTo(animX, animY)
                        fillPath.lineTo(animX, animY)
                    } else {
                        // Smooth bezier
                        val prev = Offset(
                            points[i - 1].x * animatedProgress,
                            paddingTop + (points[i - 1].y - paddingTop) * animatedProgress +
                                    (canvasH - paddingBottom - paddingTop) * (1f - animatedProgress)
                        )
                        val cx = (prev.x + animX) / 2f
                        linePath.cubicTo(cx, prev.y, cx, animY, animX, animY)
                        fillPath.cubicTo(cx, prev.y, cx, animY, animX, animY)
                    }
                }
                fillPath.lineTo(points.last().x * animatedProgress, canvasH - paddingBottom)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(fillColor, Color.Transparent),
                        startY = paddingTop,
                        endY = canvasH - paddingBottom
                    )
                )

                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                )

                // Draw selected point indicator
                selectedIndex?.let { idx ->
                    if (idx in points.indices) {
                        val selPt = Offset(
                            points[idx].x * animatedProgress,
                            paddingTop + (points[idx].y - paddingTop) * animatedProgress +
                                    (canvasH - paddingBottom - paddingTop) * (1f - animatedProgress)
                        )
                        // Vertical crosshair
                        drawLine(
                            color = lineColor.copy(alpha = 0.3f),
                            start = Offset(selPt.x, paddingTop),
                            end = Offset(selPt.x, canvasH - paddingBottom),
                            strokeWidth = 1.5f
                        )
                        // Outer ring
                        drawCircle(color = lineColor.copy(alpha = 0.3f), radius = 12f, center = selPt)
                        // Inner dot
                        drawCircle(color = lineColor, radius = 6f, center = selPt)
                        drawCircle(color = Color.White, radius = 3f, center = selPt)
                    }
                }
            }
        }
    }
}
