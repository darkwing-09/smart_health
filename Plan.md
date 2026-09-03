# Plan.md — Master Phased Development Roadmap

This master roadmap sequences the development of Personal Health OS into 12 structured phases. Each phase establishes clear objectives, explicit dependencies, concrete tasks, acceptance criteria, and deliverable artifacts.

---

## Phase Summary Overview

- **PHASE 0:** Architecture + Foundations (Current)
- **PHASE 1:** Android Health-Data Ingestion Gateway
- **PHASE 2:** FastAPI Ingestion API & PostgreSQL Database
- **PHASE 3:** Health Timeline & Data Pipeline
- **PHASE 4:** Personal Baselines & Deterministic Analytics Engine
- **PHASE 5:** LangGraph Agent Intelligence Workflows
- **PHASE 6:** LangSmith Observability & Evaluation Harness
- **PHASE 7:** Alert Hierarchy & Notification Channels (FCM & WhatsApp V1)
- **PHASE 8:** Daily Health Reports & Vector PDF Generation
- **PHASE 9:** Care Navigation & Healthcare Discovery
- **PHASE 10:** Appointment Outreach & User-Controlled Booking
- **PHASE 11:** Multi-Source Personal Health OS Expansion

---

## PHASE 0 — Architecture & Foundations
- **Objective:** Establish the complete 21-document engineering operating system, monorepo repository structure, Docker Compose development environment, and development tooling.
- **Dependencies:** None.
- **Tasks:**
  - [x] Resolve all vision contradictions and record ADR-001 through ADR-010 in `Decisions.md`.
  - [x] Author all 21 foundational source-of-truth documents.
  - [x] Scaffold repository root: `pyproject.toml`, `docker-compose.yml`, `Dockerfile`, `alembic.ini`.
  - [x] Scaffold backend application packages (`backend/app/api`, `core`, `models`, `schemas`, `services`, `graphs`, `workers`, etc.).
  - [x] Configure code quality tooling: `ruff`, `mypy --strict`, `pytest`.
- **Expected Outputs:** Fully configured monorepo ready for immediate backend and Android service implementation.
- **Acceptance Criteria:** `ruff check .` passes with 0 errors; `mypy` type-checks clean; Docker Compose spins up PostgreSQL 16 (TimescaleDB) and Redis 7 successfully.

---

## PHASE 1 — Android Health-Data Ingestion Gateway
- **Objective:** Construct the native Android companion app capable of reading authorized biometric feeds from Health Connect and queuing them in local offline storage.
- **Dependencies:** Phase 0.
- **Tasks:**
  - Set up Android Studio project (Kotlin, Jetpack Compose, Android SDK 34).
  - Implement `HealthConnectManager` handling permissions and data reads for Heart Rate, Steps, and Sleep.
  - Implement Room DB offline staging queue with `OfflineMeasurementEntity` and `MeasurementDao`.
  - Implement `HealthSyncWorker` via Android WorkManager with network and battery constraints.
  - Build connection status and permissions UI screens in Compose.
- **Expected Outputs:** Functional Android application that extracts watch data and stores it in an on-device queue.
- **Acceptance Criteria:** User pairs app, grants Health Connect permissions, and sees initial biometric records staged locally in Room DB within 5 minutes.

---

## PHASE 2 — FastAPI Ingestion API & PostgreSQL Database
- **Objective:** Build the high-performance async backend API gateway and relational time-series database.
- **Dependencies:** Phase 0.
- **Tasks:**
  - Provision PostgreSQL 16 with TimescaleDB extension and Redis 7.
  - Write SQLAlchemy models for `users`, `devices`, `wearable_sources`, `sync_batches`, and `audit_logs`.
  - Create Alembic migration creating the schema and configuring `measurements` as a 7-day chunked hypertable.
  - Implement `POST /v1/sync/batch` with `Idempotency-Key` deduplication and Pydantic validation.
  - Implement JWT authentication endpoints (`/v1/auth/login`, `/v1/auth/refresh`).
- **Expected Outputs:** Deployable FastAPI backend receiving batches and persisting them immutably.
- **Acceptance Criteria:** Automated test sends 1,000 synthetic measurements with an idempotency key; duplicate submission returns HTTP 200 with `ALREADY_PROCESSED` and zero duplicate rows in PostgreSQL.

