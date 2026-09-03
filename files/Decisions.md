# Decisions.md — Architecture Decision Record (ADR)

This file maintains an Architecture Decision Record (ADR) history for Personal Health OS. Format per entry: Date | Decision | Context | Alternatives Considered | Reasoning | Consequences | Status.
A recorded architectural decision must never be modified or reversed silently. Any change requires a new superseding ADR entry.

---

## ADR-001: Wearable Integration Scope for MVP
- **Date:** 2026-09-03
- **Decision:** MVP integrates with Android Health Connect only (covers Wear OS watches and Samsung Galaxy Watch, which routes through Health Connect on modern firmware). No direct proprietary vendor SDK integration in MVP.
- **Context:** Vision states "support all watches." Actual access reality splits into four tiers: (1) Health Connect — unified, official, no approval needed; (2) Fitbit/Garmin — real APIs but OAuth friction or B2B partnership gating; (3) Samsung/Huawei — partially routed through Health Connect, partially proprietary; (4) Xiaomi/Amazfit/Noise/boAt/Fire-Boltt — no public API at all, the highest-unit-share brands in the likely target market (India).
- **Alternatives considered:**
  - Build unofficial Gadgetbridge-style reverse-engineered adapters for Tier 4 brands from day one. Rejected: fragile, breaks on vendor firmware updates, not a sound MVP dependency.
  - Wait for Garmin B2B approval before shipping anything. Rejected: blocks MVP indefinitely on an external party's timeline.
- **Reasoning:** Health Connect gives the broadest reach for the least integration risk and is Google-maintained, so it degrades gracefully as vendors improve compliance.
- **Consequences:** Tier 4 brand users (likely majority of budget-watch owners) get no data ingestion in MVP. This must be visible in-app, not silently absent. Adapter interface (see Architecture.md §Data Source Adapters) must be vendor-agnostic from day one so Fitbit/Garmin/best-effort Zepp adapters can be added without touching core pipeline code.
- **Status:** Accepted.

---

## ADR-002: Notification Channel for MVP
- **Date:** 2026-09-03
- **Decision:** MVP alerting uses native Android push (FCM) from the companion app. WhatsApp integration is deferred to V1 and treated as an external dependency requiring verification.
- **Context:** WhatsApp Business Platform requires Meta App Review, a verified WhatsApp Business Account, and — critically — pre-approved message templates for any message sent outside a 24-hour customer-service window. Health alerts are inherently unscheduled/event-driven, so most alerts would need template pre-approval, which has unpredictable Meta review turnaround.
- **Alternatives considered:** Ship WhatsApp-only from MVP. Rejected: approval timeline is an external unknown that would block MVP launch entirely.
- **Reasoning:** FCM push is self-serve, has no approval gate, and the notification abstraction (Notification Agent, see AGENTS.md) is channel-agnostic, so WhatsApp becomes an additive channel later, not a rewrite.
- **Consequences:** V1 scope includes: apply for WhatsApp Business API access, design and submit alert templates for approval, before any WhatsApp code ships.
- **Status:** Accepted. **EXTERNAL DEPENDENCY — VERIFY BEFORE IMPLEMENTATION** (current Meta WhatsApp Business Platform template-approval process and turnaround times).

---

## ADR-003: Appointment Booking Scope
- **Date:** 2026-09-03
- **Decision:** MVP/V1 scope for care navigation stops at: research hospitals/doctors → present ranked options with sourced info → generate a pre-filled inquiry/request the user sends themselves (via their own WhatsApp/call/email). Automated end-to-end appointment booking on the user's behalf is explicitly out of scope until a dedicated legal/compliance review is done.
- **Context:** Automated booking on a user's behalf involves handling sensitive personal health data (regulated under India's DPDP Act as it matures, plus likely telemedicine guideline exposure) and there is no general-purpose hospital booking API in India — most systems would require RPA-style automation against hospital web portals, which is both fragile and legally ambiguous (terms-of-service, impersonation-adjacent action-taking).
- **Alternatives considered:** Build direct booking via scraping/RPA in MVP. Rejected: legal exposure and fragility disproportionate to MVP value.
- **Reasoning:** "Research and prepare" delivers most of the user value (saves time, surfaces good options) without the liability of an AI system executing consequential real-world medical-adjacent transactions unsupervised.
- **Consequences:** PRD.md explicitly lists automated booking as a non-goal for MVP/V1. Appointment Agent (AGENTS.md) has hard permission boundary: propose only, never execute, until this ADR is revisited.
- **Status:** Accepted. Revisit requires explicit legal review — **UNDECIDED — REQUIRES VALIDATION**.

