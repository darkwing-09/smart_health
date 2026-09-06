package com.healthos.data.remote

import com.google.gson.annotations.SerializedName

data class MeasurementItemDto(
    @SerializedName("source_record_id")
    val sourceRecordId: String,

    @SerializedName("metric_type")
    val metricType: String,

    @SerializedName("value")
    val value: Double,

    @SerializedName("unit")
    val unit: String,

    @SerializedName("recorded_at")
    val recordedAt: String, // ISO-8601 UTC

    @SerializedName("confidence")
    val confidence: Double = 1.0,

    @SerializedName("data_quality_flag")
    val dataQualityFlag: String = "nominal"
)

data class BatchIngestRequestDto(
    @SerializedName("source_id")
    val sourceId: String,

    @SerializedName("device_id")
    val deviceId: String? = null,

    @SerializedName("client_sync_timestamp")
    val clientSyncTimestamp: String,

    @SerializedName("measurements")
    val measurements: List<MeasurementItemDto>
)

data class BatchIngestResponseDto(
    @SerializedName("status")
    val status: String,

    @SerializedName("batch_id")
    val batchId: String,

    @SerializedName("accepted_count")
    val acceptedCount: Int,

    @SerializedName("duplicate_count")
    val duplicateCount: Int,

    @SerializedName("ingested_at")
    val ingestedAt: String
)

data class ReportItemDto(
    @SerializedName("report_id")
    val reportId: String,

    @SerializedName("date")
    val date: String,

    @SerializedName("generation_status")
    val generationStatus: String,

    @SerializedName("closing_quote")
    val closingQuote: String? = null,

    @SerializedName("pdf_download_url")
    val pdfDownloadUrl: String
)

data class ReportListResponseDto(
    @SerializedName("reports")
    val reports: List<ReportItemDto>
)

data class DailyInsightDto(
    @SerializedName("headline")
    val headline: String,

    @SerializedName("narrative")
    val narrative: String,

    @SerializedName("category")
    val category: String = "RECOVERY",

    @SerializedName("recommendation")
    val recommendation: String? = null,

    @SerializedName("confidence")
    val confidence: Double = 0.95,

    @SerializedName("is_fallback")
    val isFallback: Boolean = false,

    @SerializedName("disclaimer")
    val disclaimer: String = ""
)

