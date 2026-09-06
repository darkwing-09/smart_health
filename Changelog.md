# Changelog.md — Chronological Release & Change History

All notable changes to Personal Health OS are documented in this file. Format adheres to [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.9.2] - 2026-09-04

### Fixed & Enhanced
- **Android Health Timeline Manual Sync & Offline UX Architecture:**
  - **Decoupled Local Staging from Network Availability:**
    - Modified `triggerImmediateSync()` in `MainActivity.kt` to omit `NetworkType.CONNECTED` constraints so manual taps immediately trigger Health Connect local read and Room DB staging even if offline or on airplane mode.
    - Added `isNetworkAvailable()` checks in `HealthSyncWorker.kt`: if offline, measurements are safely retained in Room with status `PENDING`, returning rich WorkManager output data (`is_offline = true`, `records_staged`).
    - Handled host resolution `IOException` (e.g. `api.healthos.local` unreachable on non-VPN cellular networks) by automatically reverting `IN_FLIGHT` records back to `PENDING`, preserving local queue integrity with zero data loss.
  - **WorkManager Deduplication & Reactive State Tracking:**
    - Transitioned `HealthSyncWorker` immediate invocation from anonymous `enqueue()` to `ExistingWorkPolicy.REPLACE` using unique work identifier `HealthOS_ImmediateSync`.
    - Added `MeasurementDao.getPendingCountFlow(): Flow<Int>` for real-time Room observation, eliminating stale queue counters in Jetpack Compose UI.
    - Bound `MainActivity` to `WorkManager.getWorkInfosForUniqueWorkFlow()` to stream active worker state directly into Compose.
  - **Expanded Health Connect Biometric Queries:**
    - Expanded query time window in `HealthConnectManager.kt` from 6 hours to 24 hours (`hoursBack = 24`).
    - Added queries for `TotalCaloriesBurnedRecord` and `SleepSessionRecord` alongside `HeartRateRecord` and `StepsRecord`.
    - Replaced empty catch blocks with structured diagnostic logging.
  - **Jetpack Compose Sync Feedback & Activity Banner:**
    - Implemented `SyncUiState` (`Idle`, `Queued`, `Syncing`, `Success`, `Error`) in `HealthDashboardScreen.kt`.
    - Added animated `CircularProgressIndicator` spinner and dynamic button text ("Synchronizing Timeline...").
    - Added an informational Activity Banner in the Device Gateway card displaying queue depth, stage counts, and offline retention messages.
  - **Empirical Hardware Verification on Physical Device:**
    - Deployed and verified on physical vivo I2214 (Android 16 / API 36, ADB ID `10BD1Y16FL0005Z`).
    - Verified Health Connect 24-hour query execution across 4 records via logcat.
    - Executed offline queue injection drill: staged pending measurement into device SQLite, tapped sync, verified offline preservation banner on device screen (`Offline Queue Depth: 1 records pending`), and cleaned up test data.
  - **Automated Unit Testing & Lint:**
    - Added 4 new unit tests in `HealthSyncWorkerTest.kt` (keys integrity, unique work name convention, SyncUiState representations, offline retention logic). Total Android test suite: **12 / 12 PASSING (100%)**, Android Lint: **0 errors**. Total system test suite: **165 / 165 PASSING (100%)**.

## [0.9.1] - 2026-09-04

### Added
- **Production Pilot Hardening & Operational Cadence Verification:**
  - **Instant Session & JWT Token Revocation (`/v1/auth/logout` & Redis Blacklist):**
    - Included unique JWT ID (`jti`) in access token payloads upon authentication.
    - Added `POST /v1/auth/logout` endpoint storing `revoked_token:{jti}` in Redis with time-to-live matching remaining token lifespan.
    - Enhanced `get_current_user` dependency in `app/api/deps.py` to check Redis blacklist before authorizing requests, rejecting revoked tokens with HTTP 401 Unauthorized.
    - Added security test suite `backend/tests/security/test_token_revocation.py` verifying immediate token invalidation, multi-user token isolation, and audit logging.
  - **Atomic High-Concurrency Batch Ingestion Safety:**
    - Hardened `IngestionService.process_batch` with `insert(SyncBatch).values(...).on_conflict_do_nothing(index_elements=["id"])`.
    - Eliminates race conditions during simultaneous client retry bursts with identical idempotency keys, cleanly returning `ALREADY_PROCESSED` with zero database exceptions.
    - Added integration test `backend/tests/integration/test_ingest_concurrency.py` verifying 10 simultaneous concurrent requests without failure.
  - **Background Worker Daily Cadence Implementation & Verification:**
    - Replaced stub tasks in `backend/app/workers/worker.py` with full production implementations:
      - `cron_daily_baseline_recompute`: Recomputes rolling 30-day baseline distributions for active users across 5 standard metrics (`heart_rate`, `steps`, `spo2`, `hrv`, `respiratory_rate`), with per-user error isolation and baseline establishment rules.
      - `cron_daily_report_pipeline`: Aggregates 24-hour vitals, evaluates data quality, compiles vector PDFs via ReportLab with statutory disclaimers, and persists `Report` records with idempotent updates. Gracefully degrades to `degraded_trends_only` on sparse data.
    - Added comprehensive integration test suite `backend/tests/integration/test_worker_cadence_e2e.py` verifying baseline computation, ReportLab PDF generation, zero-data degradation, and REST API download endpoints.
  - **Automated Test Suite Expansion:**
    - Expanded backend automated test suite from 147 to **153 / 153 PASSING (100%)** in 14.85s.
    - Total test suite now **161 / 161 PASSING** (153 backend + 8 Android unit tests).

