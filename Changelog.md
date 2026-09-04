# Changelog.md — Chronological Release & Change History

All notable changes to Personal Health OS are documented in this file. Format adheres to [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.7.0] - 2026-09-04

### Added
- **Phase 7: Alert Hierarchy, Real-Time Streaming & Notification Delivery Engine (VERIFIED):**
  - **Alembic Migration `20260904_0005_notification_state_machine`:**
    - Upgraded `notifications` table with state machine tracking columns: `state`, `retry_count`, `max_retries`, `next_retry_at`, `delivered_at`, `dismissed_at`, `expires_at`, and `quiet_hours_held`.
    - Added high-throughput composite indices: `idx_notifications_user_state (user_id, state)` and `idx_notifications_held_retry (quiet_hours_held, next_retry_at)`.
  - **Deterministic 5-Tier Notification Policy (`NotificationPolicyEngine`):**
    - Enforced pure mathematical mapping from Finding severity to 5 alert tiers: Level 0 Info, Level 1 Insight, Level 2 Attention, Level 3 Important, Level 4 Urgent.
    - Permanently prohibited LLM from computing, altering, or overriding alert tier or safety level.
    - Appended mandatory calm emergency disclaimer on all Level 4 Urgent alerts.
  - **Timezone-Aware Quiet Hours Engine (`QuietHoursEvaluator`):**
    - Dynamic user timezone resolution using Python `zoneinfo.ZoneInfo`, with graceful UTC fallback.
    - Full overnight interval calculation (e.g. 22:00–07:00 local time) with morning release timestamp calculation.
    - Deterministic Level 4 Emergency Override: Level 4 alerts immediately bypass quiet hours and dispatch without delay.
  - **Atomic 12-Hour Deduplication & Severity Escalation Bypass:**
    - Race-safe database-level deduplication preventing alert fatigue for identical user/finding/channel pairs within a 12-hour window.
    - Escalation bypass: Higher-severity findings (e.g., Level 2 Attention escalating to Level 4 Urgent) immediately bypass suppression.
  - **Authoritative 7-State Notification State Machine (`NotificationStateMachine`):**
    - State lifecycle: `CREATED -> POLICY_EVALUATED -> DEDUP_CHECKED -> QUEUED -> DISPATCHING -> DELIVERED`, retries (`RETRYING`), dead-lettering (`DEAD_LETTER`), and user actions (`ACKNOWLEDGED`, `DISMISSED`).
    - Validates all forward transitions and prevents invalid backward state alterations. Persisted authoritatively in PostgreSQL 16.
  - **FCM HTTP v1 Push Notification Dispatcher (`FcmNotificationService`):**
    - Full Google Firebase Cloud Messaging HTTP v1 REST API payload formatting.
    - Notification channel segregation: `healthos_urgent` (high priority, heads-up banner) and `healthos_important` (normal priority).
    - Invalid device token deactivation (`UNREGISTERED` / `INVALID_ARGUMENT`), bounded exponential backoff (max 3 retries), and dry-run simulation mode.
  - **Real-Time WebSocket Streaming & Catch-Up Protocol (`ConnectionManager` & `/v1/ws/stream`):**
    - Authenticated per-user WebSocket streaming with strict multi-tenant isolation.
    - Periodic ping/pong heartbeats to detect disconnected clients.
    - Domain event broadcasting for live findings, notifications, and telemetry updates.
    - Missed-event catch-up replay protocol allowing clients to fetch undelivered notifications using a timestamp cursor upon reconnect.
  - **Notification & User Preference REST APIs:**
    - `GET /v1/notifications` with cursor-based pagination and status filtering.
    - `GET /v1/notifications/{id}` for single alert lookup.
    - `POST /v1/notifications/{id}/acknowledge` and `POST /v1/notifications/{id}/dismiss` for user lifecycle interaction.
    - `GET /v1/users/preferences` and `PUT /v1/users/preferences` for timezone, quiet hours, and channel settings.
    - `POST /v1/devices/fcm-token` for device push registration.
  - **Notification Orchestration Graph (`NotificationGraph`):**
    - LangGraph stateful graph implementing pure orchestration: deterministic alert tiering, deduplication, quiet hours hold, and delivery channel routing.
    - Zero LLM medical inference; fully functional under complete LLM outage.
  - **Android Notification Channels & Feed (SDK 34):**
    - `HealthOSNotificationManager.kt` configures system notification channels with audio attributes and vibrations.
    - `NotificationsScreen.kt` Jetpack Compose reactive feed with acknowledge/dismiss buttons and navigation to Finding detail.
    - Android compilation verified with Gradle 8.4 (`compileDebugSources` clean, marked **✅ COMPILES**).
  - **Notification Fatigue Telemetry & ARQ Cadence:**
    - `NotificationMetricsService` records notifications/day, tier distributions, suppressions, holds, and delivery latencies.
    - ARQ worker cadence upgraded with 15-minute cron `cron_release_quiet_hour_notifications` to dispatch held notifications when quiet hours conclude.
  - **Architectural Decision Records (ADR-025 to ADR-029):**
    - Documented in `Decisions.md`: Deterministic notification severity ownership, authoritative PostgreSQL state machine, WebSocket transport/source-of-truth separation, atomic 12-hour deduplication with escalation bypass, and timezone-aware quiet hours with emergency override.
  - **Automated Test Suite Expansion:**
    - 22 new tests across unit, graph, and real E2E integration suites.
    - Total test suite expanded to **82 / 82 PASSING** in 6.17s.

