"""Student timetable for Exam mode (docs/phase2/05-agents-and-algorithms.md §3).

Only free time and exam dates reach the agents — never course names — so the planner can fit short sessions
into gaps, go easy around exams and protect sleep.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta

from .schemas import Weekday

SLEEP_BUFFER_MIN = 120  # no session may end later than this before the target bedtime
EXAM_DAY_MAX_MIN = 30
EXAM_WEEK_MAX_MIN = 30


def to_minutes(hhmm: str) -> int:
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


def to_hhmm(minutes: int) -> str:
    return f"{minutes // 60:02d}:{minutes % 60:02d}"


@dataclass(frozen=True)
class FreeSlot:
    """A recurring free window on a weekday, e.g. MON 16:30–18:00."""

    day: Weekday
    start: str
    end: str

    @property
    def length(self) -> int:
        return to_minutes(self.end) - to_minutes(self.start)


@dataclass(frozen=True)
class Schedule:
    free_slots: list[FreeSlot] = field(default_factory=list)
    exam_dates: list[date] = field(default_factory=list)
    bedtime: str | None = None  # "23:00"

    def slots_on(self, day: Weekday) -> list[FreeSlot]:
        return sorted((s for s in self.free_slots if s.day == day), key=lambda s: to_minutes(s.start))

    def has_slots(self, day: Weekday) -> bool:
        return any(s.day == day for s in self.free_slots)

    def latest_end(self) -> int | None:
        """Latest minute-of-day a session may end (bedtime − 2 h), or None without a bedtime."""
        if not self.bedtime:
            return None
        bed = to_minutes(self.bedtime)
        if bed < 12 * 60:  # after midnight, e.g. 00:30
            bed += 24 * 60
        return bed - SLEEP_BUFFER_MIN

    def fits(self, day: Weekday, start: str, minutes: int) -> bool:
        """Does a session starting at [start] for [minutes] sit inside one free slot (and end early enough)?"""
        begin = to_minutes(start)
        end = begin + minutes
        latest = self.latest_end()
        if latest is not None and end > latest:
            return False
        if not self.has_slots(day):
            return True  # day not in the timetable: no constraint
        return any(to_minutes(s.start) <= begin and end <= to_minutes(s.end) for s in self.slots_on(day))

    def first_fit(self, day: Weekday, minutes: int) -> str | None:
        """Earliest start time on [day] that fits [minutes], respecting the sleep buffer."""
        latest = self.latest_end()
        for s in self.slots_on(day):
            end = min(to_minutes(s.end), latest) if latest is not None else to_minutes(s.end)
            if end - to_minutes(s.start) >= minutes:
                return s.start
        return None

    def is_exam(self, d: date) -> bool:
        return d in self.exam_dates

    def exam_in_window(self, start: date, days: int = 7) -> bool:
        return any(start <= e < start + timedelta(days=days) for e in self.exam_dates)
