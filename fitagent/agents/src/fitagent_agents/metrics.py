"""Deterministic fitness metrics. Mirrors app/.../domain/Readiness.kt — keep the two in sync.

Both implementations are checked against the shared fixtures in fitagent/fixtures/readiness_v2.json.
Rounding is always half-up (``_round``), matching Kotlin's ``roundToInt``.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date, timedelta
from enum import Enum

from langsmith import traceable

from .data import ActivityDay, Goals, UserContext, WorkoutRecord

SLEEP_TARGET_MIN = 8 * 60
BASELINE_WINDOW_DAYS = 14
MIN_BASELINE_DAYS = 3
MIN_HRV_NIGHTS = 7
HRV_MIN_SD = 0.05  # floor on the log-HRV spread so a very stable baseline doesn't turn noise into huge swings
NEUTRAL_SCORE = 60  # no data at all: a steady day, never "push" by default
# Readiness v2 weights (docs/phase2/05-agents-and-algorithms.md §1). Missing components are dropped and the rest
# re-normalised, so a watch that only reports sleep + heart rate still gets a sensible score.
WEIGHTS = {  # percent; integers so Kotlin and Python compute the weighted average exactly
    "sleep": 25,
    "sleep_quality": 10,
    "hrv": 25,
    "resting_hr": 20,
    "load": 15,
    "nightly": 5,
}


class Band(str, Enum):
    PUSH = "PUSH"
    STEADY = "STEADY"
    RECOVER = "RECOVER"


@dataclass(frozen=True)
class Readiness:
    score: int
    band: Band
    components: dict[str, int]
    reasons: list[str]
    acwr: float | None


def _clamp(v: float, lo: float = 0.0, hi: float = 100.0) -> float:
    return max(lo, min(hi, v))


def _round(v: float) -> int:
    """Half-up rounding, identical to Kotlin's roundToInt (Python's round() is banker's rounding)."""
    return math.floor(v + 0.5)


def band_for(score: int) -> Band:
    if score >= 67:
        return Band.PUSH
    if score >= 34:
        return Band.STEADY
    return Band.RECOVER


def minutes_between(workouts: list[WorkoutRecord], start: date, end: date) -> int:
    """Total active minutes of workouts whose start date is in [start, end]."""
    return sum(w.active_minutes for w in workouts if start <= w.start.date() <= end)


MIN_HISTORY_DAYS_FOR_LOAD = 14


def acute_chronic_ratio(workouts: list[WorkoutRecord], today: date, history_days: int = 28) -> float | None:
    """Acute (7-day) vs chronic (up to 28-day) training load.

    The chronic window is limited to the days we actually have data for; with less than two weeks of
    history the ratio is meaningless (every new user would look like a "spike"), so None is returned."""
    span = min(history_days, 28)
    if span < MIN_HISTORY_DAYS_FOR_LOAD:
        return None
    acute = minutes_between(workouts, today - timedelta(days=6), today) / 7
    chronic = minutes_between(workouts, today - timedelta(days=span - 1), today) / span
    if chronic <= 0:
        return None
    return _round(acute / chronic * 100) / 100


def history_span_days(ctx: UserContext) -> int:
    first_day = min((d.day for d in ctx.days), default=ctx.today)
    first_workout = min((w.start.date() for w in ctx.workouts), default=ctx.today)
    return (ctx.today - min(first_day, first_workout)).days + 1


def sleep_quality(deep: int, rem: int, light: int, awake: int) -> int:
    """0–100 from stage shares: rewards deep ≥ 15 % and REM ≥ 20 %, penalises awake > 5 %."""
    total = deep + rem + light + awake
    if total <= 0:
        return 50
    deep_pct, rem_pct, awake_pct = (m * 100 / total for m in (deep, rem, awake))
    return _round(_clamp(50 + 2.5 * (deep_pct - 15) + 2.5 * (rem_pct - 20) - 3 * (awake_pct - 5)))


def personal_baseline(ctx: UserContext, attr: str) -> list[float]:
    """The last 14 days of `attr` before today (missing days skipped) — "your normal"."""
    start = ctx.today - timedelta(days=BASELINE_WINDOW_DAYS)
    return [getattr(d, attr) for d in ctx.days if start <= d.day < ctx.today and getattr(d, attr) is not None]


def hrv_score(today_ms: float, baseline: list[float]) -> int:
    """75 + 25 × z, where z compares tonight's ln(HRV) with the personal baseline. Higher HRV = better recovered."""
    logs = [math.log(v) for v in baseline if v > 0]
    mean = sum(logs) / len(logs)
    sd = max(math.sqrt(sum((x - mean) ** 2 for x in logs) / len(logs)), HRV_MIN_SD)
    z = (math.log(today_ms) - mean) / sd
    return _round(_clamp(75 + 25 * z))