---

## PHASE 3 — Health Timeline & Normalization Pipeline
- **Objective:** Connect the Android client to the backend API, verifying resilient offline-first synchronization.
- **Dependencies:** Phase 1, Phase 2.
- **Tasks:**
  - Wire Android `HealthSyncWorker` to backend `POST /v1/sync/batch` via Retrofit.
  - Implement unit normalization pipeline (standardizing calories, distance, and timestamps to UTC).
  - Implement `GET /v1/measurements/timeline` endpoint with date-range and metric filtering.
  - Build Android timeline visualizer screen in Jetpack Compose.
  - Test offline queuing by disconnecting network, recording synthetic watch data, and verifying batch flush on reconnect.
- **Expected Outputs:** End-to-end data flow from smartwatch through Android app to PostgreSQL timeline.
- **Acceptance Criteria:** Real data collected on watch appears in PostgreSQL within 15 minutes of device network reconnect.

---

## PHASE 4 — Personal Baselines & Deterministic Analytics Engine
- **Objective:** Implement deterministic numerical algorithms to model personal baselines and detect candidate anomalies.
- **Dependencies:** Phase 3.
- **Tasks:**
  - Implement `BaselineService` computing rolling 30-day mean, standard deviation, and hourly circadian profiles (NumPy/SciPy).
  - Implement minimum 14-day data quality check before marking `Baseline.established = true`.
  - Implement `AnomalyDetector` executing rolling z-score and CUSUM change-point algorithms.
  - Implement hard biological boundary gates (resting HR > 150 bpm or < 38 bpm).
  - Classify events into Levels 0–4 (Information, Insight, Attention, Important, Urgent).
- **Expected Outputs:** Background service evaluating measurements against personal baselines and creating `Finding` records.
- **Acceptance Criteria:** Unit tests with synthetic physiological vectors achieve 100% classification accuracy on nocturnal tachycardia, bradycardia, and sensor detachment.

---

## PHASE 5 — LangGraph Agent Intelligence Workflows
- **Objective:** Construct stateful LangGraph workflows to interpret deterministic findings into grounded plain-language explanations.
- **Dependencies:** Phase 4.
- **Tasks:**
  - Define typed graph state schemas (`HealthIntelState`, `FindingExplanationSchema`).
  - Implement `HealthIntelligenceGraph` nodes: context retrieval, LLM reasoning, and safety guardrail.
  - Integrate Pydantic structured output with ChatOpenAI / ChatAnthropic.
  - Implement `SafetyGuardrailNode` enforcing Rule H1 (Zero Medical Diagnosis).
  - Implement Finding state machine (`new` → `notified` → `acknowledged` → `resolved`).
- **Expected Outputs:** LangGraph workflow transforming raw anomaly flags into calm, 7-part plain language explanations.
- **Acceptance Criteria:** 1,000 synthetic test runs produce 100% adherence to the 7-part schema with 0 occurrences of blacklisted diagnostic phrases.

---

## PHASE 6 — LangSmith Observability & Evaluation Harness
- **Objective:** Establish production-grade tracing, latency/token profiling, and continuous prompt evaluation using LangSmith.
- **Dependencies:** Phase 5.
- **Tasks:**
  - Configure LangSmith SDK environment variables and tracer hooks in FastAPI lifespan.
  - Tag all graph runs with `graph_name`, `severity_tier`, `user_id_hash`, and `prompt_version`.
  - Construct LangSmith permanent evaluation datasets: `health-intel-grounding` and `zero-diagnosis-guardrail`.
  - Implement automated Pytest CI evaluators scoring groundedness and hallucination rates.
- **Expected Outputs:** Live LangSmith project dashboard displaying real-time traces and regression evaluation metrics.
- **Acceptance Criteria:** All PRs modifying prompts or agent nodes automatically run the LangSmith evaluation suite in CI and pass with >98% grounding scores.

---

