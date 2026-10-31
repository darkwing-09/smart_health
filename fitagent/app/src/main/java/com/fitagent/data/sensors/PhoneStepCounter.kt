package com.fitagent.data.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The phone's hardware step counter — the fallback when no watch app shares steps with Health Connect
 * (common with Noise / boAt). The sensor reports steps since boot; [stepsSince] turns two readings into a delta.
 */
class PhoneStepCounter(private val context: Context) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val isAvailable: Boolean get() = sensor != null

    /** ACTIVITY_RECOGNITION is a runtime permission from Android 10. */
    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Reads the current steps-since-boot value, or null if the sensor is missing, not permitted or silent. */
    suspend fun read(timeoutMs: Long = 5_000): Float? {
        val s = sensor ?: return null
        if (!hasPermission()) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sensorManager.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0])
                    }
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
                }
                if (!sensorManager.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)) {
                    cont.resume(null)
                } else {
                    cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
                }
            }
        }
    }

    companion object {
        /** More than this between two readings is a sensor glitch, not walking. */
        private const val MAX_STEPS_PER_HOUR = 15_000

        /**
         * Steps taken between two counter readings. The counter resets on reboot, so a smaller value (or a reading
         * from before the last boot) means everything counted since boot is new.
         */
        fun stepsSince(previous: Pair<Float, Long>?, current: Float, nowMs: Long, bootMs: Long): Long {
            if (previous == null) return 0
            val (prevValue, prevAt) = previous
            val delta = if (prevAt < bootMs || current < prevValue) current else current - prevValue
            val hours = ((nowMs - prevAt).coerceAtLeast(0) / 3_600_000.0).coerceAtLeast(1.0)
            return if (delta < 0 || delta > MAX_STEPS_PER_HOUR * hours) 0 else delta.toLong()
        }

        fun bootTimeMs(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    }
}
