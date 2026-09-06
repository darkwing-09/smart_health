# Rules.md — Enforceable Engineering & Product Rules

These rules are strictly enforced across the Personal Health OS codebase, configuration, pipelines, and AI agent prompts. Every automated CI check, code review, and human contribution must comply without exception.

---

## 1. Product & Health-Data Safety Rules

1. **Rule H1 (Zero Fabricated Medical Diagnosis):** No component, code comment, UI text, or AI agent output may claim, suggest, or imply a definitive medical diagnosis (e.g., "You have atrial fibrillation"). All observations must be phrased as physiological metric shifts (e.g., "An irregular rhythm pattern was detected in your heart rate data").
2. **Rule H2 (Personal Baseline Primacy):** The system must never trigger an anomaly alert based solely on hardcoded population averages unless a validated physiological emergency threshold is crossed (e.g., sustained resting heart rate > 150 bpm). Baseline deviations must always be calculated against the user's personal statistical profile.
3. **Rule H3 (Mandatory 7-Part Explanation):** Every alert classified as `worth_monitoring`, `potentially_concerning`, or `urgent` must contain all seven mandatory fields: (1) what changed, (2) which measurements caused the flag, (3) how it differs from baseline, (4) historical context, (5) confidence/data quality, (6) why it matters, and (7) non-diagnostic next steps.
4. **Rule H4 (Deterministic Anomaly Gating):** An LLM or AI agent must never independently declare an anomaly that was not first identified by the deterministic analytics engine (ADR-004).
5. **Rule H5 (Explicit Action Authorization):** The system must never initiate an outbound communication, appointment request, or external data transmission without explicit, single-action human authorization. Blanket or persistent authorizations for external actions are strictly prohibited.
6. **Rule H6 (No Silent Gap Filling):** Missing sensor data must never be imputed or interpolated as "normal." Data gaps must be recorded explicitly as `missing` or `gap` in quality flags.
7. **Rule H7 (Deterministic Notification Severity Ownership):** Notification severity, alert tiers (Levels 0–4), and emergency safety classifications are strictly computed by deterministic Python code from the Finding layer. LangGraph agents and LLMs may NEVER alter, infer, or override these values.
8. **Rule H8 (Level 4 Emergency Quiet Hours Override):** Emergency Level 4 alerts must unconditionally override user quiet hours and preference minimums. Quiet hours must never delay or suppress life-critical physiological alerts.
9. **Rule H9 (12-Hour Anti-Fatigue Deduplication & Escalation Bypass):** Repeated findings for the same user, metric, and channel within a 12-hour window are suppressed, unless a higher severity tier is detected (e.g. Level 2 escalating to Level 4), which must immediately bypass suppression.
10. **Rule H10 (Zero Hardware Fabrication):** Physical wearable, mobile device, Android emulator, Health Connect IPC, or FCM production verification results must never be fabricated. In automated CI/CD and software simulation environments, hardware must be explicitly classified as BLOCKED when physical devices or runtime providers are absent.
11. **Rule H11 (Lockscreen PHI Privacy):** All Android notifications containing biometric telemetry or alert context must enforce `NotificationCompat.VISIBILITY_PRIVATE` with a generic public masking version to prevent lockscreen snooping.
12. **Rule H12 (Cryptographic ActionGate Token Binding):** External sharing or PDF exports of clinical summaries require an HMAC-SHA256 user-scoped and summary-scoped approval token with expiration $\le 3600\text{s}$.

---

## 2. Architecture & Design Rules

