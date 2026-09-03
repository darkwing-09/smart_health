# Implementation.md — Technical Implementation Playbook

This playbook provides actionable, engineering-level specifications for implementing every subsystem of Personal Health OS. No generic placeholders or invented third-party APIs are permitted.

---

## 1. Project Repository Structure

A unified monorepo structure separating the Android client, Python backend, analytics engines, and deployment manifests:

```
personal-health-os/
├── android/                        # Android companion app (Kotlin, Jetpack Compose)
│   ├── app/
│   │   ├── src/main/java/com/healthos/
│   │   │   ├── core/               # App-wide utilities, network, crypto
│   │   │   ├── data/
│   │   │   │   ├── adapter/        # Health Connect & vendor source adapters
│   │   │   │   ├── local/          # Room DB (offline queue, user cache)
│   │   │   │   └── remote/         # Retrofit API clients & DTOs
│   │   │   ├── domain/             # Use cases, models, repository interfaces
│   │   │   ├── service/            # WorkManager sync workers, FCM listener
│   │   │   └── ui/                 # Jetpack Compose views, themes, viewmodels
│   │   └── build.gradle.kts
│   └── build.gradle.kts
├── backend/                        # Backend microservices & platform (Python 3.11+)
│   ├── app/
│   │   ├── api/
│   │   │   ├── v1/                 # Endpoints: sync, measurements, findings, reports
│   │   │   └── deps.py             # FastAPI dependency injection (auth, db session)
│   │   ├── core/
│   │   │   ├── config.py           # Pydantic BaseSettings
│   │   │   ├── security.py         # JWT, password hashing, encryption
│   │   │   └── audit.py            # Audit log interceptor
│   │   ├── db/
│   │   │   ├── session.py          # Async SQLAlchemy engine & sessionmaker
│   │   │   └── models/             # Declarative SQLAlchemy ORM models
│   │   ├── services/
│   │   │   ├── ingestion.py        # Normalization & deduplication pipeline
│   │   │   ├── baseline.py         # Rolling EWMA / seasonal statistics
│   │   │   ├── anomaly.py          # Deterministic threshold & z-score classifier
│   │   │   ├── orchestrator.py     # Cadence state machine & event worker
│   │   │   ├── notification.py     # FCM & WhatsApp dispatch
│   │   │   ├── pdf_report.py       # ReportLab PDF compilation
│   │   │   └── care_nav.py         # Provider research & inquiry draft
│   │   ├── agents/
│   │   │   ├── base.py             # Agent runtime, tool runner, context builder
│   │   │   ├── health_intel.py     # 7-part explanation agent
│   │   │   ├── daily_report.py     # Synthesis & dynamic quote generator
│   │   │   └── guardrails.py       # Safety validation layer
│   │   └── main.py                 # FastAPI application factory
│   ├── alembic/                    # Database migrations
│   ├── tests/                      # Pytest suite
│   ├── Dockerfile
│   └── requirements.txt
├── docker/                         # Docker compose & infra configuration
│   ├── docker-compose.yml
│   └── init-timescale.sql
├── files/                          # Engineering operating system (21 docs)
└── README.md
```

---

## 2. Android Client Implementation

### 2.1 Health Connect Permission & Client Initialization
Health Connect provides unified on-device access for Wear OS, Samsung Galaxy Watch (via Health Connect sync), and compatible Android apps.

```kotlin
// android/app/src/main/java/com/healthos/data/adapter/HealthConnectManager.kt
package com.healthos.data.adapter

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

class HealthConnectManager(private val context: Context) {
    val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    suspend fun readHeartRate(startTime: Instant, endTime: Instant): List<HeartRateRecord> {
        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
        )
        return response.records
    }
}
```

### 2.2 Local Offline Queue (Room DB)
To guarantee zero data loss during intermittent network connectivity, all extracted Health Connect records are immediately staged in an offline Room database before transmission.

```kotlin
// android/app/src/main/java/com/healthos/data/local/OfflineMeasurementEntity.kt
package com.healthos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "offline_measurements")
data class OfflineMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val sourceRecordId: String,          // Original Health Connect record ID
    val metricType: String,              // "heart_rate", "steps", "sleep_stage"
    val value: Double,
    val unit: String,
    val recordedAt: Long,                // Epoch milliseconds
    val confidence: Double,
    val syncStatus: String = "PENDING",  // "PENDING", "SYNCING", "FAILED"
    val attempts: Int = 0
)
```

### 2.3 WorkManager Periodic Sync Engine
Background synchronization operates via Android's `PeriodicWorkRequestBuilder` with strict battery and network constraints:

