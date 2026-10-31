# 07 — Build plan

Order is chosen so every milestone ships something usable and later features build on trusted watch data.
Feature IDs refer to [04-features.md](04-features.md). Each milestone ends with: all tests green, screenshots
reviewed, README/docs updated, one commit (or PR) per milestone.

> **Owner decision (2026-10-31): the target watches are Noise and boAt.** They are closed ecosystems (no Wear OS,
> no public Bluetooth protocol), so their data can only arrive through their vendor apps writing to Health
> Connect. Build order is therefore: Health Connect path + compatibility check + phone-sensor fallback first
> (M1), charts and insights next (M0/M3 visual work), and the Wear OS app (M2) **last**, kept for Galaxy/Pixel
> users and testable only on the emulator. To avoid a risky project move, the phone app stays the Gradle root;
> `:shared` (sync protocol) and `:wear` are added as sub-modules when M2 starts.
>
> **Phone-sensor fallback (part of R1):** when no wearable writes steps to Health Connect, the app counts steps
> itself with the phone's `TYPE_STEP_COUNTER` sensor (`ACTIVITY_RECOGNITION` permission), clearly labelled
> "Phone" in Data health, so the rings never sit at zero.

## M0 — Foundations (≈ 1 week)
- Split Gradle project into `phone`, `wear`, `shared` (see 03 §1); move code with history (`git mv`).
- Toolchain: Kotlin 2.x + Compose compiler plugin, AGP 8.7+, SDK 35, version catalogue `gradle/libs.versions.toml`.
- Re-run Phase 1 tests + screenshots after the move — must be identical.
- Chart kit in `shared/ui/charts/` (all 21 components from 01 §4) with Paparazzi gallery screen `ChartGallery`.
- New colour tokens (01 §3) in `Theme.kt`.
- `fixtures/readiness_v2.json` + loader in both Kotlin and Python tests.

**Files:** `settings.gradle.kts`, `gradle/libs.versions.toml`, `phone/build.gradle.kts`, `wear/build.gradle.kts`,
`shared/build.gradle.kts`, `shared/src/main/kotlin/com/fitagent/ui/charts/*.kt` (≈ 21), `ChartGalleryTest.kt`.

## M1 — Reliable watch data (≈ 2 weeks) → R1, R2, D6
- Room v2 entities + migration (03 §3); outbox; sync log; device table.
- Health Connect upgrade, new record types, changes-token sync worker, background + history permissions,
  source priority, data-origin labels (03 §5).
- Backend: migration `0002_watch_metrics`, extended sync schemas, `/me/sync/status`, `/me/devices` (03 §7).
- Data health screen, freshness dot, compatibility check, auto-detected workout confirm.
- Fill device matrix for available watches.

**Files (phone):** `data/health/HealthConnectSource.kt` (rewrite), `data/health/HealthSyncWorker.kt`,
`data/health/SourcePriority.kt`, `data/local/Database.kt` (+ `Migrations.kt`), `data/sync/Outbox.kt`,
`data/sync/CloudSyncWorker.kt`, `ui/screens/DevicesScreen.kt`, `ui/screens/CompatibilityScreen.kt`.
**Files (backend):** `alembic/versions/0002_watch_metrics.py`, `models.py`, `schemas.py`, `routers/me.py`,
`routers/devices.py`, `services/provider.py`, tests.

## M2 — Wear OS app (≈ 3 weeks) → W1, W2, W3, W5, R3 (path A)
- `wear` module: Health Services passive + exercise, watch Room buffer, Data Layer batches + ack (03 §4).
- Phone `WearDataListenerService`, capability advertising, "install on watch" prompt.
- Watch UI: home, start, live workout, summary, breathe, study-break prompt; tile; complications.
- BLE heart-rate source for phone player (path C).

**Files (wear):** `WearApp.kt`, `health/PassiveDataService.kt`, `health/ExerciseService.kt`,
`data/WatchDatabase.kt`, `sync/BatchSender.kt`, `sync/AckReceiver.kt`, `ui/*.kt`, `tile/FitAgentTileService.kt`,
`complication/*.kt`, `res/values/wear.xml`.
**Files (shared):** `sync/Batch.kt`, `sync/WatchRecord.kt`. **Files (phone):** `wear/WearDataListenerService.kt`,
`wear/WatchCapability.kt`, `data/ble/BleHeartRateSource.kt`.

## M3 — Watch insights & charts (≈ 3 weeks) → D1, D2, D3, D4, D5, W4
- Readiness v2 in Kotlin + Python with shared fixtures; zones, WHO minutes, sleep score, stress (05 §1–2).
- New screens: Readiness detail, Sleep, Heart, Workout summary; Today redesign (02).
- Insight headlines (`domain/Insights.kt`); phone⇄watch handoff.
- Agents: new tools, planner context, safety additions, evals.

## M4 — Student features (≈ 3 weeks) → S1, S2, S3, S5, X1, X2
- Timetable editor + ICS import, Exam mode + planner rules, workout library (assets + licences in
  `docs/ASSETS.md`), guided routine player, mood + breathe, Glance widget, voice cues.

## M5 — Social growth (≈ 2 weeks) → G1, G2, G3, X4, D7
- Kudos, App Links invites + web fallback page, recap image, smart notification policy, GPS routes (osmdroid).
- Backend: kudos table/routes, invite landing route.

## M6 — Bigger bets (≈ 4–6 weeks) → S4, G4, G5, G6, X3, B1, B2
- Food log graph + screen, campus groups + leaderboards, rewards, ML Kit rep counter, localisation, admin dashboard + official challenges.

## Dependencies & decisions needed from the owner
| Needed | For | Blocking? |
|---|---|---|
| Which watches students actually own (or can borrow for testing) | Prioritise path A vs B, fill device matrix | M1 exit, M2 priority |
| A physical Wear OS watch (Galaxy Watch 4+ or Pixel Watch) | M2 testing beyond emulator | M2 exit |
| Valid `ANTHROPIC_API_KEY` + `LANGSMITH_API_KEY` | Live evals, food photo | M3/M6 exit |
| Domain name for invite links (`/j/CODE`) | G2 App Links verification | M5 |
| Partner perks / institution pilot contact | G5, B1, B2 | M6 only |

## Totals
32 features · ~76 charts · 3 Android modules · ~16–20 weeks for one developer end-to-end; M0–M3 (the watch core)
≈ 9 weeks.
