package com.fitagent.domain

import com.fitagent.data.ble.HrMeasurement
import com.fitagent.data.ble.parseHeartRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun parses8And16BitHeartRate() {
        assertEquals(HrMeasurement(72, emptyList(), null), parseHeartRate(bytes(0x00, 72)))
        assertEquals(HrMeasurement(180, emptyList(), null), parseHeartRate(bytes(0x01, 180, 0)))
        assertEquals(HrMeasurement(140, emptyList(), true), parseHeartRate(bytes(0x06, 140)))
        assertEquals(false, parseHeartRate(bytes(0x04, 140))!!.contact)
    }

    @Test fun parsesEnergyAndRrIntervals() {
        // flags: RR present (0x10) + energy (0x08); bpm 65; energy 2 bytes; RR 1024 → 1000 ms, 512 → 500 ms
        val m = parseHeartRate(bytes(0x18, 65, 0x10, 0x00, 0x00, 0x04, 0x00, 0x02))!!
        assertEquals(65, m.bpm)
        assertEquals(listOf(1000, 500), m.rrMs)
    }

    @Test fun rejectsMalformedPackets() {
        assertNull(parseHeartRate(bytes(0x00)))
        assertNull(parseHeartRate(bytes(0x01, 80)))
        assertNull(parseHeartRate(bytes(0x00, 0)))
    }

    @Test fun summaryIsTimeWeightedAndCapsGaps() {
        val max = 200
        val samples = (0 until 20).map { it * 1000L to if (it < 10) 110 else 150 } + (80_000L to 150)
        val s = hrSummary(samples, max)!!
        assertEquals(150, s.max)
        // 9 s at 110 + 1 s transition at 110 → 10 s; 150 for 9 s + 1 sample capped to 5 s.
        assertEquals(listOf(10, 0, 14, 0, 0), s.zoneSeconds)
        assertNull(hrSummary(samples.take(5), max))
    }
}
