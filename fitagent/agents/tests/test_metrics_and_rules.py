from datetime import datetime, timedelta

import pytest
from fitagent_agents.data import ActivityDay, Goals, WorkoutRecord
from fitagent_agents.metrics import Band, acute_chronic_ratio, band_for, compute_readiness, current_streak
from fitagent_agents.rules import fallback_plan, suggest_today, weekday_of
from fitagent_agents.safety import check_message, validate_plan
from fitagent_agents.schemas import PlannedSession, WeeklyPlan

from .conftest import TODAY, make_ctx


def test_band_thresholds():
    assert band_for(67) is Band.PUSH
    assert band_for(66) is Band.STEADY
    assert band_for(34) is Band.STEADY
    assert band_for(33) is Band.RECOVER


def test_well_rested_athlete_is_ready(ctx):
    r = compute_readiness(ctx)
    assert r.band is Band.PUSH
    assert set(r.components) == {"sleep", "resting_hr", "load"}


def test_short_sleep_and_high_rhr_means_recover():
    r = compute_readiness(make_ctx(sleep_today=270, rhr_today=66, rhr_baseline=58))
    assert r.band is Band.RECOVER
    assert any("above" in reason for reason in r.reasons)


def test_readiness_without_wearable_uses_load_only():
    r = compute_readiness(make_ctx(sleep_today=None, rhr_today=None))
    assert set(r.components) == {"load"}
    assert r.score == r.components["load"]


def test_no_data_at_all_is_a_steady_day():
    r = compute_readiness(make_ctx(sleep_today=None, rhr_today=None, history_days=3, workouts=[]))
    assert r.score == 60 and r.band is Band.STEADY


def test_unknown_load_does_not_inflate_poor_recovery():
    r = compute_readiness(make_ctx(sleep_today=250, rhr_today=67, rhr_baseline=58, workouts=[]))
    assert "load" not in r.components and r.band is Band.RECOVER


def test_new_user_gets_no_false_load_spike():
    start = datetime.combine(TODAY, datetime.min.time())
    c = make_ctx(history_days=5, workouts=[WorkoutRecord("HIIT", start - timedelta(days=i), 45) for i in range(4)])
    assert compute_readiness(c).acwr is None


def test_acwr_spike_detected():
    start = datetime.combine(TODAY, datetime.min.time())
    spike = [WorkoutRecord("HIIT", start - timedelta(days=i), 90) for i in range(5)]
    old = [WorkoutRecord("WALK", start - timedelta(days=20), 20)]
    assert acute_chronic_ratio(spike + old, TODAY) > 1.5
    assert acute_chronic_ratio([], TODAY) is None
    assert acute_chronic_ratio(spike + old, TODAY, history_days=7) is None


def test_streak_freeze_absorbs_one_miss_per_week():
    goals = Goals(daily_steps=8000, daily_workout_minutes=30)
    # Wed..Sun before TODAY: Sun, Sat, Fri met; Thu missed; Wed met; Tue missed -> second miss same week? no:
    # Thu (week 44) is frozen, Tue (week 44) breaks it.
    pattern = {1: 9000, 2: 9000, 3: 9000, 4: 0, 5: 9000, 6: 0, 7: 9000}
    days = [ActivityDay(TODAY - timedelta(days=i), steps=s) for i, s in pattern.items()]
    days.append(ActivityDay(TODAY, steps=100))
    c = make_ctx(goals=goals)
    c = type(c)(today=TODAY, profile=c.profile, days=days, workouts=[])
    assert current_streak(c) == 4  # Tue(TODAY-1), Mon, Sun, (Sat frozen), Fri


def test_unfinished_today_does_not_break_streak(ctx):
    assert current_streak(ctx) > 0


@pytest.mark.parametrize("weekly", [1, 2, 3, 4, 5, 6, 7])
@pytest.mark.parametrize("sleep,rhr", [(480, 58), (420, 61), (240, 70)])
@pytest.mark.parametrize("offset", range(7))
def test_fallback_plan_always_valid(weekly, sleep, rhr, offset):
    c = make_ctx(today=TODAY + timedelta(days=offset), sleep_today=sleep, rhr_today=rhr,
                 goals=Goals(weekly_workouts=weekly, daily_workout_minutes=45))
    r = compute_readiness(c)
    plan = fallback_plan(c, r)
    assert validate_plan(plan, band=r.band, last_week_minutes=90, start=weekday_of(c)) == []
    assert plan.sessions[0].day == weekday_of(c)


def _plan(spec: list[tuple[str, str, int, str]]) -> WeeklyPlan:
    return WeeklyPlan(summary="x", sessions=[PlannedSession(day=d, type=t, minutes=m, intensity=i, focus="f")
                                             for d, t, m, i in spec])


def test_validator_catches_unsafe_plan():
    plan = _plan([("MON", "HIIT", 60, "hard"), ("TUE", "RUN", 60, "hard"), ("WED", "HIIT", 60, "hard"),
                  ("THU", "RUN", 60, "hard"), ("FRI", "STRENGTH", 60, "moderate"),
                  ("SAT", "CYCLE", 60, "moderate"), ("SUN", "WALK", 60, "easy")])
    v = validate_plan(plan, band=Band.RECOVER, last_week_minutes=100)
    joined = " ".join(v)
    assert "REST" in joined and "both hard" in joined and "too many" in joined
    assert "progression cap" in joined and "Readiness is low" in joined


def test_suggestion_respects_readiness():
    low = make_ctx(sleep_today=240, rhr_today=70)
    assert suggest_today(low, compute_readiness(low)).type == "YOGA"
    high = make_ctx()
    assert suggest_today(high, compute_readiness(high)).type == "HIIT"


@pytest.mark.parametrize("text,category", [
    ("I get chest pain when I run", "emergency"),
    ("I fainted after leg day", "emergency"),
    ("how do I starve myself to lose weight fast", "eating_disorder"),
    ("my ankle is swollen after football", "injury"),
    ("I want to end my life", "self_harm"),
])
def test_red_flags(text, category):
    flag = check_message(text)
    assert flag is not None and flag.category == category


@pytest.mark.parametrize("text", ["give me a leg workout", "is 8000 steps enough?", "I feel tired after exams"])
def test_normal_messages_pass(text):
    assert check_message(text) is None
