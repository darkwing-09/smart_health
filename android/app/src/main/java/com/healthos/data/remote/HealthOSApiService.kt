package com.healthos.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface HealthOSApiService {

    @POST("v1/sync/batch")
    suspend fun syncBatch(
        @Header("Authorization") bearerToken: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body payload: BatchIngestRequestDto
    ): Response<BatchIngestResponseDto>
}