1. **Rule A1 (Layered Boundary Integrity):** The Android application is a synchronization gateway and local cache, not a clinical reasoning brain. Raw biometrics must never be interpreted solely in the client app.
2. **Rule A2 (Adapter Isolation):** All external data sources (Health Connect, Fitbit, Garmin) must be encapsulated within a standardized `DataSourceAdapter` interface. No vendor-specific SDK calls or data shapes may leak into the core normalized timeline.
3. **Rule A3 (Anti-Fatigue State Machine):** Notifications must adhere to the Finding state machine (ADR-005). An active, unresolved anomaly must never re-trigger a notification at the same severity tier within the configured deduplication window.
4. **Rule A4 (Graceful Offline Degradation):** All mobile operations must be offline-first. Ingestion records must be queued in local persistent storage (Room DB) and synchronized opportunistically without blocking the user interface.
5. **Rule A5 (WebSocket Transport Boundary):** WebSockets (`/v1/ws/stream`) serve strictly as a real-time event transport. PostgreSQL remains the single source of truth for notification states and health records. Reconnecting clients must synchronize missing state via cursor replay before resuming live feeds.

---

## 3. Coding & Naming Conventions

### Python (Backend & Services)
- **Formatting:** Code must format cleanly via `ruff format` and pass `ruff check` with zero warnings.
- **Typing:** Strict type hints (`mypy --strict`) are mandatory on all function signatures, parameters, and return types.
- **Naming Conventions:**
  - Files & Modules: `snake_case.py`
  - Classes: `PascalCase`
  - Functions & Variables: `snake_case`
  - Constants: `UPPER_SNAKE_CASE`
  - Pydantic Models & Schemas: Suffix with `Schema`, `Request`, or `Response` (e.g., `BatchIngestRequest`).
  - Database Models: Suffix with `Model` or represent clean singular entity (e.g., `Measurement`, `User`).

### Kotlin (Android Client)
- **Formatting:** Code must format cleanly via `ktlint`.
- **Architecture:** Android Jetpack MVVM / Clean Architecture with unidirectional data flow (UDF).
- **Naming Conventions:**
  - Files & Classes: `PascalCase.kt`
  - Methods & Variables: `camelCase`
  - Compose Functions: `PascalCase` (e.g., `TimelineScreen`)
  - Coroutines & Flow: Suffix Flows with `Flow` (e.g., `syncStateFlow`).

---

## 4. Security & Privacy Rules

1. **Rule S1 (Encryption Mandate):** All health data at rest must be encrypted using AES-256-GCM. All data in transit must use TLS 1.3.
2. **Rule S2 (Secret Management):** Zero hardcoded secrets, tokens, private keys, or API keys are permitted in source code, Dockerfiles, or git history. All credentials must be injected via environment variables validated by Pydantic `BaseSettings`.
3. **Rule S3 (Immutable Audit Logging):** Every state transition on a `Finding`, every `Notification` dispatched, and every `UserApproval` granted must write an immutable audit record to the `audit_logs` table.
4. **Rule S4 (Prompt Injection Defense):** AI agent prompts must sanitize and delimit untrusted user inputs (e.g., user symptom notes) using strict markdown code fences or XML tags (`<user_input>...</user_input>`) to prevent prompt injection.
5. **Rule S5 (Data Minimization):** Only biometric metrics actively supported by the normalized schema may be stored. Extraneous raw vendor payloads must be discarded post-normalization.

---

## 5. Database & Data Model Rules

1. **Rule D1 (Immutable Measurement Timeline):** The `measurements` table is append-only. Measurement records must never be updated or deleted during standard operation. Corrections must insert a new record with a `supersedes_id` reference.
2. **Rule D2 (Migration Strictness):** All schema modifications must be executed via versioned Alembic migration scripts. Direct manual SQL schema edits in production or staging are prohibited.
3. **Rule D3 (Foreign Key & Indexing):** Every foreign key must be indexed. Composite unique indexes must enforce idempotency across `(user_id, source_id, metric_type, recorded_at)`.
4. **Rule D4 (Timestamp Standardization):** All database timestamps must be stored in UTC (`TIMESTAMP WITH TIME ZONE`). Timestamps must use microsecond precision.

---

## 6. API & Integration Rules

