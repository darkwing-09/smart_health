# Personal Health OS

> A privacy-first, longitudinal personal health operating system that continuously observes biometric streams from smartwatches, establishes personal baselines, detects meaningful deviations using deterministic statistics, explains them via grounded AI agents, and assists with care navigation under strict user authorization.

---

## What is Personal Health OS?

Personal Health OS transforms commodity wearable biometric data into actionable, clinical-grade personal health intelligence. Today's consumer health platforms either show isolated raw charts (leaving interpretation to anxious users) or trigger noisy alerts based on crude population averages (e.g., "heart rate > 100 bpm is high"). 

**Personal Health OS operates on a fundamentally different thesis:**
1. **Personal Baseline over Universal Thresholds:** What is normal for a trained marathon runner is alarming for a sedentary desk worker. The system learns *your* individual circadian and rolling baseline (mean, variance, seasonality).
2. **Deterministic Gating with Grounded AI Interpretation:** Deterministic mathematics (EWMA, rolling z-scores, CUSUM) detect anomalies. AI agents never fabricate diagnoses; they explain *why* a deviation occurred, what data caused it, how confident the system is, and what practical next steps to consider.
3. **Continuous Data Provenance & Quality:** Every single biometric reading maintains immutable provenance (sensor brand, firmware, sync timestamp, confidence flags). Missing data is never treated as normal.
4. **Human-in-the-Loop Care Navigation:** If a metric shows a concerning trend, the system researches appropriate medical specialties and providers. It **never** books or shares data without explicit, single-action user authorization.

---

## System Architecture Overview

```
 ┌────────────────────────────────────────────────────────┐
 │                      DATA SOURCES                      │
 │  Wear OS (Health Connect) │ Samsung │ Fitbit (V1)      │
 └────────────────────────────┬───────────────────────────┘
                              │ On-Device Sync (Android OS)
                              ▼
 ┌────────────────────────────────────────────────────────┐
 │                      ANDROID APP                       │
 │  - Health Connect Adapter     - WorkManager Sync Queue │
 │  - Local Room DB Buffer       - FCM Notification UI    │
 │  - Biometric Auth Gate        - Daily PDF Viewer       │
 └────────────────────────────┬───────────────────────────┘
                              │ TLS 1.3 / JWT Bearer
                              ▼
 ┌────────────────────────────────────────────────────────┐
 │                    BACKEND PLATFORM                    │
 │  ┌──────────────────────────────────────────────────┐  │
 │  │ FastAPI Ingestion & Sync Gateway                 │  │
 │  └─────────────────────────┬────────────────────────┘  │
 │                            ▼                           │
 │  ┌──────────────────────────────────────────────────┐  │
 │  │ Normalized Longitudinal Timeline (Postgres/TSDB) │  │
 │  └──────┬───────────────────────────────────────────┘  │
 │         │                                              │
 │         ▼                                              │
 │  ┌──────────────────────────────────────────────────┐  │
 │  │ Deterministic Analytics Engine                   │  │
 │  │ - Rolling Baseline Service (EWMA, 30-Day Window) │  │
 │  │ - Anomaly Detector (Z-Score, CUSUM, Hard Gates)  │  │
 │  └──────┬───────────────────────────────────────────┘  │
 │         │ Candidate Findings                           │
 │         ▼                                              │
 │  ┌──────────────────────────────────────────────────┐  │
 │  │ Agent Orchestrator & State Machine               │  │
 │  │ - Finding State Machine (ADR-005 Anti-Fatigue)   │  │
 │  │ - Health Intelligence Agent (Explanation)        │  │
 │  │ - Daily Report Agent (PDF Synthesis + Quote)     │  │
 │  │ - Research Agent (Care Discovery, Read-Only)     │  │
 │  │ - Policy/Safety Guardrail Agent                  │  │
 │  └──────┬───────────────────────────────────────────┘  │
 │         │ Alerts & Artifacts                           │
 │         ▼                                              │
 │  ┌──────────────────────────────────────────────────┐  │
 │  │ Dispatchers: FCM Push (MVP) │ WhatsApp API (V1)  │  │
 │  └──────────────────────────────────────────────────┘  │
 └────────────────────────────────────────────────────────┘
```

---

## Core Capabilities

- **Multi-Resolution Event Processing:**
  - **Event-Driven:** Near-real-time evaluation of acute deterministic breaches (e.g., sustained resting tachycardia).
  - **Hourly Rollup:** Evaluation of short-term physiological shifts and micro-trends.
  - **Daily Deep Analysis:** Full rolling baseline recalculation, circadian rhythm adjustment, and daily summary compilation.
- **Five-Tier Severity Hierarchy:**
  - `normal_variation`: Within expected personal baseline variance.
  - `unusual`: Statistically infrequent, low clinical risk; batched in daily report.
  - `worth_monitoring`: Multi-hour or multi-day trend shift; single in-app badge/notification.
  - `potentially_concerning`: Significant personal deviation corroborated across metrics; immediate push notification.
  - `urgent`: Severe acute breach meeting hard safety rules; immediate multi-channel alert + directive to seek emergency care.
- **Mandatory 7-Part Explainability Structure:**
  Every user alert includes:
  1. *What changed*
  2. *Which measurements caused the flag*
  3. *How it differs from your personal baseline*
  4. *Relevant historical context*
  5. *Confidence & data quality level*
  6. *Why it matters physiologically*
  7. *Practical, non-diagnostic next steps to consider*
- **Automated Daily Health Report:**
  Vector PDF generated every evening summarizing daily exertion, sleep architecture, vitals, open/resolved anomalies, and a contextually synthesized motivational/reflective quote.