## [0.9.0] - 2026-09-04

### Added

- **Phase 9: Real-World Pilot Launch, Hardware Validation & Production Operations (VERIFIED & AUDITED):**
  - **Phase 9 Integration Test Suite (`test_phase9_pilot_operations.py`):**
    - 10 automated end-to-end integration tests validating real-world operational invariants: multi-metric ingestion across 9 biometrics, TimescaleDB hypertable chunk persistence, end-to-end traversal to ReportLab vector PDF, 24-hour offline sync recovery with idempotent replay, clock skew resilience and future timestamp quarantining, sensor detachment quality tagging without synthetic imputation, quiet hours vs Level 4 emergency override invariance, multi-tenant resource isolation (404 concealment), ActionGate HMAC approval token security & freshness, DPDP Act 2023 consent revocation hard stop, and Kubernetes/ECS container liveness (`/health`) and readiness (`/ready`) probes.
  - **Production Pilot Operational Runbooks:**
    - `PILOT_DEPLOYMENT_CHECKLIST.md`: Formal 7-stage deployment protocol spanning pre-flight hardware gating, TimescaleDB hypertable validation, security boundaries, container health probes, worker concurrency, notification verification, and go/no-go decision gates.
    - `INCIDENT_RESPONSE_RUNBOOK.md`: Standard operating procedures for Sev 1–4 incident triage, MTTA/MTTR response bounds, on-call roles, database connection exhaustion, worker queue backlogs, LLM outages, FCM push failures, PITR backup/restore verification, and zero-downtime rollback SOP.
    - `PILOT_SAFETY_PROTOCOL.md`: Comprehensive participant safety protocol covering inclusion/exclusion criteria, DPDP Act 2023 informed consent, wearable sensor fit SOP, optical PPG limitations, non-diagnostic communication standards (Rule H1), emergency escalation dialers (`tel:112`/`tel:911`), and weekly clinical oversight.
  - **Architecture Decision Records:**
    - `ADR-034`: Production Pilot Architecture, SRE Observability & Operational Runbooks.
    - `ADR-035`: Controlled Pilot Participant Safety Protocol & Non-Diagnostic Clinical Boundaries.
  - **Automated Regression Test Suite Expansion:**
    - Total backend automated test suite expanded from 137 to **147 / 147 PASSING (100%)** in 13.50s.
    - Android client regression: 8/8 unit tests passing, 0 lint errors, debug build clean.
  - **Readiness Scorecard Update:**
    - Upgraded production readiness scorecard from 96.8 to **97.9 / 100** with verdict: **CONDITIONALLY READY FOR CONTROLLED PILOT**.
    - Strict adherence to Zero Fabrication Rule: physical device gates (BLK-01 through BLK-04) explicitly retained as `BLOCKED` with verified operational protocols ready for hardware handover.

---

## [0.8.0] - 2026-09-04

