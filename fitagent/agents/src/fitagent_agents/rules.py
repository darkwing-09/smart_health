"""Deterministic coaching rules. Used directly when no LLM is configured, and as the safe fallback
whenever a model's output fails validation."""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import timedelta

from .data import UserContext
from .metrics import Band, Readiness, current_streak, last_7_days
from .safety import week_order, weekly_minutes_cap
from .schedule import EXAM_WEEK_MAX_MIN
from .schemas import WEEKDAYS, PlannedSession, Weekday, WeeklyPlan

STEPS_PER_BRISK_MINUTE = 100

FOCUS = {
    "WALK": "Brisk walk — you should be able to talk, but not sing",
    "RUN": "Easy 5 min warm-up, then steady run at a pace you could hold for an hour",
    "CYCLE": "Steady ride, light resistance, smooth cadence",
    "STRENGTH": "Full body: squats, push-ups, rows, lunges, plank — 3 rounds",
    "HIIT": "Intervals: 40 s hard / 20 s easy, 8–10 rounds, plus warm-up",
    "YOGA": "Mobility flow: hips, hamstrings, upper back — breathe slowly",
    "REST": "Rest day — a short walk or light stretching is fine",
}


@dataclass(frozen=True)
class TodaySuggestion:
    type: str
    minutes: int
    headline: str
    detail: str
    steps_remaining: int
    walk_minutes: int


def weekday_of(ctx: UserContext) -> Weekday:
    return WEEKDAYS[ctx.today.weekday()]


def suggest_today(ctx: UserContext, readiness: Readiness) -> TodaySuggestion:
    goals = ctx.profile.goals
    today = ctx.day(ctx.today)
    steps = today.steps if today else 0
    done_minutes = today.workout_minutes if today else 0
    steps_remaining = max(goals.daily_steps - steps, 0)
    walk = math.ceil(steps_remaining / STEPS_PER_BRISK_MINUTE)

    recent = {w.type for w in ctx.workouts if w.start.date() >= ctx.today - timedelta(days=3)}
    left = max(goals.daily_workout_minutes - done_minutes, 10)

    if done_minutes >= goals.daily_workout_minutes:
        detail = (
            f"{walk} more minutes of brisk walking would also close your step goal." if steps_remaining else
            "Every goal is closed for today. Rest well and hydrate."
        )
        return TodaySuggestion("REST", 0, "Workout goal done — great work", detail, steps_remaining, walk)
    if readiness.band is Band.RECOVER:
        return TodaySuggestion(
            "YOGA", 20, "Go easy today",
            "Your body is still recovering. Gentle mobility or an easy walk keeps the habit without adding strain.",
            steps_remaining, walk,
        )
    if readiness.band is Band.PUSH:
        if "HIIT" in recent:
            return TodaySuggestion(
                "RUN", max(left, 25), "You're primed for a strong session",
                "Recovery looks good. A steady run builds your aerobic base.", steps_remaining, walk,
            )
        return TodaySuggestion(
            "HIIT", 20, "You're primed for intensity",
            "Recovery looks good. Short intervals give the biggest fitness return for the time.",
            steps_remaining, walk,
        )
    if "STRENGTH" not in recent:
        return TodaySuggestion(
            "STRENGTH", max(left, 25), "Time for some strength",
            "No strength work in the last few days. Two full-body sessions a week protect joints and build power.",
            steps_remaining, walk,
        )
    return TodaySuggestion(
        "WALK", max(left, 20), "Keep the momentum",
        "A moderate session keeps you consistent without overloading.", steps_remaining, walk,
    )


_ROTATION: list[tuple[str, str]] = [
    ("STRENGTH", "moderate"),
    ("RUN", "hard"),
    ("YOGA", "easy"),
    ("CYCLE", "moderate"),
    ("HIIT", "hard"),
    ("WALK", "easy"),
]


