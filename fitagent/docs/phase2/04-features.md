# 04 — Feature specs (32)

Each feature lists what it does, the data it needs, the charts it shows (see [01](01-design-and-charts.md)) and
acceptance criteria (AC) that must pass before it is called done. Effort: S ≈ 1–3 d, M ≈ 1–2 wk, L ≈ 2–4 wk.
⌚ = uses watch data.

---

## R — Data reliability (3)

### R1. Devices & data health ⌚ · S
Lists every source (watch app, Health Connect apps, BLE strap, phone) with path, last sync, records today, last error, "Sync now".
**Charts:** sync timeline dots, records-per-source stacked bars. Freshness dot on Today.
**AC:** sync result of every worker run is visible within 1 s of completion · amber at >6 h stale, red at >24 h · "Sync now" triggers all paths and reports per-source result.

### R2. Compatibility check ⌚ · S
Reads the last 7 days from Health Connect grouped by `dataOrigin` and record type; shows a ✓/✗ grid per app ("Noise: steps ✓, sleep ✗, HR ✗") with fixes ("Enable Health Connect in NoiseFit → Settings").
**AC:** grid matches actual HC contents on a test device · runs offline · result saved to device test matrix export.

### R3. Live heart rate from any compatible watch/strap ⌚ · M
BLE Heart Rate Profile source for the phone workout player (path C) + live HR from our Wear OS app (path A).
**AC:** HR updates ≥ 1 Hz with < 2 s latency · reconnects automatically within 10 s · HR samples saved to the workout.

---

## W — Wear OS app (5)

### W1. Watch workout app ⌚ · L
Start any workout type or HIIT preset on the wrist; live HR, zone arc, time, kcal, distance (GPS on supported
watches); wrist vibration on WORK/REST and zone changes; ambient mode; summary screen; works without phone.
**AC:** 60-min workout with phone off arrives on phone and server exactly once after reconnect · battery use ≤ 10 %/h on Galaxy Watch 4 · survives process death (Health Services owns the session).

### W2. Tile ⌚ · S
Readiness number + rings + "Start" chip; refreshes on data change (max every 15 min).
**AC:** renders on round/square 192–240 dp screens · tap opens the right screen.

### W3. Complications ⌚ · S
RANGED_VALUE readiness, SHORT_TEXT streak, RANGED_VALUE steps-to-goal.
**AC:** visible on at least 2 stock watch faces · updates within 15 min of a change.

### W4. Phone ⇄ watch handoff ⌚ · M
A workout started on either device shows live on both; pause/end on one applies to both.
**AC:** state converges within 3 s · no duplicate workout records after handoff · works when one side reconnects mid-workout.

### W5. Study-break nudges ⌚ · S
During timetable study hours, after 90 min inactive, a gentle wrist tap offers a 3-min stretch; snooze/disable; max 4/day; never during classes, exams or sleep.
**AC:** fires only inside allowed windows · respects do-not-disturb · tapping starts the routine on the watch.

---

## D — Insights from watch data (7)

### D1. Readiness v2 ⌚ · M
Adds HRV, sleep quality and nightly deviations (see [05-agents-and-algorithms.md](05-agents-and-algorithms.md)).
**Charts:** gauge, contributor bars, 30-day trend with bands, HRV/RHR vs baseline, load chart, sleep-vs-readiness scatter.
**AC:** identical results in Kotlin and Python for the shared fixture set (≥ 40 cases) · degrades gracefully when HRV/stages are missing · headline sentence always matches the dominant contributor.

### D2. Sleep coach ⌚ · M
Sleep score, hypnogram, stage donut, stage bars, consistency range bars, sleep HR, SpO2, skin temp; bedtime reminder computed from the next morning's first class/exam.
**AC:** stages sum to time in bed ± 1 min · reminder fires 30 min before target bedtime · no reminder if not enough data.

### D3. Heart-rate zones ⌚ · M
HRmax = 208 − 0.7 × age (Tanaka) or user override; zones 50/60/70/80/90 %; live zone in players; weekly zone minutes; WHO summary (moderate = Z2–Z3, vigorous = Z4–Z5 counts ×2).
**Charts:** zone-line, zone split bar, weekly stacked bars, WHO bullet.
**AC:** zone seconds of a workout sum to its HR-covered duration · same zone maths on phone, watch and backend.

### D4. Stress & recovery timeline ⌚ · M
Stress estimate per 15-min window from HR above resting baseline while not moving (steps < 20) and HRV when available; labelled as an *estimate*, never a diagnosis.
**AC:** windows with movement are excluded · copy reviewed against safety rules · links to Breathe.

### D5. Cardio fitness (VO2 max) trend ⌚ · S
Shows watch-reported VO2 max; fitness-age bands by age/sex (ACSM tables); only if the watch provides it.
**AC:** hidden when no data · bands from a cited table in code comments.

### D6. Auto-detected workouts ⌚ · S
Sessions written by watch apps appear in history with a confirm/edit/delete prompt; edits stay local.
**AC:** never duplicates our own sessions (origin filter) · deleting hides it and survives re-sync.

