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
