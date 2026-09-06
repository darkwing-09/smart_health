# SKILL.md — Reusable Engineering & AI Skills Catalog

This document defines the specialized capabilities, procedures, constraints, and verification protocols required by AI assistants and engineers developing Personal Health OS.

---

## 1. Health-Data Ingestion Skill

- **Purpose:** Ingest, sanitize, validate, and normalize continuous biometric streams from wearable sources into the unified personal health timeline.
- **When to Use:** When building or modifying data adapters (Health Connect, Fitbit, Garmin), sync workers, or backend ingestion endpoints.
- **Inputs:** Raw wearable payloads, device metadata, synchronization timestamps, client authentication tokens.
- **Outputs:** Normalized `Measurement` records with standard units (bpm, steps, meters, seconds), provenance tags, and data quality flags.
- **Constraints:**
  - Never interpolate or invent missing readings.
  - Reject records outside biological plausibility (e.g., HR < 20 or > 260 bpm).
  - Enforce idempotency on batch ingestion via composite key `(user_id, source_id, metric_type, recorded_at)`.
- **Verification Requirements:** Unit test with synthetic batches including duplicate timestamps, out-of-order records, and extreme physiological bounds.

---

## 2. Android Development Skill

- **Purpose:** Develop the Android mobile gateway application using Kotlin, Jetpack Compose, Health Connect SDK, Room DB, and WorkManager.
- **When to Use:** When implementing mobile UI screens, Health Connect integration, local caching, background sync, or FCM notification handlers.
- **Inputs:** UI/UX wireframes, API contracts (`API.md`), Android OS permission specifications.
- **Outputs:** Production-grade Kotlin source code, Jetpack Compose layouts, Room DAOs, and WorkManager worker definitions.
- **Constraints:**
  - Respect Android battery optimization and Doze mode constraints.
  - Implement offline-first local queueing in Room DB before network transmission.
  - Keep biometric credentials secure using Android Keystore and EncryptedSharedPreferences.
- **Verification Requirements:** Execute on Android SDK 34 emulator/device; verify permission grant/denial flows and network disconnect resilience.

---

## 3. Backend Development Skill

- **Purpose:** Construct high-performance, asynchronous RESTful backend microservices using Python 3.11+, FastAPI, Pydantic v2, and async SQLAlchemy.
- **When to Use:** When implementing API endpoints, business logic services, dependency injection, and middleware.
- **Inputs:** OpenAPI specifications (`API.md`), database models (`DataModel.md`), architecture blueprints.
- **Outputs:** Asynchronous Python endpoints, Pydantic request/response schemas, service layer modules.
- **Constraints:**
  - Use asynchronous I/O (`async`/`await`) for all database queries and external HTTP calls.
  - Enforce strict type annotations validated by `mypy --strict`.
  - Comply with RFC 7807 error formatting.
- **Verification Requirements:** Run Pytest with FastAPI `AsyncClient`; achieve >90% code coverage on core service paths.

---

## 4. Database Operations Skill

- **Purpose:** Manage relational and time-series schemas, migrations, indexes, partitioning, and query optimization using PostgreSQL 16 and TimescaleDB.
- **When to Use:** When designing entities, modifying table structures, writing complex analytical queries, or tuning performance.
- **Inputs:** Conceptual schema (`DataModel.md`), performance requirements, data retention policies.
- **Outputs:** SQLAlchemy declarative models, Alembic migration scripts, optimized SQL queries.
- **Constraints:**
  - Maintain immutability of the `measurements` and `audit_logs` tables.
  - Every foreign key must be accompanied by an index.
  - Never execute unindexed scans over time-series measurement tables.
- **Verification Requirements:** Execute `alembic upgrade head` and `alembic downgrade -1` clean cycles; verify index usage via `EXPLAIN ANALYZE`.

---

## 5. Anomaly Analysis & Baseline Modeling Skill

- **Purpose:** Compute individualized rolling baselines, circadian seasonality curves, and evaluate candidate physiological anomalies using deterministic statistical models.
- **When to Use:** When implementing or tuning the Baseline Service and Anomaly Detection Service.
- **Inputs:** Historical normalized measurement time-series (minimum 14–30 days for established baseline), current real-time readings.
- **Outputs:** Baseline entities (rolling mean, standard deviation, circadian hourly profiles) and classified Findings (`unusual`, `worth_monitoring`, `potentially_concerning`, `urgent`).
- **Constraints:**
  - Strictly deterministic calculations (NumPy/SciPy); zero LLM hallucination in anomaly identification.
  - Never classify a metric as anomalous during the baseline learning period unless absolute emergency thresholds are breached.
- **Verification Requirements:** Run test vectors across known synthetic physiological conditions (bradycardia, nocturnal tachycardia, step count drops, sensor dropouts).

---

## 6. Agent Orchestration Skill

