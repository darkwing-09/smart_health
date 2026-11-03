package com.fitagent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RepCounterTest {
    /** Side-view leg with a given knee angle: hip above knee, ankle placed so hip–knee–ankle = angle. */
    private fun squatFrame(t: Long, knee: Double, conf: Float = 0.9f): PoseFrame {
        val hip = Landmark(0f, 0f, conf)
        val k = Landmark(0f, 1f, conf)
        val rad = Math.toRadians(180 - knee)
        val ankle = Landmark(sin(rad).toFloat(), (1 + cos(rad)).toFloat(), conf)
        return PoseFrame(t, mapOf(Joint.HIP_L to hip, Joint.KNEE_L to k, Joint.ANKLE_L to ankle))
    }

    @Test fun angleMath() {
        assertEquals(90.0, jointAngle(Landmark(1f, 0f, 1f), Landmark(0f, 0f, 1f), Landmark(0f, 1f, 1f)), 0.01)
        assertEquals(180.0, jointAngle(Landmark(-1f, 0f, 1f), Landmark(0f, 0f, 1f), Landmark(1f, 0f, 1f)), 0.01)
    }

    @Test fun countsFullRepsWithNoise() {
        val c = RepCounter(RepExercise.SQUAT)
        var t = 0L
        repeat(10) { rep ->
            // 30 frames down to 80°, 30 frames back up to 175°, ±4° jitter.
            for (i in 0 until 60) {
                val phase = if (i < 30) i / 30.0 else (60 - i) / 30.0
                val knee = 175 - 95 * phase + (if (i % 2 == 0) 4 else -4)
                c.feed(squatFrame(t, knee)); t += 33
            }
        }
        assertEquals(10, c.count)
    }

    @Test fun shallowRepsAreNotCountedAndExplained() {
        val c = RepCounter(RepExercise.SQUAT)
        var t = 0L
        var tip: String? = null
        for (i in 0 until 60) {
            val phase = if (i < 30) i / 30.0 else (60 - i) / 30.0
            tip = c.feed(squatFrame(t, 175 - 55 * phase)).formTip ?: tip; t += 33 // only down to 120°
        }
        assertEquals(0, c.count)
        assertTrue(tip!!.contains("deeper"))
    }

    @Test fun jitterAroundThresholdDoesNotDoubleCount() {
        val c = RepCounter(RepExercise.SQUAT)
        var t = 0L
        listOf(170, 100, 104, 98, 106, 100, 165, 158, 166, 159, 170).forEach { c.feed(squatFrame(t, it.toDouble())); t += 300 }
        assertEquals(1, c.count)
    }

    @Test fun lowConfidenceFramesAreIgnored() {
        val c = RepCounter(RepExercise.SQUAT)
        val u = c.feed(squatFrame(0, 90.0, conf = 0.2f))
        assertNull(u.angle)
    }

    @Test fun invertedExerciseCountsArmsUpDown() {
        val c = RepCounter(RepExercise.JUMPING_JACK)
        var t = 0L
        fun frame(arm: Double): PoseFrame {
            val shoulder = Landmark(0f, 0f, 0.9f)
            val hip = Landmark(0f, 1f, 0.9f)
            val rad = Math.toRadians(arm)
            return PoseFrame(t, mapOf(Joint.SHOULDER_L to shoulder, Joint.HIP_L to hip,
                Joint.WRIST_L to Landmark(sin(rad).toFloat(), cos(rad).toFloat(), 0.9f)))
        }
        repeat(3) { listOf(20.0, 90.0, 170.0, 90.0, 20.0).forEach { c.feed(frame(it)); t += 200 } }
        assertEquals(3, c.count)
    }
}
