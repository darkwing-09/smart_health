# FitAgent — Phase 2 Plan: Smartwatch-first

**Goal:** the smartwatch becomes the primary data source. Every metric the app shows comes from the watch,
transmission from watch → phone → cloud is reliable and verifiable, and the new features use that data to make
the app clearly better than a generic step counter.

---

## 1. Where we are today

| Area | Current state (Phase 1) | Gap |
|---|---|---|
| Watch data | Read *indirectly* through Health Connect daily aggregates (steps, distance, active kcal, resting/avg HR, sleep duration, exercise sessions) | No HRV, SpO2, sleep stages, VO2 max, skin temp, respiratory rate, GPS routes; no live data |
| Freshness | Pulled when the app opens + periodic worker | No background read permission, no incremental (changes) sync, no "last synced" indicator |
| Source visibility | Not shown | User can't tell whether a number came from the watch or the phone |
| Live workouts | Phone timer only | No live heart rate, no HR zones, no watch-side controls |
| Watch app | None | Nothing runs on the wrist |

## 2. How data gets off the watch — three paths

There is no single universal way to read every smartwatch: most brands use closed Bluetooth protocols that only
their own app can talk to. FitAgent therefore supports three paths and picks the best one per device.

```
 ┌──────────────────────────── WATCH ────────────────────────────┐
 │                                                                │
 │  A. Wear OS watch running the FitAgent watch app               │  Galaxy Watch 4+, Pixel Watch,
 │     Health Services ──► watch Room buffer ──► Data Layer API   │  OnePlus/TicWatch (Wear OS 3+)
 │                                                 │              │
 │  B. Any watch with a vendor app                 │              │  Samsung, Fitbit, Garmin, Amazfit,
 │     watch ──BT──► vendor app ──► Health Connect │              │  Xiaomi, Noise*, boAt* (*verify)
 │                                       │         │              │
 │  C. Watch / strap broadcasting BLE HR │         │              │  Garmin, Amazfit, Polar, Coros,
 │     standard Heart Rate Service 0x180D│         │              │  chest straps (live HR only)
 └───────────────────────────────────────┼─────────┼──────────────┘
                                         ▼         ▼
                         ┌───────── PHONE (FitAgent app) ─────────┐
                         │ HealthConnectSource   WearDataReceiver  │◄── C. BleHeartRateSource
                         │        └──────► Room (single truth) ◄───┘   │
                         │              dedupe by record uid           │
                         │              WorkManager sync (idempotent)  │
                         └──────────────────────┬─────────────────────┘
                                                ▼
                                FastAPI /v1/me/sync ──► Postgres
```

| Path | What it gives | Latency | Coverage |
|---|---|---|---|
| **A. Own Wear OS app** | Everything the watch sensors expose: live HR, steps, calories, distance, GPS, floors, plus our own UI on the wrist | Seconds | Wear OS 3+ watches |
| **B. Health Connect** | Whatever the vendor app writes: steps, HR, resting HR, HRV, SpO2, sleep stages, exercise, VO2 max, skin temp | Minutes (depends on vendor app sync) | Most major brands |
| **C. BLE Heart Rate Profile** | Live heart rate during workouts | ~1 s | Watches with "broadcast HR" + all HR straps |

**Brand reality check** — must be verified on real devices before we promise anything:

| Brand | Path | Confidence |
|---|---|---|
| Samsung Galaxy Watch | A + B (Samsung Health → Health Connect) | High |
| Google Pixel Watch / Fitbit | A + B (Fitbit → Health Connect) | High |
| Garmin | B (Garmin Connect → Health Connect) + C | Medium — check which data types sync |
| Amazfit / Zepp, Xiaomi | B + C (Amazfit) | Medium |
| Noise, boAt, Fire-Boltt | B only if their app has Health Connect sync enabled | **Low — test first.** If the vendor app doesn't write to Health Connect, no third-party app can read that watch. |

## 3. Reliable data transmission (the core of Phase 2)

### 3.1 Watch → phone (path A, Wear OS)
1. **Buffer on the watch first.** Every sample/session is written to a Room DB on the watch with a UUID and a
   monotonically increasing sequence number. Nothing is lost if the phone is out of range or off.
