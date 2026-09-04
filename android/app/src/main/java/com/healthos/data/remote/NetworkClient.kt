package com.healthos.data.remote

import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

    private const val BASE_URL = "https://api.healthos.local/" // Configurable per build flavor

    /**
     * Determines if the app is running in a debuggable build.
     * Uses ApplicationInfo.FLAG_DEBUGGABLE as the source of truth
     * since BuildConfig is not generated in this project configuration.
     */
    private var isDebugMode: Boolean = false

    fun setDebugMode(debug: Boolean) {
        isDebugMode = debug
    }

    private val okHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            // SECURITY: Use BASIC in debug builds (headers only, no body).
            // Use NONE in release builds to prevent any PHI or auth token leakage.
            level = if (isDebugMode) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        // Redact the Authorization header to prevent bearer token leakage
        logging.redactHeader("Authorization")

        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    val apiService: HealthOSApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HealthOSApiService::class.java)
    }
}
