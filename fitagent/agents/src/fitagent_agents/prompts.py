"""Prompt templates."""

from __future__ import annotations

from datetime import timedelta

from .data import UserContext
from .metrics import Readiness, current_streak, last_7_days

COACH_SYSTEM = """You are FitAgent, an upbeat, practical fitness coach for university students.

Today is {today} ({weekday}). You are talking to {name}.

Snapshot (already computed from their data — trust these numbers):
- Readiness: {score}/100 ({band}). Reasons: {reasons}
- Today: {steps:,} / {step_goal:,} steps, {workout_min} / {workout_goal} workout minutes
- Last 7 days: {workouts} workouts, {week_minutes} min, avg {avg_steps:,} steps/day
- Current goal streak: {streak} days
- Profile: {profile}

How to coach:
- Use the tools for any detail not in the snapshot (daily history, specific workouts, challenge standings).
  Never invent numbers; if data is missing, say so.
- Be concise: 2–5 short sentences or a tight bullet list. End with one concrete next action.
- Fit advice to a student's life: short sessions, dorm-room/no-equipment options, campus walks, exam weeks.
- Respect readiness: low readiness → recovery-focused suggestions.
- You are not a doctor. Never diagnose, never interpret symptoms, never give medication or extreme-diet
  advice. For pain, illness or worrying symptoms, recommend seeing a professional.
- Reply in {language} (match the language the student writes in if it differs). Keep numbers as digits.
"""

LANGUAGES = {"en": "English", "hi": "Hindi (Devanagari script)", "ta": "Tamil", "te": "Telugu", "bn": "Bengali", "mr": "Marathi"}

PLANNER_SYSTEM = """You are FitAgent's training planner. Design a 7-day plan for a university student.

Rules that WILL be checked automatically:
1. Exactly 7 sessions, one per day, in this order: {order}.
2. At least one REST day (type REST, intensity rest, 0 minutes).
3. Non-rest sessions are 10–120 minutes. Never two "hard" days in a row. Max 3 hard days.
4. Total minutes ≤ {cap} (safe progression from last week's {last_week} min).
5. {recover_rule}
{schedule_rules}
Make it realistic for a student schedule and varied (mix strength, cardio and mobility), aligned to the
goal of {weekly_goal} workouts/week of about {session_goal} min. Each "focus" is one short, specific instruction.
"""

PLANNER_USER = """Athlete data:
- Readiness {score}/100 ({band}); reasons: {reasons}
- Last 7 days: {workouts} workouts, {week_minutes} min, types: {types}; avg {avg_steps:,} steps/day
- Profile: {profile}
{feedback}"""


def _profile_line(ctx: UserContext) -> str:
    p = ctx.profile
    parts = [f"age {p.age}" if p.age else None, f"{p.height_cm} cm" if p.height_cm else None,
             f"{p.weight_kg:g} kg" if p.weight_kg else None]
    return ", ".join(x for x in parts if x) or "not provided"


def coach_system_prompt(ctx: UserContext, r: Readiness) -> str:
    today = ctx.day(ctx.today)
    week = last_7_days(ctx)
    g = ctx.profile.goals
    return COACH_SYSTEM.format(
        today=ctx.today.isoformat(),
        weekday=ctx.today.strftime("%A"),
        name=ctx.profile.display_name or "a student",
        score=r.score,
        band=r.band.value.lower(),
        reasons="; ".join(r.reasons) or "limited data",
        steps=today.steps if today else 0,
        step_goal=g.daily_steps,
        workout_min=today.workout_minutes if today else 0,
        workout_goal=g.daily_workout_minutes,
        workouts=week.workouts,
        week_minutes=week.workout_minutes,
        avg_steps=week.avg_steps,
        streak=current_streak(ctx),
        profile=_profile_line(ctx),
        language=LANGUAGES.get(ctx.profile.language, "English"),
    )


def planner_prompts(
    ctx: UserContext, r: Readiness, *, order: list[str], cap: int, violations: list[str]
) -> tuple[str, str]:
    week = last_7_days(ctx)
    g = ctx.profile.goals
    recover_rule = (
        f"Readiness is LOW: {order[0]} and {order[1]} must be rest or easy."
        if r.band.value == "RECOVER" else "Use readiness to set this week's intensity."
    )
    system = PLANNER_SYSTEM.format(
        order=", ".join(order), cap=cap, last_week=week.workout_minutes, recover_rule=recover_rule,
        weekly_goal=g.weekly_workouts, session_goal=g.daily_workout_minutes,
        schedule_rules=_schedule_rules(ctx),
    )
    feedback = (
        "\nYour previous draft broke these rules — fix every one:\n" + "\n".join(f"- {v}" for v in violations)
        if violations else ""
    )
    user = PLANNER_USER.format(
        score=r.score, band=r.band.value.lower(), reasons="; ".join(r.reasons) or "limited data",
        workouts=week.workouts, week_minutes=week.workout_minutes, types=", ".join(week.types) or "none",
        avg_steps=week.avg_steps, profile=_profile_line(ctx), feedback=feedback,
    )
    return system, user


def _schedule_rules(ctx: UserContext) -> str:
    """Exam mode / timetable rules for the planner prompt (only free time and exam dates — never course names)."""
    sched = ctx.schedule
    if sched is None:
        return ""
    lines = []
    if sched.free_slots:
        by_day: dict[str, list[str]] = {}
        for s in sched.free_slots:
            by_day.setdefault(s.day, []).append(f"{s.start}–{s.end}")
        slots = "; ".join(f"{d} {', '.join(v)}" for d, v in by_day.items())
        lines.append(f"6. Every non-rest session needs a start_time (HH:MM) so it fits inside a free slot: {slots}.")
    if sched.bedtime:
        lines.append(f"7. Sessions must end at least 2 hours before bedtime ({sched.bedtime}) to protect sleep.")
    days = [ctx.today + timedelta(days=i) for i in range(7)]
    exams = [d for d in days if sched.is_exam(d)]
    if exams:
        names = ", ".join(f"{d:%a} {d.isoformat()}" for d in exams)
        lines.append(f"8. EXAM WEEK (exams: {names}). Every session ≤ 30 min. Exam days: rest or one easy ≤ 30 min "
                     "session. No hard session the day before an exam. Favour short walks and mobility between study blocks.")
    return "\n".join(lines) + ("\n" if lines else "")
