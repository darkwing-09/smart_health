# 01 — Design system & chart catalogue

Phase 2 makes FitAgent **chart-first**: almost every number gets a picture, and every picture leads with a plain-language headline,
so users understand their data at a glance without reading axes.

## 1. Research — what we took from where

| Source | Takeaway adopted |
|---|---|
| Dribbble — [Analytics Chart (Daily UI 018)](https://dribbble.com/shots/19707936-Analytics-Chart-Daily-UI-Challenge-018) | Put the metrics users check most (activity, kcal, HR zones, sleep) first; everything else lower down |
| Dribbble — [Pai fitness tracker shots](https://dribbble.com/MinhDao/shots), [Sleep tracker statistics animation](https://dribbble.com/KatieSyuma/shots), [Graphs & data collection](https://dribbble.com/zykloid/collections/629772-Graphs-Data), [Activity summary](https://dribbble.com/ChangB), [Mirovis wellbeing collection](https://dribbble.com/Mirovis/collections/7664737-wellbeing) | Rounded "pill" bars, soft gradient area charts, charts that animate in from the baseline, one accent per metric, dark surfaces with glowing data |
| [Survey on sleep visualisations for fitness trackers (EuroVis)](https://diglib.eg.org/handle/10.2312/eurp20191152) | Users most want *last night*; hypnograms, bars and donuts are the easiest to read; duration, stages, quality and HR belong in one combined view |
| [WHOOP community request: combined stages / zones view](https://www.community.whoop.com/t/feature-request-show-sleep-stages-and-hr-zones-in-combined-view/14680) | Never hide stages or zones behind per-item taps — show them all at once |
| [Heart-rate visualisations on a smartwatch (SciTePress 2023)](https://scitepress.org/PublishedPapers/2023/116655) | Combine current and historical HR + zone in one bar view; on the watch keep it to a single glanceable shape |
| Fitbit / Garmin Connect pattern | HR line drawn over coloured zone bands; personal baseline shown as a shaded "normal range" |
| WHOOP / Oura pattern (Phase 1 research) | One score first, contributors on tap; trends shown against *your own* baseline, not population norms |
| Apple Fitness pattern | Rings + a calendar of closed/partial rings; GitHub-style heatmap for consistency |

## 2. Chart principles

1. **Headline first.** Every chart card starts with one generated sentence: *"You slept 42 min more than your average"*, *"HRV is back in your normal range"*. The chart is the evidence.
2. **Personal baseline, not population.** Trend charts show a shaded band = your 14- or 30-day normal range. Points outside it are highlighted.
3. **Scrub to read.** Press-and-drag on any time chart shows a marker + value bubble with a light haptic tick per point. No tiny tooltips to aim at.
4. **Period chips** `D · W · M · 6M · Y` on every trend card; the selection is remembered per card.
5. **One accent per metric** (existing tokens): steps lime, move coral, workout cyan, sleep violet, heart pink. New tokens below for zones and stages.
6. **Animate in once** from the baseline (300 ms ease-out); no animation in screenshot tests (`LocalInspectionMode`).
7. **Accessible.** Each chart has a `contentDescription` summarising it in words; zones and stages also differ by pattern/position, not only colour; all text ≥ 4.5:1 contrast; respects font scale and reduced motion.
8. **Honest empty states.** No data → a ghost chart with "Wear your watch tonight to see sleep stages", never fake numbers.

## 3. New colour tokens

| Token | Dark | Light | Use |
|---|---|---|---|
| `zone1` (warm-up, 50–60 % HRmax) | `#8FA3BF` | `#6B7F99` | HR zones |
| `zone2` (fat burn, 60–70 %) | `#3DA5FF` | `#1E7FD6` | |
| `zone3` (cardio, 70–80 %) | `#2EE59D` | `#0E9F6E` | |
| `zone4` (hard, 80–90 %) | `#FFC93C` | `#C98A00` | |
| `zone5` (max, 90–100 %) | `#FF5C5C` | `#D63B3B` | |
| `stageAwake` | `#FF9F7A` | `#E5743F` | Sleep stages |
| `stageRem` | `#5EC8FF` | `#1D93CC` | |
| `stageLight` | `#8C8CFF` | `#6A58E0` | |
| `stageDeep` | `#4B3FD9` | `#3326B0` | |
| `hrv` | `#7CF2D0` | `#139C78` | HRV trend |
| `spo2` | `#7FB4FF` | `#2F6FD6` | Blood oxygen |
| `baselineBand` | `#FFFFFF` @ 8 % | `#000000` @ 6 % | Normal-range shading |
| `mood1…mood5` | red → amber → grey → teal → lime | | Mood heatmap |

## 4. Chart kit (`app/.../ui/charts/`)

Built in-house on Compose `Canvas`, extending the existing `ActivityRings`, `ReadinessGauge` and `BarChart`.
**Why not a library:** our charts are highly custom (rings, hypnogram, zone bands, polar clock), the same kit must
render on Wear OS and in Paparazzi tests, and it avoids pinning the toolchain to a third-party release cycle.
All components take plain data classes so they are trivially previewable and testable.

| Component | Draws | Used for |
|---|---|---|
| `BarChart` *(exists, extend)* | Pill bars, goal line, highlighted bars, scrubbing | Steps, workout minutes, kcal, hourly steps |
| `StackedBarChart` | Segmented vertical bars | Sleep stages per night, zone minutes per week |
| `HorizontalStackBar` | One segmented bar | Zone split of a workout, stage split of a night |
| `LineChart` | Smooth line + optional gradient fill + baseline band + scrub | RHR, HRV, VO2 max, weight, readiness trend |
| `ZoneLineChart` | Line over horizontal coloured bands | Intraday HR, workout HR curve |
| `RangeBarChart` | Floating bars (min–max) | Bedtime→wake per night, SpO2 night range, daily HR range |
| `Hypnogram` | Step chart with 4 depth lanes | Last night's sleep |
| `DonutChart` | Segmented ring with centre label | Stage split, workout type mix, macros |
| `CalendarHeatmap` | Month grid of tinted cells | Goal days, mood, streak calendar |
| `PolarClockChart` | 24-h radial bars | Time-of-day activity, sleep clock |
| `DivergingBarChart` | Bars above/below zero | Skin-temp deviation, readiness vs average |
| `ScatterChart` | Dots + trend line | "Sleep vs readiness" insight |
| `BulletChart` | Progress bar with target marker | WHO 150 min/week, challenge target |
| `Sparkline` | Tiny line, no axes | Stat tiles, recap card, watch tile |
| `RaceBarChart` | Animated horizontal ranking bars | Challenge leaderboards |
| `BumpChart` | Rank-over-time lines | "Your rank this challenge" |
| `TimelineChart` | Gantt-style blocks on a day axis | Exam/class timetable with planned sessions |
| `LoadChart` | Acute vs chronic lines + 0.8–1.3 sweet-spot band | Training load |
| `RouteMap` | Polyline coloured by pace/HR on a map | GPS workouts (osmdroid, no API key) |
| `ArcGauge` *(exists as ReadinessGauge, generalise)* | 270° arc | Readiness, live zone on watch, sleep score |
| `ActivityRings` *(exists)* | Concentric rings | Today, week row, watch tile |

## 5. Chart inventory — every graph in the app (76)

| Screen | Charts |
|---|---|
| **Today** (10) | Readiness gauge · contributor bars (sleep, HRV, RHR, load) · activity rings · hourly-steps bars · sparklines in 4 stat tiles (distance, RHR, HRV, sleep) · week ring row · data-freshness dot |
| **Readiness detail** (6) | 30-day readiness line with push/steady/recover bands · contributor stacked bars per day · HRV vs baseline line · RHR vs baseline line · load chart (acute/chronic + sweet spot) · "sleep vs readiness" scatter |
| **Sleep** (8) | Hypnogram · stage donut · sleep score gauge · stacked stage bars 7/30 d · bedtime–wake range bars (consistency) · sleep HR line · SpO2 range bars · skin-temp deviation bars |
| **Heart** (6) | Intraday HR zone-line · daily HR range bars · RHR trend + band · HRV trend + band · weekly zone-minutes stacked bars · VO2 max trend with fitness-age bands |
| **Activity / Train** (7) | Weekly goal segments (exists) · WHO 150-min bullet · workout-type donut · time-of-day polar clock · 30-day steps bars · goal-day calendar heatmap · monthly distance line |
| **Workout summary** (5) | HR curve over zones · zone split bar · pace/splits bars · route map coloured by pace · kcal/minute area |
| **Live workout player** (3) | Live HR sparkline · zone arc · interval progress (exists) |
| **Stress & mood** (4) | Stress timeline area · mood calendar heatmap · breathing-session HR before/after bars · mood vs sleep scatter |
| **Exam mode** (2) | Week timetable timeline with planned sessions · exam-week load vs normal-week load bars |
| **Food** (3) | Macro donut · kcal in vs active kcal out bars · protein-goal bullet |
| **Squad** (4) | Race-bar leaderboard · bump chart of your rank · you vs leader vs average line · team contribution donut |
| **You** (3) | Achievements progress rings · year-in-review heatmap · personal records sparkline |
| **Data health** (2) | Sync timeline (one dot per batch, coloured by result) · records-per-source stacked bars |
| **Recap card** (4, shareable) | 3 sparklines · 1 ring |
| **Wear OS** (5) | Tile rings · readiness complication arc · live HR zone arc · interval ring · mini hypnogram |
| **Admin dashboard (web)** (4) | Participation line · hostel heatmap · steps distribution histogram · challenge funnel |

## 6. Interaction details

- **Scrub:** `pointerInput` drag → nearest index → `Marker` (vertical line + bubble) → `HapticFeedbackType.TextHandleMove` on index change. Releasing hides it after 1.2 s.
- **Drill-down:** tapping a bar on a weekly chart opens that day; tapping a stage segment highlights that stage in the hypnogram.
- **Compare:** trend cards have a "vs last period" toggle that draws the previous period as a dashed ghost line.
- **Headlines:** generated by pure functions in `domain/Insights.kt` (unit-tested), not by the LLM, so they are instant and offline.
