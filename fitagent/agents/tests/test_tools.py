"""The coach's read-only tools, called the way ToolNode calls them (state injected)."""

from dataclasses import replace
from datetime import timedelta

from fitagent_agents.tools import COACH_TOOLS, get_heart_summary, get_sleep_detail, get_trends

from .conftest import TODAY, make_ctx


def call(tool, **args):
    return tool.func(**args)


def with_watch_data(ctx):
    days = [
        replace(d, hrv_ms=55 + (i % 5), sleep_deep_min=70, sleep_rem_min=95, sleep_light_min=260, sleep_awake_min=25,
                spo2_avg=96.5, vo2max=43.0 if d.day == TODAY - timedelta(days=3) else None)
        for i, d in enumerate(ctx.days)
    ]
    return replace(ctx, days=days)


def test_tools_are_registered_and_documented():
    names = {t.name for t in COACH_TOOLS}
    assert {"get_sleep_detail", "get_heart_summary", "get_trends"} <= names
    assert all(t.description for t in COACH_TOOLS)


def test_sleep_detail_includes_stages_and_quality():
    out = call(get_sleep_detail, nights=7, state={"ctx": with_watch_data(make_ctx())})
    assert out["watch_reports_stages"] is True
    assert len(out["nights"]) == 7 and 0 <= out["nights"][-1]["quality"] <= 100


def test_sleep_detail_without_stages():
    out = call(get_sleep_detail, nights=3, state={"ctx": make_ctx()})
    assert out["watch_reports_stages"] is False and "deep" not in out["nights"][0]


def test_heart_summary_has_personal_baselines():
    out = call(get_heart_summary, days=7, state={"ctx": with_watch_data(make_ctx())})
    assert out["normal_resting_hr"] == 58 and out["normal_hrv_ms"] is not None and out["vo2max"] == 43.0


def test_trends_compare_periods():
    ctx = make_ctx()
    days = [replace(d, steps=10000 if d.day > TODAY - timedelta(days=7) else 5000) for d in ctx.days]
    out = call(get_trends, metric="steps", days=7, state={"ctx": replace(ctx, days=days)})
    assert out["average"] > out["previous_average"] and out["change_percent"] > 0
    assert "error" in call(get_trends, metric="mood", days=7, state={"ctx": ctx})
