package com.fitagent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    /** Points heading north at [speed] m/s, one per 2 s (1° latitude ≈ 111.2 km). */
    private fun line(n: Int, speed: Double, startMs: Long = 0) = (0 until n).map { i ->
        RoutePoint(startMs + i * 2000L, 28.6 + i * 2 * speed / 111_195.0, 77.2, 5f)
    }

    @Test fun haversineMatchesKnownDistance() {
        val a = RoutePoint(0, 28.6139, 77.2090, 5f) // New Delhi
        val b = RoutePoint(0, 19.0760, 72.8777, 5f) // Mumbai
        assertEquals(1_148_000.0, haversine(a, b), 5_000.0)
    }

    @Test fun cleaningDropsInaccurateFixesAndTeleports() {
        val pts = line(10, 3.0).toMutableList()
        pts.add(5, pts[5].copy(timeMs = pts[5].timeMs + 1, accuracyM = 80f))       // inaccurate
        pts.add(7, pts[7].copy(timeMs = pts[7].timeMs + 1, lat = pts[7].lat + 0.01)) // 1 km jump in 1 ms
        val clean = cleanRoute(pts, WorkoutType.RUN)
        assertEquals(10, clean.size)
        assertEquals(9 * 2 * 3.0, routeDistance(clean), 0.5)
    }

    @Test fun splitsAreSecondsPerKilometre() {
        val pts = line(800, 3.0) // 3 m/s for ~4.8 km → 333 s/km
        val splits = kmSplits(pts)
        assertEquals(5, splits.size)
        splits.forEach { assertEquals(333.0, it.toDouble(), 2.0) }
        assertEquals("5:33 /km", formatPace(333))
        assertTrue(segmentPaces(pts).all { kotlin.math.abs(it - 333.3) < 1 })
    }
}