2. **Durable transport:** `DataClient` (Data Layer) for batches — it queues and redelivers across disconnects
   and reboots. `MessageClient` only for live, droppable data (current HR while the phone screen is open).
3. **Acknowledgement:** the phone replies with the highest sequence number it has stored; the watch deletes
   only acknowledged records. Unacked batches are retried with exponential backoff.
4. **Idempotent on the phone:** records are upserted by UUID, so a duplicate delivery never double-counts.
5. **Battery-aware:** batch every 15 min (or on workout end / phone reconnect), not per sample.

### 3.2 Vendor watch → phone (path B, Health Connect)
1. Upgrade `androidx.health.connect:connect-client` to the latest stable 1.1.x.
2. **Incremental sync with the Changes API** (`getChangesToken` / `getChanges`): read only what changed since
   the last token instead of re-reading 30 days. Handle token expiry (30 days) with a full re-read.
3. **Background read permission** (`READ_HEALTH_DATA_IN_BACKGROUND`, when the feature is available) so the
   periodic worker can pull data without the app being open.
4. **History permission** (`READ_HEALTH_DATA_HISTORY`) to import more than 30 days on first connect.
5. **Prefer watch over phone:** use `dataOrigin` to label each value's source; when both phone and watch report
   a metric that can't be aggregated (e.g. sleep), take the watch's record. Aggregates keep doing step dedupe.
6. **New record types:** `HeartRateVariabilityRmssd`, `OxygenSaturation`, `RespiratoryRate`, `Vo2Max`,
   `SkinTemperature`, sleep **stages**, `ExerciseRoute`, `FloorsClimbed`, `TotalCaloriesBurned`, `Weight`.

### 3.3 Phone → cloud
Already idempotent (`POST /v1/me/sync` upserts). Additions:
- Sync the new metrics (HRV, SpO2, sleep stages, VO2 max) — schema + Alembic migration.
- Per-record `source` / `device` fields so the coach knows where data came from.
- Sync outbox table on the phone: rows stay until the server confirms, retries survive app kills.

### 3.4 Proving it works
- **"Data health" screen** (You → Devices): each connected device, last sync time, records received today,
  last error, and a "Sync now" button. A stale-data banner on Today if the watch hasn't synced in > 6 h.
- **Automated tests:** duplicate delivery, out-of-order batches, watch offline for 3 days then reconnect, token
  expiry, app killed mid-sync, clock/timezone change.
- **Manual device test matrix:** each brand above × {fresh install, Bluetooth off/on, phone reboot, airplane
  mode, battery saver}. Results recorded in `docs/DEVICE_TEST_MATRIX.md`.

## 4. All features we can add

Effort: **S** ≈ 1–3 days, **M** ≈ 1–2 weeks, **L** ≈ 2–4 weeks. ⌚ = needs or is improved by watch data.

### 4.1 Smartwatch app (Wear OS)
| # | Feature | What it does | Effort |
|---|---|---|---|
| W1 | **Watch workout app** ⌚ | Start/pause/finish workouts on the wrist; live HR, zone, time, calories, distance; HIIT presets with wrist vibration on WORK/REST | L |
| W2 | **Tile** ⌚ | Swipe-to tile with readiness score, rings and "today's coach" one-liner | S |
| W3 | **Watch-face complications** ⌚ | Readiness and streak on any compatible watch face | S |
| W4 | **Phone ↔ watch handoff** ⌚ | Workout started on either device shows on both; finishing on one ends it on the other | M |
| W5 | **Study-break nudge on the wrist** ⌚ | Vibrate after 90 min of inactivity during study hours; one tap starts a 3-min stretch | S |

### 4.2 Insights from watch data
| # | Feature | What it does | Effort |
|---|---|---|---|
| D1 | **Readiness v2** ⌚ | Add HRV vs 14-day baseline, sleep stages (deep/REM share), respiratory rate and skin temp deviation to the score; same formula in Kotlin and Python | M |
| D2 | **Sleep coach** ⌚ | Sleep stages chart, consistency score, personalised bedtime reminder ("sleep by 23:40 for 8 h before your 8 am class") | M |
| D3 | **Heart-rate zones** ⌚ | Live zone during workouts, minutes per zone per week, WHO-aligned "moderate vs vigorous" minutes | M |
| D4 | **Stress & recovery timeline** ⌚ | Day timeline of HR/HRV, highlights high-stress periods (e.g. during exams), suggests breathing | M |
| D5 | **VO2 max / fitness age trend** ⌚ | Long-term cardio fitness trend with plain-language meaning | S |
| D6 | **Auto-detected workouts** ⌚ | Sessions the watch auto-detects appear automatically with a "was this right?" confirm | S |
| D7 | **GPS routes** ⌚ | Route map, pace, splits for runs/walks/cycles from the watch or phone | M |

