"""Generates readiness_v2.json — shared test cases for the Kotlin and Python readiness implementations.

Run from fitagent/:  uv run python fixtures/generate_readiness_fixtures.py
The Python implementation produces the expected values; Kotlin's DomainTest must reproduce them exactly.
Hand-checked expectations for the important behaviours live in each language's own unit tests.
"""

from __future__ import annotations

import json
import random
from dataclasses import asdict
from datetime import date, datetime, time, timedelta
from pathlib import Path

from fitagent_agents.data import ActivityDay, Profile, UserContext, WorkoutRecord
from fitagent_agents.metrics import compute_readiness

TODAY = date(2026, 10, 30)
OUT = Path(__file__).with_name("readiness_v2.json")


def day(offset: int, **kw) -> ActivityDay:
    return ActivityDay(day=TODAY - timedelta(days=offset), **kw)


def workouts(pattern: list[tuple[int, int]]) -> list[WorkoutRecord]:
    return [WorkoutRecord(type="RUN", start=datetime.combine(TODAY - timedelta(days=o), time(7, 0)), active_minutes=m)
            for o, m in pattern]


def history(n: int, rng: random.Random, *, hrv=True, rhr=True, resp=False, stages=False) -> list[ActivityDay]:
    out = []
    for o in range(n, 0, -1):
        out.append(day(
            o,
            steps=rng.randint(4000, 11000),
            sleep_minutes=rng.randint(380, 500),
            resting_hr=rng.randint(55, 62) if rhr else None,
            hrv_ms=round(rng.uniform(45, 75), 1) if hrv else None,
            resp_rate=round(rng.uniform(13, 16), 1) if resp else None,
            sleep_deep_min=rng.randint(50, 100) if stages else None,
            sleep_rem_min=rng.randint(70, 120) if stages else None,
            sleep_light_min=rng.randint(200, 260) if stages else None,
            sleep_awake_min=rng.randint(10, 40) if stages else None,
        ))
    return out


def cases() -> list[tuple[str, list[ActivityDay], list[tuple[int, int]]]]:
    rng = random.Random(7)
    steady = [(o, 35) for o in range(1, 28, 2)]
    spike = [(o, 35) for o in range(8, 28, 3)] + [(o, 70) for o in range(7)]
    out: list[tuple[str, list[ActivityDay], list[tuple[int, int]]]] = [
        ("no data", [], []),
        ("sleep only, 8h", [day(0, sleep_minutes=480)], []),
        ("sleep only, 5h", [day(0, sleep_minutes=300)], []),
        ("very short sleep is capped", history(14, rng) + [day(0, sleep_minutes=200, resting_hr=56, hrv_ms=70)], steady),
        ("sleep with stages", [day(0, sleep_minutes=450, sleep_deep_min=80, sleep_rem_min=100, sleep_light_min=270, sleep_awake_min=20)], []),
        ("poor stages", [day(0, sleep_minutes=420, sleep_deep_min=20, sleep_rem_min=40, sleep_light_min=360, sleep_awake_min=70)], []),
        ("rhr with 3 baseline days", [day(3, resting_hr=57), day(2, resting_hr=58), day(1, resting_hr=59), day(0, resting_hr=60, sleep_minutes=430)], []),
        ("rhr with 2 baseline days is ignored", [day(1, resting_hr=59), day(0, resting_hr=70, sleep_minutes=430)], []),
        ("hrv needs 7 nights", history(5, rng) + [day(0, sleep_minutes=450, resting_hr=58, hrv_ms=60)], []),
        ("hrv high vs baseline", history(14, rng) + [day(0, sleep_minutes=460, resting_hr=56, hrv_ms=85)], steady),
        ("hrv low vs baseline", history(14, rng) + [day(0, sleep_minutes=460, resting_hr=58, hrv_ms=35)], steady),
        ("elevated rhr + low hrv", history(14, rng) + [day(0, sleep_minutes=400, resting_hr=68, hrv_ms=38)], steady),
        ("fever-like signals capped", history(14, rng, resp=True) +
         [day(0, sleep_minutes=470, resting_hr=66, hrv_ms=62, skin_temp_delta=1.3, resp_rate=18.5)], steady),
        ("low spo2 overnight", history(14, rng) + [day(0, sleep_minutes=450, resting_hr=58, hrv_ms=60, spo2_avg=92.5)], steady),
        ("normal nightly signals", history(14, rng, resp=True) +
         [day(0, sleep_minutes=450, resting_hr=58, hrv_ms=60, spo2_avg=97, resp_rate=14.2, skin_temp_delta=0.1)], steady),
        ("load spike", history(20, rng) + [day(0, sleep_minutes=450, resting_hr=58, hrv_ms=60)], spike),
        ("load only, history < 14 days", [], [(o, 30) for o in range(5)]),
        ("load only, steady month", [], steady),
        ("stages + all signals, good night", history(14, rng, resp=True, stages=True) +
         [day(0, sleep_minutes=490, resting_hr=54, hrv_ms=78, spo2_avg=97.5, resp_rate=13.8, skin_temp_delta=-0.2,
              sleep_deep_min=95, sleep_rem_min=115, sleep_light_min=280, sleep_awake_min=15)], steady),
        ("no today row", history(14, rng), steady),
    ]
    # Randomised mixes for broad parity coverage.
    for i in range(24):
        hist = history(rng.randint(3, 20), rng, hrv=rng.random() < 0.7, rhr=rng.random() < 0.8,
                       resp=rng.random() < 0.4, stages=rng.random() < 0.5)
        stages = rng.random() < 0.5
        today_kw = {
            "sleep_minutes": rng.choice([None, rng.randint(180, 560)]),
            "resting_hr": rng.choice([None, rng.randint(50, 72)]),
            "hrv_ms": rng.choice([None, round(rng.uniform(25, 95), 1)]),
            "spo2_avg": rng.choice([None, round(rng.uniform(90, 99), 1)]),
            "resp_rate": rng.choice([None, round(rng.uniform(12, 20), 1)]),
            "skin_temp_delta": rng.choice([None, round(rng.uniform(-0.5, 1.6), 2)]),
            "sleep_deep_min": rng.randint(10, 110) if stages else None,
            "sleep_rem_min": rng.randint(20, 130) if stages else None,
            "sleep_light_min": rng.randint(150, 320) if stages else None,
            "sleep_awake_min": rng.randint(5, 80) if stages else None,
        }
        w = rng.choice([[], steady, spike, [(o, rng.randint(10, 80)) for o in range(0, 27, rng.randint(1, 4))]])
        out.append((f"random mix {i + 1}", hist + [day(0, **today_kw)], w))
    return out


def main() -> None:
    payload = []
    for name, days, pattern in cases():
        ws = workouts(pattern)
        r = compute_readiness(UserContext(today=TODAY, profile=Profile(), days=days, workouts=ws))
        payload.append({
            "name": name,
            "today": TODAY.isoformat(),
            "days": [{k: (v.isoformat() if isinstance(v, date) else v) for k, v in asdict(d).items() if v is not None} for d in days],
            "workouts": [{"date": w.start.date().isoformat(), "minutes": w.active_minutes, "type": w.type} for w in ws],
            "expected": {"score": r.score, "band": r.band.value, "components": r.components},
        })
    OUT.write_text(json.dumps(payload, indent=1) + "\n")
    print(f"wrote {len(payload)} cases to {OUT}")


if __name__ == "__main__":
    main()
