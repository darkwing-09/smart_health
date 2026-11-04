# Phase 2 — Progress tracker

Last updated: 4 Nov 2026 · branch `feat/fitagent`

`[x]` = built, unit/screenshot-tested and committed · `[ ]` = not built yet.
Built does **not** mean phone-verified: every item is confirmed on a real device through [TESTING.md](../TESTING.md).

**Summary:** 31 of 32 features built. Not built: **W4** live phone ⇄ watch handoff (finished watch workouts already sync).
The phone test can start now (see [§4](#4-when-to-test)). Final APKs: [§6](#6-final-apks).

---

## 1. Features (32)

### R — Data reliability
- [x] **R1** Devices & data health: per-source last data and watch model, sync timeline, records per day, freshness dot on Today, Sync now
- [x] **R2** Compatibility check: which data types each watch app actually shares with Health Connect
- [x] **R3** Live heart rate from Bluetooth sensors (Heart Rate Profile) in the workout player; avg/max/zone time saved

### W — Wear OS app (for Galaxy Watch / Pixel Watch; Noise/boAt can't run apps)
- [x] **W1** Watch workout app: Health Services ExerciseClient, live HR/zone/kcal/distance, Tabata/interval HIIT with wrist haptics, avg/max/zone time; finished workouts queued to the phone as durable Data Layer items (deleted by the phone = acknowledged; saved idempotently by uid)
- [x] **W2** Tile: readiness, step-progress ring, Start chip; refreshed when the phone pushes a new summary
- [x] **W3** Complication: readiness as RANGED_VALUE or SHORT_TEXT
- [ ] **W4** Live handoff (a workout started on one device mirrored and controlled on the other). *Built instead: one-way, acknowledged sync of finished watch workouts + phone → watch summary*
- [x] **W5** Study-break nudges, phone notification version (the wrist-tap version needs W1)

### D — Insights from watch data
- [x] **D1** Readiness v2 (HRV, sleep stages, nightly signals, safety caps); identical in Kotlin and Python (44 shared fixtures)
- [x] **D2** Sleep coach: score, hypnogram, stage donut and bars, consistency, SpO₂, skin temperature, bedtime reminder
- [x] **D3** Heart-rate zones: intraday zones, WHO active minutes, live zone in the player
- [x] **D4** Stress estimate from heart rate while still (movement excluded)
- [x] **D5** VO₂ max trend (shown only when the watch reports it)
- [x] **D6** Auto-detected workouts: imported automatically; removing one hides it, so it isn't re-imported
- [x] **D7** GPS routes for walk/run/ride: foreground service, pace-coloured trace, km splits

### S — Student-specific
- [x] **S1** Exam mode: plans fit free slots, exam days easy, ≤ 30 min in exam weeks, sleep protected
- [x] **S2** Timetable editor + `.ics` import (class names stay on the phone)
- [x] **S3** Dorm workout library: 40 exercises with animated demos, 12 guided routines, quiet filter
- [x] **S4** Food log: photo estimate (needs an AI key), 42 built-in Indian foods, protein goal, eating-disorder guard
- [x] **S5** Mood check-in, stress & mood screen, guided breathing; helplines after 5 low days

### G — Social & motivation
- [x] **G1** Kudos (👏🔥💪) on challenge leaderboards
- [x] **G2** Invite links `/j/CODE` with a web fallback page *(App Link auto-verification needs a public domain + release key: §3)*
- [x] **G3** Shareable weekly recap image (sleep hidden unless enabled)
- [x] **G4** Campus leaderboards (hostel/college/batch, per-capita, groups < 5 hidden, opt-out)
- [x] **G5** Rewards points (sensor-backed only) and partner perks with redemption codes
- [x] **G6** Camera rep counter: on-device ML Kit pose detection (CameraX), squats/push-ups/lunges/jacks, time-smoothed angle state machine, form tips, spoken count; frames never stored *(accuracy on real recordings to be checked in TESTING C9)*

### X — Experience & reach
- [x] **X1** Home-screen widget (small/medium)
- [x] **X2** Voice cues in the player and routines (mute toggle remembered)
- [x] **X3** Hindi: coach replies, Hindi safety routing, translated tabs/greetings/notifications *(most other UI text is still English: §2)*
- [x] **X4** Smart notifications: ≤ 3/day, quiet in class/at night, only reachable goals

### B — For the institution
- [x] **B1** College admin dashboard at `/admin` (aggregates only, groups ≥ 10)
- [x] **B2** Official campus challenges + perk management

---

## 2. Engineering work still open

- [x] Extend CI: `ruff check .`, shared-fixture drift check, Phase 2 smoke test against `docker compose` (Postgres)
- [ ] Room auto-migration instrumented test on an emulator (migrations are compile-time checked against the exported schemas today)
- [x] Update the root `fitagent/README.md` for Phase 2 (features, screenshots, new endpoints, `/admin`, `make_admin.py`)
- [ ] Move the remaining hard-coded UI text into `strings.xml` so the Hindi UI is complete; add Hindi screenshot tests
- [ ] Release setup: signing key, public HTTPS domain, `ANDROID_CERT_SHA256` for App Links, `fitagent.inviteHost`
- [ ] Live-model checks once API keys are in `fitagent/.env`: `evals.run_evals` against LangSmith; food photo estimates on real meals
- [x] 10 more library exercises (40 total: donkey kick, bird dog, dead bug, flutter kicks, reverse crunch, sumo squat, pike push-up, low lunge, arm circles, jump lunges)
- [x] Wear OS module `:wear` (W1–W3), 3 unit tests mirroring the phone's interval/zone maths, lint clean
- [ ] W4 live handoff; Wear screenshots/instrumented tests (no Wear device or emulator here)
- [x] Release APKs split per CPU architecture (ML Kit's native library): ~25–28 MB each; Play app bundles do this automatically

## 3. Needs you (can't be done from this machine)

- [ ] Install the APK on your phone and run [TESTING.md](../TESTING.md)
- [ ] Check whether **NoiseFit** and **boAt Crest** share data with Health Connect, using the compatibility check (fill in [DEVICE_TEST_MATRIX.md](../DEVICE_TEST_MATRIX.md))
- [ ] API keys in `fitagent/.env`: `ANTHROPIC_API_KEY`, `LANGSMITH_API_KEY` (for the AI coach, food photos and LangSmith)
- [ ] A public domain + release signing key (only for Play Store / App Links)
- [ ] Optional: a Wear OS watch (Galaxy Watch 4+/Pixel Watch) to test the `:wear` app; not needed for Noise/boAt

## 4. When to test

**Now.** Everything a Noise/boAt user touches is built. The open items (W4, extra Hindi strings, release setup) don't
block the phone test, and the test answers the biggest unknown: whether your watches' apps share data at all.

## 5. Verification at this point

| Check | Result |
|---|---|
| Agent tests (`uv run pytest agents`) | 255 passed |
| Backend tests (`uv run pytest` in backend) | 30 passed |
| Offline evals | 10/10 on every coach evaluator; 3/3 plans safe |
| Android unit + screenshot tests | 94 phone (30 screenshots) + 3 watch passed |
| Android lint | 0 errors (phone and watch) |
| Debug APK / release APK | 114 MB (unminified, arm64 + x86_64) / 28 MB (arm64, minified) |
| Postgres stack + smoke test (`docker compose`, 7 migrations) | 19/19 checks passed |
| Ruff | clean |

## 6. Final APKs

Built on 4 Nov 2026 from `feat/fitagent` (paths relative to `fitagent/app/`):

| APK | For | Install |
|---|---|---|
| `build/outputs/apk/debug/FitAgent-debug.apk` | **Phone testing** (arm64 phones + x86_64 emulators), package `com.fitagent.app.debug`, talks to a LAN server | `adb install -r …` or copy to the phone |
| `build/outputs/apk/release/FitAgent-arm64-v8a-release-unsigned.apk` | Modern phones, minified | Must be **signed** before install (needs your keystore, §3) |
| `build/outputs/apk/release/FitAgent-armeabi-v7a-release-unsigned.apk` | Older 32-bit phones | Signed first |
| `wear/build/outputs/apk/debug/wear-debug.apk` | Wear OS watch (pairs with the debug phone app) | `adb -s <watch> install -r …` |