---

## ADR-004: Deterministic vs. LLM Boundary for Anomaly Detection
- **Date:** 2026-09-03
- **Decision:** Baseline computation and anomaly *detection* (i.e., "is this value/pattern statistically unusual for this user") are deterministic code, not LLM judgment. The LLM's role is strictly: explain an already-detected anomaly, rank/prioritize multiple concurrent findings, and generate natural-language output. The LLM never independently declares something anomalous that the deterministic layer did not flag.
- **Context:** Vision principle #6 ("LLMs should not replace deterministic calculations where deterministic logic is more reliable") and the hard rule against fabricated medical facts require a bright line, or agent behavior drifts over time as prompts get tuned.
- **Alternatives considered:** Let the LLM reason freely over raw time-series data end-to-end. Rejected: unauditable, inconsistent thresholds, harder to test, higher fabrication risk.
- **Reasoning:** Statistical baseline/deviation math is well-suited to deterministic implementation (rolling stats, z-scores, EWMA, missing-data flags) and must be independently testable without an LLM in the loop. This also makes the system's severity classification (normal / unusual / worth monitoring / potentially concerning / urgent) reproducible and auditable.
- **Consequences:** Every anomaly the system surfaces must carry a deterministic trace (what rule/statistic fired) that the LLM explanation is grounded in — never invented independently. See AGENTS.md (Baseline Agent, Anomaly Agent vs. Health Intelligence Agent) and Architecture.md (Agent Layer).
- **Status:** Accepted.

---

