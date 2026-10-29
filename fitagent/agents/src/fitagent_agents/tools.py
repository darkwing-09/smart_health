"""Read-only tools the coach agent can call. Each reads the UserContext loaded for the current user
(injected from graph state), so the model can only ever see the caller's own data."""

from __future__ import annotations

from datetime import timedelta
from typing import Annotated

from langchain_core.tools import tool
from langgraph.prebuilt import InjectedState

from .data import UserContext
from .metrics import compute_readiness, current_streak, goal_met
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


COACH_TOOLS = [get_daily_activity, get_workouts, get_readiness_and_suggestion, get_goals_and_streak, get_challenges]
