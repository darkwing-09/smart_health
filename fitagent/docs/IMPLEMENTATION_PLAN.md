# FitAgent — Implementation Plan

**Problem statement:** *Student Innovation — ideas that can boost fitness activities and assist in keeping fit.*

**Product:** FitAgent is an AI fitness coach for students. It turns the data a phone or watch already collects into one daily decision ("push, steady, or recover?"), makes exercise a habit through streaks, achievements and campus/friend challenges, and gives every student a personal coach that knows their numbers.

This folder is a ground-up rebuild. The earlier `android/` and `backend/` folders at the repo root are left untouched for reference.

---

## 1. Audit of the previous project (why rebuild)

| Area | Problem found | Consequence |
|---|---|---|
| Fit to problem statement | Built as a clinical anomaly monitor (tachycardia alerts, doctor PDFs) | Does not "boost fitness activity" at all |
| App ↔ backend | App sends `Bearer dev_token`; backend validates real JWTs | Every API call returns 401 — sync, insights and reports never worked |
| Networking | Base URL hard-coded to a LAN IP `10.181.181.106` | Fails on every other network |
| Fake data | Hard-coded "158 bpm nocturnal tachycardia" alerts; PDF with invented clinical findings | Misleading and unsafe |
| Step counts | Summed raw Health Connect records | Phone + watch double-count steps |
| Calories | Read *total* calories (incl. BMR) and labelled them "active" | Numbers 3–5× too high |
| Health Connect on Android ≤ 13 | No `<queries>` entry, no permission-rationale activity | Permission dialog silently never appears |
| "Today" | Start-of-day computed once in `onCreate` | Stale after midnight |
| No accounts | No register/login flow in the app | No way to obtain a valid token |

## 2. Design research (frontend direction)

Sources reviewed: WHOOP design breakdown, Uxcel readiness-dashboard showcase, NTC/Strava design critiques, fitness gamification guides (see README "Design references").

Principles adopted:

1. **One number first, details on demand.** A single 0–100 *Readiness* score with green/yellow/red bands (WHOOP pattern) moves users from passive tracking to a decision. Progressive disclosure: tap for the reasons.
2. **Coach, not spreadsheet.** Every screen ends in an action ("Start 20-min HIIT", "11-min walk closes your ring").
3. **Rings for daily goals** (Apple Fitness pattern): steps, active kcal, workout minutes.
4. **Dark, high-contrast, data-dense but calm** — near-black surfaces, one energetic accent (lime), semantic colours per metric.
5. **Workout player** (NTC pattern): huge timer, full-screen colour change between WORK and REST, haptics on phase change, screen kept on.
6. **Healthy gamification for students:** streaks *with one rest-day freeze per week* (avoids the anxiety/burn-out that streak pressure causes), achievements with visible progress, **team-friendly challenges** with an "active days" metric so beginners can compete on consistency rather than raw performance.

## 3. Architecture

```
fitagent/
├── app/        Android · Kotlin · Jetpack Compose · Room · WorkManager · Health Connect · Retrofit
├── backend/    Python · FastAPI · SQLAlchemy 2 (async) · Alembic · JWT · Postgres/SQLite
├── agents/     Python · LangGraph · LangChain · LangSmith (tracing + evals)
├── docs/       Plan, architecture, API
└── docker-compose.yml
```

```
 Health Connect ──► Android app (offline-first: Room is the source of truth on device)
                        │  rings · readiness · streaks · workout player · reminders  (all work offline)
                        │  sync (WorkManager, JWT)
                        ▼
                   FastAPI backend ──► Postgres
                        │  auth · profile · activity/workout sync · challenges & leaderboards
                        │  coach endpoints
                        ▼
                   fitagent_agents (LangGraph)
                        ├── Coach chat graph:  guard → agent ⇄ tools → respond
                        └── Weekly planner graph: context → assess → draft → validate ⟲ revise → finalize
                        LangSmith: traces every run; eval suite with custom evaluators
```

**Key decisions**

- **Offline-first app.** Everything that matters daily (rings, readiness, coach suggestion, workout tracking, streaks, reminders) is computed on the phone. The backend adds accounts, cloud sync, challenges and the LLM coach. The app never breaks because a server is down.
- **Health Connect aggregates**, not raw records — the platform de-duplicates overlapping sources.
- **Deterministic core, LLM on top.** Readiness, training load (acute:chronic workload ratio), and plan safety rules are pure functions. The LLM explains and personalises; a validator enforces the rules; if no LLM key is configured, deterministic fallbacks keep every endpoint working.
- **Safety guard.** Messages mentioning chest pain, fainting, eating-disorder signals, etc. bypass the LLM and return a fixed safe response pointing to professional help.
- **Provider-agnostic LLM** via `init_chat_model` (`FITAGENT_MODEL=anthropic:claude-sonnet-5-5` by default; OpenAI/Gemini supported).

## 4. Domain logic (shared definitions, implemented in Kotlin and Python)

- **Readiness (0–100)** = weighted mean of available components: sleep (40%) `min(sleep/8h,1)·100`; resting-HR vs personal 14-day baseline (35%) `clamp(85 − 10·Δbpm)`; training load (25%) from ACWR (≤1.3 → 90, ≤1.5 → 60, else 30; no history → 80). Bands: ≥67 Push · 34–66 Steady · <34 Recover.
- **Goal day:** steps ≥ step goal **or** workout minutes ≥ workout goal.
- **Streak:** consecutive goal days; one missed day per Mon–Sun week is absorbed by a *freeze*; an unfinished today never breaks the streak.
- **Calories:** MET × kg × hours for workouts; 0.04 kcal/step × kg/70 for walking; device value used when larger.

## 5. Work breakdown & order

| # | Milestone | Done when |
|---|---|---|
| 1 | Plan + repo scaffold (uv workspace, gradle project) | This doc committed |
| 2 | `agents`: metrics, safety, schemas, tools, coach graph, planner graph, fallbacks, LangSmith tracing + evals | `pytest` green with fake LLMs |
| 3 | `backend`: models, Alembic migration, auth (access+refresh JWT, argon2), profile, sync, activity, challenges + leaderboard, coach endpoints, rate limiting, account deletion | `pytest` green, OpenAPI loads |
| 4 | `app`: theme, data layer (Room, Health Connect, prefs, API client w/ token refresh), domain logic + unit tests, screens: Onboarding, Today, Train + Workout player, Coach, Squad, You, Sign-in, reminders, sync worker | `./gradlew assembleDebug testDebugUnitTest` green |
| 5 | Infra: Dockerfile, docker-compose (Postgres + API), `.env.example`, CI workflow | compose config validates |
| 6 | Docs: README (setup, architecture, API, design references), end-to-end smoke test against running backend | API smoke script passes |

## 6. Out of scope (explicitly)

- iOS client, GPS route maps, BLE direct-to-watch (Health Connect covers Noise/Wear OS/Samsung/Fitbit via their apps).
- Medical diagnosis of any kind — FitAgent is a wellness coach, not a medical device.
