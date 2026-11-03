# 05 — Algorithms & agents

All scoring stays **deterministic and duplicated** in Kotlin (`shared/domain`) and Python
(`agents/src/fitagent_agents/metrics.py`). A shared fixture file `fitagent/fixtures/readiness_v2.json` (≥ 40 cases)
is loaded by both test suites so the two implementations can never drift.

## 1. Readiness v2

Components are scored 0–100. Missing components are dropped and the remaining weights renormalised (same rule as
v1). No data at all → neutral 60. Bands unchanged: ≥ 67 Push, 34–66 Steady, < 34 Recover.

| Component | Weight | Score | Needs |
|---|---|---|---|
| Sleep duration | 0.25 | `min(100, minutes / 480 × 100)` (v1 rule) | sleep |
| Sleep quality | 0.10 | 50 + 2.5 × (deep% − 15) + 2.5 × (REM% − 20) − 3 × (awake% − 5), clamped | stages |
| HRV | 0.25 | z = (ln today − mean ln 14 d) / sd; score = 75 + 25 × z, clamped | ≥ 7 nights HRV |
| Resting HR | 0.20 | `85 − 10 × (today − 14-d mean)` (v1 rule) | ≥ 7 days RHR |
| Load (ACWR) | 0.15 | ≤ 1.3 → 90; ≤ 1.5 → 60; else 30 (v1 rule) | ≥ 14 days history |
| Nightly signals | 0.05 | start 100; −20 if resp. rate > baseline + 2 br/min; −20 if skin temp delta > +0.5 °C; −20 if SpO2 avg < 94 % | any of the three |

**Caps (safety overrides, applied after weighting):**
- Skin temp > +1.0 °C **and** RHR > baseline + 5 → score capped at 45 and reason "Your body may be fighting something — take it easy". No medical wording.
- Sleep < 4 h → score capped at 55.

**Reasons:** each component contributes at most one reason string; the headline uses the component with the
largest negative (or positive, if all good) deviation. Strings live next to the maths and are unit-tested.

## 2. Other algorithms

| Algorithm | Definition |
|---|---|
| HRmax | `208 − 0.7 × age` (Tanaka); user override wins; default age 20 |
| Zones | Z1 50–60 %, Z2 60–70 %, Z3 70–80 %, Z4 80–90 %, Z5 ≥ 90 % HRmax; < 50 % = no zone |
| WHO minutes | moderate = Z2+Z3 minutes, vigorous = Z4+Z5; weekly score = moderate + 2 × vigorous vs target 150 |
| Training load v2 | per workout: TRIMP-lite = Σ zone minutes × zone weight (1,2,3,4,5); falls back to minutes × RPE/3 when no HR; ACWR on this load |
| Sleep score | 0.5 × duration score + 0.3 × quality score + 0.2 × consistency (bedtime SD over 7 nights, 0 min → 100, ≥ 120 min → 0) |
| Stress estimate | per 15-min window with steps < 20: HR − RHR baseline → 0–100 scale; HRV z-score adjusts ±15; labelled "estimate" |
| Resting HR (watch path) | lowest 30-min rolling mean HR during the main sleep session |
| Free slots | timetable gaps ≥ 15 min between 06:00 and 22:00 excluding meals 13:00–14:00 & 20:00–21:00 defaults |
| Bedtime target | first commitment next day − 45 min − sleep need (default 8 h), rounded to 10 min |

## 3. Agent changes (LangGraph)

### New coach tools (all read-only, `InjectedState`, user-scoped as today)
| Tool | Returns |
|---|---|
| `get_sleep_detail(nights)` | per night: duration, stage minutes, bed/wake, sleep score, SpO2, resp rate |
| `get_heart_summary(days)` | RHR, HRV with baseline bands, zone minutes, WHO minutes, VO2 max |
| `get_trends(metric, days)` | series + 7-day/30-day averages + change vs previous period (for "am I improving?") |
| `get_schedule(days)` | free slots and exam days only — no course names |
| `get_mood(days)` | mood scores + tags (only if the user enabled mood sharing with the coach) |

### Planner graph
- Context adds timetable free slots, exam days, zone history, readiness v2.
- `WeeklyPlan.PlannedSession` gains `slot` (start time), `target_zone`, `routine_id` (links to the workout library).
- Validator new rules: no Z4–Z5 on exam days; ≤ 30 min/day in exam week; sessions must fit a free slot; at
  least 2 nights with bedtime target respected (no late sessions within 2 h of target bedtime).
- Repair loop and rule fallback unchanged; `fallback_plan` learns slots + exam days.

### Food estimate (new graph `food_estimate`)
`guard (eating-disorder patterns) → vision model with structured output FoodEstimate(items[name, portion, kcal,
protein_g, confidence]) → sanity check (kcal per item 20–1500) → reply`. Falls back to "couldn't recognise — add
manually". Photos are sent to the model provider only, never stored on our server.

### Safety additions
- Red-flag categories gain: very low SpO2 mentions with symptoms → emergency copy; "fasting/skipping meals to
  lose weight fast" → eating-disorder copy.
- Post-check additionally blocks medical claims about HRV/SpO2/temperature ("you have an infection").

### Evals (LangSmith + offline)
New scenarios: exam-week plan ×3, sleep-debt question, HRV-drop question, zone question, Hindi question,
food-photo low-kcal request (must refuse/redirect), "fever + workout?" (must advise rest, no diagnosis).
New evaluators: `plan_respects_slots`, `exam_day_intensity`, `language_match`, `no_medical_claims_v2`.
Target: 100 % on safety evaluators, ≥ 90 % overall with a live model.
