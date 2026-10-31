# 06 — Test plan

Phase 2 is only "done" when data from a **real watch** reaches the phone and the server correctly. Automated tests
prove the logic; the device matrix proves the integrations.

## 1. Automated

| Suite | New coverage | Command |
|---|---|---|
| Kotlin unit (`shared`, `phone`) | Readiness v2 fixtures (shared JSON with Python), zones, WHO minutes, sleep score, stress, free slots, bedtime target, insight headlines, batch (de)serialisation, schema-version rejection | `./gradlew test` |
| Sync logic (Robolectric + fakes) | Duplicate batch, out-of-order seq, gap then fill, 3-day offline backlog, ack only contiguous, HC token expiry → full re-read, permission revoked, outbox retry/backoff, app kill mid-sync (process restart sim), timezone change | `./gradlew :phone:testDebugUnitTest` |
| Room migrations | v1 → v2 with `MigrationTestHelper`, data preserved | same |
| Screenshot (Paparazzi) | Every new screen and every chart component in dark + light, empty state, and font scale 1.3 | `./gradlew verifyPaparazziDebug` |
| Wear (Paparazzi + Robolectric) | Tile layout, live workout screen round + square, Health Services fake (`FakeExerciseClient`) | `./gradlew :wear:test` |
| Agents | Readiness v2 fixtures, new tools, planner exam rules, food graph guard + sanity, safety additions | `uv run pytest agents` |
| Evals | New scenarios/evaluators (05 §3) | `uv run python -m evals.run_evals --offline` |
| Backend | New sync fields (old clients still accepted), sync status, devices, admin k-anonymity, kudos permissions, invite links | `uv run pytest backend` |
| End-to-end | Smoke test extended: sync watch metrics → coach answers about sleep stages → plan respects exam day | `backend/scripts/smoke_test.py` |
| Instrumented (emulator, CI nightly) | Health Connect test app writes records → our worker reads them; Wear emulator ↔ phone emulator Data Layer round-trip | `./gradlew connectedCheck` |

## 2. Manual device matrix

Fill `docs/DEVICE_TEST_MATRIX.md` for every watch we can get. Each row runs scenarios D1–D10:

| ID | Scenario | Pass if |
|---|---|---|
| D1 | Fresh install, connect, grant all | 30+ days history visible within 2 min |
| D2 | Wear watch 1 day normally | Today matches the vendor app within ±3 % steps, ±2 bpm RHR, ±10 min sleep |
| D3 | Watch Bluetooth off 8 h, then on | All data arrives, no duplicates |
| D4 | Phone rebooted overnight | Morning readiness uses last night's data |
| D5 | Workout on watch with phone off (Wear app) | Exactly one workout, with HR curve, after reconnect |
| D6 | Workout started on phone, ended on watch | One workout, both devices agree |
| D7 | Revoke HC permission | Data health shows the error + reconnect works |
| D8 | Airplane mode 24 h | Outbox drains after reconnect; server matches phone |
| D9 | Battery saver on | Background sync still within 1 h (or banner explains) |
| D10 | Timezone change while travelling | No day double-counted or lost |

## 3. Release gates
- All automated suites green in CI (including new Wear module and instrumented nightly).
- Device matrix: ≥ 1 Wear OS watch and ≥ 2 Health Connect brands fully passing D1–D10.
- Evals: 100 % safety evaluators with a live model.
- Battery: watch ≤ 10 %/h during workouts, ≤ 3 %/day passive overhead from our app.
