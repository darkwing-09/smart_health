"""Exam mode: timetable-aware plans, easy exam days, short exam weeks, sleep protected."""

from dataclasses import replace
from datetime import timedelta

import pytest
from fitagent_agents.metrics import Band, compute_readiness
from fitagent_agents.rules import fallback_plan, weekday_of
from fitagent_agents.safety import validate_plan
from fitagent_agents.schedule import FreeSlot, Schedule
from fitagent_agents.schemas import WEEKDAYS, PlannedSession, WeeklyPlan

from .conftest import TODAY, make_ctx

SLOTS = [FreeSlot(d, "17:00", "18:30") for d in WEEKDAYS] + [FreeSlot("SAT", "09:00", "12:00")]


def _validate(ctx, plan):
    return validate_plan(plan, band=compute_readiness(ctx).band, last_week_minutes=200, start=weekday_of(ctx),
                         schedule=ctx.schedule, start_date=ctx.today)


def _plan(ctx, **overrides) -> WeeklyPlan:
    order = [WEEKDAYS[(TODAY.weekday() + i) % 7] for i in range(7)]
    sessions = [PlannedSession(day=d, type="REST", minutes=0, intensity="rest", focus="Rest") for d in order]
    for i, s in overrides.items():
        sessions[int(i)] = replace_session(sessions[int(i)], **s)
    return WeeklyPlan(summary="test", sessions=sessions)


def replace_session(s: PlannedSession, **kw) -> PlannedSession:
    return s.model_copy(update=kw)


@pytest.mark.parametrize("exam_offset", [None, 0, 1, 3, 6])
@pytest.mark.parametrize("bedtime", [None, "23:00", "00:30"])
@pytest.mark.parametrize("weekly", [2, 4, 6])
def test_fallback_plan_always_passes_with_a_timetable(exam_offset, bedtime, weekly):
    from fitagent_agents.data import Goals

    exams = [] if exam_offset is None else [TODAY + timedelta(days=exam_offset)]
    ctx = replace(make_ctx(goals=Goals(weekly_workouts=weekly)), schedule=Schedule(SLOTS, exams, bedtime))
    plan = fallback_plan(ctx, compute_readiness(ctx))
    assert _validate(ctx, plan) == []
    assert all(s.start_time for s in plan.sessions if s.type != "REST")


def test_exam_week_keeps_sessions_short_and_exam_day_easy():
    ctx = replace(make_ctx(), schedule=Schedule(SLOTS, [TODAY + timedelta(days=2)], "23:00"))
    plan = fallback_plan(ctx, compute_readiness(ctx))
    assert all(s.minutes <= 30 for s in plan.sessions)
    exam_day = plan.sessions[2]
    assert exam_day.intensity in ("rest", "easy")
    assert "Exam week" in plan.summary


def test_validator_catches_exam_and_slot_violations():
    ctx = replace(make_ctx(), schedule=Schedule(SLOTS, [TODAY + timedelta(days=1)], "23:00"))
    plan = _plan(ctx, **{
        "0": {"type": "HIIT", "minutes": 25, "intensity": "hard", "start_time": "17:00"},   # hard before exam
        "1": {"type": "RUN", "minutes": 40, "intensity": "moderate", "start_time": "17:00"},  # exam day, too long
        "3": {"type": "WALK", "minutes": 20, "intensity": "easy", "start_time": "13:00"},     # outside free slot
        "4": {"type": "WALK", "minutes": 20, "intensity": "easy"},                           # no start time
    })
    v = " | ".join(_validate(ctx, plan))
    assert "day before an exam" in v
    assert "exam day" in v
    assert "doesn't fit a free slot" in v
    assert "give a start_time" in v
    assert "exam week" in v


def test_late_sessions_too_close_to_bedtime_are_rejected():
    ctx = replace(make_ctx(), schedule=Schedule([FreeSlot(d, "19:00", "22:30") for d in WEEKDAYS], [], "23:00"))
    plan = _plan(ctx, **{"0": {"type": "WALK", "minutes": 30, "intensity": "easy", "start_time": "21:00"}})
    assert any("bedtime" in v for v in _validate(ctx, plan))
    ok = _plan(ctx, **{"0": {"type": "WALK", "minutes": 30, "intensity": "easy", "start_time": "19:00"}})
    assert _validate(ctx, ok) == []


def test_busy_day_without_a_gap_becomes_rest_or_a_snack():
    slots = [FreeSlot(d, "17:00", "18:30") for d in WEEKDAYS if d != WEEKDAYS[TODAY.weekday()]]
    slots.append(FreeSlot(WEEKDAYS[TODAY.weekday()], "12:50", "13:00"))  # 10-minute gap only
    ctx = replace(make_ctx(), schedule=Schedule(slots, [], None))
    plan = fallback_plan(ctx, compute_readiness(ctx))
    first = plan.sessions[0]
    assert first.type in ("REST", "WALK") and first.minutes <= 10
    assert _validate(ctx, plan) == []


def test_without_schedule_rules_are_unchanged():
    ctx = make_ctx()
    plan = fallback_plan(ctx, compute_readiness(ctx))
    assert all(s.start_time is None for s in plan.sessions)
    assert validate_plan(plan, band=Band.STEADY, last_week_minutes=200, start=weekday_of(ctx)) == []



@pytest.mark.asyncio
async def test_planner_graph_repairs_a_draft_that_ignores_the_exam():
    from fitagent_agents.data import InMemoryProvider
    from fitagent_agents.graphs.planner import build_planner_graph, run_planner

    from .conftest import StructuredFake

    ctx = replace(make_ctx(), schedule=Schedule(SLOTS, [TODAY + timedelta(days=1)], "23:00"))
    bad = _plan(ctx, **{"1": {"type": "RUN", "minutes": 45, "intensity": "hard", "start_time": "17:00"}})
    good = _plan(ctx, **{"1": {"type": "WALK", "minutes": 15, "intensity": "easy", "start_time": "17:00"},
                         "3": {"type": "STRENGTH", "minutes": 25, "intensity": "moderate", "start_time": "17:00"}})
    llm = StructuredFake(messages=iter([]), outputs=[bad, good])
    result = await run_planner(build_planner_graph(InMemoryProvider({"u": ctx}), llm), "u")
    assert result.source == "llm" and result.attempts == 2
    assert result.plan.sessions[1].intensity == "easy"
