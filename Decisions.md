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

---

## ADR-022: Multi-Tier Envelope Encryption Architecture with AES-256-GCM and Key Rotation
- **Date:** 2026-09-04
- **Decision:** Implement multi-tier envelope encryption (`EnvelopeEncryptionService`) utilizing Master Key (KEK) $\to$ Ephemeral Data Encryption Key (DEK) $\to$ AES-256-GCM authenticated cipher for all sensitive PHI payloads and credentials. Support seamless zero-downtime key rotation using canonical token prefixing (`env:<key_id>:...`).
- **Context:** Storing sensitive personal health records and provider communications requires cryptographic defense-in-depth against snapshot exfiltration, database compromises, and insider threats. Hardcoding single static symmetric keys prevents secure key lifecycle management and periodic rotation.
- **Alternatives considered:**
  - Single static AES-256 key: Rejected due to inability to rotate keys without decrypting and re-encrypting the entire database in a high-risk maintenance window.
  - Database-only storage volume encryption (EBS): Necessary but insufficient on its own; does not protect against SQL injection or privileged database role leakage.
- **Reasoning:** Envelope encryption limits the blast radius of any individual key: data is encrypted under an ephemeral DEK generated per record, and the DEK is encrypted under the KEK. Canonical token formatting embeds the key version (`env:v1:...`), allowing the service to decrypt historical records using `OLD_KEYS` while encrypting all new writes with `CURRENT_KEY_ID`.
- **Consequences:** `backend/app/core/crypto.py` and unit test suite `test_crypto.py` verify authenticated roundtrips, tampering detection, and background re-encryption.
- **Status:** Accepted.

---

## ADR-023: Redis-Backed Sliding-Window Rate Limiting with Fail-Open Clinical Safety
- **Date:** 2026-09-04
- **Decision:** Implement distributed sliding-window rate limiting (`RateLimiter`) backed by Redis Sorted Sets (ZSET) across public authentication, wearable ingestion, and clinical document synthesis endpoints. In the event of a Redis outage, the limiter must fail open with a diagnostic log warning.
- **Context:** High-frequency wearable sync endpoints and computationally expensive clinical brief synthesis (PDF compilation, trend analysis) are vulnerable to Denial of Service (DoS), brute force credential attacks, and rogue sync loops. However, an overly rigid rate limiter that fails closed during infrastructure degradation could dangerously block critical physiological monitoring.
- **Alternatives considered:**
  - In-memory fixed-window counter: Rejected because fixed windows allow $2\times$ burst at window boundaries, and in-memory counters do not scale across multiple API worker replicas.
  - Fail-closed rate limiting: Rejected because Personal Health OS processes safety-critical telemetry; denying sync due to a cache glitch could prevent urgent anomaly detection.
- **Reasoning:** Redis ZSET sliding-window algorithm guarantees mathematically exact rolling request counts. Wrapping Redis calls in try/except with fail-open fallback balances security against healthcare system availability.
- **Consequences:** Quotas enforced: `/v1/auth/login` (5 req/min per IP), `/v1/sync/batch` (60 req/min per user), `/v1/care/summary/draft` (10 req/min per user), and `/v1/care/summary/{id}/export/pdf` (10 req/min per user).
- **Status:** Accepted.

---

## ADR-024: Non-Root Container Security Standard and Disaster Recovery Drill Standard
- **Date:** 2026-09-04
- **Decision:** Production containers must run strictly as unprivileged user `appuser:10001`, enforce read-only root filesystems, drop all Linux capabilities (`cap_drop: [ALL]`), and mount temporary files on `tmpfs`. Database disaster recovery procedures must be verified through actual live restore drills with table row parity audits.
- **Context:** Running containers as root creates severe host privilege escalation risks. Furthermore, treating a disaster recovery strategy as complete simply because a `pg_dump` script exists is a major operational anti-pattern in health-tech.
- **Alternatives considered:**
  - Standard root container with writable filesystem: Rejected as violating basic CIS Docker benchmarks and DevSecOps standards.
  - Theoretical disaster recovery runbook: Rejected because untested backups frequently fail during real outages due to extension mismatches or permission errors.
- **Reasoning:** Enforcing non-root UID 10001 and read-only filesystems prevents runtime binary tampering. Conducting a live restore drill into a temporary database (`healthos_db_drill`) proved 100% row parity across all 7 core tables and TimescaleDB hypertable chunks with $<30$ second RTO.
- **Consequences:** `Dockerfile`, `docker-compose.prod.yml`, `scripts/backup_db.sh`, and `scripts/restore_db.sh` establish production-grade operational readiness.
- **Status:** Accepted.

---

## ADR-025: Deterministic Notification Severity Ownership and 5-Tier Alert Hierarchy
- **Date:** 2026-09-04
- **Decision:** All alert tiers and severity ratings must strictly originate from the deterministic health pipeline (Rule H2 hard biological gates, NumPy z-score calculations, and baseline deviation modeling). The LangGraph notification agent (`NotificationGraph`) and any LLM node are strictly forbidden from changing, inferring, overriding, or recalculating severity or alert eligibility.
- **Context:** An AI model interpreting notification urgency could hallucinate or downgrade a severe cardiovascular event (e.g. resting heart rate >= 150 bpm) or trigger alarmist push alerts for benign circadian fluctuations.
- **Alternatives considered:**
  - LLM-determined notification urgency: Rejected as an unsafe clinical anti-pattern.
  - Hardcoded single-tier push notifications for all anomalies: Rejected due to catastrophic alert fatigue.
