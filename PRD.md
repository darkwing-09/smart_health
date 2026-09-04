# PRD.md — Product Requirements Document

## 1. Product Vision & Philosophy

Personal Health OS is a personal health operating system that turns continuous, multi-resolution biometric data from smartwatches and companion Android devices into an actionable, longitudinal health timeline.

The core philosophy follows:
**OBSERVE → UNDERSTAND → DETECT → EXPLAIN → RECOMMEND → ASK PERMISSION → ACT → VERIFY → RECORD OUTCOME**

### The Four Layers of Truth
The system strictly separates and never blurs the four layers of truth:
1. **Source Data:** What the device actually reported (device timestamps, raw sensor values, battery/wear telemetry, firmware version).
2. **Deterministic Analysis:** What our software calculated (rolling mean, standard deviation, circadian hourly distribution, z-scores, CUSUM change points, data quality flags).
3. **AI Interpretation:** What an agent inferred from available evidence (grounded plain-language 7-part explanation, contextual trends, uncertainties, non-diagnostic guidance).
4. **User / Action State:** What the user authorized, what choices were made, and what the system actually executed (user acknowledgment, doctor visit summaries, dispatched notifications).

---

## 2. Problem Statement

Consumer wearables collect vast amounts of biometric data that remain functionally useless to everyday users. Current platforms suffer from two polar failure modes:
1. **Isolated Dashboards:** Showing raw numbers, daily step counts, and isolated graphs without longitudinal context or personalized interpretation, forcing anxious users to guess what is happening.
2. **Generic Population Alarms:** Firing alarms based on crude population averages (e.g., "heart rate > 100 bpm is high"), resulting in high false-alarm rates, alert fatigue, and anxiety for athletes or individuals with idiosyncratic normal resting baselines.

Personal Health OS solves this by anchoring all analysis in the user's **individual personal baseline** and strictly separating deterministic math from grounded AI explanations.

---

## 3. Target Users & Personas

### Primary Persona: The Dedicated Wearable User ("Arjun", 34)
- **Profile:** Wears a Wear OS or Samsung Galaxy Watch 23 hours a day. Works a high-stress tech job.
- **Pain Point:** Sees occasional heart rate spikes or poor sleep scores on stock apps, but has no way to know if they represent real physiological deviations or normal variations.
- **Needs:** Calm, explainable notifications that tell him *why* something was flagged, how it compares to his past 30 days, and what sensible steps to take.

### Secondary Persona: The Proactive Health Optimizer ("Maya", 48)
- **Profile:** Managing borderline hypertension and sleep apnea risks.
- **Pain Point:** Visits doctors once a year and struggles to recall how her resting heart rate and sleep patterns varied across the previous 6 months.
- **Needs:** A structured, printable Doctor Visit Summary and daily PDF reports synthesizing her longitudinal trends without any AI hallucinations.

---

## 4. Notification & Alert Hierarchy (Anti-Fatigue Framework)

Personal Health OS implements a 5-level notification hierarchy designed to eliminate alarm fatigue:

| Level | Name | Trigger Condition | Delivery Channel | User Experience & Fatigue Control |
| :---: | :--- | :--- | :--- | :--- |
| **0** | **INFORMATION** | Nominal data, within expected variance ($Z < 2.0$). | App only | Silently appended to timeline; zero badges or interruptions. |
| **1** | **INSIGHT** | Infrequent statistical shift ($2.0 \le Z < 2.8$); minor trend. | Daily summary | Batched exclusively into the evening Daily Health Report PDF. |
| **2** | **ATTENTION** | Trend shift sustained across multiple hours ($2.8 \le Z < 3.8$). | In-app badge / quiet notification | Single in-app card on next app launch; respects quiet hours. |
| **3** | **IMPORTANT** | Statistically significant deviation ($3.8 \le Z < 5.0$) corroborated across signals. | Push notification (FCM) | Immediate alert with mandatory 7-part explanation; deduped for 12 hours. |
| **4** | **URGENT** | Severe acute breach ($Z \ge 5.0$ or hard physiological bounds: resting HR > 150 or < 38 bpm). | Multi-channel (Push + WhatsApp V1) | Overrides quiet hours; prominent persistent banner; direct emergency disclaimer. |

---

## 5. Mandatory 7-Part Explainability Structure

Every finding classified as Level 2 (Attention) or higher must contain all seven elements:
1. **What Changed:** 1-sentence summary of the physiological shift.
2. **Which Measurements Caused the Flag:** Exact metric values, units, and timestamps that triggered the threshold.
3. **Difference from Personal Baseline:** Explicit comparison against the rolling 30-day circadian mean and variance.
4. **Relevant Historical Context:** Occurrence frequency of similar patterns over the past 30–90 days.
5. **Confidence & Data Quality:** Optical sensor quality, coverage completeness, and motion artifact status.
6. **Why It Matters:** Non-diagnostic physiological context (e.g., sympathetic nervous activation, recovery deficit).
7. **What the User Can Consider Doing Next:** Practical, non-diagnostic next steps (rest, hydration, sensor fit verification, or consulting a physician if symptoms arise).