### 4.3 Student-specific
| # | Feature | What it does | Effort |
|---|---|---|---|
| S1 | **Exam mode** | Add exam dates/timetable; AI planner fits short sessions into free slots, lowers intensity in exam week, pushes sleep | M |
| S2 | **Class timetable import** | Manual entry or ICS file; powers S1, study-break nudges and bedtime suggestions | S |
| S3 | **Dorm workout library** | 40+ no-equipment exercises with animated demos, 10–20 min routines, beginner→advanced | M |
| S4 | **Mess/canteen food log** | Photo → AI estimate of calories/protein, tuned for Indian hostel food; ties into coach advice | M |
| S5 | **Mood & stress check-in + breathing** ⌚ | 5-second daily check-in; guided breathing with live HR on the watch | S |

### 4.4 Social & motivation
| # | Feature | What it does | Effort |
|---|---|---|---|
| G1 | **Kudos & reactions** | React to squad members' goal days and workouts | S |
| G2 | **Invite deep links** | Share a link that opens straight into a challenge | S |
| G3 | **Weekly recap card** | Story-sized shareable image: steps, streak, rank, best day | S |
| G4 | **Campus vs campus** | Leaderboards by college/hostel/batch; inter-hostel events | M |
| G5 | **Rewards & partner perks** | Points for goal days, redeemable for gym/canteen discounts | L |
| G6 | **Camera rep counter** | On-device pose detection counts squats/push-ups and flags bad form | L |

### 4.5 Experience & reach
| # | Feature | What it does | Effort |
|---|---|---|---|
| X1 | **Phone home-screen widget** | Readiness + rings without opening the app | S |
| X2 | **Voice cues** | Spoken "10 seconds left", "Rest", zone changes | S |
| X3 | **Hindi + regional languages** | App strings and coach replies | M |
| X4 | **Smart notifications** | Fewer, better: one morning readiness card, one evening "close your ring" only when achievable | S |

### 4.6 For the institution (B2B client)
| # | Feature | What it does | Effort |
|---|---|---|---|
| B1 | **College admin dashboard** | Anonymised, aggregated activity and challenge participation for a sports committee or wellness cell | L |
| B2 | **Official campus challenges** | Admin-created events pushed to all students | M |

## 5. Delivery order

| Milestone | Scope | Exit criteria |
|---|---|---|
| **M1 — Reliable watch data** | §3 entirely: HC upgrade, changes API, background read, new record types, data-origin labels, outbox, Data health screen, sync tests | Data from a real watch appears within one worker cycle; all fault-injection tests pass; device test matrix filled for available watches |
| **M2 — Wear OS app** | New `:wear` Gradle module; W1, W2, W3, W5; Data Layer transport with ack | Workout recorded on the watch with phone off arrives on the phone and server exactly once |
| **M3 — Watch insights** | D1, D2, D3, D6 + BLE HR (path C) in the phone workout player | Readiness v2 identical in Kotlin & Python tests; coach tools expose the new metrics |
| **M4 — Student features** | S1, S2, S3, S5, X1, X2 | Planner evals cover exam weeks |
| **M5 — Social growth** | G1, G2, G3, X4, D7 | |
| **M6 — Bigger bets** | S4, G4, G6, X3, B1, B2, G5 | |

## 6. Risks
- **Closed watch ecosystems.** Budget Indian brands (Noise, boAt, Fire-Boltt) may not export to Health Connect.
  Mitigation: confirm on real devices in M1; show an in-app compatibility check; phone sensors as fallback.
- **Wear OS testing** needs a physical watch or the Wear OS emulator; Health Services data on the emulator is
  synthetic.
- **Play policy:** Health Connect background and history permissions need a declared justification at
  publishing time.
- **Battery:** batching and passive monitoring only; continuous sensors run only during workouts.