- **Reasoning:** A deterministic 5-level policy (Level 0 Info, Level 1 Insight, Level 2 Attention, Level 3 Important, Level 4 Urgent) provides verifiable mathematical boundaries. Level 0 remains on the timeline, Level 1 is batched into daily digests, Level 2 updates in-app feeds, Level 3 triggers waking push alerts, and Level 4 executes immediate high-priority dispatch with emergency disclaimers.
- **Consequences:** `NotificationPolicyEngine` operates as a pure deterministic service. In the event of total LLM failure, all alerts continue to generate and dispatch with 100% mathematical fidelity.
- **Status:** Accepted.

---

## ADR-026: Authoritative PostgreSQL Notification State Machine
- **Date:** 2026-09-04
- **Decision:** Model notification lifecycle through an authoritative, persistent state machine in PostgreSQL: `CREATED` $\to$ `POLICY_EVALUATED` $\to$ `DEDUP_CHECKED` $\to$ `QUEUED` $\to$ `DISPATCHING` $\to$ `DELIVERED`. Support explicit failure transitions (`FAILED` $\to$ `RETRYING` $\to$ `DEAD_LETTER`), terminal dismissals (`ACKNOWLEDGED`, `DISMISSED`), and expirations (`EXPIRED`).
- **Context:** Ephemeral, un-persisted message queues lose alert delivery state during process restarts and prevent reliable multi-device synchronization and clinical auditing.
- **Alternatives considered:**
  - In-memory dispatch tracking: Rejected due to data loss during worker restarts.
  - Simple binary `sent: bool` column: Rejected because it cannot track retries, quiet hours deferrals, user acknowledgements, or dead-letter queue diagnostics.
- **Reasoning:** Storing explicit state in `notifications.state` ensures auditability, supports bounded retries, and enables users to review active and historical alerts across multiple client sessions.
- **Consequences:** Alembic migration `20260904_0005` added state machine columns, retry counters, and quiet hours hold flags. `NotificationStateMachine` validates all transitions.
- **Status:** Accepted.

---

## ADR-027: WebSocket as Transport Separation and Missed-Event Catch-Up
- **Date:** 2026-09-04
- **Decision:** Design WebSocket streaming (`/v1/ws/stream`) strictly as an ephemeral transport mechanism. PostgreSQL remains the sole authoritative source of truth. The WebSocket protocol must support JWT authentication, tenant isolation, ping/pong heartbeats, and a cursor-based catch-up protocol (`?since=<timestamp>`) upon reconnection.
- **Context:** Mobile clients on cellular networks frequently drop connections due to sleep modes or cell tower transitions. Relying on WebSocket message queues for durable storage leads to missing critical health events.
- **Alternatives considered:**
  - WebSocket as message store: Rejected due to memory overhead and potential data loss during socket reconnection.
  - Client polling only: Rejected due to excessive network traffic and latency for critical alerts.
- **Reasoning:** Coupling real-time WebSocket broadcast with a database replay protocol ensures instantaneous delivery when connected, while guaranteeing zero missed alerts upon reconnection.
- **Consequences:** `WebSocketConnectionManager` isolates user sockets into dedicated pools and supports catch-up notification replay queries directly against PostgreSQL.
- **Status:** Accepted.

---

## ADR-028: Atomic 12-Hour Deduplication with Severity Escalation Bypass
- **Date:** 2026-09-04
- **Decision:** Implement atomic, database-backed 12-hour deduplication window per `(user_id, finding_id, channel)`. Repeated worker evaluations of the same finding within 12 hours must be suppressed as duplicates (`SUPPRESSED_DUPLICATE`). However, if an existing finding escalates in severity (e.g. Level 2 Attention $\to$ Level 4 Urgent), the 12-hour window must be immediately bypassed to dispatch the urgent alert.
- **Context:** Wearable devices sync continuously. If an anomaly persists across multiple sync batches, naive alerting will spam the user with identical push alerts every 15 minutes. Conversely, suppressing an alert whose physiological severity suddenly spiked to emergency levels is clinically dangerous.
- **Alternatives considered:**
  - Rigid 12-hour window with no escalation bypass: Rejected as life-threatening.
  - Stateless deduplication in Redis only: Rejected because Redis restarts or cache evictions would cause alert storms.
- **Reasoning:** Database query against recent notification states within the 12-hour cutoff combined with deterministic severity comparison (`current_tier > prior_tier`) guarantees both fatigue prevention and emergency escalation responsiveness.
- **Consequences:** Verified by unit and integration tests: identical findings yield 0 duplicate notifications, while escalation to Level 4 immediately triggers new delivery.
- **Status:** Accepted.

---

## ADR-029: Timezone-Aware Quiet Hours with Non-Negotiable Safety Override
- **Date:** 2026-09-04
- **Decision:** Enforce user quiet hours (default 22:00–07:00) calculated dynamically in the user's localized timezone (`ZoneInfo`). Non-urgent alerts (Levels 2 & 3) generated during quiet hours must be persisted to the in-app feed silently with `quiet_hours_held = True`, with audible FCM push postponed until quiet hours conclude. **Level 4 Urgent alerts strictly and permanently override quiet hours** and cannot be disabled by user preferences.
- **Context:** Nocturnal alert fatigue causes patients to disable health monitoring apps entirely. However, dangerous biological thresholds (e.g. acute resting tachycardia >= 150 bpm) require immediate waking intervention.
- **Alternatives considered:**
  - UTC-based quiet hours: Rejected as completely erroneous for global users across differing timezones.
  - Allowing users to mute Level 4 Urgent alerts: Rejected under safety guidelines.
- **Reasoning:** `QuietHoursEvaluator` converts UTC timestamps to localized time and computes exact morning release UTC timestamps. `UserPreferenceService` permanently sets `emergency_override_enabled = True`.
- **Consequences:** Nighttime vital variations are calmly held for waking review, while acute biological emergencies penetrate quiet hours immediately.
- **Status:** Accepted.