def fallback_plan(ctx: UserContext, readiness: Readiness) -> WeeklyPlan:
    """A rule-based 7-day plan that always passes `validate_plan` (including Exam mode and timetable rules)."""
    goals = ctx.profile.goals
    order = week_order(weekday_of(ctx))
    sched = ctx.schedule
    exam_week = sched is not None and sched.exam_in_window(ctx.today)
    n = min(max(goals.weekly_workouts, 2), 6)
    last_week = last_7_days(ctx).workout_minutes
    per = max(10, min(goals.daily_workout_minutes, weekly_minutes_cap(last_week) // n, 60))
    if exam_week:
        per = min(per, EXAM_WEEK_MAX_MIN - 5)
    positions = sorted({min(round(i * 7 / n), 6) for i in range(n)})

    sessions: list[PlannedSession] = []
    slot = 0
    for idx, day in enumerate(order):
        date_ = ctx.today + timedelta(days=idx)
        if idx not in positions:
            sessions.append(PlannedSession(day=day, type="REST", minutes=0, intensity="rest", focus=FOCUS["REST"]))
            continue
        type_, intensity = _ROTATION[slot % len(_ROTATION)]
        slot += 1
        if readiness.band is Band.RECOVER and idx < 2:
            type_, intensity = ("YOGA", "easy") if idx == 0 else ("WALK", "easy")
        minutes = min(per, 25) if type_ == "HIIT" else per
        if sched is not None:
            if sched.is_exam(date_):
                type_, intensity, minutes = "WALK", "easy", min(minutes, 15)
            elif sched.is_exam(date_ + timedelta(days=1)) and intensity == "hard":
                type_, intensity = "YOGA", "easy"
            start = sched.first_fit(day, minutes) if (sched.has_slots(day) or sched.bedtime) else None
            if (sched.has_slots(day) or sched.bedtime) and start is None:
                # No gap in the timetable is long enough: try a 10-minute "movement snack", else rest.
                start = sched.first_fit(day, 10)
                if start is None:
                    sessions.append(PlannedSession(day=day, type="REST", minutes=0, intensity="rest",
                                                   focus="Busy day — no free slot. Take the stairs and walk between classes."))
                    continue
                type_, intensity, minutes = "WALK", "easy", 10
            sessions.append(PlannedSession(day=day, type=type_, minutes=minutes, intensity=intensity, focus=FOCUS[type_],
                                           start_time=start))
            continue
        sessions.append(PlannedSession(day=day, type=type_, minutes=minutes, intensity=intensity, focus=FOCUS[type_]))

    hard = sum(1 for s in sessions if s.intensity == "hard")
    active = sum(1 for s in sessions if s.type != "REST")
    if exam_week:
        why = "Exam week: short sessions in your free slots keep you fresh without stealing study time."
    elif readiness.band is Band.RECOVER:
        why = "Starting gently because your readiness is low."
    else:
        why = "Built to keep you consistent and progressing safely."
    summary = (
        f"{active} sessions this week ({sum(s.minutes for s in sessions)} min) with {hard} hard day"
        f"{'s' if hard != 1 else ''} spaced by easier ones. {why}"
    )
    return WeeklyPlan(summary=summary, sessions=sessions)


def fallback_reply(ctx: UserContext, readiness: Readiness, message: str) -> str:
    """Offline coach answer built purely from the user's data."""
    s = suggest_today(ctx, readiness)
    week = last_7_days(ctx)
    streak = current_streak(ctx)
    today = ctx.day(ctx.today)
    steps = today.steps if today else 0
    lines = [
        f"Readiness today: {readiness.score}/100 ({readiness.band.value.lower()}).",
        *[f"• {r}" for r in readiness.reasons[:3]],
        "",
        f"Suggestion: {s.headline}. "
        + (f"{s.minutes} min of {s.type.lower()}. " if s.minutes else "")
        + s.detail,
        f"Steps so far: {steps:,} of {ctx.profile.goals.daily_steps:,}"
        + (f" — about {s.walk_minutes} min of brisk walking to go." if s.steps_remaining else " — goal reached!"),
        (f"Last 7 days: {week.workouts} workouts, {week.workout_minutes} min. "
         f"Current streak: {streak} day{'s' if streak != 1 else ''}."),
    ]
    if any(k in message.lower() for k in ("plan", "schedule", "week")):
        lines.append("\nTap “Generate weekly plan” on the Coach tab for a full 7-day schedule.")
    lines.append("\n(The AI coach is offline, so this answer comes from your data and FitAgent's training rules.)")
    return "\n".join(lines)
