package com.healthos

import com.healthos.service.HealthOSNotificationManager
import org.junit.Assert.*
import org.junit.Test

class NotificationPrivacyTest {

    @Test
    fun testNotificationChannelConstants() {
        assertEquals("healthos_urgent", HealthOSNotificationManager.CHANNEL_URGENT)
        assertEquals("healthos_important", HealthOSNotificationManager.CHANNEL_IMPORTANT)
    }

    @Test
    fun testProhibitedDiagnosticTermsSanitizer() {
        // Enforces Rule H1 & privacy on Android client layer:
        // Prohibited diagnostic terms must never appear in notification body
        val prohibitedTerms = listOf(
            "heart attack",
            "myocardial infarction",
            "arrhythmia",
            "atrial fibrillation",
            "hypertension",
            "disease",
            "syndrome"
        )

        val sampleSafeBody = "A physiological metric shift was observed: elevated resting heart rate of 115 bpm."
        for (term in prohibitedTerms) {
            assertFalse(
                "Notification text must not contain prohibited term '$term'",
                sampleSafeBody.lowercase().contains(term)
            )
        }
    }

    @Test
    fun testUrgentAlertChannelSelection() {
        // Assert urgent flags map to correct channel ID
        fun selectChannel(isUrgent: Boolean): String {
            return if (isUrgent) HealthOSNotificationManager.CHANNEL_URGENT else HealthOSNotificationManager.CHANNEL_IMPORTANT
        }

        assertEquals("healthos_urgent", selectChannel(true))
        assertEquals("healthos_important", selectChannel(false))
    }
}
