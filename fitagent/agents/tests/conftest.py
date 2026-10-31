from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import Any

import pytest
from fitagent_agents.data import ActivityDay, Goals, Profile, UserContext, WorkoutRecord
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.runnables import RunnableLambda

TODAY = date(2026, 10, 28)  # a Wednesday


def make_ctx(
    *,
    today: date = TODAY,
    sleep_today: int | None = 7 * 60 + 40,
    rhr_today: int | None = 58,
    rhr_baseline: int = 58,
    steps_today: int = 4200,
    workouts: list[WorkoutRecord] | None = None,
    goals: Goals | None = None,
    history_days: int = 21,
) -> UserContext:
    days = []
    for i in range(history_days, 0, -1):
        d = today - timedelta(days=i)
        days.append(ActivityDay(day=d, steps=9000 if i % 3 else 5000, distance_m=6500, active_kcal=380,
                                workout_minutes=30 if i % 2 == 0 else 0, resting_hr=rhr_baseline,
                                sleep_minutes=450))
    days.append(ActivityDay(day=today, steps=steps_today, distance_m=3000, active_kcal=150,
                            resting_hr=rhr_today, sleep_minutes=sleep_today))
    if workouts is None:
        workouts = [
            WorkoutRecord(type=t, start=datetime.combine(today - timedelta(days=i), datetime.min.time()).replace(hour=18),
                          active_minutes=30, kcal=250)
            for i, t in [(2, "STRENGTH"), (4, "RUN"), (6, "YOGA"), (9, "WALK"), (12, "STRENGTH"), (15, "CYCLE"),
                         (18, "RUN"), (20, "STRENGTH")]
        ]
    return UserContext(today=today, profile=Profile(display_name="Asha", age=20, height_cm=165, weight_kg=58,
                                                    goals=goals or Goals()), days=days, workouts=workouts)


@pytest.fixture
def ctx() -> UserContext:
    return make_ctx()


class ToolCallingFake(GenericFakeChatModel):
    """GenericFakeChatModel that accepts bind_tools (returns itself) so scripted tool calls flow through."""

    def bind_tools(self, tools: Any, **kwargs: Any):  # type: ignore[override]
        return self


class StructuredFake(GenericFakeChatModel):
    """Fake model whose with_structured_output yields scripted objects (or raises them if exceptions)."""

    outputs: list[Any]

    def with_structured_output(self, schema: Any, **kwargs: Any):  # type: ignore[override]
        queue = list(self.outputs)

        def next_output(_: Any) -> Any:
            item = queue.pop(0)
            if isinstance(item, Exception):
                raise item
            return item

        return RunnableLambda(next_output)
