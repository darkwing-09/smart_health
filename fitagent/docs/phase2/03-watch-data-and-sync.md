# 03 — Smartwatch data & sync: technical spec

Rule for Phase 2: **the watch is the source of truth for body data.** Phone sensors are a labelled fallback only.
Overview and brand matrix: [../PHASE2_SMARTWATCH_PLAN.md](../PHASE2_SMARTWATCH_PLAN.md) §2.

## 1. Project structure change

The Android project becomes multi-module (today it is a single `app` module):

```
fitagent/app/            (Gradle root, renamed settings)
├── phone/               com.fitagent.app — current app moves here
├── wear/                com.fitagent.app — Wear OS app (SAME applicationId + signing key: required by Data Layer)
└── shared/              Kotlin library: domain math (readiness, zones, streaks), sync protocol models, chart kit
```

- `shared` holds pure Kotlin + Compose chart kit so phone, watch and tests use identical maths.
- Toolchain bump (M0): Kotlin 2.x with the Compose compiler Gradle plugin, AGP 8.7+, compileSdk/targetSdk 35,
  Health Connect client 1.1.x stable (or newer), Health Services 1.1.0, Wear Compose Material 3, Tiles 1.6,
  Glance for the widget, play-services-wearable 19+. Paparazzi re-verified after the bump. Exact versions are
  pinned in `gradle/libs.versions.toml` at build time after checking release dates.
- Phone minSdk stays 28; wear minSdk 30 (Health Services requirement).

## 2. Data types

| Metric | Path A (watch app, Health Services) | Path B (Health Connect record) | Stored on phone | Synced to cloud |
|---|---|---|---|---|
| Steps | `PassiveMonitoring` STEPS_DAILY + per-hour | `StepsRecord` (aggregate) | hourly + daily | daily |
| Distance / floors | DISTANCE, FLOORS | `DistanceRecord`, `FloorsClimbedRecord` | daily | daily |
| Active kcal | CALORIES | `ActiveCaloriesBurnedRecord` | hourly + daily | daily |
| Heart rate | HEART_RATE_BPM (passive + exercise) | `HeartRateRecord` | **per-minute** buckets (min/avg/max) | daily min/avg/max + zone minutes |
| Resting HR | derived (lowest 30-min sleep avg) | `RestingHeartRateRecord` | daily | daily |
| HRV (RMSSD) | — (not exposed by Health Services) | `HeartRateVariabilityRmssdRecord` | nightly | nightly |
| SpO2 | — | `OxygenSaturationRecord` | nightly min/avg | nightly avg/min |
| Respiratory rate | — | `RespiratoryRateRecord` | nightly | nightly |
| Skin temperature | — | `SkinTemperatureRecord` (deltas) | nightly delta | nightly delta |
| Sleep + stages | — | `SleepSessionRecord.stages` | segments | per-night stage minutes, bed/wake times |
| VO2 max | VO2_MAX (exercise) | `Vo2MaxRecord` | per measurement | latest |
| Workouts | `ExerciseClient` sessions | `ExerciseSessionRecord` (+ `ExerciseRoute`) | session + HR curve + route | session + zone minutes (route stays on device) |
| Weight | — | `WeightRecord` | latest | latest |

**Privacy rule:** intraday HR, sleep segments and GPS routes never leave the phone; only daily/nightly summaries go
to the cloud (unchanged Phase 1 principle).

## 3. Phone database (Room v2, migration from v1)

| Entity | Key | Notes |
|---|---|---|
| `DailyDeviceEntity` *(extend)* | epoch_day | + `hrv_ms`, `spo2_avg`, `spo2_min`, `resp_rate`, `skin_temp_delta`, `vo2max`, `floors`, `hr_min`, `hr_max`, `sleep_deep/rem/light/awake_min`, `bed_ms`, `wake_ms`, `primary_source` |
| `HourlyEntity` | (epoch_day, hour) | steps, active kcal |
| `HrMinuteEntity` | minute_epoch | min/avg/max bpm, source; pruned after 90 days |
| `SleepSegmentEntity` | (session_uid, start_ms) | stage, end_ms |
| `WorkoutEntity` *(extend)* | uid | + `avg_hr`, `max_hr`, `zone1..5_sec`, `distance_m`, `rpe`, `device` |
| `WorkoutSampleEntity` | (workout_uid, t_ms) | hr, pace, lat/lon (nullable) |
| `DeviceEntity` | source_id | name, path (WEAR_APP / HEALTH_CONNECT / BLE / PHONE), package, last_sync_ms, last_error |
| `SyncLogEntity` | id | time, source, records, result, duration — powers Data health timeline |
| `OutboxEntity` | id | kind, payload JSON, attempts, next_attempt_ms — phone→cloud queue |
| `WatchBatchEntity` | (node_id, seq) | received batches for dedupe/ack |
| `MoodEntity`, `TimetableEntity`, `FoodEntity` | | see 04-features |

## 4. Path A — watch ⇄ phone protocol (Data Layer)

