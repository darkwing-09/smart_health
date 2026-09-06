# AGENTS.md — Agent System Architecture & Specifications

This document formally specifies all 12 autonomous, semi-autonomous, and deterministic worker agents within Personal Health OS. Every agent operates under strict least-privilege boundaries, explicit state schemas, and verifiable decision rules.

---

## Agent System Overview & Orchestration

```
 ┌────────────────────────────────────────────────────────┐
 │            1. INGESTION & DATA QUALITY TIER            │
 │  [1. Data Intelligence Agent] [2. Data Quality Agent]  │
 │  (Deterministic Worker)       (Deterministic Worker)   │
 └───────────────────────────┬────────────────────────────┘
                             │ Normalized Health Timeline (PostgreSQL)
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │            2. STATISTICAL ANALYTICS TIER               │
 │  [3. Baseline Intelligence]   [5. Anomaly Evaluation]  │
 │  (Deterministic Worker)       (Deterministic + Graph)  │
 └───────────────────────────┬────────────────────────────┘
                             │ Candidate Anomalies
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │        3. LANGGRAPH REASONING & ORCHESTRATION          │
 │  [4. Health Intelligence]     [12. Safety/Policy Agent]│
 │  (LangGraph Reasoning Node)   (Deterministic Rule Gate)│
 │                                                        │
 │  [6. Notification Agent]      [7. Daily Report Agent]  │
 │  (LangGraph Router Graph)     (LangGraph Synthesis)    │
 └───────────────────────────┬────────────────────────────┘
                             │ User-Authorized Context
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │          4. CARE NAVIGATION & USER ACTION TIER         │
 │  [8. Research Agent]          [9. Care Navigation]     │
 │  (LangGraph Search Tools)     (Workflow Coordinator)   │
 │                                                        │
 │  [10. Appointment Agent]      [11. User Preference]    │
 │  (Drafting + Human Interrupt) (Deterministic Profile)  │
 └────────────────────────────────────────────────────────┘
```

---

## The 12 System Agents

### 1. Data Intelligence Agent (Deterministic Worker)
- **Mission:** Ingest, parse, sanitize, and normalize heterogeneous wearable biometric feeds into the unified personal health timeline.
- **Engine Type:** **Deterministic Logic** (Zero LLM involvement).
- **Responsibilities:** Validate batch payloads against Pydantic schemas, convert vendor units to SI/standard units, verify biological bounds, assign immutable provenance and ingestion timestamps.
- **Inputs:** Raw JSON batch records from Android sync worker or third-party webhooks.
- **Outputs:** Ingested `Measurement` rows in database; emitted `measurement.ingested` internal events.
- **Tools & Permissions:** Database write access to `measurements` and `sync_batches`.
- **Decision Boundaries:** Accepts or rejects payloads based on schema validity and idempotency key.
- **Failure Behavior:** Emits HTTP 422 with RFC 7807 problem details; records failed batch in audit log.
- **What It Must NEVER Do:** Interpolate or generate synthetic data to fill gaps; modify existing immutable historical measurements.

---

### 2. Data Quality Agent (Deterministic Worker)
- **Mission:** Monitor continuous data streams for sensor detachment, missing windows, sampling frequency decay, and corrupted signals.
- **Engine Type:** **Deterministic Logic**.
- **Responsibilities:** Tag measurements with quality flags (`nominal`, `estimated`, `gap_filled`, `missing`); detect wearable off-wrist periods.
- **Inputs:** Ingested measurement sequences and device telemetry (battery, wear state).
- **Outputs:** `data_quality_flag` updates, telemetry gap records.
- **Tools & Permissions:** Read/update access to `measurements.data_quality_flag`.
- **Decision Boundaries:** Marks data as unwearable/gap if step count and heart rate are concurrently zero for >30 minutes while awake.
- **Failure Behavior:** Defaults to `nominal` if quality heuristic computation times out.
- **What It Must NEVER Do:** Delete records identified as noisy; classify missing data as healthy baseline.

---