@traceable(name="compute_readiness", run_type="chain")
def compute_readiness(ctx: UserContext) -> Readiness:
    today = ctx.day(ctx.today)
    components: dict[str, int] = {}
    reasons: list[str] = []

    if today and today.sleep_minutes is not None:
        components["sleep"] = _round(_clamp(today.sleep_minutes / SLEEP_TARGET_MIN * 100))
        h, m = divmod(today.sleep_minutes, 60)
        reasons.append(f"Slept {h}h {m}m last night")
        if today.has_stages:
            components["sleep_quality"] = sleep_quality(
                today.sleep_deep_min or 0, today.sleep_rem_min or 0, today.sleep_light_min or 0,
                today.sleep_awake_min or 0,
            )

    hrv_base = personal_baseline(ctx, "hrv_ms")
    if today and today.hrv_ms and len(hrv_base) >= MIN_HRV_NIGHTS:
        components["hrv"] = hrv_score(today.hrv_ms, hrv_base)
        avg = sum(hrv_base) / len(hrv_base)
        direction = "above" if today.hrv_ms >= avg else "below"
        reasons.append(f"HRV {today.hrv_ms:.0f} ms, {direction} your normal {avg:.0f} ms")

    rhr_base = personal_baseline(ctx, "resting_hr")
    rhr_delta: float | None = None
    if today and today.resting_hr and len(rhr_base) >= MIN_BASELINE_DAYS:
        rhr_delta = today.resting_hr - sum(rhr_base) / len(rhr_base)
        components["resting_hr"] = _round(_clamp(85 - 10 * rhr_delta))
        direction = "above" if rhr_delta > 0 else "below"
        reasons.append(f"Resting HR {today.resting_hr} bpm, {abs(rhr_delta):.0f} bpm {direction} your 14-day average")

    if today:
        nightly = 100
        signals = 0
        resp_base = personal_baseline(ctx, "resp_rate")
        if today.resp_rate is not None:
            signals += 1
            if len(resp_base) >= MIN_BASELINE_DAYS and today.resp_rate > sum(resp_base) / len(resp_base) + 2:
                nightly -= 20
                reasons.append(f"Breathing rate {today.resp_rate:.1f}/min is higher than usual")
        if today.skin_temp_delta is not None:
            signals += 1
            if today.skin_temp_delta > 0.5:
                nightly -= 20
                reasons.append(f"Skin temperature {today.skin_temp_delta:+.1f} °C vs your baseline")
        if today.spo2_avg is not None:
            signals += 1
            if today.spo2_avg < 94:
                nightly -= 20
                reasons.append(f"Blood oxygen averaged {today.spo2_avg:.0f}% overnight")
        if signals:
            components["nightly"] = nightly

    acwr = acute_chronic_ratio(ctx.workouts, ctx.today, history_span_days(ctx))
    # Training load is evidence of *risk*, not of recovery: a normal load (or an unknown one) only counts when
    # there is nothing else to go on, so it can't prop up a night of bad sleep.
    if acwr is None:
        if not components:
            components["load"] = NEUTRAL_SCORE
    elif acwr <= 1.3:
        if not components:
            components["load"] = 90
    elif acwr <= 1.5:
        components["load"] = 60
        reasons.append(f"Training load rising fast (ratio {acwr})")
    else:
        components["load"] = 30
        reasons.append(f"Training load spiked (ratio {acwr}) — injury risk is higher")

    total_weight = sum(WEIGHTS[k] for k in components)
    weighted = sum(WEIGHTS[k] * v for k, v in components.items())
    score = (2 * weighted + total_weight) // (2 * total_weight)  # exact half-up rounding of weighted / total

    # Safety caps: never suggest a hard day when the body shows strain, whatever the other numbers say.
    if today and today.skin_temp_delta is not None and today.skin_temp_delta > 1.0 and rhr_delta is not None and rhr_delta > 5:
        score = min(score, 45)
        reasons.insert(0, "Your body may be fighting something — take it easy today")
    if today and today.sleep_minutes is not None and today.sleep_minutes < 240:
        score = min(score, 55)
    if acwr is not None and acwr > 1.5:
        score = min(score, 66)  # a load spike is never a "push" day
    return Readiness(score=score, band=band_for(score), components=components, reasons=reasons, acwr=acwr)


def goal_met(day: ActivityDay, goals: Goals) -> bool:
    return day.steps >= goals.daily_steps or day.workout_minutes >= goals.daily_workout_minutes


def current_streak(ctx: UserContext) -> int:
    """Consecutive goal days ending today (or yesterday if today is unfinished).
    One missed day per Mon–Sun week is absorbed by a freeze."""
    met = {d.day for d in ctx.days if goal_met(d, ctx.profile.goals)}
    cursor = ctx.today if ctx.today in met else ctx.today - timedelta(days=1)
    streak = 0
    frozen_weeks: set[tuple[int, int]] = set()
    oldest = min((d.day for d in ctx.days), default=ctx.today)
    while cursor >= oldest:
        if cursor in met:
            streak += 1
        else:
            week = cursor.isocalendar()[:2]
            if week in frozen_weeks or streak == 0:
                break
            frozen_weeks.add(week)
        cursor -= timedelta(days=1)
    return streak


@dataclass(frozen=True)
class WeekSummary:
    workouts: int
    workout_minutes: int
    avg_steps: int
    hard_sessions: int
    types: list[str]


def last_7_days(ctx: UserContext) -> WeekSummary:
    start = ctx.today - timedelta(days=6)
    ws = [w for w in ctx.workouts if start <= w.start.date() <= ctx.today]
    days = [d for d in ctx.days if start <= d.day <= ctx.today]
    return WeekSummary(
        workouts=len(ws),
        workout_minutes=sum(w.active_minutes for w in ws),
        avg_steps=_round(sum(d.steps for d in days) / len(days)) if days else 0,
        hard_sessions=sum(1 for w in ws if w.is_hard),
        types=sorted({w.type for w in ws}),
    )