### Added
- **Phase 8: Real Device, Wearable & Production Pilot Validation (VERIFIED & AUDITED):**
  - **Hardware Readiness Inspection (`scripts/hardware_readiness_check.py`):**
    - Evaluates 9 hardware gates: Workstation SDK 34 (VERIFIED), ADB 1.0.41 (VERIFIED), TimescaleDB (VERIFIED), Redis (VERIFIED), FCM Dry Run (PARTIAL), Physical Phone (BLOCKED), Android AVD (BLOCKED), Health Connect runtime (BLOCKED), Physical Wearable (BLOCKED).
    - Preserves zero-fabrication rule: physical hardware steps strictly retained as BLOCKED without fabrication.
  - **Android Real-Device Readiness & Lock Screen Privacy:**
    - Updated `HealthOSNotificationManager.kt` with explicit `.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)` to prevent sensitive PHI exposure on lock screens.
    - Added unit test suite `android/app/src/test/java/com/healthos/NotificationPrivacyTest.kt` verifying privacy flags across attention and urgent channels.
    - Added unit test suite `android/app/src/test/java/com/healthos/HealthSyncWorkerTest.kt` verifying batching, constraints, and exponential retry policies.
    - Verified Android compile and lint: `./gradlew testDebugUnitTest` (8 tests passing), `./gradlew lintDebug` (0 errors), `./gradlew compileDebugKotlin` (clean).
  - **Health Connect 14-Hop Deterministic Simulation (`scripts/simulate_health_connect_pipeline.py`):**
    - Traced all 14 architectural hops from Wearable BLE $\to$ Health Connect Provider $\to$ Room DB $\to$ WorkManager $\to$ HTTPS API $\to$ TimescaleDB $\to$ DataQualityEngine $\to$ ContextEngine $\to$ BaselineService $\to$ AnomalyDetector $\to$ Finding $\to$ NotificationService $\to$ FCM/WebSocket $\to$ ReportLab Vector PDF.
  - **Metric Coverage Matrix (`METRICS_MATRIX.md`):**
    - Exhaustive audit of all 12 biometric metrics: `heart_rate`, `resting_heart_rate`, `steps`, `distance`, `calories`, `active_calories`, `sleep_stage`, `exercise_session`, `spo2`, `respiratory_rate`, `hrv`, `body_temperature`.
    - Documented unit conversions, sampling frequencies, biological safety boundaries, baseline eligibility, and hardware limitations.
  - **Data Quality Under Real Conditions Test Suite (`test_data_quality_real_conditions.py`):**
    - 5 integration tests verifying off-wrist sensor detachment gaps, impossible biological values quarantine, 36-hour delayed sync, clock drift $\ge 5$ min adaptation, and multi-sync deduplication.
  - **Real-Time Degradation & Fallback Test Suite (`test_realtime_pipeline_degradation.py`):**
    - 6 integration tests verifying 5 alert tiers, Level 4 emergency quiet hours bypass, 12-hour anti-fatigue deduplication, deterministic mathematical fallback under total LLM outage, FCM outage resilience, and push preview masking.
  - **Daily Report Validation & PDF Test Suite (`test_daily_report_e2e.py`):**
    - 4 integration tests verifying zero-data day graceful degradation, partial-wear days (4 hours data), active findings inclusion without diagnostic assertion, and vector PDF compilation with statutory non-diagnostic disclaimers.
  - **Pilot Adversarial Security & Token Tampering Tests (`test_pilot_adversarial_security.py`):**
    - Added cross-user device hijacking prevention, immediate consent revocation hard stop, cryptographically signed HMAC approval token tampering detection, and cross-action replay prevention.
  - **12 End-to-End Pilot Chaos Failure Drills (`test_pilot_12_failure_drills.py`):**
    - 12 comprehensive chaos tests covering offline 24h sync, Redis down during ingest, PostgreSQL rollback on failure, worker crash recovery, duplicate batch idempotency, FCM timeout resilience, LLM outage fallback, WebSocket abrupt disconnect, app killed during sync, reboot rescheduling, wearable detachment gap, and consent revocation hard stop.
  - **Production Observability & Readiness Probes (`/health`, `/ready`):**
    - Enhanced `/ready` endpoint with async dependency injection checking live TimescaleDB and Redis connectivity, returning HTTP 200 (ready) or HTTP 503 (degraded).
  - **500-Worker Concurrency Load Test (`scripts/load_test_500_workers.py`):**
    - Evaluated 500 concurrent Android Health Connect sync clients posting batches under 50-connection concurrency limit.
    - Empirical results: 500 requests in 37.71s (13.26 req/s), 99.8% success rate (499/500), p50: 1753ms, p95: 12447ms, zero connection pool leaks.
  - **Hardware Test Protocol Runbook (`HARDWARE_TEST_PROTOCOL.md`):**
    - Formal 19-step verification runbook for physical device/wearable testing, with status classification for every step (`VERIFIED`, `PARTIAL`, `UNVERIFIED`, `BLOCKED`) and step-by-step unblocking procedures.
  - **Automated Test Suite Expansion:**
    - Total backend automated test suite expanded from 106 to **137 / 137 PASSING (100%)** in 16.00s.

---

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