---

## 6. Functional Requirements

- **FR1 (Ingestion):** Ingest heart rate, resting heart rate, steps, distance, sleep stages, active minutes, and SpO₂ from Android Health Connect.
- **FR2 (Synchronization):** Offline-first staging in Android Room DB with resilient background sync via WorkManager.
- **FR3 (Timeline Persistence):** Append-only storage in PostgreSQL with TimescaleDB hypertables, preserving full provenance metadata.
- **FR4 (Personal Baseline):** Calculate rolling 30-day mean, standard deviation, and circadian hourly buckets; mark baseline established after 14 nominal days.
- **FR5 (Deterministic Anomaly Gating):** Classify deviations using z-score and CUSUM; hard physiological gates trigger Level 4 Urgent automatically.
- **FR6 (LangGraph Health Intelligence):** Run stateful LangGraph workflows to synthesize explanations, verify safety guardrails, and manage finding lifecycles.
- **FR7 (Daily Report Compilation):** Compile daily metrics, open findings, and an LLM-synthesized reflective quote into a vector PDF via ReportLab.
- **FR8 (Care Navigation):** Research verified healthcare facilities and specialists within user radius; format a structured Doctor Visit Summary.
- **FR9 (Zero Autonomous Booking):** Consequential actions (booking, sharing data) strictly require human-in-the-loop authorization per ADR-003.

---

## 7. Scope Partitioning

### MVP Scope (Strictly Prioritized)
- Android companion app with Health Connect ingestion (Wear OS & modern Samsung Galaxy Watch).
- Room DB offline queue + WorkManager sync worker.
- FastAPI backend with PostgreSQL 16 + TimescaleDB hypertable for measurements.
- Deterministic Baseline Engine (rolling 30-day window, circadian bins).
- Anomaly Detection Service (Levels 0–4).
- LangGraph Health Intelligence Graph with LangSmith tracing.
- Firebase Cloud Messaging (FCM) push notifications for Level 3 and Level 4 alerts.
- ReportLab daily PDF generation with dynamic stoic closing quote.

### Phase 5 / V1 Scope (Clinical Readiness & Care Navigation)
- Granular patient consent lifecycle (`ClinicalConsent`) with purpose specification and revocability (DPDP Act 2023).
- Deterministic specialty-routing rules (`SpecialtyRouter`) evaluating physiological deviations without diagnostic claims.
- 5-stage Doctor Visit Summary lifecycle: `DRAFT -> REVIEW -> REDACT -> APPROVE -> EXPORT`.
- Patient-controlled redaction masking for sensitive metrics and individual findings.
- ReportLab vector PDF compilation of clinical briefs with embedded SHA-256 integrity seal.
- Upgraded Care Navigation Graph with clinician consultation notes, patient uncertainty explanation, and human approval gating.
- Verified hospital and clinic directory search with explicit user authorization.

### Phase 7 Scope (Alert Hierarchy, Real-Time Streaming & Notification Delivery Engine - VERIFIED)
- Deterministic 5-tier notification policy engine (Levels 0–4) strictly derived from Finding layer.
- Timezone-aware quiet hours evaluation with automatic morning release cadence.
- Level 4 Urgent emergency override permanently bypassing quiet hours.
- Atomic 12-hour deduplication preventing alert fatigue while allowing higher-severity escalation bypass.
- Authoritative PostgreSQL-persisted notification state machine (7 operational states, retries, dead-letter, user actions).
- Google Firebase Cloud Messaging (FCM) HTTP v1 dispatcher with dry-run mode and high/normal priority channels.
- Authenticated per-user WebSocket streaming (`/v1/ws/stream`) with heartbeat and missed-event catch-up protocol.
- REST endpoints for notification feed pagination, acknowledgement, dismissal, user preferences, and FCM token registration.
- Android Jetpack Compose notification feed with channel configurations and finding detail deep-linking.
- Notification fatigue telemetry service tracking distribution, latencies, suppressions, and delivery rates.


### V2/V3 Scope (Deferred)
- Garmin Health API integration (pending B2B partnership approval).
- Smart scale, blood pressure monitor, and continuous glucose monitor (CGM) ingestion.
- Multi-source correlation (nutrition, supplements, medication adherence).
- Best-effort reverse-engineered adapters for Tier 4 Indian budget wearables (Noise, boAt, Fire-Boltt).
- Automated appointment booking (strictly deferred pending dedicated legal/compliance review).

---

## 8. Non-Goals

- **No Medical Diagnosis:** Personal Health OS will never provide clinical diagnoses or prescribe medication.
- **No Autonomous Booking in MVP/V1:** The system prepares visit summaries; the patient retains complete control over contacting providers.
- **No Unofficial Scrapers in MVP:** No fragile reverse-engineering of vendor proprietary cloud portals.
- **No Black-Box Clinical Advice:** No ungrounded LLM speculations on diet or illness without recorded data.