### 3. Baseline Intelligence Agent (Deterministic Worker)
- **Mission:** Continuously model user-specific baseline distributions, variance, and circadian seasonality curves.
- **Engine Type:** **Deterministic Logic** (NumPy / SciPy statistical modeling).
- **Responsibilities:** Calculate rolling 30-day mean, standard deviation, and hourly circadian profiles (00:00–23:00) for every metric per user.
- **Inputs:** Historical normalized measurements flagged as `nominal`.
- **Outputs:** New rows in `baselines` table with updated statistical profiles and `rule_version`.
- **Tools & Permissions:** Read access to `measurements`; insert access to `baselines`.
- **Decision Boundaries:** Designates baseline as `established = true` only after minimum 14 days of representative data.
- **Failure Behavior:** Preserves previous day's baseline snapshot if daily recomputation fails.
- **What It Must NEVER Do:** Alter baseline parameters based on subjective or qualitative LLM text feedback.

---

### 4. Health Intelligence Agent (LangGraph Reasoning Node)
- **Mission:** Transform deterministic findings and baseline deviations into grounded, calm, 7-part plain language explanations.
- **Engine Type:** **LangGraph Reasoning Node** (Claude 3.5 Sonnet / GPT-4o with temperature=0.1, structured Pydantic output).
- **Responsibilities:** Generate the mandatory 7-part explanation structure: (1) what changed, (2) measurements caused, (3) baseline diff, (4) historical context, (5) confidence/quality, (6) physiological meaning, (7) safe next steps.
- **Inputs:** Flagged `Finding`, associated raw measurements, active `Baseline`, user profile context.
- **Outputs:** Validated `FindingExplanation` record linked to the `Finding`.
- **Tools & Permissions:** Read access to `findings`, `baselines`, `measurements`; write access to `finding_explanations`.
- **Decision Boundaries:** Restricts scope strictly to explaining why the flag was created; never adds external ungrounded facts.
- **Failure Behavior:** If LLM times out or schema validation fails, outputs a pre-compiled fallback explanation citing the raw mathematical deviation.
- **What It Must NEVER Do:** Declare a medical diagnosis; use panic-inducing language; contradict the deterministic severity tier.

---

### 5. Anomaly Evaluation Agent (Deterministic + LangGraph Evaluator)
- **Mission:** Score incoming or rolled-up metrics against current personal baseline using z-score and CUSUM; cross-examine secondary metrics to distinguish exertion from resting anomalies.
- **Engine Type:** **Hybrid** (NumPy z-score gating + LangGraph contextual evaluation).
- **Responsibilities:** Evaluate whether an anomaly candidate warrants an alert; assign initial severity tier (Level 0 Info, Level 1 Insight, Level 2 Attention, Level 3 Important, Level 4 Urgent).
- **Inputs:** Real-time measurements, hourly rollups, active baseline profile.
- **Outputs:** Created or escalated `Finding` records.
- **Tools & Permissions:** Read access to `baselines` and `measurements`; write access to `findings`.
- **Decision Boundaries:** Adheres strictly to mathematical cutoffs in `Config.md` (e.g., z-score >= 3.8 for Level 3 Important). Suppresses resting tachycardia if accelerometer/step count indicates simultaneous intense exercise.
- **Failure Behavior:** Logs calculation error and retries; fails safe by not alerting rather than generating false panic.
- **What It Must NEVER Do:** Downgrade an urgent finding without deterministic proof of physiological normalization; use LLM logic alone to detect deviations.

---

### 6. Notification Agent (LangGraph Router Graph)
- **Mission:** Route verified findings to appropriate delivery channels while strictly preventing alert fatigue and guaranteeing emergency dispatch.
- **Engine Type:** **LangGraph Stateful Graph (`NotificationGraph`)**.
- **Responsibilities:** Orchestrate deterministic alert tiers (Levels 0–4); execute atomic 12-hour deduplication and escalation bypass; evaluate timezone-aware quiet hours with Level 4 emergency override; transition notifications through the authoritative 7-state PostgreSQL state machine (`CREATED -> POLICY_EVALUATED -> DEDUP_CHECKED -> QUEUED -> DISPATCHING -> DELIVERED`); dispatch to FCM HTTP v1 and WebSocket streaming transports; track delivery fatigue metrics.
- **Inputs:** Approved `FindingExplanation` / `Finding` payload, user delivery preferences, device FCM tokens.
- **Outputs:** Transitioned `Notification` row in PostgreSQL, FCM HTTP v1 push, WebSocket broadcast event.
- **Tools & Permissions:** Read access to `user_preferences`, `findings`; write access to `notifications`, `audit_logs`; network access to FCM API.
- **Decision Boundaries:** Operates purely as an orchestration coordinator over deterministic inputs. Cannot change or infer alert severity. Level 4 Urgent alerts permanently bypass quiet hours.
- **Failure Behavior:** Bounded exponential backoff retries (max 3); routes to `DEAD_LETTER` upon exhaustion; falls back to deterministic text templates during LLM outages.
- **What It Must NEVER Do:** Alter, infer, or override deterministic severity; suppress Level 4 emergency alerts; re-notify for the same unresolved finding within 12 hours unless escalated; expose raw PHI in push payloads.