- **Purpose:** Orchestrate multi-agent workflows, managing state transitions, cadences (event-driven, hourly, daily), and LLM tool execution.
- **When to Use:** When implementing the Agent Orchestrator, dispatching jobs, or enforcing finding lifecycle states.
- **Inputs:** Incoming ingestion events, scheduled cron triggers, deterministic Findings.
- **Outputs:** Executed agent tasks, updated Finding states (`new` → `notified` → `acknowledged` → `resolved`), audit log entries.
- **Constraints:**
  - Strictly enforce ADR-005 anti-fatigue deduplication logic.
  - Sandbox all agent tool calls with explicit parameter schemas and execution timeouts.
- **Verification Requirements:** Multi-cadence integration tests validating that an ongoing anomaly does not re-alert on consecutive hourly runs.

---

## 7. Medical Research & Care Navigation Skill

- **Purpose:** Discover, verify, and summarize relevant healthcare specialties, hospitals, and clinical resources based on user-authorized contexts.
- **When to Use:** When the user requests care options following an anomaly or explicitly initiates clinic research.
- **Inputs:** User geographic coordinates, target specialty, confirmed anomaly context, user authorization token.
- **Outputs:** Ranked list of verified facilities and doctors with explicit source provenance and structured outreach drafts.
- **Constraints:**
  - Never fabricate hospital names, doctor qualifications, phone numbers, or booking availability.
  - Mark unverified external directories as `EXTERNAL DEPENDENCY — VERIFY BEFORE IMPLEMENTATION`.
  - Autonomous booking is strictly prohibited per ADR-003.
- **Verification Requirements:** Assert that every returned provider record originates from an active external directory response.

---

## 8. PDF Generation Skill

- **Purpose:** Compile comprehensive daily health digests into structured, publication-quality vector PDF documents.
- **When to Use:** When generating the scheduled daily health digest in the Report Service.
- **Inputs:** Daily aggregated vitals, active/resolved findings, LLM synthesized narrative, dynamically generated quote.
- **Outputs:** Standards-compliant binary PDF document (`application/pdf`).
- **Constraints:**
  - Guarantee deterministic compilation without external browser dependencies (using ReportLab).
  - Gracefully degrade to "trends-only" on days without anomalies without crashing.
- **Verification Requirements:** Automated unit tests verifying PDF byte validity, table rendering, text wrapping, and page-count bounds.

---

## 9. Security Review Skill

- **Purpose:** Audit code, APIs, and data flows against the STRIDE threat model, cryptographic standards, and healthcare privacy regulations (DPDP Act, HIPAA).
- **When to Use:** Before merging any changes affecting authentication, authorization, data storage, or third-party integrations.
- **Inputs:** Git diffs, architecture diagrams, API specifications, environment configurations.
- **Outputs:** Security audit report, vulnerability classifications (CVSS), and actionable remediation directives.
- **Constraints:**
  - Enforce zero PHI in logs.
  - Verify encryption at rest (AES-256-GCM) and in transit (TLS 1.3).
  - Audit prompts against indirect prompt injection vectors.
- **Verification Requirements:** Run automated static analysis (`bandit`, `trivy`), verify secret-scanning hooks, and test token expiration handling.

---

## 10. Testing & QA Skill

- **Purpose:** Formulate and execute comprehensive test plans across unit, integration, statistical, agent evaluation, and end-to-end suites.
- **When to Use:** During continuous development, pull request verification, and pre-release hardening.
- **Inputs:** Acceptance criteria (`PRD.md`), edge case matrices, test datasets.
- **Outputs:** Automated Pytest suites, Android instrumented tests, synthetic data generators, CI test reports.
- **Constraints:**
  - Mocks must strictly conform to verified third-party schemas.
  - Flaky tests are treated as critical bugs and must be resolved immediately.
- **Verification Requirements:** CI pipeline green status with >90% test coverage across deterministic analytics modules.

---

## 11. Debugging & Observability Skill

- **Purpose:** Trace, diagnose, and resolve distributed defects across mobile sync queues, backend ingestion pipelines, and agent state machines.
- **When to Use:** When investigating data loss, sync stalls, false-positive alerts, or unhandled exceptions.
- **Inputs:** OpenTelemetry traces, structured JSON server logs, Android logcat outputs, database query plans.
- **Outputs:** Root cause analysis (RCA), targeted bugfix pull requests, and regression test additions.
- **Constraints:**
  - Never reproduce bugs using production PHI; use anonymized or synthetic test vectors.
- **Verification Requirements:** Reproduction of defect in isolated automated test followed by verified green test execution post-fix.

---

## 12. Deployment & DevOps Skill

- **Purpose:** Manage containerization, infrastructure as code, CI/CD pipelines, database migrations, and release runbooks.
- **When to Use:** When configuring Docker environments, updating GitHub Actions workflows, or deploying staging/production environments.
- **Inputs:** Service source code, Dockerfiles, environment variables (`Config.md`), infrastructure manifests.
- **Outputs:** Deployable Docker images, Helm charts / Compose manifests, automated CI/CD pipelines, release artifacts.
- **Constraints:**
  - Zero downtime for database schema migrations.
  - Enforce least privilege on all deployment service accounts and API tokens.
- **Verification Requirements:** Successful automated container build, smoke test suite execution against staging container, clean rollback simulation.