1. **Rule P1 (Idempotent Ingestion):** All batch ingestion endpoints must mandate an `Idempotency-Key` UUID header. Duplicate batch submissions must return HTTP 200 with the original processing summary without re-inserting records.
2. **Rule P2 (Standardized Error Responses):** All API errors must conform to RFC 7807 Problem Details JSON:
   ```json
   {
     "type": "https://api.healthos.local/errors/invalid-metric",
     "title": "Invalid Metric Value",
     "status": 422,
     "detail": "Heart rate value 350.0 bpm exceeds physiological bounds [20, 260].",
     "instance": "/v1/sync/batch"
   }
   ```
3. **Rule P3 (Zero Invented APIs):** Integrations with third-party platforms (Fitbit, Garmin, WhatsApp, Hospital portals) must use verified, documented contracts. Mocking third-party behavior for testing is permitted only when backed by documented schemas.

---

## 7. Testing & Quality Assurance Rules

1. **Rule T1 (Statistical Determinism Testing):** All baseline math (EWMA, z-score, variance calculations) must have 100% unit test coverage with synthetic deterministic datasets.
2. **Rule T2 (Grounding Verification on Agents):** AI agent evaluation tests must assert that generated explanations contain no factual assertions absent from the input telemetry.
3. **Rule T3 (Offline Sync Simulation):** Android client tests must explicitly verify offline queuing, network disconnects during sync, backoff retry policies, and crash resilience.
4. **Rule T4 (Pre-Commit Automation):** Commits must pass all pre-commit hooks (`ruff`, `mypy`, `ktlint`, unit tests) before pushing to remote branches.

---

## 8. Logging & Observability Rules

1. **Rule L1 (Structured JSON Logging):** All backend services must emit structured JSON logs with standard fields: `timestamp`, `level`, `service`, `trace_id`, `user_id` (pseudonymized/hashed), and `message`.
2. **Rule L2 (Zero PHI in Application Logs):** Protected Health Information (PHI) — including raw heart rate series, sleep logs, symptom notes, and doctor names — must **never** be written to standard stdout or debug application logs. Only entity IDs and metric types may appear in logs.
3. **Rule L3 (Agent Execution Tracing):** Every AI agent invocation must log execution latency, token counts, prompt template version, and model version to `agent_executions`.

---

## 9. Scope Control & Git Rules

1. **Rule G1 (Branching Strategy):** `main` is protected and deployable. Features must branch from `main` (`feature/<short-desc>`), undergo peer review via Pull Request, pass CI, and squash-merge.
2. **Rule G2 (Documentation Synchronization):** Any commit modifying an API, data model, or architectural behavior must include simultaneous updates to the corresponding documentation files (e.g., `API.md`, `DataModel.md`, `Changelog.md`, `Progress.md`).
3. **Rule G3 (No Scope Creep):** Proposed features outside the explicit MVP scope (e.g., autonomous doctor booking, multi-device syncing, continuous ECG analysis) must be recorded in `Issues.md` or `PRD.md` under V2/V3 and rejected from MVP pull requests.

---

## 10. Operational Governance & Real-World Pilot Launch Rules

1. **Rule O1 (Zero Hardware Fabrication Protocol):** Physical hardware validation (smartphones, Wear OS watches, Bluetooth LE streams) and external production push services (FCM) must **never** be reported as verified unless physically executed against real physical hardware and live credentials. Missing physical devices must be explicitly preserved as `BLOCKED` with documented unblocking runbooks.
2. **Rule O2 (Runbook-Gated Pilot Launch):** No participant data may be admitted into the production cluster without completing and signing off on all verification gates in `PILOT_DEPLOYMENT_CHECKLIST.md` and enforcing the onboarding protocols in `PILOT_SAFETY_PROTOCOL.md`.
3. **Rule O3 (SRE Response & Clinical Invariance):** Production incident triage must adhere strictly to `INCIDENT_RESPONSE_RUNBOOK.md`. Sev 1 incidents (critical alert delays or multi-tenant boundary compromise) require MTTA < 5 minutes and MTTR < 30 minutes with immediate notification to the Clinical Safety Auditor.