---

### 7. Daily Report Agent (LangGraph Synthesis Graph)
- **Mission:** Synthesize 24-hour health, exertion, and sleep trends into a cohesive narrative and generate a personalized closing reflection.
- **Engine Type:** **LangGraph Stateful Graph** (Claude 3.5 Sonnet / GPT-4o).
- **Responsibilities:** Ingest daily rollups and findings; write an executive health summary; generate a contextually relevant, non-cliché motivational or reflective quote; compile data for the ReportLab PDF engine.
- **Inputs:** Daily aggregated metrics, resolved/open findings, previous 7-day trend history.
- **Outputs:** Populated `Report` entity with synthesized narrative and closing quote.
- **Tools & Permissions:** Read access to daily summaries and findings; write access to `reports`.
- **Decision Boundaries:** Must generate report even on days with zero anomalies (degrades gracefully to trends-only).
- **Failure Behavior:** Falls back to deterministic metric table and static wellness quote if LLM synthesis fails.
- **What It Must NEVER Do:** Include hallucinations or speculate on non-measured health factors (e.g., diagnosing diet when food tracking is empty).

---

### 8. Research Agent (LangGraph + Verified Search Tools)
- **Mission:** Discover and verify local medical facilities, clinics, and medical specialties corresponding to user-requested care inquiries.
- **Engine Type:** **LangGraph Agent with Verified Tools**.
- **Responsibilities:** Query verified geographic and healthcare directory APIs (Google Places / OSM); filter results by distance, specialty match, and source confidence.
- **Inputs:** User coordinates, requested specialty/symptom context, explicit user invocation.
- **Outputs:** Ranked, verified list of providers with source URLs and verified clinic contact details.
- **Tools & Permissions:** Network access to verified directory APIs; write access to `hospitals` and `doctors` cache.
- **Decision Boundaries:** Operates only upon explicit user request or confirmed user tap on "Find Care".
- **Failure Behavior:** Returns "No verified providers found within your radius" rather than suggesting unverified clinics.
- **What It Must NEVER Do:** Fabricate doctors, phone numbers, addresses, or clinical qualifications; book appointments autonomously.

---

### 9. Care Navigation Agent (LangGraph Workflow Coordinator)
- **Mission:** Guide the patient through clinical evidence review, deterministic specialty routing, physician note synthesis, and human-in-the-loop care summaries.
- **Engine Type:** **LangGraph Stateful Graph (`CareNavigationGraph`)**.
- **Responsibilities:** Ingest structured summary payload and deterministic specialty routing decisions (`SpecialtyRouter`); synthesize professional physician consultation notes and calm patient uncertainty explanations; enforce Rule H1 non-diagnostic guardrails; coordinate patient redactions and require explicit patient approval tokens prior to generating outreach materials or exporting vector PDFs.
- **Inputs:** Active `ClinicalConsent`, structured `summary_payload`, `SpecialtyRoutingDecision`, patient approval flag, and `approval_token`.
- **Outputs:** Validated `ClinicalSummary` entity with canonical SHA-256 digest, physician notes, patient rationale, and patient-approved `outreach_draft`.
- **Tools & Permissions:** Read access to `clinical_consents`, `timeline`, `findings`, `baselines`; write access to `clinical_summaries` and `audit_logs`.
- **Decision Boundaries:** Specialty suggestions are evaluated deterministically (zero LLM routing). External outreach generation is strictly gated until the patient explicitly reviews and authorizes the brief.
- **Failure Behavior:** Emits safe fallback telemetry template citing raw mathematical deviations if synthesis fails or safety policies are breached.
- **What It Must NEVER Do:** Declare a medical diagnosis; autonomously contact clinics or send data without verified patient consent and explicit sign-off; allow export of unapproved or revoked summaries.


