# 02 — Screens & navigation

Bottom navigation stays at five tabs (**Today · Train · Coach · Squad · You**). New areas open as detail screens
from cards, so the app gets deeper without getting more crowded. Charts referenced by name are defined in
[01-design-and-charts.md](01-design-and-charts.md).

```
Today ─┬─ Readiness detail        Train ─┬─ Workout library ── Exercise demo
       ├─ Sleep                          ├─ Live player (phone) ⇄ watch
       ├─ Heart                          ├─ Workout summary
       ├─ Stress & mood                  └─ Exam mode / timetable
       └─ Data health (from banner)
Coach ─┬─ Chat · Weekly plan      Squad ─┬─ Challenge detail (race bars, bump chart)
       └─ Food log                       └─ Campus leaderboard
You ───┬─ Trends · Achievements · Year in review
       ├─ Devices & data health
       └─ Settings (language, notifications, timetable, privacy)
```

## Today (redesigned)

```
┌──────────────────────────────────────┐
│ FRI, 30 OCT                 🔥5  ⟳   │
│ Good evening, Asha     ● Watch 4m ago│  ← freshness dot (green <1h, amber <6h, red)
├──────────────────────────────────────┤
│  ╭───╮  Ready to push                 │
│ │ 81 │  HRV ▲ above normal, slept 7h42│  ← headline from Insights.kt
│  ╰───╯  ▇▇▇▇▇ sleep  ▇▇▇▇ HRV  ▇▇ load│  ← contributor bars
├──────────────────────────────────────┤
│  ◎ rings     STEPS 6,240 / 8,000      │
│              MOVE 376 / 400 kcal      │
│              WORKOUT 35 / 30 min      │
│  ▁▂▁▁▃▅▇▆▃▂▅▆▂▁  hourly steps         │
├──────────────────────────────────────┤
│ TODAY'S COACH  …  [ Start 18-min walk ]│
├────────────┬────────────┬────────────┤
│ 4.6 km     │ 57 bpm     │ 68 ms HRV  │  ← every tile has a sparkline
│ ⌒⌒╱        │ ╲⌒_        │ _╱⌒        │
├────────────┴────────────┴────────────┤
│ SLEEP 7h 42m   ▂▅▇▅▂▇▅▂ hypnogram mini│ → Sleep
├──────────────────────────────────────┤
│ This week  ◎◎◎◎◎◎◎   5/7 goal days    │
└──────────────────────────────────────┘
```

## Readiness detail
Gauge + full contributor breakdown → 30-day readiness line with coloured bands → HRV vs baseline → RHR vs baseline
→ training load chart with the 0.8–1.3 sweet spot → scatter "your readiness vs hours slept" with a one-line
finding ("Each extra hour of sleep ≈ +6 readiness for you").

## Sleep
```
 Last night  7h 42m   Sleep score ◠ 84
 ┌ Awake ─╥──────────────╥─────────┐
 │ REM    ║  ▄▄    ▄▄▄   ║   ▄▄▄▄  │   hypnogram
 │ Light ▄╨▄▄  ▄▄▄    ▄▄▄╨▄▄▄     │
 │ Deep     ▀▀▀▀   ▀▀           ▀ │
 └ 23:41 ───────────────────── 07:23┘
 ◔ donut: Deep 18% · REM 22% · Light 54% · Awake 6%
 [W | M]  stacked stage bars per night
 Consistency: bedtime→wake range bars, target window shaded
 Sleep HR line · SpO2 range bars · Skin temp deviation bars
 💡 "Your bedtime varied by 1h 50m this week. Aim for 23:30 ± 30 min."
```

## Heart
Intraday zone-line (HR over coloured zone bands) → daily HR range bars → RHR trend + band → HRV trend + band →
weekly zone-minutes stacked bars with WHO moderate/vigorous summary → VO2 max trend with fitness-age bands.

## Train
Existing weekly goal + intervals + quick start, plus: **Workout library** row (dorm, no-equipment, by duration),
WHO 150-min bullet, workout-type donut, time-of-day polar clock, goal-day calendar heatmap, **Exam mode** card.

## Live workout player (phone)
Existing timer/interval UI plus live HR (from watch via Data Layer or BLE), zone arc around the timer, live HR
sparkline, voice cues toggle, "Watch connected" chip. Starting/stopping on either device mirrors to the other.

## Workout summary (new)
Headline ("32 min, mostly cardio zone") → HR curve over zones → zone split bar → splits bars + route map (GPS) →
kcal area → "Share" (recap image) → "How did it feel?" RPE slider (feeds load).

## Workout library (new)
Filter chips (Duration · Body area · Level · No equipment). Routine cards with total time and exercise count.
Exercise demo: looping animated illustration, cues, common mistakes, "Start routine" → guided player with
next-exercise preview and voice cues. Optional camera rep counter on supported exercises.

## Exam mode (new)
Timetable editor (manual or `.ics` import) → week timeline chart with classes, exams and AI-planned sessions in
free slots → toggle "Exam week" (planner lowers intensity, adds study-break nudges, protects sleep).

## Stress & mood (new)
5-second check-in (emoji 1–5 + optional tags: exams, sleep, social) → stress timeline (from HR/HRV) → mood
calendar heatmap → mood vs sleep scatter → "Breathe" button: 1/3/5-min guided breathing with live HR on the
watch and before/after HR bars.

## Coach
Chat and Weekly plan unchanged in structure; plan items show mini zone bars; new **Food log** tab: camera →
AI estimate card (editable) → macro donut, kcal in vs out bars, protein bullet.

## Squad
Challenge detail becomes chart-led: race-bar leaderboard (animated), bump chart of your rank, you vs leader vs
average line, team contribution donut, kudos reactions on each row. Campus leaderboard (college/hostel/batch).
Invite button shares a deep link `https://fitagent.app/j/ABC123` (falls back to the code).

## You
Profile + streak stats, trends (existing charts + period chips + compare toggle), achievements rings,
year-in-review heatmap, **Devices & data health**, settings.

## Devices & data health (new)
```
 Galaxy Watch 6        ● Connected · synced 4 min ago     [Sync now]
   Path: FitAgent watch app + Health Connect
   Today: 1,240 HR samples · 6,240 steps · 1 workout · sleep ✓
 Samsung Health        ● via Health Connect · 12 min ago
 Phone sensors         ● fallback only
 ── Sync timeline ── ●●●●●○●●●  (○ = retried)   records per source ▇▇▅
 Cloud backup          ✓ up to date · 0 pending
 [Run compatibility check]  → checks which data types each source writes
```

## Phone widget (Glance)
Small: readiness number + band colour. Medium: rings + readiness + "today's coach" line. Tapping opens Today.

## Wear OS app
```
 Tile                 Workout (live)          Complication
 ┌───────────┐       ┌───────────┐            ◠ 81
 │  ◎ 81     │       │  ◜ 142 ◝  │ zone arc
 │ 6.2k steps│       │  ZONE 3   │
 │ [Start ▶] │       │ 12:41 ·216│
 └───────────┘       └───────────┘
 Screens: Home (readiness + rings) · Start workout (types + HIIT presets) · Live workout · Summary
          · Breathe · Study-break prompt
```

## Admin dashboard (web, B2B)
Served by the backend at `/admin` (server-rendered, institution-scoped login): participation line, hostel
heatmap, steps-distribution histogram, challenge funnel, create official challenge. Only aggregated data for
groups of ≥ 10 students is ever shown.
