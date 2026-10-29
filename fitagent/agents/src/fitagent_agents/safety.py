"""Safety rules. These run before and after every model call and cannot be overridden by a prompt."""

from __future__ import annotations

import re
from dataclasses import dataclass
from itertools import pairwise

from langsmith import traceable

from .metrics import Band
from .schemas import WEEKDAYS, Weekday, WeeklyPlan

DISCLAIMER = "FitAgent is a fitness coach, not a medical service."


@dataclass(frozen=True)
class SafetyFlag:
    category: str
    response: str


_EMERGENCY = (
    "That can be a sign of something that needs medical attention right away. Please stop exercising "
    "and contact a doctor — if symptoms are severe or sudden, call your local emergency number "
    "(112 in India / 911 in the US). I can't assess symptoms, but I'll be here to help you get back "
    "to training once you've been checked out."
)
_EATING = (
    "It sounds like food and body image might be weighing on you, and that deserves real support. "
    "Please consider talking to a doctor, your campus counsellor, or a helpline such as the NEDA "
    "Helpline (US) or Tele-MANAS 14416 (India). I won't give advice on extreme restriction or "
    "over-exercising, but I'm happy to help with balanced, enjoyable movement."
)
_SELF_HARM = (
    "I'm really sorry you're feeling this way. You deserve support right now — please reach out to "
    "someone you trust or a crisis line: Tele-MANAS 14416 or iCall +91 9152987821 (India), "
    "988 (US). If you're in immediate danger, call your local emergency number."
)
_INJURY = (
    "Pain that is sharp, swelling, or getting worse is a signal to stop that movement and have it "
    "looked at by a doctor or physiotherapist. I can suggest training that avoids the area once "
    "you've been cleared."
)

_RULES: list[tuple[str, re.Pattern[str], str]] = [
    ("self_harm", re.compile(r"\b(kill myself|suicid\w*|self[- ]harm|end my life|want to die)\b", re.IGNORECASE), _SELF_HARM),
    (
        "emergency",
        re.compile(
            r"\b(chest (pain|tight\w*|pressure)|faint(ed|ing)?|passed out|blacked out|can'?t breathe|"
            r"shortness of breath|heart (racing|palpitations?)|irregular heartbeat|numbness in my arm)\b",
            re.IGNORECASE,
        ),
        _EMERGENCY,
    ),
    (
        "eating_disorder",
        re.compile(
            r"\b(purg(e|ing)|throw(ing)? up after eat\w*|starv(e|ing) myself|not eat(ing)? for \d+ days|"
            r"under \d{3} (calories|kcal) a day|laxatives?|burn off everything i ate)\b",
            re.IGNORECASE,
        ),
        _EATING,
    ),
    (
        "injury",
        re.compile(r"\b(sharp pain|swollen|swelling|torn|fracture|sprain\w*|pop(ped)? in my (knee|ankle))\b", re.IGNORECASE),
        _INJURY,
    ),
]


@traceable(name="safety_check", run_type="chain")
def check_message(text: str) -> SafetyFlag | None:
    for category, pattern, response in _RULES:
        if pattern.search(text):
            return SafetyFlag(category, response)
    return None


def weekly_minutes_cap(last_week_minutes: int) -> int:
    """Progressive overload: at most +15% on last week, with a floor of the WHO 150-minute guideline."""
    return max(round(last_week_minutes * 1.15), 150)


def week_order(start: Weekday) -> list[Weekday]:
    i = WEEKDAYS.index(start)
    return list(WEEKDAYS[i:] + WEEKDAYS[:i])


@traceable(name="validate_plan", run_type="chain")
def validate_plan(plan: WeeklyPlan, *, band: Band, last_week_minutes: int, start: Weekday = "MON") -> list[str]:
    """Returns human-readable rule violations; empty means the plan is safe to show.
    The plan covers 7 consecutive days beginning on [start]."""
    violations: list[str] = []
    order = week_order(start)
    if sorted(s.day for s in plan.sessions) != sorted(order):
        violations.append("Plan must contain exactly one entry for each day of the week.")

    by_day = {s.day: s for s in plan.sessions}
    ordered = [by_day[d] for d in order if d in by_day]

    if not any(s.type == "REST" or s.intensity == "rest" for s in ordered):
        violations.append("Include at least one full REST day.")
    for s in ordered:
        if s.type == "REST" and s.minutes != 0:
            violations.append(f"{s.day}: REST days must have 0 minutes.")
        if s.type != "REST" and not 10 <= s.minutes <= 120:
            violations.append(f"{s.day}: sessions must be 10–120 minutes.")
    for a, b in pairwise(ordered):
        if a.intensity == "hard" and b.intensity == "hard":
            violations.append(f"{a.day} and {b.day} are both hard — put an easier day between hard sessions.")

    hard_days = sum(1 for s in ordered if s.intensity == "hard")
    if hard_days > 3:
        violations.append(f"{hard_days} hard sessions is too many; max 3 per week.")

    cap = weekly_minutes_cap(last_week_minutes)
    if plan.total_minutes > cap:
        violations.append(
            f"Total {plan.total_minutes} min exceeds the safe progression cap of {cap} min "
            f"(last week {last_week_minutes} min + 15%)."
        )

    if band is Band.RECOVER:
        early = [s for s in ordered[:2] if s.intensity in ("moderate", "hard")]
        if early:
            violations.append("Readiness is low: the first two days must be rest or easy.")
    return violations
