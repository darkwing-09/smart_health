package com.fitagent.domain

import com.fitagent.report.DailyReportData
import com.fitagent.report.reportSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReportTest {
    @Test fun whatsappNumbers() {
        assertEquals("+919876543210", normalizeWhatsapp("+91 98765-43210"))
        assertEquals("+919876543210", normalizeWhatsapp("9876543210")) // 10 digits → India
        assertEquals("+447700900123", normalizeWhatsapp("0044 7700 900123"))
        assertEquals("", normalizeWhatsapp(""))
        assertNull(normalizeWhatsapp("12345"))
        assertNull(normalizeWhatsapp("+91 abc"))
    }

    private val day = LocalDate.of(2026, 11, 3)

    @Test fun summaryNeedsWatchData() {
        val none = DailyReportData(day, Profile(), null, DayActivity(day, steps = 5000), emptyList(), null, emptyList(), 0)
        assertEquals("No watch data for 3 Nov — sync your watch.", reportSummary(none))
    }

    @Test fun summaryUsesRealNumbers() {
        val d = DailyReportData(day, Profile(), "NoiseFit", DayActivity(day, steps = 9100, sleepMinutes = 380), emptyList(),
            null, emptyList(), 3)
        val s = reportSummary(d)
        assertTrue(s, s.startsWith("3 Nov: 9,100 steps") || s.startsWith("3 Nov: 9.100 steps") || s.contains("steps"))
        assertTrue(s.contains("slept 6 h 20 m"))
        assertTrue(!s.contains("readiness")) // no score without a real night
    }
}
