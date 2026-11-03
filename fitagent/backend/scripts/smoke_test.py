"""End-to-end smoke test against a running FitAgent API (e.g. `docker compose up`).

    uv run python backend/scripts/smoke_test.py http://localhost:8000

Walks the same flow the Android app does: register → sync a month of activity (with watch metrics) → coach
chat (English + Hindi) → Exam mode plan → challenge, kudos, campus board → rewards → food guard → invite page →
delete accounts. Exits non-zero on failure. Phase 2 checks exercise the Postgres-only code paths (e.g. the
rewards ledger upsert) that the SQLite unit tests can't.
"""

from __future__ import annotations

import sys
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import httpx

TZ = ZoneInfo("Asia/Kolkata")


def check(cond: bool, what: str) -> None:
    print(("  ✓ " if cond else "  ✗ ") + what)
    if not cond:
        sys.exit(1)


def register(c: httpx.Client, name: str) -> dict[str, str]:
    email = f"{name.lower()}-{uuid.uuid4().hex[:8]}@fitagent-smoke.dev"
    r = c.post("/v1/auth/register", json={"email": email, "password": "smoke-test-pass-1", "display_name": name,
                                          "timezone": "Asia/Kolkata"})
    check(r.status_code == 201, f"register {name} ({r.status_code})")
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


def month_of_activity(base_steps: int) -> dict:
    today = datetime.now(TZ).date()
    days, workouts = [], []
    for i in range(28):
        d = today - timedelta(days=i)
        days.append({"date": d.isoformat(), "steps": base_steps + (i % 5) * 900, "distance_m": base_steps * 0.75,
                     "active_kcal": base_steps // 25, "workout_minutes": 30 if i % 2 == 0 else 0,
                     "resting_hr": 58 + i % 3, "sleep_minutes": 440 + (i % 3) * 15,
                     # watch metrics as a Noise/boAt app shares them via Health Connect
                     "hrv_ms": 55 + i % 7, "spo2_avg": 96.5, "sleep_deep_min": 80, "sleep_rem_min": 95,
                     "sleep_light_min": 250, "sleep_awake_min": 20, "steps_source": "com.noisefit"})
        if i % 2 == 0:
            start = datetime.combine(d, datetime.min.time(), TZ).replace(hour=18)
            workouts.append({"client_id": f"app:{uuid.uuid4()}", "type": ["RUN", "STRENGTH", "YOGA", "HIIT"][i % 4],
                             "start": start.isoformat(), "end": (start + timedelta(minutes=32)).isoformat(),
                             "active_seconds": 1800, "kcal": 250, "source": "APP"})
    return {"timezone": "Asia/Kolkata", "days": days, "workouts": workouts, "deleted_workout_ids": []}


def main(base: str) -> None:
    with httpx.Client(base_url=base, timeout=90) as c:
        print(f"FitAgent smoke test → {base}")
        health = c.get("/health").json()
        check(health["status"] == "ok", f"health ok (coach mode: {health['ai_coach']}, tracing: {health['tracing']})")

        asha, bilal = register(c, "Asha"), register(c, "Bilal")
        check(c.get("/v1/me", headers={"Authorization": "Bearer dev_token"}).status_code == 401,
              "fake tokens are rejected")

        r = c.post("/v1/me/sync", headers=asha, json=month_of_activity(8000))
        check(r.status_code == 200 and r.json()["days_upserted"] == 28, "sync 28 days + 14 workouts")
        c.post("/v1/me/sync", headers=bilal, json=month_of_activity(11000))

        r = c.post("/v1/coach/chat", headers=asha, json={"message": "What should I do today? I have an exam tomorrow."})
        check(r.status_code == 200 and len(r.json()["reply"]) > 20,
              f"coach reply (ai_powered={r.json().get('ai_powered')}, tools={r.json().get('tools_used')})")
        print("    " + r.json()["reply"].replace("\n", "\n    ")[:600])

        r = c.post("/v1/coach/chat", headers=asha, json={"message": "I get chest pain when I sprint"})
        check(r.json()["safety"] == "emergency", "red-flag message routed to safety response")

        r = c.post("/v1/coach/plan", headers=asha)
        plan = r.json()
        check(r.status_code == 201 and len(plan["plan"]["sessions"]) == 7,
              f"weekly plan (source={plan.get('source')}, readiness={plan.get('readiness', {}).get('score')})")
        for s in plan["plan"]["sessions"]:
            print(f"    {s['day']}  {s['type']:<9} {s['minutes']:>3} min  {s['intensity']:<8} {s['focus'][:60]}")

        r = c.post("/v1/challenges", headers=asha, json={"name": "Smoke Step-Off", "metric": "steps", "days": 7})
        check(r.status_code == 201, f"create challenge (code {r.json().get('code')})")
        r = c.post("/v1/challenges/join", headers=bilal, json={"code": r.json()["code"]})
        board = r.json()["leaderboard"]
        check(r.status_code == 200 and board[0]["display_name"] == "Bilal", "join + leaderboard ranks by steps")

        phase2(c, asha, bilal, r.json())

        for h in (asha, bilal):
            check(c.delete("/v1/me", headers=h).status_code == 204, "delete account")
        print("All checks passed.")


