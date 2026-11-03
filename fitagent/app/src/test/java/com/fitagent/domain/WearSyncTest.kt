package com.fitagent.domain

import com.fitagent.wearsync.parseWatchWorkout
import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearSyncTest {
    private fun map(block: DataMap.() -> Unit) = DataMap().apply {
        putInt("v", 1); putString("uid", "wear:abc"); putString("type", "RUN")
        putLong("start_ms", 1_000_000); putLong("end_ms", 2_800_000); putLong("active_s", 1_800); putDouble("kcal", 310.0)
        putInt("avg_hr", 148); putInt("max_hr", 176); putIntegerArrayList("zone_s", arrayListOf(60, 300, 900, 480, 60))
        block()
    }

    @Test fun parsesAWatchWorkout() {
        val w = parseWatchWorkout(map {})!!
        assertEquals("wear:abc", w.uid)
        assertEquals(WorkoutType.RUN, w.type)
        assertEquals(HrSummary(148, 176, listOf(60, 300, 900, 480, 60)), w.hr)
    }

    @Test fun rejectsUnknownVersionsBadIdsAndImpossibleTimes() {
        assertNull(parseWatchWorkout(map { putInt("v", 2) }))
        assertNull(parseWatchWorkout(map { putString("uid", "app:abc") }))
        assertNull(parseWatchWorkout(map { putLong("end_ms", 500) }))
        assertNull(parseWatchWorkout(map { putLong("active_s", 10) }))
    }
}
