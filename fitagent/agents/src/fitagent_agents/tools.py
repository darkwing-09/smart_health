"""Read-only tools the coach agent can call. Each reads the UserContext loaded for the current user
(injected from graph state), so the model can only ever see the caller's own data."""

from __future__ import annotations

from datetime import timedelta
from typing import Annotated

from langchain_core.tools import tool
from langgraph.prebuilt import InjectedState

from .data import UserContext
from .metrics import compute_readiness, current_streak, goal_met, personal_baseline, sleep_quality
from .rules import suggest_today


def _ctx(state: dict) -> UserContext:
    return state["ctx"]


@tool
def get_daily_activity(days: int, state: Annotated[dict, InjectedState]) -> list[dict]:
    """Daily steps, distance, active kcal, workout minutes, resting/average heart rate and sleep for the
    last `days` days (1–30), oldest first. Use for trends and comparisons."""
    ctx = _ctx(state)
    days = max(1, min(days, 30))
    start = ctx.today - timedelta(days=days - 1)
    return [
        {
            "date": d.day.isoformat(),
            "steps": d.steps,
            "distance_km": round(d.distance_m / 1000, 2),
            "active_kcal": d.active_kcal,
            "workout_minutes": d.workout_minutes,
            "resting_hr": d.resting_hr,
            "avg_hr": d.avg_hr,
            "sleep_minutes": d.sleep_minutes,
            "goal_met": goal_met(d, ctx.profile.goals),
        }
        for d in ctx.days
        if d.day >= start
    ]


@tool
def get_workouts(days: int, state: Annotated[dict, InjectedState]) -> list[dict]:
    """Workouts logged in the last `days` days (1–60), newest first: type, start time, minutes, kcal."""
    ctx = _ctx(state)
    start = ctx.today - timedelta(days=max(1, min(days, 60)) - 1)
    rows = [w for w in ctx.workouts if w.start.date() >= start]
    rows.sort(key=lambda w: w.start, reverse=True)
    return [
        {"type": w.type, "title": w.title, "start": w.start.isoformat(timespec="minutes"),
         "minutes": w.active_minutes, "kcal": round(w.kcal)}
        for w in rows
    ]


@tool
def get_readiness_and_suggestion(state: Annotated[dict, InjectedState]) -> dict:
    """Today's readiness score with its components and reasons, plus the rule-based workout suggestion."""
    ctx = _ctx(state)
    r = compute_readiness(ctx)
    s = suggest_today(ctx, r)
    return {
        "score": r.score, "band": r.band.value, "components": r.components, "reasons": r.reasons,
        "acute_chronic_load_ratio": r.acwr,
        "suggestion": {"type": s.type, "minutes": s.minutes, "headline": s.headline, "detail": s.detail},
        "steps_remaining_today": s.steps_remaining,
    }


@tool
def get_goals_and_streak(state: Annotated[dict, InjectedState]) -> dict:
    """The user's goals, today's progress against them, and their current goal streak."""
    ctx = _ctx(state)
    g = ctx.profile.goals
    today = ctx.day(ctx.today)
    return {
        "goals": {"daily_steps": g.daily_steps, "daily_active_kcal": g.daily_active_kcal,
                  "daily_workout_minutes": g.daily_workout_minutes, "weekly_workouts": g.weekly_workouts},
        "today": {"steps": today.steps if today else 0, "active_kcal": today.active_kcal if today else 0,
                  "workout_minutes": today.workout_minutes if today else 0},
        "streak_days": current_streak(ctx),
    }


@tool
def get_challenges(state: Annotated[dict, InjectedState]) -> list[dict]:
    """The challenges the user has joined with their rank, value and the leader's value."""
    return [
        {"name": c.name, "metric": c.metric, "rank": c.rank, "participants": c.participants,
         "my_value": c.my_value, "leader_value": c.leader_value, "ends_on": c.ends_on.isoformat()}
        for c in _ctx(state).challenges
    ]


def _window(ctx: UserContext, days: int, cap: int) -> list:
    start = ctx.today - timedelta(days=max(1, min(days, cap)) - 1)
    return [d for d in ctx.days if d.day >= start]


def _avg(values: list[float]) -> float | None:
    return round(sum(values) / len(values), 1) if values else None