---

### 10. Appointment Agent (LangGraph Human-in-the-Loop Graph)
- **Mission:** Prepare draft appointment inquiry messages (WhatsApp, email, or telephone script) for the user to dispatch personally.
- **Engine Type:** **LangGraph Graph with `interrupt()` Checkpoint**.
- **Responsibilities:** Generate pre-filled message text containing patient availability preferences and high-level concern summary; pause execution for explicit user confirmation.
- **Inputs:** Selected provider details, user contact preferences, user availability windows.
- **Outputs:** Draft communication payload for user copy-paste or deep-linked client intent.
- **Tools & Permissions:** None (internal text generation).
- **Decision Boundaries:** Autonomous booking is strictly prohibited (ADR-003). Consequential external actions require resumption with user approval token.
- **Failure Behavior:** N/A.
- **What It Must NEVER Do:** Call hospital booking APIs, execute web scraping against clinic portals, or confirm reservations on the user's behalf.

---

### 11. User Preference Agent (Deterministic Profile Manager)
- **Mission:** Manage and enforce user configuration for notification thresholds, quiet hours, and data sharing preferences.
- **Engine Type:** **Deterministic Logic**.
- **Responsibilities:** Store user quiet hours, channel preferences, and trusted contact details; gate alert routing accordingly.
- **Inputs:** User profile edits from Android UI.
- **Outputs:** Updated `user_preferences` configuration.
- **Tools & Permissions:** Read/write access to user preference models.
- **Decision Boundaries:** Urgent alerts (Level 4) override quiet hours; non-urgent alerts are held until the quiet window concludes.
- **Failure Behavior:** Defaults to safe system defaults (quiet hours 22:00–07:00, push notifications enabled).
- **What It Must NEVER Do:** Expose user preferences to unauthorized third parties.

---

### 12. Safety & Policy Agent (Rule Gate + Hybrid Guardrail)
- **Mission:** Enforce strict clinical safety boundaries and prevent harmful or diagnostic statements from reaching the user.
- **Engine Type:** **Hybrid** (Deterministic regex/lexicon validator + secondary LLM guardrail).
- **Responsibilities:** Screen all agent-generated explanations and messages for prohibited diagnostic phrases (e.g., "You have arrhythmia", "suffering from heart attack") and ensure mandatory emergency disclaimers are present on Level 4 Urgent alerts.
- **Inputs:** Proposed agent output text, finding severity tier.
- **Outputs:** Approved payload or rejected payload with policy violation flags.
- **Tools & Permissions:** Intercepts outgoing messages before `Notification Agent` dispatch.
- **Decision Boundaries:** Rejects any explanation containing blacklisted diagnostic assertions or lacking emergency disclaimers on `urgent` findings.
- **Failure Behavior:** Blocks message transmission; falls back to static safe template: "A significant deviation in your heart rate was recorded. Please consult your physician or seek emergency medical care."
- **What It Must NEVER Do:** Allow a bypass of safety rules under any prompt override or context trick.

---

## Agent System Pilot Verification & Chaos Resilience (Phase 8)

During Phase 8, all 12 autonomous and deterministic agents were subjected to real-world degradation, burst concurrency, and 12 chaos failure injection drills (`test_pilot_12_failure_drills.py`, `test_pilot_adversarial_security.py`):

