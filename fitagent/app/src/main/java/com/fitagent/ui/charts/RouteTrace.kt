package com.fitagent.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fitagent.domain.RoutePoint
import kotlin.math.cos

/**
 * The route's shape, coloured by pace (fast → [fast], slow → [slow]); no map tiles, so it works offline and
 * never sends location to a map provider.
 */
@Composable
fun RouteTrace(points: List<RoutePoint>, paces: List<Double>, fast: Color, slow: Color, modifier: Modifier = Modifier, description: String = "") {
    val reveal = rememberReveal(points.size)
    Canvas(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }) {
        if (points.size < 2) return@Canvas
        val lat0 = points.map { it.lat }.average()
        val k = cos(Math.toRadians(lat0))
        val xs = points.map { it.lon * k }
        val ys = points.map { it.lat }
        val w = (xs.max() - xs.min()).takeIf { it > 0 } ?: 1e-6
        val h = (ys.max() - ys.min()).takeIf { it > 0 } ?: 1e-6
        val pad = 12.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
        val ox = (size.width - w * scale) / 2
        val oy = (size.height - h * scale) / 2
        fun at(i: Int) = Offset((ox + (xs[i] - xs.min()) * scale).toFloat(), (oy + (ys.max() - ys[i]) * scale).toFloat())
        val valid = paces.filter { !it.isNaN() }
        val lo = valid.minOrNull() ?: 0.0
        val hi = valid.maxOrNull() ?: 1.0
        val visible = ((points.size - 1) * reveal).toInt()
        for (i in 0 until visible) {
            val p = paces.getOrNull(i)?.takeIf { !it.isNaN() }
            val f = if (p == null || hi <= lo) 0.5f else ((p - lo) / (hi - lo)).toFloat()
            drawLine(lerp(fast, slow, f), at(i), at(i + 1), 4.dp.toPx(), StrokeCap.Round)
        }
        drawCircle(fast, 6.dp.toPx(), at(0))
        drawCircle(Color.White, 3.dp.toPx(), at(0))
        drawCircle(slow, 6.dp.toPx(), at(points.lastIndex))
    }
}