## ADR-005: Analysis Cadence and Event Orchestration
- **Date:** 2026-09-03
- **Decision:** Three distinct trigger types run against the same underlying data with explicit precedence rules to avoid duplicate/conflicting flags: (1) event-driven — fires on ingestion of a measurement that crosses a hard deterministic threshold; (2) hourly — rolls up recent data, checks short-term trend deviation; (3) daily — full baseline recompute, trend analysis, and report generation. A given underlying anomaly is only "announced" once per severity escalation, not re-announced by every subsequent cadence that also sees it.
- **Context:** Vision specifies all three cadences without defining how they interact; naive implementation would re-flag the same anomaly three times (event, then hourly, then daily), causing notification fatigue (violates principle #16).
- **Alternatives considered:** Independent, unaware jobs. Rejected: directly causes duplicate alerts.
- **Reasoning:** A shared "open findings" state (see DataModel.md — Anomaly entity with status: new/acknowledged/escalated/resolved) lets each cadence check "has this already been surfaced" before notifying, and only notify again on genuine escalation or resolution.
- **Consequences:** Requires an Anomaly state machine, not just point-in-time flags. See Architecture.md §Event Orchestration.
- **Status:** Accepted.

---

## ADR-006: Backend Framework & Ecosystem
- **Date:** 2026-09-04
- **Decision:** Build the backend platform using Python 3.11+, FastAPI, Pydantic v2, and SQLAlchemy 2.0 with `asyncio`.
- **Context:** The system demands high-throughput asynchronous batch ingestion alongside heavy numerical and statistical time-series computation (NumPy, SciPy) and seamless native orchestration with modern LLM frameworks (LangChain, LiteLLM).
- **Alternatives considered:**
  - Node.js / TypeScript (NestJS): Excellent for general I/O, but poor native data-science and statistical modeling libraries.
  - Go: Superior raw concurrency and low memory, but significantly slows down agentic LLM experimentation and complex statistical baseline prototyping.
- **Reasoning:** Python provides the premier unified ecosystem spanning asynchronous microservices, robust statistical computing, and LLM tooling. FastAPI provides automated OpenAPI generation and strict Pydantic validation.
- **Consequences:** Asynchronous I/O must be strictly enforced across all database queries and HTTP network clients to prevent event loop starvation. Heavy statistical baseline recalculations must be dispatched to worker processes.
- **Status:** Accepted.

---

## ADR-007: Database Architecture & Time-Series Engine
- **Date:** 2026-09-04
- **Decision:** Adopt PostgreSQL 16 equipped with the TimescaleDB extension as the primary database store.
- **Context:** The system requires relational modeling for users, devices, approvals, findings, and audit trails, while concurrently storing high-frequency, append-only biometric time-series measurements requiring fast rolling aggregations.
- **Alternatives considered:**
  - Pure NoSQL Time-Series (InfluxDB) alongside a separate relational DB: Introduces dual-write hazards, complex cross-database transactions, and infrastructure maintenance overhead.
  - Pure MongoDB: Lacks strict foreign key constraints and transactional integrity needed for clinical audit compliance.
- **Reasoning:** PostgreSQL with TimescaleDB hypertables provides the ideal hybrid: ACID relational integrity for core entities alongside native chunk-based time-series partitioning and continuous rollups for biometrics.
- **Consequences:** Developers must write standard SQLAlchemy models while utilizing TimescaleDB hypertable DDL migration scripts for the `measurements` table.
- **Status:** Accepted.

---

## ADR-008: Android Mobile Gateway Architecture
- **Date:** 2026-09-04
- **Decision:** Standardize the Android companion application on Kotlin, Jetpack Compose for declarative UI, Room DB for local offline staging, and WorkManager for resilient background sync.
- **Context:** Android is the primary user interface and ingestion gateway. It must operate across varied OEM power-management regimes (MIUI, OxygenOS, One UI) without dropping wearable biometric data.
- **Alternatives considered:**
  - Cross-platform Flutter / React Native: Insufficient low-level integration with the emerging Android Health Connect SDK and background OS battery optimization hooks.
- **Reasoning:** Native Android is strictly necessary to reliably integrate Google Health Connect, manage WorkManager background execution, and handle Android 14+ foreground service permissions.
- **Consequences:** Mobile codebase is dedicated to Android; iOS support is deferred until HealthKit adapters are architected in V2.
- **Status:** Accepted.

---

## ADR-009: Adoption of LangGraph & LangSmith for Agent Workflows and Observability
- **Date:** 2026-09-04
- **Decision:** Adopt LangGraph 0.2+ as the agent orchestration framework and LangSmith as the tracing, evaluation, and observability platform.
- **Context:** Multi-step agentic workflows in healthcare require stateful execution, deterministic branching, human-in-the-loop interruption (`interrupt()`), checkpoint persistence, and rigorous evaluation against hallucinations and diagnostic claims.
- **Alternatives considered:**
  - CrewAI / AutoGen: Too unstructured and conversational; difficult to enforce strict typed state schemas and deterministic branching.
  - Custom pure Python state machines: High maintenance burden for checkpointing, retries, and step-by-step tracing.
- **Reasoning:** LangGraph provides first-class `StateGraph` primitives, typed state dictionaries (`TypedDict`/Pydantic), interruptible checkpoints (`AsyncPostgresSaver`), and seamless native integration with LangSmith for automated evaluation datasets.
- **Consequences:** All agent workflows must be modeled as formal graphs with explicit state schemas. LangChain is used selectively for chat model wrappers and structured outputs, avoiding abstraction bloat.
- **Status:** Accepted.

---

## ADR-010: Adoption of ARQ with Redis for Background Worker Execution
- **Date:** 2026-09-04
- **Decision:** Use ARQ (asyncio-native Redis job queue) for background tasks, cadence scheduling (hourly/daily crons), and asynchronous LangGraph workflow execution.
- **Context:** FastAPI HTTP request handlers must remain fast (<50ms) and never execute long-running baseline recomputations, LLM agent graphs, or PDF compilations synchronously.
- **Alternatives considered:**
  - Celery: Heavyweight, complex broker configuration, traditionally synchronous execution models with tricky `asyncio` event loop integration.
  - Dramatiq: Good, but extra dependencies compared to lightweight ARQ.
- **Reasoning:** ARQ is built natively on Python `asyncio` and Redis. It shares the exact same async event loop, connection pool patterns, and Pydantic schemas as FastAPI, making task dispatch and async execution completely seamless.
- **Consequences:** Redis 7 is required as both the cache layer and the ARQ job broker. All background tasks must be idempotent.
- **Status:** Accepted.

---

## ADR-011: Non-Conflicting Host Port Segregation (PostgreSQL 5435 / Redis 6380)
- **Date:** 2026-09-04
- **Decision:** Expose Docker Compose services on dedicated host ports (`5435:5432` for TimescaleDB, `6380:6379` for Redis) while strictly preserving default ports (`5432` and `6379`) for inter-container Docker networking.
- **Context:** Developers and multi-project workstations frequently run unrelated existing databases or caches on standard ports `5432` and `6379`. Forcing standard host ports broke initial container startup due to collision with host services (`acharya_postgres`, `acharya_redis`).
- **Alternatives considered:**
  - Stop or terminate existing foreign containers: Rejected as high-risk, violating least-privilege safety protocols.
  - Require host-network mode: Breaks isolated multi-container DNS and portability.
- **Reasoning:** Port mapping allows developer host tools (Alembic CLI, local pytest, pgAdmin) to target `localhost:5435` and `localhost:6380` via `.env`, while Docker services (`backend`, `worker`) communicate across internal Docker DNS (`db:5432`, `redis:6379`) using standard ports.
- **Consequences:** `.env.example` documents `5435` and `6380`. Docker Compose environment variables override these for internal container networking.
- **Status:** Accepted.

---

## ADR-012: NullPool Engine Isolation for Pytest AsyncPG Integration Testing
- **Date:** 2026-09-04
- **Decision:** Use SQLAlchemy `poolclass=NullPool` with FastAPI `dependency_overrides[get_db]` for async integration testing against live PostgreSQL/TimescaleDB.
- **Context:** In `pytest-asyncio`, test cases run on distinct event loops or concurrent task contexts. Default pooled asyncpg connections are bound to the specific event loop in which they were created; sharing pooled connections across test cases throws `asyncpg.InterfaceError: cannot perform operation: another operation is in progress`.
- **Alternatives considered:**
  - Mock database: Rejected per explicit architectural rule requiring genuine database integration tests without mocks.
  - Session-scoped event loop: Can lead to state leakage between test cases and deprecation warnings in modern `pytest-asyncio`.
- **Reasoning:** `NullPool` opens a fresh connection per session and disposes of it immediately upon commit/close, preventing connection bleeding across event loops while ensuring 100% genuine database persistence and index validation.
- **Consequences:** Integration test suite executes safely against live TimescaleDB without interface deadlocks.
- **Status:** Accepted.

---

## ADR-013: Deterministic Analytical Provenance and Idempotent Finding Identity
- **Date:** 2026-09-04
- **Decision:** Every detected anomaly persisted to the `findings` table must store complete mathematical provenance (`observed_value`, `baseline_value`, `deviation`, `standard_deviation`, `reading_timestamp`, `timezone`, `activity_context`, `data_quality`, `confidence`, `source_measurement_ids`, and `evidence`), backed by a database-level unique constraint `(user_id, metric_type, rule_id, reading_timestamp)`.
- **Context:** Anomaly detection must be fully auditable without LLM involvement. Background worker pipelines (hourly crons or acute event triggers) may run repeatedly across overlapping windows; without deterministic identity and database-level unique constraints, duplicate findings and alert storms would result.
- **Alternatives considered:**
  - Store only LLM-generated text explanation in database: Rejected as unauditable, non-reproducible, and violates core system principles.
  - Rely solely on in-memory application checks (`if exists`): Rejected as vulnerable to race conditions under concurrent worker executions.
- **Reasoning:** Storing raw mathematical inputs directly on the finding enables instant retrospective clinical auditing and exact reconstruction of why a rule fired. The unique index with `ON CONFLICT DO NOTHING` guarantees complete idempotency across repeated worker runs.
- **Consequences:** Findings schema and migration `20260904_0002` added these columns. LLMs generate secondary explanatory text only, never the primary finding.
- **Status:** Accepted.

---

## ADR-014: Timezone-Aware Circadian Seasonality Profiling
- **Date:** 2026-09-04
- **Decision:** The Baseline Intelligence engine must convert UTC measurement timestamps to the patient's local wall-clock timezone before aggregating into the 24-hour circadian seasonality profile (00:00–23:00).
- **Context:** Normal human physiology exhibits strong diurnal and circadian rhythms (e.g. nocturnal sleeping heart rate is substantially lower than afternoon active heart rate). If circadian bins are calculated using UTC timestamps, patients in non-UTC timezones (e.g., India `Asia/Kolkata` UTC+5:30) have their circadian curves shifted by several hours, confounding morning waking vitals with nocturnal rest.
- **Alternatives considered:**
  - Standardize all circadian bins on UTC: Rejected because 03:00 UTC corresponds to 08:30 AM in India, completely corrupting nocturnal baseline models.
  - Compute single 24-hour aggregate mean without circadian bins: Rejected because nocturnal resting tachycardia cannot be distinguished from normal daytime elevation without hourly circadian baselines.
- **Reasoning:** Grounding the 24-hour profile in the patient's local timezone accurately captures their biological circadian rhythm regardless of geographical location.
- **Consequences:** `users.timezone` is a mandatory analytical dependency. Fallback to UTC occurs only when timezone is invalid or unspecified.
- **Status:** Accepted.

---

## ADR-015: Longitudinal Personal Health Timeline Domain Abstraction
- **Date:** 2026-09-04
- **Decision:** Implement the Personal Health Timeline as a high-performance unified query and domain abstraction over existing `measurements`, `findings`, and `baselines` tables rather than duplicating telemetry into a secondary timeline table.
- **Context:** Storing billions of wearable time-series samples across duplicate database tables introduces severe storage bloat, synchronization lag, and dual-write inconsistency risks.
- **Alternatives considered:**
  - Materialize a separate `timeline_events` table: Rejected due to 2x storage overhead and dual-write failure points.
  - Query only raw measurements without timeline abstraction: Rejected because client applications and downstream LangGraph agents need unified chronological context combining observations with analytical findings.
- **Reasoning:** A domain abstraction provides a clean, unified view (`TimelineEvent`, `TimelineContextWindow`) backed by hypertable index scans, answering queries like "What was happening around this anomaly?" with sub-10ms query latency.
- **Consequences:** TimelineService coordinates queries across hypertable partitions and finding tables on demand.
- **Status:** Accepted.

---

## ADR-016: Deterministic Data Quality & Activity Context Classification
- **Date:** 2026-09-04
- **Decision:** Data quality evaluation (`excellent`, `good`, `limited`, `poor`, `invalid`) and activity context classification (`RESTING`, `WALKING`, `RUNNING`, `EXERCISE`, `SLEEPING`, `POST_EXERCISE`, `UNKNOWN`) must be computed strictly via deterministic mathematical rules and biological bounds, with zero LLM involvement.
- **Context:** Anomaly detection and baseline computation fail if noisy, detached, or biologically impossible data silently enters statistical pipelines.
- **Alternatives considered:**
  - Prompt LLM to infer whether the user was exercising or sleeping: Rejected as non-deterministic, cost-inefficient, and prone to hallucination.
  - Trust client-reported flags blindly: Rejected because wearable manufacturers use differing, uncalibrated heuristics.
- **Reasoning:** Mathematical thresholding of concurrent steps, circadian hours, and sensor confidence provides reproducible, auditable quality ratings and prevents poor-quality data from generating false alert storms.
- **Consequences:** `DataQualityEngine` and `ContextEngine` gate all downstream anomaly detection and baseline modeling.
- **Status:** Accepted.

---

## ADR-017: Multi-Day Longitudinal Trend & Baseline Drift Modeling
- **Date:** 2026-09-04
- **Decision:** Implement deterministic longitudinal trend detection using ordinary least squares regression over 7-to-28-day daily aggregates, strictly differentiating `POINT_ANOMALY`, `TREND`, `BASELINE_SHIFT`, and `SAFETY_FINDING`.
- **Context:** An acute, isolated spike (e.g. nocturnal resting tachycardia for 1 hour) is fundamentally different from a gradual, progressive elevation in resting heart rate over 14 days (e.g. overtraining, chronic stress, or illness incubation). Conflating these leads to incorrect clinical communication.
- **Alternatives considered:**
  - Treat all deviations as point anomalies: Rejected because gradual trends are missed until they breach extreme z-score cutoffs.
  - Rely on LLMs to describe trend charts: Rejected because slope, $R^2$, and drift z-scores must be exact and auditable.
- **Reasoning:** Computing slope, coefficient of determination ($R^2$), and drift z-scores over daily aggregates provides unambiguous evidence of progressive physiological drift.
- **Consequences:** `TrendEngine` produces structured `LongitudinalTrendReport` instances with evidence strength ratings (`strong`, `moderate`, `weak`).
- **Status:** Accepted.

---

## ADR-018: Human-in-the-Loop Consequential Action Gating
- **Date:** 2026-09-04
- **Decision:** Formally categorize system actions into `INFORMATIONAL_ACTION`, `RECOMMENDATION`, and `EXTERNAL_ACTION`. Autonomous execution of `EXTERNAL_ACTION` (such as doctor outreach, WhatsApp messaging, appointment booking, or medical record sharing) is strictly prohibited without an explicit user approval token.
- **Context:** AI health assistants must never initiate real-world clinical outreach or external communication without verified human patient consent (Rule H3, ADR-003, DPDP Act 2023).
- **Alternatives considered:**
  - Allow autonomous low-risk clinic inquiries: Rejected due to patient privacy risks, false alarms, and lack of clinical authorization.
- **Reasoning:** The `ActionGate` service intercepts all external action intents and verifies the presence of an authorized user approval token, writing immutable entries to `audit_logs`.
- **Consequences:** External actions cannot be executed autonomously under any circumstances.
- **Status:** Accepted.

---

## ADR-019: Granular Clinical Consent Lifecycle & DPDP Act 2023 Compliance
- **Date:** 2026-09-04
- **Decision:** Implement granular, revocable patient consent (`ClinicalConsent`) specifying purpose, permitted metrics, permitted findings, date range scope, and mandatory TTL expiration. Revoking consent immediately terminates downstream data export and sharing.
- **Context:** Under India's Digital Personal Data Protection (DPDP) Act 2023 and medical privacy best practices, health telemetry can only be processed and shared for explicit, consented purposes. Blanket, perpetual, or irrevocable consents are non-compliant.
- **Alternatives considered:**
  - Blanket account-level data sharing toggle: Rejected as non-compliant with DPDP Act data minimization and purpose limitation mandates.
  - Relying on client-side state alone: Rejected because revocation must be enforced server-side against all downstream pipelines.
- **Reasoning:** Storing consent records with immutable audit trails (`consent_granted`, `consent_revoked`) and gating all Doctor Visit Summary exports through `ConsentService.validate_consent_active` guarantees strict legal compliance.
- **Consequences:** All clinical briefs require an active, non-expired, non-revoked consent record.
- **Status:** Accepted.

---

## ADR-020: Deterministic Specialty-Routing Engine vs Prohibited Medical Diagnosis
- **Date:** 2026-09-04
- **Decision:** Clinical specialty recommendations (e.g. Cardiology, Internal Medicine, Sleep Medicine) must be evaluated exclusively via deterministic rule-based logic (`SpecialtyRouter`) and never by LLM inference. All outputs must carry mandatory non-diagnostic disclaimers.
- **Context:** Suggesting a clinical specialty to consult must not cross the boundary into diagnosing medical conditions (Rule H1). LLMs are prone to diagnostic drift and hallucinated clinical classifications.
- **Alternatives considered:**
  - LLM-based triage and specialty classification: Rejected due to diagnostic hallucination and regulatory medical device liability.
  - Zero specialty suggestions: Rejected because patients benefit from knowing whether their wearable vital shifts warrant primary care vs cardiology consultation.
- **Reasoning:** Rule-based mapping of objective sensor deviations (e.g., nocturnal tachycardia without motion -> Cardiology; multi-day gradual drift -> Internal Medicine) provides consistent, explainable routing without asserting pathology.
- **Consequences:** `SpecialtyRouter` produces deterministic routing decisions with statutory disclaimers.
- **Status:** Accepted.

---

## ADR-021: Five-Stage Doctor Visit Summary Lifecycle (DRAFT -> REVIEW -> REDACT -> APPROVE -> EXPORT) & Cryptographic Checksums
- **Date:** 2026-09-04
- **Decision:** Enforce a strict 5-stage state machine for clinical summaries. The patient must have the power to preview the draft, redact sensitive metrics or findings, and explicitly issue an approval token before vector PDF export is permitted. Documents are sealed with SHA-256 integrity checksums.
- **Context:** Patients must retain full agency over what physiological data is disclosed to healthcare providers. System-generated summaries must not be exportable without verified patient review.
- **Alternatives considered:**
  - Direct 1-click PDF download: Rejected because patients cannot redact sensitive windows or verify included findings prior to dissemination.
- **Reasoning:** The `DoctorVisitSummaryService` verifies state transitions (`draft -> reviewed -> redacted -> approved -> exported`), recalculates SHA-256 hashes upon redaction, and blocks unapproved exports with HTTP 400.
- **Consequences:** Every clinical brief exported is evidence-grounded, patient-approved, tamper-evident, and auditable.
- **Status:** Accepted.




