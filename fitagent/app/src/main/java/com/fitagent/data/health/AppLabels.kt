package com.fitagent.data.health

import android.content.Context
import com.fitagent.domain.PHONE_SENSOR_SOURCE

/** Human names for the apps that write health data. Installed apps report their own label. */
object AppLabels {
    /** Watch companion apps we know about; also declared in the manifest's <queries> so labels resolve. */
    val KNOWN: Map<String, String> = mapOf(
        "com.noisefit" to "NoiseFit",
        "com.noisefit.prime" to "NoiseFit Prime",
        "com.crrepa.band.noise" to "NoiseFit Track",
        "com.zjw.noisefitsync" to "NoiseFit Sync",
        "com.coveiot.android.boat" to "boAt Crest",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.fitbit.FitbitMobile" to "Fitbit",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "com.huami.watch.hmwatchmanager" to "Zepp (Amazfit)",
        "com.xiaomi.wearable" to "Mi Fitness",
        "com.xiaomi.hm.health" to "Zepp Life",
        "com.mi.health" to "Mi Health",
        "com.google.android.apps.healthdata" to "Health Connect",
    )

    fun label(context: Context, id: String): String {
        if (id == PHONE_SENSOR_SOURCE) return "This phone's step sensor"
        return runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION") // the flags overload is API 33+; minSdk is 28
            pm.getApplicationLabel(pm.getApplicationInfo(id, 0)).toString()
        }.getOrNull() ?: KNOWN[id] ?: id
    }
}