def phase2(c: httpx.Client, asha: dict, bilal: dict, challenge: dict) -> None:
    print("Phase 2")
    status = c.get("/v1/me/sync/status", headers=asha).json()
    check(status["days_stored"] == 28, "sync status reports 28 days")
    day = c.get("/v1/me/activity?days=1", headers=asha).json()[-1]
    check(day["hrv_ms"] is not None and day["steps_source"] == "com.noisefit", "watch metrics stored (HRV, sleep stages, source)")

    days = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
    exam = (datetime.now(TZ).date() + timedelta(days=1)).isoformat()
    c.put("/v1/me/schedule", headers=asha, json={"free_slots": [{"day": d, "start": "17:00", "end": "18:30"} for d in days],
                                                 "exam_dates": [exam], "bedtime": "23:00"})
    plan = c.post("/v1/coach/plan", headers=asha).json()["plan"]["sessions"]
    check(all(s["minutes"] <= 30 for s in plan) and plan[1]["intensity"] in ("rest", "easy"), "Exam mode: short week, easy exam day")

    c.patch("/v1/me", headers=asha, json={"language": "hi"})
    r = c.post("/v1/coach/chat", headers=asha, json={"message": "आज मुझे क्या करना चाहिए?"}).json()
    check(any("\u0900" <= ch <= "\u097f" for ch in r["reply"]), "Hindi coach reply")

    board = c.get(f"/v1/challenges/{challenge['id']}", headers=asha).json()["leaderboard"]
    bilal_id = next(row["user_id"] for row in board if not row["is_me"])
    r = c.post(f"/v1/challenges/{challenge['id']}/kudos", headers=asha, json={"to_user_id": bilal_id, "emoji": "🔥"})
    check(r.status_code == 200 and "🔥" in next(x for x in r.json()["leaderboard"] if x["user_id"] == bilal_id)["kudos"], "kudos")

    c.patch("/v1/me", headers=asha, json={"hostel": "Smoke Hostel", "college": "Smoke College"})
    check(c.get("/v1/campus/leaderboard?scope=hostel", headers=asha).status_code == 200, "campus leaderboard")

    first = c.get("/v1/rewards", headers=asha).json()
    again = c.get("/v1/rewards", headers=asha).json()
    check(first["balance"] > 0 and first["balance"] == again["balance"], f"rewards ledger idempotent on Postgres ({first['balance']} pts)")

    r = c.post("/v1/food/estimate", headers=asha, json={"image_base64": "/9j/4AAQ" + "A" * 200, "note": "skip meals to lose weight"})
    check(r.status_code == 200 and r.json()["safety"] == "eating_disorder", "food photo guard")

    check(challenge["code"] in c.get(f"/j/{challenge['code']}").text, "invite landing page")
    check(c.get("/v1/admin/overview", headers=asha).status_code == 403, "admin endpoints need the admin role")
    check("Campus wellness dashboard" in c.get("/admin").text, "admin dashboard page")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8000")
