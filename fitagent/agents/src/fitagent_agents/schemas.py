"""Structured outputs produced by the agents."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

Weekday = Literal["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
WEEKDAYS: tuple[Weekday, ...] = ("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
SessionType = Literal["WALK", "RUN", "CYCLE", "STRENGTH", "HIIT", "YOGA", "SWIM", "SPORTS", "DANCE", "REST"]
Intensity = Literal["rest", "easy", "moderate", "hard"]


class PlannedSession(BaseModel):
    day: Weekday
    type: SessionType
    minutes: int = Field(ge=0, le=120, description="Active minutes; 0 for REST")
    intensity: Intensity
    focus: str = Field(max_length=140, description="One-line instruction, e.g. '5×3 min brisk intervals'")
    start_time: str | None = Field(
        None, pattern=r"^([01]\d|2[0-3]):[0-5]\d$",
        description="HH:MM start inside one of the user's free slots for that day; null for REST or when no timetable",
    )


class WeeklyPlan(BaseModel):
    """A 7-day training plan, Monday first."""

    summary: str = Field(max_length=400, description="Two sentences: the week's goal and why it fits this user")
    sessions: list[PlannedSession] = Field(min_length=7, max_length=7)

    @property
    def total_minutes(self) -> int:
        return sum(s.minutes for s in self.sessions)