## PHASE 7 — Alert Hierarchy & Notification Channels
- **Objective:** Implement anti-fatigue alert routing and multi-channel dispatch.
- **Dependencies:** Phase 5, Phase 6.
- **Tasks:**
  - Implement `NotificationAgent` LangGraph router enforcing the 5-level alert hierarchy.
  - Build Firebase Cloud Messaging (FCM) push dispatcher for Level 3 (Important) and Level 4 (Urgent) alerts.
  - Implement deduplication window (12 hours) preventing repeated notifications for unresolved findings.
  - Build Android notification receiver and alert details screen displaying the 7-part explanation.
  - Prepare WhatsApp Cloud API adapter module (deferred to V1 pending Meta template approval).
- **Expected Outputs:** Working push notification system delivering grounded alerts without alert fatigue.
- **Acceptance Criteria:** Ongoing anomaly triggers exactly 1 push notification within a 12-hour period unless severity escalates to Level 4 Urgent.

---

## PHASE 8 — Daily Health Reports & Vector PDF Generation
- **Objective:** Automate the synthesis of daily 24-hour health digests and vector PDF compilation.
- **Dependencies:** Phase 4, Phase 5.
- **Tasks:**
  - Implement ARQ scheduled task running nightly at 23:50 local user time.
  - Implement `DailyReportGraph` synthesizing daily exertion, sleep architecture, vitals, and open findings.
  - Generate dynamically synthesized stoic or reflective closing quote.
  - Implement `ReportLab` vector PDF compiler rendering structured tables, charts, and narrative sections.
  - Store generated PDFs in encrypted object storage and expose via `GET /v1/reports/daily/{id}/download`.
  - Add PDF viewer in Android application.
- **Expected Outputs:** Automated nightly PDF health report delivered to the user's mobile app.
- **Acceptance Criteria:** PDF compiles and renders successfully even on days with zero anomalies (degrades gracefully to trends-only).

---

## PHASE 9 — Care Navigation & Healthcare Discovery (V1)
- **Objective:** Build verified provider discovery and structured Doctor Visit Summary generation.
- **Dependencies:** Phase 5, Phase 7.
- **Tasks:**
  - Implement `CareNavigationGraph` deriving medical specialties from confirmed anomalies.
  - Build verified healthcare directory tool using Google Places / OpenStreetMap APIs.
  - Implement deterministic ranking algorithm sorting providers by proximity and verified contact details.
  - Format exportable Doctor Visit Summary document synthesizing longitudinal vitals for clinical consultation.
  - Implement LangGraph `interrupt()` primitive to await explicit user approval before external actions.
- **Expected Outputs:** In-app Care Navigation screen presenting verified local clinics and exportable summaries.
- **Acceptance Criteria:** System presents 3–5 verified providers with explicit source provenance; zero fabricated doctor profiles.

---

## PHASE 10 — Appointment Outreach & User-Controlled Booking (V2)
- **Objective:** Facilitate user-dispatched appointment inquiries without autonomous booking liability.
- **Dependencies:** Phase 9.
- **Tasks:**
  - Implement `AppointmentAgent` generating pre-filled WhatsApp/email/phone outreach drafts.
  - Build Android deep-linking intents allowing user to open draft messages in their personal messaging apps.
  - Record user action timestamp and outreach status in `appointment_requests` table.
  - Revisit legal compliance review under DPDP Act before considering automated booking API integrations.
- **Expected Outputs:** Patient-controlled communication workflow for booking consultations.
- **Acceptance Criteria:** User can generate and send a pre-filled appointment inquiry to a clinic in < 2 taps; zero autonomous external API bookings executed without human presence.

---

## PHASE 11 — Multi-Source Personal Health OS Expansion (V3)
- **Objective:** Expand the platform from smartwatches into a multi-device, multi-source personal health operating system.
- **Dependencies:** Phases 1–10.
- **Tasks:**
  - Build Fitbit Web API adapter (OAuth2 PKCE) and Garmin Health API adapter.
  - Add data ingestion for smart scales (weight, body fat), blood pressure monitors, and continuous glucose monitors (CGM).
  - Implement nutrition, meal, and medication tracking modules.
  - Implement cross-signal correlation engine (e.g., analyzing sleep efficiency changes following late meals).
  - Build patient-authorized clinical data export package for electronic health record (EHR) integration.
- **Expected Outputs:** Comprehensive, multi-source Personal Health OS.
- **Acceptance Criteria:** System seamlessly correlates biometrics across 3+ distinct hardware vendors on a single longitudinal timeline.