---

## [0.6.0] - 2026-09-04

### Added
- **Phase 6: Production Hardening, Security Auditing & Operational Readiness Foundation (VERIFIED):**
  - **Multi-Tier Envelope Encryption & Key Rotation (`EnvelopeEncryptionService`):**
    - Built AES-256-GCM envelope encryption hierarchy: Master Key (KEK) encrypts ephemeral 256-bit Data Encryption Keys (DEKs) per record.
    - Encoded tokens carry explicit key versions (`env:v1:...`).
    - Implemented zero-downtime key rotation supporting active master key and historical key dictionary (`OLD_ENCRYPTION_KEYS_JSON`) with automated re-encryption migration utility.
    - Verified with 5 automated security tests in `backend/tests/security/test_crypto.py`.
  - **Distributed Sliding-Window Rate Limiter (`RateLimiter`):**
    - Implemented high-throughput Redis sorted set (ZSET) sliding-window algorithm.
    - Protected endpoints: `/v1/auth/login` (5/min per IP), `/v1/sync/batch` (60/min per user), and clinical document endpoints.
    - Clinical fail-open safety: Redis connectivity exceptions degrade gracefully to allow requests rather than blocking critical patient care.
  - **HMAC Cryptographic Approval Tokens & Tamper Proofing:**
    - Cryptographically bound patient approval tokens (`appr_<uuid12>_<hmac24>`) derived from user ID, summary ID, payload checksum, and timestamp.
    - Added post-approval tampering detection in `export_pdf` aborting immediately with HTTP 409 Conflict if payload is altered post-sign-off.
  - **Observability, Tracing & PHI Scrubbing:**
    - Implemented `CorrelationIdMiddleware` injecting canonical `X-Correlation-ID` into response headers and structlog contextvars.
    - Built `phi_and_secret_sanitizer` structlog processor masking credentials, tokens, passwords, and raw biometrics (`heart_rate`, `steps`, `raw_payload`) from application logs.
  - **Zero-LLM Pipeline Resilience:**
    - Proved in `backend/tests/graphs/test_llm_resilience.py` that core data pipelines (`measurements -> baseline -> anomaly detection -> findings`) execute with 100% mathematical fidelity under total LLM outage.
    - Verified safe deterministic fallback synthesis in `HealthIntelligenceGraph` and `CareNavigationGraph`.
  - **Hardened Container Infrastructure & Supply Chain Security:**
    - Hardened `Dockerfile` executing under dedicated unprivileged non-root user `appuser:10001` with drop capabilities.
    - Created `docker-compose.prod.yml` enforcing `read_only: true` root filesystem, `tmpfs` `/tmp`, capability drops (`cap_drop: ALL`), and CPU/memory quotas.
    - Generated `uv.lock` cryptographically locking 114 production dependencies.
  - **Live Disaster Recovery Restore Drill:**
    - Created `scripts/backup_db.sh` and `scripts/restore_db.sh`.
    - Executed live drill against PostgreSQL: backed up `healthos_db` (2.2 MB, SHA-256 sealed), restored into `healthos_db_drill`, and verified 100% exact table row and hypertable chunk parity across all 7 core tables with $<30$s RTO.
  - **Android SDK & Build Toolchain Verification (BLK-00 Resolved):**
    - Provisioned Android SDK 34 (`platforms/android-34`), Build-Tools (`34.0.0`), and Gradle 8.4 runtime.
    - Configured AndroidX and standard XML resources in `android/app/src/main/res/`.
    - Executed live compilation: `compileDebugSources` and `assembleDebug` completed with code 0 (`BUILD SUCCESSFUL`).
  - **18-Threat STRIDE Threat Model & Operational Readiness Scorecard:**
    - Formulated 18-threat threat matrix covering Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, and Elevation of Privilege across Health IoT architecture in `Security.md`.
    - Created `Scorecard.md` certifying Stage 1 Operational Readiness with a score of **94.8 / 100** (Pilot Ready).
  - **Automated Test Suite Expansion:**
    - 14 new security, resilience, and cryptographic tests added.
    - Total test suite expanded to **60 / 60 PASSING** in 5.26s.

