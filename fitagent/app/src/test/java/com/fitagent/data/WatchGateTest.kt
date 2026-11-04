package com.fitagent.data

import com.fitagent.data.health.HealthConnectSource
import com.fitagent.domain.DeviceDay
import com.fitagent.domain.watchReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The no-dummy-data rule: nothing about the body is shown unless a watch delivered it. */
class WatchGateTest {
    private val hour = 3_600_000L
    private val now = 1_000 * hour
    private val day = LocalDate.of(2026, 11, 4)
    private fun src(wearable: Boolean, last: Long, label: String = "NoiseFit") =
        DataSource("pkg.$label", label, if (wearable) "ColorFit Pro 5" else null, last, 10, listOf("Steps"), wearable)

    @Test fun noSourcesMeansNoWatch() = assertEquals(WatchState.None, WatchState.from(emptyList(), now))

    @Test fun phoneOnlySourceIsNotAWatch() =
        assertEquals(WatchState.None, WatchState.from(listOf(src(false, now - hour, "Google Fit")), now))

    @Test fun recentWearableIsConnected() {
        val w = WatchState.from(listOf(src(true, now - 2 * hour), src(false, now, "Google Fit")), now)
        assertTrue(w is WatchState.Connected)
        assertEquals("ColorFit Pro 5", (w as WatchState.Connected).device)
    }

    @Test fun quietWearableIsStale() {
        val w = WatchState.from(listOf(src(true, now - 30 * hour)), now)
        assertTrue(w is WatchState.Stale)
        assertTrue(w.hasWatch)
    }

    @Test fun newestWearableWins() {
        val w = WatchState.from(listOf(src(true, now - 40 * hour, "boAt Crest"), src(true, now - hour, "NoiseFit")), now)
        assertEquals("NoiseFit", (w as WatchState.Connected).label)
    }

    @Test fun noWatchHidesEverything() {
        val watch = mapOf(day to DeviceDay(day, steps = 6000))
        val phone = mapOf(day to 4000L)
        assertTrue(FitnessRepository.visibleDays(watch, phone, WatchState.None, usePhoneSteps = false).isEmpty())
    }

    @Test fun phoneStepsOnlyWhenOptedIn() {
        val phone = mapOf(day to 4000L)
        val shown = FitnessRepository.visibleDays(emptyMap(), phone, WatchState.None, usePhoneSteps = true)
        assertEquals(4000L, shown[day]?.steps)
    }

    @Test fun watchDataShownWhenConnected() {
        val watch = mapOf(day to DeviceDay(day, steps = 6000))
        val shown = FitnessRepository.visibleDays(watch, emptyMap(), WatchState.Connected("NoiseFit", null, now), false)
        assertEquals(6000L, shown[day]?.steps)
    }

    @Test fun wearableDetection() {
        assertTrue(HealthConnectSource.isWearable("com.noisefit", null))
        assertFalse(HealthConnectSource.isWearable("com.google.android.apps.fitness", null))
    }

    @Test fun reminders() {
        assertEquals("Connect your watch", watchReminder(null, null, now)?.first)
        assertNull(watchReminder("NoiseFit", now - hour, now))
        val stale = watchReminder("boAt Crest", now - 30 * hour, now)
        assertNotNull(stale)
        assertTrue(stale!!.first.contains("yesterday"))
        assertTrue(watchReminder("boAt Crest", now - 80 * hour, now)!!.first.contains("3 days ago"))
    }
}
