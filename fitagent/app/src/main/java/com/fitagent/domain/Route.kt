package com.fitagent.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * GPS routes (D7). Points stay on the phone. Noisy fixes are dropped before any distance is computed.
 */

data class RoutePoint(val timeMs: Long, val lat: Double, val lon: Double, val accuracyM: Float)

private const val EARTH_M = 6_371_000.0
const val MAX_ACCURACY_M = 30f

/** Great-circle distance in metres. */
fun haversine(a: RoutePoint, b: RoutePoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_M * atan2(sqrt(h), sqrt(1 - h))
}

/** Highest believable speed for the activity, m/s (anything faster is a GPS jump). */
fun maxSpeed(type: WorkoutType): Double = when (type) {
    WorkoutType.CYCLE -> 25.0
    WorkoutType.RUN -> 9.0
    else -> 4.0
}

/** Drops inaccurate fixes and teleports. */
fun cleanRoute(points: List<RoutePoint>, type: WorkoutType): List<RoutePoint> {
    val out = mutableListOf<RoutePoint>()
    for (p in points.sortedBy { it.timeMs }) {
        if (p.accuracyM > MAX_ACCURACY_M) continue
        val last = out.lastOrNull()
        if (last != null) {
            val dt = (p.timeMs - last.timeMs) / 1000.0
            if (dt <= 0 || haversine(last, p) / dt > maxSpeed(type)) continue
        }
        out += p
    }
    return out
}

fun routeDistance(points: List<RoutePoint>): Double = points.zipWithNext().sumOf { (a, b) -> haversine(a, b) }

/** Seconds per completed kilometre (the last partial km is included when ≥ 200 m, scaled to a full km). */
fun kmSplits(points: List<RoutePoint>): List<Int> {
    val splits = mutableListOf<Int>()
    var dist = 0.0
    var splitStart = points.firstOrNull()?.timeMs ?: return emptyList()
    points.zipWithNext().forEach { (a, b) ->
        dist += haversine(a, b)
        if (dist >= 1000) {
            splits += ((b.timeMs - splitStart) / 1000.0 * 1000 / dist).roundToInt()
            dist = 0.0
            splitStart = b.timeMs
        }
    }
    if (dist >= 200) splits += ((points.last().timeMs - splitStart) / 1000.0 * 1000 / dist).roundToInt()
    return splits
}

/** Pace (seconds per km) for each segment, smoothed over a window, for colouring the trace. */
fun segmentPaces(points: List<RoutePoint>, window: Int = 5): List<Double> {
    val raw = points.zipWithNext().map { (a, b) ->
        val d = haversine(a, b)
        if (d < 0.5) Double.NaN else (b.timeMs - a.timeMs) / 1000.0 / d * 1000
    }
    return raw.indices.map { i ->
        val w = raw.subList((i - window).coerceAtLeast(0), (i + window + 1).coerceAtMost(raw.size)).filter { !it.isNaN() }
        if (w.isEmpty()) Double.NaN else w.average()
    }
}

fun formatPace(secondsPerKm: Int): String = "%d:%02d /km".format(secondsPerKm / 60, secondsPerKm % 60)
