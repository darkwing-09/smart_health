package com.fitagent.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same expectations as the phone's domain tests — the formulas must not drift. */
class WatchModelTest {
    @Test fun intervalsMatchPhone() {
        val c = Intervals.TABATA
        assertEquals(PhaseState(Phase.WORK, 1, 20), phaseAt(c, 0))
        assertEquals(PhaseState(Phase.REST, 1, 10), phaseAt(c, 20))
        assertEquals(PhaseState(Phase.WORK, 2, 20), phaseAt(c, 30))
        assertEquals(Phase.DONE, phaseAt(c, c.totalSeconds.toLong()).phase)
    }

    @Test fun zonesMatchPhone() {
        assertEquals(194, hrMax(20))
        assertEquals(listOf(0, 2, 5), listOf(80, 120, 180).map { zoneOf(it, 194) })
    }

    @Test fun hrAccumulatorIsTimeWeighted() {
        val acc = HrAccumulator(200)
        (0 until 20).forEach { acc.add(it * 1000L, if (it < 10) 110 else 150) }
        assertEquals(130, acc.average)
        assertEquals(150, acc.peak)
        assertNull(HrAccumulator(200).apply { add(0, 100) }.average)
    }
}