```kotlin
// android/app/src/main/java/com/healthos/service/HealthSyncWorker.kt
package com.healthos.service

import android.content.Context
import androidx.work.*
import com.healthos.data.local.MeasurementDao
import com.healthos.data.remote.SyncApiClient
import java.util.concurrent.TimeUnit

class HealthSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val measurementDao: MeasurementDao,
    private val syncApiClient: SyncApiClient
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val pendingRecords = measurementDao.getPending(batchSize = 250)
        if (pendingRecords.isEmpty()) return Result.success()

        val syncPayload = pendingRecords.map { it.toSyncDto() }
        return try {
            val response = syncApiClient.batchIngest(syncPayload)
            if (response.isSuccessful) {
                measurementDao.markSynced(pendingRecords.map { it.localId })
                Result.success()
            } else {
                if (response.code() in 500..599) Result.retry() else Result.failure()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<HealthSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "HealthOS_SyncWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }
    }
}
```

---

## 3. Backend Ingestion & Timeline Subsystem

### 3.1 FastAPI Ingestion Gateway
The ingestion endpoint requires an `Idempotency-Key` to prevent duplicated measurements from retransmitted batches:

```python
# backend/app/api/v1/sync.py
from fastapi import APIRouter, Depends, Header, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.deps import get_current_user, get_db
from app.models.user import User
from app.schemas.sync import BatchIngestRequest, BatchIngestResponse
from app.services.ingestion import IngestionService

router = APIRouter(prefix="/sync", tags=["synchronization"])

@router.post("/batch", response_model=BatchIngestResponse, status_code=status.HTTP_200_OK)
async def batch_ingest(
    payload: BatchIngestRequest,
    idempotency_key: str = Header(..., description="Unique client batch UUID"),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
):
    service = IngestionService(db)
    result = await service.process_batch(
        user_id=current_user.id,
        idempotency_key=idempotency_key,
        payload=payload
    )
    return result
```

### 3.2 Ingestion & Deduplication Pipeline
```python
# backend/app/services/ingestion.py
from typing import List
from uuid import UUID
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.dialects.postgresql import insert
from app.models.measurement import Measurement
from app.models.sync_batch import SyncBatch
from app.schemas.sync import BatchIngestRequest, BatchIngestResponse

class IngestionService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def process_batch(self, user_id: UUID, idempotency_key: str, payload: BatchIngestRequest) -> BatchIngestResponse:
        # 1. Idempotency Check
        existing_batch = await self.db.get(SyncBatch, idempotency_key)
        if existing_batch:
            return BatchIngestResponse(
                status="ALREADY_PROCESSED",
                accepted_count=existing_batch.accepted_count,
                duplicate_count=existing_batch.duplicate_count
            )

        # 2. Bulk Upsert with Deduplication on (user_id, source_id, recorded_at, metric_type)
        accepted = 0
        duplicates = 0
        
        for item in payload.measurements:
            stmt = insert(Measurement).values(
                user_id=user_id,
                source_id=payload.source_id,
                metric_type=item.metric_type,
                value=item.value,
                unit=item.unit,
                recorded_at=item.recorded_at,
                confidence=item.confidence,
                data_quality_flag=item.data_quality_flag
            ).on_conflict_do_nothing(
                index_elements=["user_id", "source_id", "metric_type", "recorded_at"]
            )
            res = await self.db.execute(stmt)
            if res.rowcount > 0:
                accepted += 1
            else:
                duplicates += 1

        # 3. Record SyncBatch metadata
        batch_record = SyncBatch(
            id=idempotency_key,
            user_id=user_id,
            accepted_count=accepted,
            duplicate_count=duplicates
        )
        self.db.add(batch_record)
        await self.db.commit()

        # 4. Trigger Event-Driven Pipeline if hard thresholds crossed
        await self._trigger_event_checks(user_id, payload.measurements)

        return BatchIngestResponse(
            status="SUCCESS",
            accepted_count=accepted,
            duplicate_count=duplicates
        )

    async def _trigger_event_checks(self, user_id: UUID, items):
        # Fast deterministic hard checks (e.g., resting HR > 140 or < 40 sustained)
        pass
```

---

## 4. Deterministic Analytics Engine

### 4.1 Rolling Personal Baseline Service
Computes mean, variance, and circadian seasonality for each metric using a rolling 30-day window:

```python
# backend/app/services/baseline.py
import numpy as np
from datetime import datetime, timedelta, timezone
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.models.measurement import Measurement
from app.models.baseline import Baseline

class BaselineService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def compute_user_baseline(self, user_id: str, metric_type: str, current_date: datetime) -> Baseline:
        window_start = current_date - timedelta(days=30)
        
        stmt = select(Measurement.value, Measurement.recorded_at).where(
            Measurement.user_id == user_id,
            Measurement.metric_type == metric_type,
            Measurement.recorded_at >= window_start,
            Measurement.recorded_at <= current_date,
            Measurement.data_quality_flag == "nominal"
        )
        result = await self.db.execute(stmt)
        records = result.all()

        if len(records) < 14 * 24: # Less than minimum established window
            is_established = False
            mean_val = float(np.mean([r[0] for r in records])) if records else 0.0
            std_val = float(np.std([r[0] for r in records])) if len(records) > 1 else 0.0
        else:
            is_established = True
            values = np.array([r[0] for r in records])
            mean_val = float(np.mean(values))
            std_val = float(np.std(values))

        # Hourly circadian breakdown (00:00 - 23:00 buckets)
        seasonality = {}
        for hour in range(24):
            hour_values = [r[0] for r in records if r[1].hour == hour]
            if hour_values:
                seasonality[str(hour)] = {
                    "mean": float(np.mean(hour_values)),
                    "std": float(np.std(hour_values)) if len(hour_values) > 1 else 0.0
                }

        baseline = Baseline(
            user_id=user_id,
            metric_type=metric_type,
            window_start=window_start,
            window_end=current_date,
            mean=mean_val,
            stddev=std_val,
            seasonality_profile=seasonality,
            established=is_established,
            rule_version="1.0.0"
        )
        self.db.add(baseline)
        await self.db.commit()
        return baseline
```

### 4.2 Anomaly Classification Service (Deterministic)
Evaluates readings against the established baseline and classifies candidate deviations into severity tiers:

```python
# backend/app/services/anomaly.py
from typing import Optional
from app.models.baseline import Baseline
from app.models.finding import Finding

class AnomalyDetector:
    @staticmethod
    def evaluate_reading(
        current_val: float,
        reading_hour: int,
        baseline: Baseline
    ) -> Optional[dict]:
        if not baseline.established:
            return None # Do not alert during baseline learning period unless hard physiological bounds breached

        # Prefer circadian hour stats if available
        hour_key = str(reading_hour)
        if baseline.seasonality_profile and hour_key in baseline.seasonality_profile:
            expected_mean = baseline.seasonality_profile[hour_key]["mean"]
            expected_std = max(baseline.seasonality_profile[hour_key]["std"], 1.0)
        else:
            expected_mean = baseline.mean
            expected_std = max(baseline.stddev, 1.0)

        z_score = (current_val - expected_mean) / expected_std

        # Classify severity based on deterministic thresholds
        if abs(z_score) < 2.0:
            return None # Normal variation
        elif 2.0 <= abs(z_score) < 2.8:
            severity = "unusual"
        elif 2.8 <= abs(z_score) < 3.8:
            severity = "worth_monitoring"
        elif 3.8 <= abs(z_score) < 5.0:
            severity = "potentially_concerning"
        else:
            severity = "urgent"

        return {
            "severity": severity,
            "z_score": round(z_score, 2),
            "expected_mean": expected_mean,
            "expected_std": expected_std,
            "observed_value": current_val
        }
```

---

## 5. Agent Layer & Orchestration

### 5.1 Finding State Machine (ADR-005)
```
   [Deterministic Detection]
             │
             ▼
        ┌─────────┐
        │   NEW   │
        └────┬────┘
             │ Agent generates 7-part explanation
             ▼
        ┌─────────┐
        │ NOTIFIED│ ◄──────────┐ Escalation to higher severity
        └────┬────┘            │ (Re-alert allowed)
             │                 │
     ┌───────┴────────┐        │
     ▼                ▼        │
┌──────────┐   ┌────────────┐  │
│ACKNOWLEDGED  │ ESCALATED  ├──┘
└────┬─────┘   └────────────┘
     │ Underlying metric returns to personal baseline
     ▼
┌──────────┐
│ RESOLVED │
└──────────┘
```

### 5.2 Health Intelligence Agent Implementation
Uses strict Pydantic output parsing to guarantee the mandatory 7-part explanation:

```python
# backend/app/agents/health_intel.py
from pydantic import BaseModel, Field
from typing import List
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI

class HealthExplanationSchema(BaseModel):
    what_changed: str = Field(description="Clear 1-sentence summary of the physiological shift")
    measurements_caused: List[str] = Field(description="Exact metric values and timestamps that triggered this flag")
    baseline_difference: str = Field(description="Statistical comparison against personal baseline")
    historical_context: str = Field(description="Historical occurrence of similar patterns for this user")
    confidence_and_data_quality: str = Field(description="Assessment of sensor quality, completeness, and confidence")
    why_it_matters: str = Field(description="Physiological context without clinical diagnosis")
    next_steps: List[str] = Field(description="Actionable, safe steps to consider (non-diagnostic)")

async def generate_explanation(flag_data: dict, baseline_data: dict) -> HealthExplanationSchema:
    prompt = ChatPromptTemplate.from_messages([
        ("system", "You are the Health Intelligence Agent for Personal Health OS. Provide an objective, calm, grounded explanation of the deterministic anomaly. Never diagnose, never fabricate facts. Ground all statements strictly in the supplied data."),
        ("user", "Deterministic Anomaly Data: {flag_data}\nPersonal Baseline Data: {baseline_data}")
    ])
    
    llm = ChatOpenAI(model="gpt-4o", temperature=0.1)
    structured_llm = llm.with_structured_output(HealthExplanationSchema)
    
    chain = prompt | structured_llm
    return await chain.ainvoke({"flag_data": flag_data, "baseline_data": baseline_data})
```

---

## 6. Daily Report Generation (PDF Engine)

The Daily Report compiles daily metrics, open findings, and a dynamic closing quote into a vector PDF:

```python
# backend/app/services/pdf_report.py
import io
from reportlab.lib.pagesizes import letter
from reportlab.lib import colors
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle

class DailyReportGenerator:
    @staticmethod
    def build_pdf(report_data: dict) -> bytes:
        buffer = io.BytesIO()
        doc = SimpleDocTemplate(buffer, pagesize=letter, rightMargin=36, leftMargin=36, topMargin=36, bottomMargin=36)
        story = []
        styles = getSampleStyleSheet()

        # Header Title
        title_style = ParagraphStyle(
            'ReportTitle',
            parent=styles['Heading1'],
            fontSize=20,
            leading=24,
            textColor=colors.HexColor("#1A365D")
        )
        story.append(Paragraph(f"Personal Health OS — Daily Digest ({report_data['date']})", title_style))
        story.append(Spacer(1, 14))

        # Trends Summary
        story.append(Paragraph("<b>Daily Metric Summary</b>", styles['Heading2']))
        table_data = [["Metric", "Value", "Baseline Mean", "Status"]]
        for row in report_data['metrics']:
            table_data.append([row['name'], str(row['value']), str(row['baseline']), row['status']])
        
        t = Table(table_data, colWidths=[150, 100, 100, 150])
        t.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor("#EDF2F7")),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.HexColor("#2D3748")),
            ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
            ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
            ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor("#CBD5E0"))
        ]))
        story.append(t)
        story.append(Spacer(1, 18))

        # LLM Synthesized Narrative
        story.append(Paragraph("<b>Observations & Trends</b>", styles['Heading2']))
        story.append(Paragraph(report_data['narrative'], styles['Normal']))
        story.append(Spacer(1, 18))

        # Dynamic Reflective Quote
        quote_style = ParagraphStyle(
            'QuoteStyle',
            parent=styles['Italic'],
            fontSize=11,
            leading=14,
            textColor=colors.HexColor("#4A5568")
        )
        story.append(Paragraph(f"“{report_data['closing_quote']}”", quote_style))

        doc.build(story)
        buffer.seek(0)
        return buffer.getvalue()
```

---

## 7. External Integrations: Status & Unknowns

| Integration | Category | MVP Status | Implementation Unknowns & Technical Realities |
| :--- | :--- | :--- | :--- |
| **Android Health Connect** | Ingestion | **MVP** | OEM variations in background sync permissions; requires handling edge-case missing permissions. |
| **Firebase Cloud Messaging** | Notification | **MVP** | Self-serve; payload structure must be kept under 4KB; tokens refreshed via Android listener. |
| **WhatsApp Business Platform** | Notification | **DEFERRED (V1)** | **EXTERNAL DEPENDENCY — VERIFY BEFORE IMPLEMENTATION**: Requires Meta App Review, verified WABA, and pre-approved template HSMs for unsolicited health alerts. |
| **Fitbit Web API** | Ingestion | **DEFERRED (V1)** | OAuth2 token refresh cycle, 150 req/hr rate limits; needs batch webhook subscription. |
| **Garmin Health API** | Ingestion | **DEFERRED (V2)** | **EXTERNAL DEPENDENCY**: Enterprise B2B contract required; no self-serve developer portal. |
| **Hospital / Doctor Discovery** | Care Nav | **DEFERRED (V1)** | **EXTERNAL DEPENDENCY — VERIFY BEFORE IMPLEMENTATION**: No unified India-wide provider API. Google Places / OpenStreetMap for clinics; real-time slot checking requires licensed aggregator partnerships. |
| **Appointment Booking** | Care Nav | **DEFERRED (V3)** | **DEFERRED — NOT MVP**: Autonomous booking disallowed per ADR-003 pending legal compliance review. |