- **Care Navigation:**
  On-demand or alert-driven research into local specialists and clinics. Formats a structured clinical summary the user can share, without automated booking in MVP (strict safety isolation per ADR-003).

---

## Repository Documentation Map

This repository operates under a strict 21-document engineering operating system. Every file serves a specific, non-overlapping architectural function:

| Document | Purpose & Scope |
| :--- | :--- |
| [CLAUDE.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/CLAUDE.md) | Master operating manual, non-negotiable engineering principles, and definition of done. |
| [README.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/README.md) | High-level system overview, value proposition, quickstart, and directory navigation. |
| [PRD.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/PRD.md) | Product Requirements Document, user stories, personas, scope tiers (MVP/V1/V2), and non-goals. |
| [Architecture.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/Architecture.md) | Complete technical architecture, subsystem boundaries, data flow diagrams, and design rationales. |
| [Plan.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/Plan.md) | Master phased implementation roadmap with strict dependencies and exit criteria. |
| [Implementation.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/Implementation.md) | Technical implementation playbook: Android Health Connect, FastAPI backend, and pipelines. |
| [Rules.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/Rules.md) | Enforceable engineering constraints: coding standards, safety rules, and PR policies. |
| [SKILL.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/SKILL.md) | Reusable domain skills and operational capabilities for AI assistants and engineers. |
| [AGENTS.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/AGENTS.md) | Complete specification of all system agents: missions, inputs, outputs, tools, and hard boundaries. |
| [PROMPTS.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/PROMPTS.md) | Canonical, versioned prompt library with strict JSON schemas and evaluation benchmarks. |
| [API.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/API.md) | Complete OpenAPI/REST specification: auth, sync, measurements, findings, and care navigation. |
| [DataModel.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/DataModel.md) | Conceptual and relational schema definitions, entity relationships, and immutability rules. |
| [Config.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/Config.md) | Environment configuration reference, feature flags, baseline tuning, and threshold settings. |
| [.env.example](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/.env.example) | Sanitized environment template with comprehensive variable documentation. |
| [Progress.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/Progress.md) | Live execution tracker: completed tasks, active work streams, blockers, and milestone status. |
| [Changelog.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/Changelog.md) | Chronological log of changes adhering to the Keep a Changelog standard. |
| [Decisions.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/Decisions.md) | Architecture Decision Records (ADRs) capturing rationale, alternatives, and consequences. |
| [Issues.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/Issues.md) | Registry of active bugs, architectural debt, integration blockers, and research questions. |
| [TestPlan.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/TestPlan.md) | Quality assurance strategy: unit tests, statistical test vectors, agent evaluation, and E2E runs. |
| [Deployment.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/Deployment.md) | Infrastructure, Docker configurations, CI/CD pipelines, database migrations, and disaster recovery. |
| [Security.md](file:///home/darkwing/Desktop/SMART_HEALTH%20/files/Security.md) | Health data privacy spec, STRIDE threat model, encryption standards, and DPDP compliance. |

---

## Technology Stack

- **Mobile Client:** Native Android (Kotlin, Jetpack Compose, Health Connect SDK, Room DB, WorkManager, Retrofit).
- **Backend Framework:** Python 3.11+, FastAPI (async), Pydantic v2, SQLAlchemy 2.0 (asyncio).
- **Database & Storage:** PostgreSQL 16 with TimescaleDB extension, Redis (caching & task queues), Local/S3-compatible Object Storage for PDF reports.
- **Analytics & Math:** NumPy, SciPy (rolling statistical baselines, CUSUM, EWMA deviation modeling).
- **Agent Orchestration & LLMs:** LangChain / LiteLLM router (Claude 3.5 Sonnet / GPT-4o) with strict Pydantic structured outputs.
- **Reporting Engine:** ReportLab / WeasyPrint (server-side vector PDF generation).
- **Push & Messaging:** Firebase Cloud Messaging (FCM) for MVP; Meta WhatsApp Business Cloud API for V1.

---

## Development Prerequisites

- **Host OS:** Linux (Ubuntu 22.04+ LTS recommended) or macOS.
- **Python:** Version 3.11 or higher with `uv` or `poetry`.
- **Android Development:** Android Studio Hedgehog (2023.1.1+) or newer, JDK 17, Android SDK Platform 34+.
- **Database:** Docker & Docker Compose to run PostgreSQL 16 + TimescaleDB and Redis.
- **Wearable Test Setup:** Wear OS 3.0+ physical watch or Android Emulator with Health Connect installed.

---

## Quickstart (Local Development)

### 1. Clone & Configure Environment
```bash
git clone https://github.com/your-org/personal-health-os.git
cd personal-health-os
cp files/.env.example .env
# Edit .env with your local test configuration and LLM API keys
```

### 2. Start Supporting Infrastructure
```bash
docker compose up -d db redis
```

### 3. Run Database Migrations
```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
alembic upgrade head
```

### 4. Launch FastAPI Local Server
```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

Interactive API documentation will be available at `http://localhost:8000/docs`.

---

## Security & Clinical Safety Warning

> [!CAUTION]
> **NOT A CERTIFIED MEDICAL DEVICE**
> Personal Health OS is an assistive software tool designed for personal wellness tracking, longitudinal pattern recognition, and informational health navigation. It is **NOT** a diagnostic system, does **NOT** substitute for professional medical advice, diagnosis, or treatment, and should never be used during an acute medical emergency. In the event of a medical crisis, users must immediately contact their regional emergency medical services (e.g., 112 / 911 / 108).