```
WATCH                                              PHONE
Health Services ─► WatchRepo ─► Room(watch)
                         │ every 15 min / workout end / reconnect
                         ▼
   DataClient.putDataItem("/fitagent/batch/<seq>",            WearableListenerService.onDataChanged
       asset = gzip(JSON Batch{seq, nodeId, records[]}),  ──►    ├─ unzip, validate schema version
       urgent = workoutEnded)                                    ├─ upsert by record uid (idempotent)
                                                                 ├─ insert WatchBatchEntity(node, seq)
   MessageClient "/fitagent/ack" {upToSeq}               ◄──     └─ send ack(highest contiguous seq)
   delete batches ≤ upToSeq; deleteDataItems(path)

Live (only while phone player is open):
   MessageClient "/fitagent/live" {hr, zone, elapsed} ~1 Hz  ──►  live player UI (droppable)
Control:
   "/fitagent/workout/start|pause|resume|end" both directions — handoff (W4)
```

- **Schema:** `Batch(v=1, seq, nodeId, createdAt, records: List<WatchRecord>)`, `WatchRecord` sealed:
  `Steps(hourStart, count)`, `Hr(minute, min, avg, max)`, `Workout(uid, type, start, end, samples…)`, `Vo2(t, value)`.
  Defined once in `shared/sync/` with kotlinx.serialization; unknown `v` → reject + log, never crash.
- **Retry:** unacked batches resent after 1, 5, 15, 60 min (max), plus immediately on `CapabilityClient`
  reachability change. Watch keeps up to 14 days of unacked data, then drops oldest hourly HR first.
- **Capability check:** phone advertises `fitagent_phone`, watch `fitagent_watch` (`wear.xml`); the app shows
  "Install FitAgent on your watch" with a Play deep link when the phone sees a watch without our app.
- **Standalone:** the watch app works without the phone (records, buffers, shows stats); data flows when reconnected.

## 5. Path B — Health Connect incremental sync

1. First connect: request read permissions + `READ_HEALTH_DATA_HISTORY` (if available) → full import of 90 days via aggregates/records.
2. Store a changes token per record type (`getChangesToken(ChangesTokenRequest(types))`).
3. `HealthSyncWorker` (periodic 15 min with `READ_HEALTH_DATA_IN_BACKGROUND`, else 1 h + on app open):
   `getChanges(token)` → upsert/delete by record id → recompute affected days → save `nextChangesToken`.
   `changesTokenExpired` → full re-read of last 30 days.
4. **Source priority** per metric: user-chosen primary device > watch package > other wearable > phone. Steps and
   kcal still use platform aggregates (dedupe), but `dataOriginFilter` is applied when the user pins a device.
5. Every read writes a `SyncLogEntity`; errors (permission revoked, quota, remote exception) set
   `DeviceEntity.last_error` and show in Data health.
6. Rate limits: HC read quotas respected by batching types per request and backing off on `RemoteException`.

## 6. Path C — BLE heart rate

`BleHeartRateSource`: scan for service `0x180D`, connect, subscribe to `0x2A37` notifications, parse flags
(8/16-bit HR, RR intervals). Only active while a workout is running; auto-reconnect; permission
`BLUETOOTH_SCAN` (neverForLocation) + `BLUETOOTH_CONNECT`. Remembered device shown in Devices.

## 7. Phone → cloud

- **Outbox pattern:** every change enqueues an `OutboxEntity`; `CloudSyncWorker` sends up to 200 items per
  `POST /v1/me/sync`, deletes them only on 2xx; exponential backoff; survives kills/reboots.
- **API v1 additions** (backwards compatible — old clients still work):
  - `DailyActivityIn` + `hrv_ms, spo2_avg, spo2_min, resp_rate, skin_temp_delta, vo2max, floors, hr_min, hr_max,
    sleep_deep_min, sleep_rem_min, sleep_light_min, sleep_awake_min, bed_time, wake_time, primary_source`
  - `WorkoutIn` + `avg_hr, max_hr, zone_seconds[5], distance_m, rpe, device`
  - `GET /v1/me/sync/status` → server's latest day/workout timestamps (Data health "cloud up to date" check)
  - `POST /v1/me/devices` / `GET` → registered device list (name, path, last sync) for support/debugging
- **Backend:** Alembic migration `0002_watch_metrics`; `DbDataProvider` exposes new fields to agents.

## 8. Failure handling matrix

| Failure | Behaviour | User sees |
|---|---|---|
| Watch out of range | Batches queue on watch (≤14 d) | Freshness dot turns amber after 6 h |
| Phone app killed | DataItems delivered on next process start by Play services | Nothing |
| Duplicate batch | Ignored by (node, seq) and record uid | Nothing |
| HC permission revoked | Worker stops reading, logs error | Data health: "Permission removed — Reconnect" |
| HC token expired | Full 30-day re-read | Nothing |
| Vendor app not syncing | No new HC records for 24 h while watch connected | Banner: "Open Samsung Health to sync" with deep link |
| Server down | Outbox retries with backoff | "Cloud backup pending (n)" |
| Clock/timezone change | All storage UTC epoch; days computed in user zone at read time | Nothing |