---

## [0.5.0] - 2026-09-04


### Added
- **Phase 5: Clinical Readiness & Human-Controlled Care Navigation (VERIFIED):**
  - **Alembic Migration `20260904_0004_clinical_readiness`:**
    - Created `clinical_consents` table with granular scopes (`permitted_metrics`, `permitted_finding_ids`, `scope_date_start`, `scope_date_end`, `purpose`, `status`, `expires_at`, `revoked_at`, `ip_address`).
    - Created `clinical_summaries` table with lifecycle status (`draft`, `reviewed`, `redacted`, `approved`, `revoked`), `summary_payload`, `redaction_mask`, `recommended_specialties`, `routing_rationale`, `approval_token`, `checksum_sha256`, and `pdf_storage_path`.
  - **Granular Consent & Disclosure Service (`ConsentService`):**
    - Strict DPDP Act 2023 compliance: explicit purpose declaration, metric and finding filtering, TTL expiry, and immediate revocation.
    - Immediate revocation defense: revoking consent instantly terminates downstream PDF export and data sharing with HTTP 403.
    - Immutable audit trails: records `consent_granted` and `consent_revoked` to `audit_logs`.
  - **Deterministic Specialty-Routing Engine (`SpecialtyRouter`):**
    - Zero-LLM mathematical routing logic evaluating findings, longitudinal drift, and sleep disruption.
    - Implemented rules: `RULE_SPEC_URGENT_CARDIO`, `RULE_SPEC_NOCTURNAL_CARDIO`, `RULE_SPEC_TREND_DRIFT`, `RULE_SPEC_SLEEP_DISRUPTION`, and `RULE_SPEC_ROUTINE_PRIMARY`.
    - Mandatory Rule H1 statutory non-diagnostic disclaimers attached to all decisions.
  - **Doctor Visit Summary & Redaction Service (`DoctorVisitSummaryService`):**
    - Deterministic extraction: reporting period, sensor coverage, device adherence %, vitals rollups, circadian baseline comparisons, longitudinal trends, and evaluated findings.
    - 5-stage lifecycle state machine: `DRAFT -> REVIEW -> REDACT -> APPROVE -> EXPORT`.
    - Patient-controlled redaction engine: masks specific findings or entire biometric streams as `[REDACTED BY PATIENT]`.
    - Cryptographic integrity verification: calculates and seals canonical SHA-256 digests.
    - Human approval gating: requires explicit patient approval confirmation, generating secure `approval_token`.
  - **Publication-Grade Vector PDF Generator (`DoctorVisitSummaryPdfService`):**
    - ReportLab compilation of clinical briefs: patient metadata, prominent advisory disclaimer, vitals comparison tables, multi-day trend analysis, finding logs, clinician notes, and SHA-256 seal.
  - **Upgraded Care Navigation Graph (`CareNavigationGraph`):**
    - Multi-node LangGraph pipeline: specialty ingestion, clinician consultation note synthesis, calm patient uncertainty explanation, Rule H1 safety guardrail, and human approval verification.
  - **Full Clinical REST API Suite (`/v1/care/...`):**
    - `POST /v1/care/consent`, `GET /v1/care/consent/{id}`, `DELETE /v1/care/consent/{id}`.
    - `POST /v1/care/summary/draft`, `GET /v1/care/summary/{id}`, `POST /v1/care/summary/{id}/redact`, `POST /v1/care/summary/{id}/approve`, `GET /v1/care/summary/{id}/export/pdf`.
    - `GET /v1/care/routing`.
  - **Automated Test Suite Expansion:**
    - 9 new unit, graph, and live integration tests added.
    - Total test suite expanded to **46 / 46 PASSING** with 100% success in 4.93s.