@tool
def get_sleep_detail(nights: int, state: Annotated[dict, InjectedState]) -> dict:
    """Sleep from the watch for the last `nights` nights (1–30), oldest first: minutes asleep, stage minutes
    (deep/REM/light/awake) and a 0–100 stage-quality score when the watch reports stages, plus averages.
    Stages are estimates from a wrist device — never describe them as a medical measurement."""
    rows = _window(_ctx(state), nights, 30)
    out = []
    for d in rows:
        if d.sleep_minutes is None:
            continue
        night = {"date": d.day.isoformat(), "asleep_minutes": d.sleep_minutes}
        if d.has_stages:
            night |= {"deep": d.sleep_deep_min, "rem": d.sleep_rem_min, "light": d.sleep_light_min,
                      "awake": d.sleep_awake_min,
                      "quality": sleep_quality(d.sleep_deep_min or 0, d.sleep_rem_min or 0,
                                               d.sleep_light_min or 0, d.sleep_awake_min or 0)}
        out.append(night)
    asleep = [n["asleep_minutes"] for n in out]
    return {"nights": out, "average_minutes": _avg(asleep), "watch_reports_stages": any("deep" in n for n in out)}


@tool
def get_heart_summary(days: int, state: Annotated[dict, InjectedState]) -> dict:
    """Heart data from the watch for the last `days` days (1–30): resting HR and HRV per day with the user's own
    14-day normal range, overnight SpO2 and breathing rate when available, and the latest VO2 max estimate.
    Use to explain readiness; never diagnose."""
    ctx = _ctx(state)
    rows = _window(ctx, days, 30)
    rhr_base, hrv_base = personal_baseline(ctx, "resting_hr"), personal_baseline(ctx, "hrv_ms")
    vo2 = next((d.vo2max for d in sorted(ctx.days, key=lambda d: d.day, reverse=True) if d.vo2max), None)
    return {
        "days": [
            {"date": d.day.isoformat(), "resting_hr": d.resting_hr, "hrv_ms": d.hrv_ms, "spo2_avg": d.spo2_avg,
             "resp_rate": d.resp_rate, "hr_min": d.hr_min, "hr_max": d.hr_max}
            for d in rows if any(v is not None for v in (d.resting_hr, d.hrv_ms, d.spo2_avg, d.hr_max))
        ],
        "normal_resting_hr": _avg(rhr_base),
        "normal_hrv_ms": _avg(hrv_base),
        "vo2max": vo2,
    }


TREND_METRICS = {
    "steps": lambda d: d.steps,
    "workout_minutes": lambda d: d.workout_minutes,
    "active_kcal": lambda d: d.active_kcal,
    "sleep_minutes": lambda d: d.sleep_minutes,
    "resting_hr": lambda d: d.resting_hr,
    "hrv_ms": lambda d: d.hrv_ms,
}


@tool
def get_trends(metric: str, days: int, state: Annotated[dict, InjectedState]) -> dict:
    """Compares a metric's average over the last `days` days (7–30) with the `days` before that — answers
    "am I improving?". metric is one of: steps, workout_minutes, active_kcal, sleep_minutes, resting_hr, hrv_ms."""
    ctx = _ctx(state)
    pick = TREND_METRICS.get(metric)
    if pick is None:
        return {"error": f"unknown metric {metric!r}", "metrics": sorted(TREND_METRICS)}
    days = max(7, min(days, 30))
    current_start = ctx.today - timedelta(days=days - 1)
    previous_start = current_start - timedelta(days=days)
    cur = [v for d in ctx.days if d.day >= current_start and (v := pick(d)) is not None]
    prev = [v for d in ctx.days if previous_start <= d.day < current_start and (v := pick(d)) is not None]
    a, b = _avg(cur), _avg(prev)
    change = round((a - b) / b * 100, 1) if a is not None and b else None
    return {"metric": metric, "days": days, "average": a, "previous_average": b, "change_percent": change,
            "data_points": len(cur)}


COACH_TOOLS = [
    get_daily_activity, get_workouts, get_readiness_and_suggestion, get_goals_and_streak, get_challenges,
    get_sleep_detail, get_heart_summary, get_trends,
]
