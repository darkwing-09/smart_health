package com.healthos.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface HealthOSApiService {

    @POST("v1/sync/batch")
    suspend fun syncBatch(
        @Header("Authorization") bearerToken: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body payload: BatchIngestRequestDto
    ): Response<BatchIngestResponseDto>

    @GET("v1/insights/daily")
    suspend fun getDailyInsight(
        @Header("Authorization") bearerToken: String
    ): Response<DailyInsightDto>

    @GET("v1/reports/daily")
    suspend fun listReports(
        @Header("Authorization") bearerToken: String,
        @Query("limit") limit: Int = 14
    ): Response<ReportListResponseDto>

    @GET("v1/reports/daily/{reportId}/download")
    @Streaming
    suspend fun downloadReportPdf(
        @Header("Authorization") bearerToken: String,
        @Path("reportId") reportId: String
    ): Response<ResponseBody>
}