---

## [0.4.0] - 2026-09-04

### Added
- **Phase 4: Longitudinal Personal Health Intelligence Foundation (VERIFIED):**
  - **Alembic Migration `20260904_0003_notification_provenance`:**
    - Added `payload` (JSONB), `created_at` (TIMESTAMPTZ), `failure_info` (TEXT), and `idempotency_key` (VARCHAR) to `notifications` table.
    - Added unique index `idx_notifications_idempotency` and composite index `idx_notifications_finding_channel`.
  - **Personal Health Timeline Domain Abstraction (`TimelineService`):**
    - High-performance domain query abstraction unifying `measurements`, `findings`, and `baselines` without storage duplication.
    - Added `get_context_window()` extracting surrounding vitals, step activity, active baseline, and data quality within an adjustable window around any timestamp.
  - **Deterministic Data Quality Engine (`DataQualityEngine`):**
    - Enforces hard biological bounds (`heart_rate` 30–240 bpm, `steps` 0–35000, `sleep_session` 0–1440 min).
    - Detects future timestamps, negative values, and sampling gaps; assigns data ratings (`excellent`, `good`, `limited`, `poor`, `invalid`).
  - **Deterministic Context Engine (`ContextEngine`):**
    - Classifies physical behavioral state (`RESTING`, `WALKING`, `RUNNING`, `EXERCISE`, `SLEEPING`, `POST_EXERCISE`, `UNKNOWN`) based on concurrent steps, recent heart rate, prior 30-minute exertion, and circadian wall-clock time.
  - **Longitudinal Trend & Baseline Drift Engine (`TrendEngine`):**
    - Ordinary least squares regression over 7-to-28-day daily aggregates ($R^2$, slope/day, drift z-scores).
    - Formally separates `POINT_ANOMALY`, `TREND`, `BASELINE_SHIFT`, and `SAFETY_FINDING`.
    - Computes evidence strength (`strong`, `moderate`, `weak`) and clinical relevance flags.
  - **Multi-Channel Notification Service (`NotificationService`):**
    - Abstraction covering `IN_APP`, `PUSH`, `EMAIL`, and `WHATSAPP_FUTURE`.
    - Idempotent deduplication per `(user_id, finding_id, channel)` preventing alert storms; records all dispatches to `audit_logs`.
  - **Daily Health Digest Data Layer (`DailyDigestService`):**
    - Compiles 24-hour deterministic health dossiers with mathematical aggregations (zero manufactured statistics).
    - Enforces architectural separation of `DATA`, `INSIGHTS`, `LIMITATIONS`, and `RECOMMENDED_ACTIONS`.
  - **Health Intelligence Graph V2 (`HealthIntelState` & Graph):**
    - Ingests longitudinal evidence (observations, baselines, trends, activity context, data quality).
    - Produces structured 8-part evidence output with full backward compatibility to the 7-part schema.
    - Enforces Rule H1 safety guardrail with deterministic fallback.
  - **Human-in-the-Loop Action Gate (`ActionGate`):**
    - Strict classification into `INFORMATIONAL_ACTION`, `RECOMMENDATION`, and `EXTERNAL_ACTION`.
    - Blocks autonomous external actions (doctor outreach, WhatsApp dispatch, booking) without explicit user approval token.
  - **Automated LangSmith Evaluation Suite (`backend/tests/evals/test_langsmith_evals.py`):**
    - 4 automated evaluations covering evidence grounding, uncertainty disclosure, calm tone, and action gating.
  - **Comprehensive Phase 4 Integration Test Suite (`backend/tests/integration/test_phase4_longitudinal_intelligence.py`):**
    - 6 end-to-end integration tests covering all Phase 4 engines and services.
    - Total test suite expanded to **37 / 37 PASSING**.