### D7. GPS routes ⌚ · M
Route from watch (Health Services location) or phone; osmdroid map coloured by pace/HR; splits per km.
**AC:** route stays on device · accuracy filter drops points > 30 m error · map works offline with cached tiles.

---

## S — Student-specific (5)

### S1. Exam mode · M
Mark exam dates; planner lowers intensity in exam weeks, prefers 10–20-min sessions in free slots, protects sleep, adds study-break nudges.
**Charts:** timetable timeline, exam-week vs normal load bars.
**AC:** planner validator enforces exam-week caps (no Z4–Z5 sessions on exam days, ≤ 30 min/day) · evals cover 3 exam scenarios.

### S2. Timetable · S
Manual weekly editor + `.ics` import; free-slot detection.
**AC:** imports a sample university `.ics` with recurring events · free slots shown in the timeline · data stays on device unless planner is used (then sent as slots only, not course names).

### S3. Dorm workout library · M
40+ exercises (bodyweight, no equipment, quiet/no-jump options for hostels) with looping Lottie/vector animations, cues, mistakes; 12 routines (5–25 min, beginner→advanced); guided player with voice cues.
**AC:** every exercise has animation, 3 cues and a beginner variant · routines playable offline · licences for all assets recorded in `docs/ASSETS.md`.

### S4. Mess/canteen food log · M
Photo → vision model estimates dish, portion, kcal, protein (Indian hostel food examples in the prompt); user edits before saving; manual search fallback.
**Charts:** macro donut, kcal in vs active out, protein bullet.
**AC:** eating-disorder safety guard applies · never suggests < 1,500 kcal/day or skipping meals · works without AI as manual entry · photos are not stored on the server.

### S5. Mood check-in + guided breathing ⌚ · S
One-tap mood (1–5) + tags; Breathe 1/3/5 min (4-7-8 / box) on phone and watch with live HR before/after.
**Charts:** mood heatmap, mood vs sleep scatter, before/after HR bars.
**AC:** low mood for 5+ consecutive days shows support resources (existing helpline copy) · no streaks/pressure on mood.

---

## G — Social & motivation (6)

### G1. Kudos & reactions · S
React (👏🔥💪) to squad members' goal days/workouts; daily digest notification, not per reaction.
**AC:** rate-limited · only challenge members can react · removable.

### G2. Invite deep links · S
`https://<host>/j/CODE` App Links → opens join screen; falls back to a web page with the code + Play link.
**AC:** verified App Link on release build · works when app not installed.

### G3. Weekly recap card · S
Story-sized (1080×1920) image rendered from Compose: steps sparkline, workout minutes, streak, rank, best day; share sheet.
**AC:** no personal health metrics beyond what the user toggles on · renders in < 500 ms.

### G4. Campus vs campus · M
Optional college/hostel/batch on profile; leaderboards by group (per-capita goal days, so small groups can win); seasonal events.
**Charts:** race bars, bump chart.
**AC:** groups < 5 members hidden · per-capita metric documented.

### G5. Rewards & partner perks · L
Points for goal days and challenge finishes; catalogue of partner perks (admin-managed); redemption codes.
**AC:** points can't be earned from manual entries alone (needs device data) · audit log of redemptions.

### G6. Camera rep counter · L
On-device ML Kit Pose Detection counts squats, push-ups, jumping jacks, lunges; simple form flags (depth, back angle); no video stored or uploaded.
**AC:** ≥ 90 % count accuracy on a recorded test set of 20 sets per exercise · runs ≥ 15 fps on a mid-range phone.

---

## X — Experience & reach (4)

### X1. Home-screen widget · S
Glance widgets (small: readiness; medium: rings + readiness + coach line).
**AC:** updates after each sync · dark/light · tap targets ≥ 48 dp.

### X2. Voice cues · S
TextToSpeech in players: phase changes, halfway, last 10 s, zone changes, per-km splits; ducks music.
**AC:** toggle per workout · uses app language · no cue overlap.

### X3. Hindi + regional languages · M
All strings in resources (audit hard-coded text), Hindi first, then Tamil/Telugu/Bengali/Marathi; coach replies in the user's language.
**AC:** pseudo-locale build has no clipped text · evals include Hindi prompts.

### X4. Smart notifications · S
Max 3/day: morning readiness card, evening "close your ring" only if achievable (≤ 25 min walk), challenge milestones; quiet hours from timetable/sleep.
**AC:** notification channels per type · daily cap enforced in one place.

---

## B — For the institution (2)

### B1. College admin dashboard · L
Web `/admin` for institution admins: participation over time, hostel heatmap, steps histogram, challenge funnel. Only aggregates for groups ≥ 10; no individual data.
**AC:** separate admin role + institution scoping · k-anonymity threshold enforced in SQL · CSV export of aggregates only.

### B2. Official campus challenges · M
Admins create challenges visible to all students of the institution; opt-in join.
**AC:** students can hide from public leaderboards · admin can't see individual health data.
