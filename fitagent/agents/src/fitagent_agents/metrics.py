"""Deterministic fitness metrics. Mirrors app/.../domain/Readiness.kt — keep the two in sync."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from enum import Enum

from langsmith import traceable

from .data import ActivityDay, Goals, UserContext, WorkoutRecord

SLEEP_TARGET_MIN = 8 * 60
BASELINE_WINDOW_DAYS = 14
MIN_BASELINE_DAYS = 3
NEUTRAL_SCORE = 60  # no data at all: a steady day, never "push" by default
WEIGHTS = {"sleep": 0.40, "resting_hr": 0.35, "load": 0.25}


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
    return round(acute / chronic, 2)


def history_span_days(ctx: UserContext) -> int:
    first_day = min((d.day for d in ctx.days), default=ctx.today)
    first_workout = min((w.start.date() for w in ctx.workouts), default=ctx.today)
    return (ctx.today - min(first_day, first_workout)).days + 1


@traceable(name="compute_readiness", run_type="chain")
def compute_readiness(ctx: UserContext) -> Readiness:
    today = ctx.day(ctx.today)
    components: dict[str, int] = {}
    reasons: list[str] = []

    if today and today.sleep_minutes is not None:
        components["sleep"] = round(_clamp(today.sleep_minutes / SLEEP_TARGET_MIN * 100))
        h, m = divmod(today.sleep_minutes, 60)
        reasons.append(f"Slept {h}h {m}m last night")

    window_start = ctx.today - timedelta(days=BASELINE_WINDOW_DAYS)
    baseline = [d.resting_hr for d in ctx.days if window_start <= d.day < ctx.today and d.resting_hr]
    if today and today.resting_hr and len(baseline) >= MIN_BASELINE_DAYS:
        mean = sum(baseline) / len(baseline)
        delta = today.resting_hr - mean
        components["resting_hr"] = round(_clamp(85 - 10 * delta))
        direction = "above" if delta > 0 else "below"
        reasons.append(f"Resting HR {today.resting_hr} bpm, {abs(delta):.0f} bpm {direction} your 14-day average")

    acwr = acute_chronic_ratio(ctx.workouts, ctx.today, history_span_days(ctx))
    if acwr is None:
        # Unknown load says nothing about recovery: leave it out, unless it's all we have.
        if not components:
            components["load"] = NEUTRAL_SCORE
    elif acwr <= 1.3:
        components["load"] = 90
    elif acwr <= 1.5:
        components["load"] = 60
        reasons.append(f"Training load rising fast (ratio {acwr})")
    else:
        components["load"] = 30
        reasons.append(f"Training load spiked (ratio {acwr}) — injury risk is higher")

    total_weight = sum(WEIGHTS[k] for k in components)
    score = round(sum(WEIGHTS[k] * v for k, v in components.items()) / total_weight)
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
        avg_steps=round(sum(d.steps for d in days) / len(days)) if days else 0,
        hard_sessions=sum(1 for w in ws if w.is_hard),
        types=sorted({w.type for w in ws}),
    )