---

## [0.3.0] - 2026-09-04

### Added
- **Phase 3: Baseline Intelligence & Deterministic Anomaly Pipeline (VERIFIED):**
  - **Alembic Migration `20260904_0002_finding_provenance`:**
    - Extended `findings` table with complete analytical provenance: `observed_value`, `baseline_value`, `deviation`, `standard_deviation`, `reading_timestamp`, `timezone`, `activity_context`, `data_quality`, `confidence`, `source_measurement_ids`, and `evidence`.
    - Added composite unique index `idx_findings_dedup` on `(user_id, metric_type, rule_id, reading_timestamp)` for database-level idempotency.
  - **Timezone-Aware Circadian Baseline Modeling (`BaselineService`):**
    - Rolling 30-day longitudinal computation using NumPy.
    - 24-hour hourly circadian seasonality curve (00:00–23:00) calculated in the patient's local wall-clock timezone (preserving circadian biology).
    - Deterministic establishment rule: requires span >= 14 calendar days and >= 140 nominal samples; unestablished baselines suppress false statistical alerts.
  - **Exertion-Aware Deterministic Anomaly Detector (`AnomalyDetector`):**
    - Distinct representation of `STATISTICAL_FINDING` vs `SAFETY_FINDING`.
    - Cross-examination of concurrent step counts: high-step activity suppresses resting tachycardia alerts (zero false positives during workouts).
    - Hard biological safety gates (Rule H2: HR >= 150 bpm ceiling, HR <= 38 bpm floor) trigger `urgent` regardless of baseline establishment.
    - Configurable z-score thresholds (Unusual >= 2.0, Monitoring >= 2.8, Concerning >= 3.8, Urgent >= 5.0).
  - **Deterministic Synthetic 30-Day Longitudinal Generator (`SyntheticDataGenerator`):**
    - Seeded, 100% reproducible 30-day physiological telemetry with circadian curves, normal sleep, daytime workouts, and injected nocturnal resting tachycardia episode on Day 30 at 03:00 AM.
  - **End-to-End Deterministic Anomaly Pipeline Service (`AnomalyPipelineService`):**
    - Coordinates measurements, temporal step context, active baseline, deterministic anomaly scoring, idempotent database persistence (`ON CONFLICT DO NOTHING`), and automated `HealthIntelligenceGraph` execution.
    - Attached grounded 7-part plain-language explanation without medical diagnoses.
  - **5 Comprehensive Integration Tests (`test_phase3_baseline_anomaly_e2e.py`):**
    - Baseline calculation, circadian curves, unestablished suppression, workout false-positive resistance, nocturnal tachycardia detection, and idempotency.
    - Total test suite expanded to **27 / 27 PASSING**.

---

## [0.2.0] - 2026-09-04

### Added
- **Live Infrastructure & Multi-Service Docker Compose Orchestration:**
  - Resolved host port collisions on PostgreSQL (`5432 -> 5435`) and Redis (`6379 -> 6380`) while maintaining internal standard container ports.
  - Successfully brought up isolated `healthos_postgres` (TimescaleDB 2.29.2 on PostgreSQL 16) and `healthos_redis` (Redis 7).
  - Executed Alembic migration `20260904_0001` against live database; verified 15 tables, 37 foreign keys, and 7-day chunk hypertable on `measurements`.
- **End-to-End Real Ingestion & Worker Integration Tests:**
  - Implemented `backend/tests/integration/test_sync_e2e.py` with 5 real integration tests:
    - Android Health Connect payload persistence and provenance preservation in TimescaleDB.
    - Batch-level idempotency via `Idempotency-Key` returning `ALREADY_PROCESSED` with 0 duplicate rows.
    - Unique index measurement-level deduplication (`ON CONFLICT DO NOTHING`).
    - RFC 7807 problem details and atomic zero-row rollback on malformed payloads.
    - Multi-tenant data isolation preventing cross-patient visibility.
  - Implemented `backend/tests/integration/test_worker_e2e.py` with 2 real integration tests:
    - Live ARQ job enqueueing, worker processing, and successful completion in Redis.
    - Cron cadence configuration validation for deterministic hourly/daily rollups.
