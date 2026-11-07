package com.fitagent.data

import com.fitagent.data.health.HealthConnectSource
import com.fitagent.domain.basalKcalPerDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** NoiseFit / Google Fit write total calories only; FitAgent's Move ring needs active calories. */
class CaloriesTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun mifflinStJeor() {
        assertEquals(1765.0, basalKcalPerDay(73.0, 180, 19, "male"), 0.5) // 730 + 1125 - 95 + 5
        assertEquals(1370.25, basalKcalPerDay(60.0, 165, 20, "female"), 0.5) // 600 + 1031.25 - 100 - 161
    }

    @Test fun activeIsTotalMinusBasalForTheCoveredPartOfTheDay() {
        assertEquals(500.0, HealthConnectSource.activeFromTotal(2200.0, 1700.0, 1.0), 0.01) // full past day
        assertEquals(150.0, HealthConnectSource.activeFromTotal(1000.0, 1700.0, 0.5), 0.01) // midday: 850 basal so far
        assertEquals(0.0, HealthConnectSource.activeFromTotal(300.0, 1700.0, 0.5), 0.01) // never negative
    }

    @Test fun dayFraction() {
        val now = ZonedDateTime.of(2026, 11, 8, 6, 0, 0, 0, zone)
        assertEquals(1.0, HealthConnectSource.dayFraction(LocalDate.of(2026, 11, 7), zone, now), 0.0)
        assertEquals(0.25, HealthConnectSource.dayFraction(LocalDate.of(2026, 11, 8), zone, now), 0.001)
    }
}
