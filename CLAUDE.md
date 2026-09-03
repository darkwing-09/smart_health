# CLAUDE.md — Master Operating Manual

This is the highest-level operating document for Personal Health OS. When in doubt about how to behave, this file wins over instinct — but never over Decisions.md once a decision is recorded, and never over Security.md on anything touching health data.

---

## 1. Project Identity & Mission

- **Project Name:** Personal Health OS
- **Mission:** Give a person a continuously observed, honestly explained, privacy-respecting picture of their own health — built from wearable + (eventually) multi-source data — without ever pretending to be a doctor.
- **Core Loop:** OBSERVE → UNDERSTAND → DETECT → EXPLAIN → RECOMMEND → ASK PERMISSION → ACT → VERIFY → RECORD OUTCOME.
- **Current Phase:** Phase 0 (Architecture & Foundations) complete, transitioning to Phase 1.

---

## 2. The Four Layers of Truth (Non-Negotiable)

Never blur these four distinct layers:
1. **Source Data:** What the device physically recorded (timestamps, raw values, battery, wear telemetry, firmware).
2. **Deterministic Analysis:** What our Python statistical code calculated (rolling mean, stddev, circadian bins, z-scores, CUSUM, quality flags).
3. **AI Interpretation:** What an agent inferred from available evidence (grounded 7-part plain language explanation, contextual trends, uncertainty notes).
4. **User / Action State:** What the user approved and what the system actually executed (user acknowledgment, doctor visit summaries, dispatched notifications).

---

## 3. Technology Stack & Framework Boundaries

- **Primary Language:** Python 3.11+ exclusively for backend, analytics, and intelligence. Zero Node.js, Go, or Java backend services.
- **Backend API:** FastAPI 0.111+ with Pydantic v2 validation and async SQLAlchemy 2.0.
- **Database:** PostgreSQL 16 with TimescaleDB hypertable extension for append-only `measurements`.
- **Cache & Worker Broker:** Redis 7 with ARQ for native `asyncio` background tasks and cron cadence scheduling.
- **Agent Orchestration:** LangGraph 0.2+ for stateful, branching, interruptible workflows with human-in-the-loop checkpoints.
- **Model Abstraction:** LangChain Core used strictly for model wrappers (`ChatOpenAI`, `ChatAnthropic`) and structured Pydantic outputs.
- **Observability & Evaluation:** LangSmith for run tracing, latency/token profiling, prompt versioning, and regression dataset testing.
- **PDF Compiler:** ReportLab for server-side vector PDF compilation.
- **Android Gateway:** Native Kotlin with Jetpack Compose, Room DB, WorkManager, and Health Connect SDK.

---

## 4. The 5-Level Notification Hierarchy

Prevent alert fatigue by adhering strictly to the notification hierarchy:
- **Level 0 (Information):** Nominal data within expected variance ($Z < 2.0$). App only; zero badges.
- **Level 1 (Insight):** Infrequent statistical shift ($2.0 \le Z < 2.8$). Batched into nightly PDF report.
- **Level 2 (Attention):** Multi-hour trend shift ($2.8 \le Z < 3.8$). In-app badge; respects quiet hours.
- **Level 3 (Important):** Significant personal deviation ($3.8 \le Z < 5.0$). Immediate FCM push with 7-part explanation; deduped for 12 hours.
- **Level 4 (Urgent):** Severe acute breach ($Z \ge 5.0$ or resting HR > 150 / < 38 bpm). Multi-channel alert + emergency medical disclaimer.

---

## 5. Development Philosophy & Coding Rules

- **Deterministic Primacy:** Use standard Python services for calculations, normalization, baseline stats, threshold gating, and deduplication. Reserve LangGraph agents for multi-step reasoning, tool coordination, and grounded explanation.
- **Zero Medical Diagnosis (Rule H1):** Never state or imply a medical diagnosis. Frame observations around physiological metric shifts.
- **Mandatory 7-Part Explanation:** Every finding at Level 2+ must include: (1) what changed, (2) measurements caused, (3) baseline diff, (4) historical context, (5) confidence/quality, (6) physiological meaning, (7) safe next steps.
- **Consequential Action Authorization:** Never initiate an outbound communication, appointment request, or third-party data transmission without an explicit single-action user authorization token.
- **Idempotency Mandate:** All batch ingestion and external action workflows must be idempotent (`Idempotency-Key` header).
- **Offline-First Resilience:** The Android app must stage records in Room DB before transmission and retry with exponential backoff.

---

## 6. Uncertainty, External Dependencies & Scope Control

- **Labeling Unknowns:**
  - Mark unverified external capabilities as `EXTERNAL DEPENDENCY — VERIFY BEFORE IMPLEMENTATION`.
  - Mark unconfirmed scientific/algorithm parameters as `UNDECIDED — REQUIRES VALIDATION`.
  - Mark non-MVP proposed features as `DEFERRED — NOT MVP`.
- **Scope Control Evaluation:** Before adding any proposed capability evaluate: VALUE → FEASIBILITY → COMPLEXITY → RISK → DEPENDENCIES → MVP FIT.

---

## 7. The Development & Verification Loop

For every substantial engineering task:
1. Understand the requirement and inspect existing code and documentation.
2. Formulate a concise implementation plan.
3. Implement the smallest correct change using modern Python and strict typing.
4. Run automated unit/integration tests and static analysis (`ruff`, `mypy`).
5. Run LangSmith evaluation datasets if modifying agent prompts or nodes.
6. Verify behavior and inspect for regressions.
7. Update relevant documentation, `Progress.md`, and `Changelog.md`.
8. Record major decisions in `Decisions.md` and surface open questions in `Issues.md`.

---

## 8. Definition of Done

A task or feature is done ONLY when:
- Code is fully implemented with strict type annotations.
- Unit and integration tests pass with >90% coverage on deterministic logic.
- Agent prompts satisfy LangSmith grounding and zero-diagnosis evaluation suites.
- Structured JSON logging and OpenTelemetry tracing are active with zero PHI logged.
- Documentation is synchronized across all affected files.
- Acceptance criteria defined in `PRD.md` and `Plan.md` are satisfied.
