# FitAgent Phase 2 — Smartwatch-first, chart-first

**Status:** 31 of 32 features built and tested on this machine. **Ready for phone testing:** see [STATUS.md](STATUS.md) (progress, ticked) and [../TESTING.md](../TESTING.md) (phone test plan).

| # | Document | Contents |
|---|---|---|
| — | [../PHASE2_SMARTWATCH_PLAN.md](../PHASE2_SMARTWATCH_PLAN.md) | Overview: 3 data paths, brand reality check, feature list |
| 01 | [Design & charts](01-design-and-charts.md) | Research (Dribbble + studies), chart principles, colour tokens, 21-component chart kit, inventory of 76 charts |
| 02 | [Screens](02-screens.md) | Navigation map and wireframes for every new/changed screen, watch app and admin dashboard |
| 03 | [Watch data & sync](03-watch-data-and-sync.md) | Module split, data types per path, Room v2, Data Layer protocol with acks, Health Connect incremental sync, BLE HR, outbox, failure matrix |
| 04 | [Features](04-features.md) | 32 feature specs with acceptance criteria |
| 05 | [Algorithms & agents](05-agents-and-algorithms.md) | Readiness v2, zones, sleep score, stress, new coach tools, planner rules, food graph, evals |
| 06 | [Test plan](06-test-plan.md) | Automated suites, device scenarios D1–D10, release gates |
| 07 | [Build plan](07-build-plan.md) | Milestones M0–M6 with file lists and owner decisions |
| — | [STATUS.md](STATUS.md) | **Progress tracker**: every feature ticked when built, open work, verification results |
| — | [../TESTING.md](../TESTING.md) | **Phone test plan** (A–I) with checkboxes |
| — | [../DEVICE_TEST_MATRIX.md](../DEVICE_TEST_MATRIX.md) | Fill in per watch while testing |

## Ready-to-build checklist
- [x] Data paths and transmission protocol specified (03)
- [x] Every feature has acceptance criteria (04)
- [x] Every screen has a chart plan (01, 02)
- [x] Algorithms specified with shared Kotlin/Python fixtures (05)
- [x] Test plan and device matrix (06)
- [x] Milestones with file lists (07)
- [x] Owner: list of watch brands to support first (Noise, boAt)
- [ ] Owner: physical watch available for M1/M2 testing (emulator covers development)
