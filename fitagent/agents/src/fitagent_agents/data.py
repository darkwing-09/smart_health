"""Data contracts between the agents and whoever stores user data (the backend, tests, evals).

Agents never query a database directly. The backend implements :class:`DataProvider`, and every
tool is bound to a single user id, so a model can never read another user's data.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Protocol

WORKOUT_TYPES = ("WALK", "RUN", "CYCLE", "STRENGTH", "HIIT", "YOGA", "SWIM", "SPORTS", "DANCE", "OTHER")
HARD_TYPES = frozenset({"RUN", "HIIT", "SWIM", "SPORTS"})


@dataclass(frozen=True)
class Goals:
    daily_steps: int = 8000
    daily_active_kcal: int = 400
    daily_workout_minutes: int = 30
    weekly_workouts: int = 4


@dataclass(frozen=True)
class Profile:
    display_name: str = ""
    age: int | None = None
    height_cm: int | None = None
    weight_kg: float | None = None
    goals: Goals = field(default_factory=Goals)


@dataclass(frozen=True)
class ActivityDay:
    day: date
    steps: int = 0
    distance_m: float = 0.0
    active_kcal: int = 0
    workout_minutes: int = 0
    resting_hr: int | None = None
    avg_hr: int | None = None
    sleep_minutes: int | None = None
    # Watch extras (Phase 2). Any may be missing: most budget watches report only some of them.
    sleep_deep_min: int | None = None
    sleep_rem_min: int | None = None
    sleep_light_min: int | None = None
    sleep_awake_min: int | None = None
    hrv_ms: float | None = None
    spo2_avg: float | None = None
    resp_rate: float | None = None
    skin_temp_delta: float | None = None
    vo2max: float | None = None
    hr_min: int | None = None
    hr_max: int | None = None

    @property
    def has_stages(self) -> bool:
        return any(v for v in (self.sleep_deep_min, self.sleep_rem_min, self.sleep_light_min))


@dataclass(frozen=True)
class WorkoutRecord:
    type: str
    start: datetime
    active_minutes: int
    kcal: float = 0.0
    title: str | None = None

    @property
    def is_hard(self) -> bool:
        return self.type in HARD_TYPES or self.active_minutes >= 60


@dataclass(frozen=True)
class ChallengeStanding:
    name: str
    metric: str
    rank: int
    participants: int
    my_value: float
    leader_value: float
    ends_on: date


@dataclass(frozen=True)
class UserContext:
    """Everything an agent may know about one user, as of [today]."""

    today: date
    profile: Profile
    days: list[ActivityDay]
    workouts: list[WorkoutRecord]
    challenges: list[ChallengeStanding] = field(default_factory=list)

    def day(self, d: date) -> ActivityDay | None:
        return next((x for x in self.days if x.day == d), None)


class DataProvider(Protocol):
    async def get_context(self, user_id: str) -> UserContext: ...


class InMemoryProvider:
    """Provider backed by a dict — used by tests, evals and local demos."""

    def __init__(self, contexts: dict[str, UserContext]):
        self._contexts = contexts

    async def get_context(self, user_id: str) -> UserContext:
        try:
            return self._contexts[user_id]
        except KeyError as exc:
            raise LookupError(f"unknown user {user_id}") from exc