- **LangGraph & LangSmith Verification:**
  - Implemented `backend/tests/test_graphs.py` testing `HealthIntelligenceGraph`, `DailyReportGraph`, `CareNavigationGraph`, and Rule H1 safety guardrail enforcement.
  - Created evaluation dataset foundation in `evals/eval_datasets.json` covering anomaly classification, explanation quality, hallucination prevention, and care navigation.
- **Android Static Contract Audit:**
  - Statically verified Kotlin models against Pydantic schemas and API contracts.
  - Flagged host build environment limitation: `⚠️ BLOCKED — ANDROID BUILD TOOLCHAIN UNAVAILABLE`.

### Fixed
- Fixed AttributeError in `backend/alembic/env.py` (`config.alembic_config_section` -> `config.config_ini_section`).
- Fixed multi-stage `Dockerfile` dependency installation ordering and container `PYTHONPATH`.
- Fixed local developer virtual environment with 79 verified dependencies via `uv`.

---

## [0.1.0] - 2026-09-04

### Added
- **Phase 0 (Architecture & Engineering OS):**
  - Created and synchronized all 21 foundational source-of-truth documents.
  - Configured repository root tooling: `pyproject.toml`, `docker-compose.yml`, `Dockerfile`, and `alembic.ini`.
  - Implemented core backend architecture in `backend/app/`:
    - `core/config.py`: Pydantic Settings with multi-environment configuration.
    - `core/exceptions.py`: Standardized RFC 7807 problem details exception handling.
    - `db/session.py`: Async SQLAlchemy 2.0 engine and sessionmaker.
    - `models/`: Complete ORM entities for users, devices, measurements, baselines, findings, notifications, reports, care discovery, and audit logs.
    - `schemas/`: Pydantic v2 schemas for batch ingestion, timeline queries, findings, and reports.
    - `services/`: IngestionService, BaselineService (NumPy/SciPy), AnomalyDetector (z-scores and biological hard gates), and DailyReportPdfService (ReportLab).
    - `graphs/`: LangGraph stateful graphs (`HealthIntelligenceGraph`, `DailyReportGraph`, `CareNavigationGraph`).
    - `workers/`: ARQ worker settings and background cadence cron schedulers.
    - `observability/`: LangSmith execution tracer hooks and structured JSON logging (`structlog`).
    - `api/v1/`: Modular FastAPI endpoints for authentication, sync, measurements, findings, reports, and care navigation.
- **Phase 1 (Android Ingestion Gateway):**
  - `HealthConnectManager.kt`: Health Connect client integration reading heart rate, steps, and sleep.
  - Room DB offline staging queue: `OfflineMeasurementEntity.kt`, `MeasurementDao.kt`, and `AppDatabase.kt`.
  - `HealthSyncWorker.kt`: Background WorkManager synchronization with network constraints and exponential backoff.
  - Remote Network Layer: Retrofit `HealthOSApiService.kt`, `NetworkClient.kt`, and `SyncModels.kt`.
  - Jetpack Compose UI: `PersonalHealthOSTheme`, `HealthDashboardScreen.kt`, and `MainActivity.kt` with permission handling.
- **Phase 2 (Database Schema & Ingestion API):**
  - Created initial Alembic migration `20260904_0001_initial_schema.py` creating all 15 core entities.
  - Configured TimescaleDB hypertable for `measurements` partitioned by 7-day chunks.
  - Configured composite unique index on `(user_id, source_id, metric_type, recorded_at)` for idempotent deduplication.
- **Testing & Verification:**
  - `backend/tests/unit/test_anomaly_math.py`: Validated normal variation, nocturnal tachycardia, biological hard gates, and unestablished baseline suppression.
  - `backend/tests/unit/test_schemas.py`: Validated Pydantic batch ingestion models.
  - `backend/tests/graphs/test_health_intel_graph.py`: Validated LangGraph execution and Rule H1 zero-diagnosis safety guardrail.
  - 10 / 10 automated unit and graph tests passing.