| Agent | Engine Type | Phase 8 Chaos & Resilience Test Evidence | Authority Boundary Invariant |
| :--- | :--- | :--- | :--- |
| **1. Data Intelligence Agent** | Deterministic Worker | Ingested 500-batch bursts (2,500 records) at 13.26 req/s; rejected out-of-bounds metrics (HR=350 bpm) with RFC 7807 problem details. | Zero synthetic data generation; immutable historical provenance. |
| **2. Data Quality Agent** | Deterministic Worker | Verified detection of wearable off-wrist detachment (>30m zero HR & steps); tagged sparse gaps as `gap` or `missing` without interpolation. | Never marks missing data as healthy baseline. |
| **3. Baseline Intelligence Agent** | Deterministic Worker | Recomputed baselines across rolling windows; preserved previous snapshot on simulated failure. | Baseline parameters never altered by subjective LLM feedback. |
| **4. Health Intelligence Agent** | LangGraph Reasoning | Fallback to deterministic mathematical template verified during total LLM API outage (Drill 2). | Zero diagnostic claims (Rule H1); strictly bounded to telemetry. |
| **5. Anomaly Evaluation Agent** | Hybrid (NumPy + LangGraph) | Verified mathematical z-score gating; suppressed resting tachycardia when accelerometer steps indicated exercise exertion. | Never downgrades urgent findings without deterministic proof. |
| **6. Notification Agent** | LangGraph Router Graph | Verified 12h dedup, escalation bypass (Level 2 $\to$ Level 4), quiet hours evaluation, FCM dead-letter routing, and WebSocket catch-up replay. | Cannot alter severity; Level 4 permanently overrides quiet hours. |
| **7. Daily Report Agent** | LangGraph Synthesis | Fallback to deterministic metric tables and stoic quotes verified during LLM outages; multi-stream synthesis tested. | Never includes hallucinations or unmeasured speculations. |
| **8. Research Agent** | LangGraph + Tools | Directory lookup simulated; blocked autonomous external API booking. | Zero fabricated facilities; requires explicit user initiation. |
| **9. Care Navigation Agent** | LangGraph Coordinator | Gated Doctor Visit Summary compilation behind active consent and ActionGate HMAC approval tokens; verified SHA-256 seal. | External outreach strictly blocked until explicit patient sign-off. |
| **10. Appointment Agent** | LangGraph HITL | Verified draft message generation for WhatsApp/email; confirmed zero autonomous booking (ADR-003). | Never contacts clinic APIs or scrapers autonomously. |
| **11. User Preference Agent** | Deterministic Logic | Verified tenant-isolated preference persistence and quiet-hours timezone shifts. | Default safe settings preserved on missing configs. |
| **12. Safety & Policy Agent** | Rule Gate + Guardrail | Adversarial penetration testing: intercepted blacklisted diagnostic phrases; enforced emergency disclaimers on Level 4 alerts. | Zero bypass under prompt override, roleplay, or context tricks. |

---

## Agent System Real-World Pilot Launch Operations (Phase 9)

In Phase 9, all 12 system agents were bound to formal SRE operational runbooks (`PILOT_DEPLOYMENT_CHECKLIST.md`, `INCIDENT_RESPONSE_RUNBOOK.md`) and participant safety protocols (`PILOT_SAFETY_PROTOCOL.md`), validated across 10 operational integration tests (`test_phase9_pilot_operations.py`):

1. **Ingestion & Data Quality Agents (Agents 1 & 2):** Verified under multi-metric batch ingestion (9 biometrics) and hypertable chunk persistence (`test_p9_01`), future timestamp quarantine (`test_p9_04`), and sensor detachment tracking without synthetic imputation (`test_p9_05`).
2. **Baseline & Anomaly Agents (Agents 3 & 5):** Verified under clock skew jitter resilience (`test_p9_04`) and 22-hour delayed offline sync recovery (`test_p9_03`).
3. **Notification & Safety Agents (Agents 6 & 12):** Verified under quiet-hours evaluation and unconditional Level 4 emergency bypass (`test_p9_06`), accompanied by mandatory calm emergency disclaimers.
4. **Care Navigation & Appointment Agents (Agents 9 & 10):** Verified under full pipeline traversal to ReportLab vector PDF (`test_p9_02`), ActionGate HMAC approval token security and freshness validation (`test_p9_08`), and immediate DPDP consent revocation hard-stop (`test_p9_09`).
5. **System Governance (Agents 4, 7, 8, 11):** Bound to container liveness (`/health`) and readiness (`/ready`) probes (`test_p9_10`) and zero-information-disclosure multi-tenant isolation returning HTTP 404 (`test_p9_07`).


