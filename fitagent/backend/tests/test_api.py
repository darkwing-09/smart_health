from __future__ import annotations

from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from app.deps import limiter

from .conftest import auth, register

IST = ZoneInfo("Asia/Kolkata")


def today() -> date:
    return datetime.now(IST).date()


def days_payload(n: int, steps: int, workout_minutes: int = 0) -> list[dict]:
    return [{"date": (today() - timedelta(days=i)).isoformat(), "steps": steps + i, "distance_m": steps * 0.75,
             "active_kcal": steps // 25, "workout_minutes": workout_minutes, "resting_hr": 60,
             "sleep_minutes": 450} for i in range(n)]


# ── Auth ────────────────────────────────────────────────────────────────────

async def test_health(client):
    r = await client.get("/health")
    assert r.status_code == 200 and r.json()["ai_coach"] == "rules"
    assert "X-Request-ID" in r.headers


async def test_register_login_refresh_flow(client):
    h = await register(client)
    me = await client.get("/v1/me", headers=auth(h))
    assert me.status_code == 200 and me.json()["display_name"] == "Asha"

    assert (await client.post("/v1/auth/register", json={"email": "ASHA@uni.edu", "password": "another-pass-1",
                                                         "display_name": "x"})).status_code == 409
    bad = await client.post("/v1/auth/login", json={"email": "asha@uni.edu", "password": "wrong-password"})
    assert bad.status_code == 401
    good = await client.post("/v1/auth/login", json={"email": "Asha@Uni.edu", "password": "correct-horse-9"})
    assert good.status_code == 200

    refreshed = await client.post("/v1/auth/refresh", json={"refresh_token": h["_refresh"]})
    assert refreshed.status_code == 200
    # an access token is not accepted as a refresh token
    assert (await client.post("/v1/auth/refresh",
                              json={"refresh_token": h["Authorization"][7:]})).status_code == 401


async def test_auth_required_and_garbage_token(client):
    assert (await client.get("/v1/me")).status_code == 401
    assert (await client.get("/v1/me", headers={"Authorization": "Bearer dev_token"})).status_code == 401


async def test_logout_all_revokes_tokens(client):
    h = await register(client)
    assert (await client.post("/v1/auth/logout-all", headers=auth(h))).status_code == 204
    assert (await client.get("/v1/me", headers=auth(h))).status_code == 401
    assert (await client.post("/v1/auth/refresh", json={"refresh_token": h["_refresh"]})).status_code == 401


async def test_weak_password_and_bad_timezone_rejected(client):
    r = await client.post("/v1/auth/register", json={"email": "a@b.co", "password": "short", "display_name": "A"})
    assert r.status_code == 422
    r = await client.post("/v1/auth/register", json={"email": "a@b.co", "password": "long-enough-1",
                                                     "display_name": "A", "timezone": "Mars/Base"})
    assert r.status_code == 422


async def test_login_rate_limited(client):
    for _ in range(10):
        await client.post("/v1/auth/login", json={"email": "x@y.co", "password": "nope-nope"})
    r = await client.post("/v1/auth/login", json={"email": "x@y.co", "password": "nope-nope"})
    assert r.status_code == 429 and "Retry-After" in r.headers


# ── Profile & sync ──────────────────────────────────────────────────────────

async def test_profile_update(client):
    h = await register(client)
    r = await client.patch("/v1/me", headers=auth(h), json={
        "age": 20, "weight_kg": 58.5, "goals": {"daily_steps": 10000, "daily_active_kcal": 450,
                                                "daily_workout_minutes": 40, "weekly_workouts": 5}})
    assert r.status_code == 200
    assert r.json()["goals"]["daily_steps"] == 10000 and r.json()["weight_kg"] == 58.5


async def test_sync_is_idempotent_and_supports_deletes(client):
    h = await register(client)
    start = datetime.now(IST).replace(microsecond=0) - timedelta(hours=2)
    workout = {"client_id": "app-1", "type": "RUN", "start": start.isoformat(),
               "end": (start + timedelta(minutes=35)).isoformat(), "active_seconds": 1800, "kcal": 300,
               "avg_hr": 148, "max_hr": 176, "zone_seconds": [60, 300, 900, 480, 60]}
    body = {"timezone": "Asia/Kolkata", "days": days_payload(7, 8000), "workouts": [workout]}
    for _ in range(2):
        r = await client.post("/v1/me/sync", headers=auth(h), json=body)
        assert r.status_code == 200 and r.json()["days_upserted"] == 7

    body["days"][0]["steps"] = 12345
    await client.post("/v1/me/sync", headers=auth(h), json=body)
    days = (await client.get("/v1/me/activity?days=7", headers=auth(h))).json()
    assert len(days) == 7 and days[-1]["steps"] == 12345

    ws = (await client.get("/v1/me/workouts", headers=auth(h))).json()
    assert len(ws) == 1
    assert datetime.fromisoformat(ws[0]["start"]) == start  # timezone preserved through storage
    assert ws[0]["avg_hr"] == 148 and ws[0]["zone_seconds"] == [60, 300, 900, 480, 60]

    r = await client.post("/v1/me/sync", headers=auth(h), json={"deleted_workout_ids": ["app-1"]})
    assert r.json()["workouts_deleted"] == 1


async def test_sync_validation(client):
    h = await register(client)
    bad = {"days": [{"date": today().isoformat(), "steps": -5}]}
    assert (await client.post("/v1/me/sync", headers=auth(h), json=bad)).status_code == 422
    naive = {"workouts": [{"client_id": "x", "type": "RUN", "start": "2026-10-28T10:00:00",
                           "end": "2026-10-28T10:30:00", "active_seconds": 1800}]}
    assert (await client.post("/v1/me/sync", headers=auth(h), json=naive)).status_code == 422


async def test_users_are_isolated(client):
    a = await register(client, "a@uni.edu", "A")
    b = await register(client, "b@uni.edu", "B")
    await client.post("/v1/me/sync", headers=auth(a), json={"days": days_payload(3, 9000)})
    assert (await client.get("/v1/me/activity", headers=auth(b))).json() == []


async def test_delete_account_erases_everything(client):
    h = await register(client)
    await client.post("/v1/me/sync", headers=auth(h), json={"days": days_payload(3, 9000)})
    await client.post("/v1/coach/chat", headers=auth(h), json={"message": "hi"})
    assert (await client.delete("/v1/me", headers=auth(h))).status_code == 204
    assert (await client.get("/v1/me", headers=auth(h))).status_code == 401
    r = await client.post("/v1/auth/login", json={"email": "asha@uni.edu", "password": "correct-horse-9"})
    assert r.status_code == 401


# ── Challenges ──────────────────────────────────────────────────────────────

async def test_challenge_lifecycle_and_leaderboard(client):
    a = await register(client, "a@uni.edu", "Asha")
    b = await register(client, "b@uni.edu", "Bilal")
    c = await register(client, "c@uni.edu", "Chen")
    await client.post("/v1/me/sync", headers=auth(a), json={"days": days_payload(1, 6000)})
    await client.post("/v1/me/sync", headers=auth(b), json={"days": days_payload(1, 11000)})

    created = await client.post("/v1/challenges", headers=auth(a), json={"name": "Hostel Step-Off", "days": 7})
    assert created.status_code == 201
    code = created.json()["code"]
    assert len(code) == 6 and created.json()["status"] == "active"

    joined = await client.post("/v1/challenges/join", headers=auth(b), json={"code": code.lower()})
    assert joined.status_code == 200
    await client.post("/v1/challenges/join", headers=auth(c), json={"code": code})
    again = await client.post("/v1/challenges/join", headers=auth(b), json={"code": code})
    assert again.json()["participants"] == 3  # joining twice is a no-op

    board = again.json()["leaderboard"]
    assert [r["display_name"] for r in board] == ["Bilal", "Asha", "Chen"]
    assert board[0]["value"] == 11000 and again.json()["my_rank"] == 1

    cid = created.json()["id"]
    outsider = await register(client, "d@uni.edu", "Dev")
    assert (await client.get(f"/v1/challenges/{cid}", headers=auth(outsider))).status_code == 404
    assert (await client.post("/v1/challenges/join", headers=auth(outsider),
                              json={"code": "ZZZZZZ"})).status_code == 404

    assert (await client.delete(f"/v1/challenges/{cid}/membership", headers=auth(c))).status_code == 204
    assert len((await client.get("/v1/challenges", headers=auth(a))).json()[0]["leaderboard"]) == 2
    # owner leaving deletes the challenge for everyone
    await client.delete(f"/v1/challenges/{cid}/membership", headers=auth(a))
    assert (await client.get("/v1/challenges", headers=auth(b))).json() == []


async def test_active_days_metric_counts_goal_days(client):
    a = await register(client, "a@uni.edu", "Asha")
    await client.post("/v1/me/sync", headers=auth(a), json={"days": days_payload(1, 9000)})
    r = await client.post("/v1/challenges", headers=auth(a), json={"name": "Consistency", "metric": "active_days"})
    assert r.json()["leaderboard"][0]["value"] == 1


# ── Coach ───────────────────────────────────────────────────────────────────

async def test_coach_chat_rules_mode_and_history(client):
    h = await register(client)
    await client.post("/v1/me/sync", headers=auth(h), json={"days": days_payload(14, 7000, 20)})
    r = await client.post("/v1/coach/chat", headers=auth(h), json={"message": "What should I do today?"})
    assert r.status_code == 200
    body = r.json()
    assert body["ai_powered"] is False and "Readiness today" in body["reply"]

    hist = (await client.get("/v1/coach/messages", headers=auth(h))).json()
    assert [m["role"] for m in hist] == ["user", "assistant"]
    assert (await client.delete("/v1/coach/messages", headers=auth(h))).status_code == 204
    assert (await client.get("/v1/coach/messages", headers=auth(h))).json() == []


async def test_coach_chat_safety(client):
    h = await register(client)
    r = await client.post("/v1/coach/chat", headers=auth(h), json={"message": "I fainted during HIIT"})
    assert r.json()["safety"] == "emergency"


async def test_weekly_plan(client):
    h = await register(client)
    assert (await client.get("/v1/coach/plan", headers=auth(h))).status_code == 404
    await client.post("/v1/me/sync", headers=auth(h), json={"days": days_payload(14, 8000, 30)})
    r = await client.post("/v1/coach/plan", headers=auth(h))
    assert r.status_code == 201, r.text
    plan = r.json()
    assert plan["source"] == "rules" and len(plan["plan"]["sessions"]) == 7
    assert plan["readiness"]["components"]
    latest = (await client.get("/v1/coach/plan", headers=auth(h))).json()
    assert latest["id"] == plan["id"] and latest["readiness"]["reasons"] == plan["readiness"]["reasons"]


async def test_llm_outage_degrades_to_rules(client, monkeypatch):
    from app.db import SessionLocal
    from app.services import agents
    from app.services.provider import DbDataProvider
    from fitagent_agents import build_coach_graph
    from langchain_core.language_models.fake_chat_models import GenericFakeChatModel

    class Down(GenericFakeChatModel):
        def bind_tools(self, tools, **kwargs):
            return self

        async def _agenerate(self, *args, **kwargs):
            raise TimeoutError("provider unavailable")

    runtime = agents.get_runtime()
    broken = build_coach_graph(DbDataProvider(SessionLocal), Down(messages=iter([])))
    monkeypatch.setattr(runtime, "coach", broken)

    h = await register(client)
    r = await client.post("/v1/coach/chat", headers=auth(h), json={"message": "plan my day"})
    assert r.status_code == 200
    assert r.json()["ai_powered"] is False and "Readiness today" in r.json()["reply"]


async def test_watch_metrics_round_trip(client):
    h = await register(client)
    night = datetime.now(IST).replace(hour=23, minute=40, second=0, microsecond=0) - timedelta(days=1)
    day = {**days_payload(1, 7000)[0], "sleep_minutes": 452, "sleep_deep_min": 80, "sleep_rem_min": 100,
           "sleep_light_min": 272, "sleep_awake_min": 18, "hrv_ms": 61.5, "spo2_avg": 96.8, "spo2_min": 92.0,
           "resp_rate": 14.2, "skin_temp_delta": 0.12, "vo2max": 44.0, "hr_min": 51, "hr_max": 148, "floors": 6,
           "bed_time": night.isoformat(), "wake_time": (night + timedelta(hours=8)).isoformat(),
           "steps_source": "com.noisefit"}
    assert (await client.post("/v1/me/sync", headers=auth(h), json={"days": [day]})).status_code == 200

    stored = (await client.get("/v1/me/activity?days=3", headers=auth(h))).json()[-1]
    assert stored["hrv_ms"] == 61.5 and stored["sleep_deep_min"] == 80 and stored["steps_source"] == "com.noisefit"
    assert datetime.fromisoformat(stored["bed_time"]) == night  # timezone preserved through storage

    status = (await client.get("/v1/me/sync/status", headers=auth(h))).json()
    assert status["days_stored"] == 1 and status["latest_day"] == day["date"] and status["last_synced_at"]


async def test_watch_metrics_are_validated(client):
    h = await register(client)
    base = days_payload(1, 7000)[0]
    for bad in ({"hrv_ms": 0}, {"spo2_avg": 120}, {"bed_time": "2026-10-28T23:00:00"}, {"skin_temp_delta": 9}):
        r = await client.post("/v1/me/sync", headers=auth(h), json={"days": [{**base, **bad}]})
        assert r.status_code == 422, bad


async def test_old_clients_without_watch_fields_still_sync(client):
    h = await register(client)
    r = await client.post("/v1/me/sync", headers=auth(h), json={"days": days_payload(2, 5000)})
    assert r.status_code == 200
    stored = (await client.get("/v1/me/activity?days=3", headers=auth(h))).json()
    assert all(d["hrv_ms"] is None for d in stored)


async def test_exam_mode_schedule_shapes_the_rule_based_plan(client):
    h = await register(client)
    days = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
    exam = today() + timedelta(days=1)
    body = {"free_slots": [{"day": d, "start": "17:00", "end": "18:30"} for d in days],
            "exam_dates": [exam.isoformat()], "bedtime": "23:00"}
    r = await client.put("/v1/me/schedule", headers=auth(h), json=body)
    assert r.status_code == 200
    assert (await client.get("/v1/me/schedule", headers=auth(h))).json()["exam_dates"] == [exam.isoformat()]

    await client.post("/v1/me/sync", headers=auth(h), json={"days": days_payload(14, 8000)})
    plan = (await client.post("/v1/coach/plan", headers=auth(h))).json()["plan"]
    sessions = plan["sessions"]
    assert all(s["minutes"] <= 30 for s in sessions)  # exam week
    assert sessions[1]["intensity"] in ("rest", "easy")  # exam day
    assert all(s["start_time"] and "17:00" <= s["start_time"] < "18:30" for s in sessions if s["type"] != "REST")


async def test_schedule_validation_and_clearing(client):
    h = await register(client)
    bad = [{"free_slots": [{"day": "MON", "start": "18:00", "end": "17:00"}]},
           {"free_slots": [{"day": "Monday", "start": "17:00", "end": "18:00"}]},
           {"bedtime": "25:00"},
           {"free_slots": [], "course": "Maths"}]
    for b in bad[:3]:
        assert (await client.put("/v1/me/schedule", headers=auth(h), json=b)).status_code == 422, b
    # Unknown fields such as course names are ignored, never stored.
    await client.put("/v1/me/schedule", headers=auth(h), json=bad[3])
    assert "course" not in (await client.get("/v1/me/schedule", headers=auth(h))).json()
    assert (await client.put("/v1/me/schedule", headers=auth(h), json={})).json() == {"free_slots": [], "exam_dates": [], "bedtime": None}


async def _challenge_with(client, n: int):
    owner = await register(client, "owner@uni.edu", "Owner")
    c = (await client.post("/v1/challenges", headers=auth(owner), json={"name": "Hostel Step-Off", "metric": "steps", "days": 7})).json()
    others = []
    for i in range(n):
        limiter.reset()
        h = await register(client, f"m{i}@uni.edu", f"M{i}")
        await client.post("/v1/challenges/join", headers=auth(h), json={"code": c["code"]})
        others.append(h)
    return owner, c, others


async def test_kudos_are_member_only_daily_and_idempotent(client):
    _owner, c, (a, b) = await _challenge_with(client, 2)
    board = (await client.get(f"/v1/challenges/{c['id']}", headers=auth(a))).json()["leaderboard"]
    owner_id = next(r["user_id"] for r in board if r["display_name"] == "Owner")
    me_id = next(r["user_id"] for r in board if r["is_me"])

    for _ in range(2):  # sending twice counts once
        r = await client.post(f"/v1/challenges/{c['id']}/kudos", headers=auth(a), json={"to_user_id": owner_id, "emoji": "🔥"})
        assert r.status_code == 200
    await client.post(f"/v1/challenges/{c['id']}/kudos", headers=auth(b), json={"to_user_id": owner_id, "emoji": "🔥"})
    rows = (await client.get(f"/v1/challenges/{c['id']}", headers=auth(a))).json()["leaderboard"]
    owner_row = next(r for r in rows if r["user_id"] == owner_id)
    assert owner_row["kudos"] == {"🔥": 2} and owner_row["my_kudos"] == ["🔥"]

    assert (await client.post(f"/v1/challenges/{c['id']}/kudos", headers=auth(a), json={"to_user_id": me_id, "emoji": "👏"})).status_code == 422
    assert (await client.post(f"/v1/challenges/{c['id']}/kudos", headers=auth(a), json={"to_user_id": owner_id, "emoji": "😡"})).status_code == 422
    outsider = await register(client, "out@uni.edu", "Out")
    assert (await client.post(f"/v1/challenges/{c['id']}/kudos", headers=auth(outsider), json={"to_user_id": owner_id, "emoji": "👏"})).status_code == 404

    r = await client.delete(f"/v1/challenges/{c['id']}/kudos", headers=auth(a), params={"to_user_id": owner_id, "emoji": "🔥"})
    assert next(x for x in r.json()["leaderboard"] if x["user_id"] == owner_id)["kudos"] == {"🔥": 1}


async def test_campus_leaderboard_is_per_capita_and_hides_small_groups(client):
    async def student(i: int, hostel: str, goal_days: int):
        limiter.reset()  # many sign-ups from one test IP
        h = await register(client, f"s{i}@uni.edu", f"S{i}")
        await client.patch("/v1/me", headers=auth(h), json={"hostel": hostel})
        days = days_payload(goal_days, 9000)
        await client.post("/v1/me/sync", headers=auth(h), json={"days": days})
        return h

    hs = [await student(i, "Hostel 4", 5) for i in range(5)]           # 5 members × 5 goal days
    [await student(10 + i, " hostel  9 ", 7) for i in range(6)]        # 6 members × 7 goal days (name normalised)
    [await student(20 + i, "Tiny Hostel", 7) for i in range(2)]        # under 5 members → hidden
    out = (await client.get("/v1/campus/leaderboard", headers=auth(hs[0]), params={"scope": "hostel"})).json()
    names = [r["name"] for r in out["rows"]]
    assert names == ["hostel 9", "Hostel 4"]
    assert out["rows"][0]["value"] == 7 and out["rows"][1]["is_mine"] is True
    assert "Tiny Hostel" not in names

    await client.patch("/v1/me", headers=auth(hs[1]), json={"show_on_campus": False})
    out = (await client.get("/v1/campus/leaderboard", headers=auth(hs[0]), params={"scope": "hostel"})).json()
    assert [r["name"] for r in out["rows"]] == ["hostel 9"]  # Hostel 4 drops to 4 visible members


async def test_invite_page_and_assetlinks(client):
    _owner, c, _ = await _challenge_with(client, 0)
    page = await client.get(f"/j/{c['code'].lower()}")
    assert page.status_code == 200 and c["code"] in page.text and "Hostel Step-Off" in page.text
    assert (await client.get("/j/ZZZZZZ")).status_code == 404
    assert (await client.get("/.well-known/assetlinks.json")).json() == []


async def test_food_estimate_without_ai_offers_manual_entry_and_validates_input(client):
    import base64

    h = await register(client)
    jpeg = base64.b64encode(b"\xff\xd8\xff\xe0" + b"0" * 200).decode()
    r = await client.post("/v1/food/estimate", headers=auth(h), json={"image_base64": jpeg})
    assert r.status_code == 200 and r.json()["items"] == [] and r.json()["ai_powered"] is False

    png_as_jpeg = base64.b64encode(b"\x89PNG" + b"0" * 200).decode()
    assert (await client.post("/v1/food/estimate", headers=auth(h), json={"image_base64": png_as_jpeg})).status_code == 422
    assert (await client.post("/v1/food/estimate", headers=auth(h), json={"image_base64": "!" * 200})).status_code == 422

    r = await client.post("/v1/food/estimate", headers=auth(h), json={"image_base64": jpeg, "note": "I want to skip meals to lose weight"})
    assert r.json()["safety"] == "eating_disorder" and r.json()["items"] == []


async def _make_admin(email: str, college: str):
    from app.db import SessionLocal
    from app.models import User
    from sqlalchemy import select

    async with SessionLocal() as s:
        u = await s.scalar(select(User).where(User.email == email))
        u.role, u.college = "admin", college
        await s.commit()


async def test_points_need_sensor_data_and_are_idempotent(client):
    h = await register(client)
    sensor = [{**d, "steps_source": "com.noisefit"} for d in days_payload(8, 9000)]  # 8 sensor goal days in a row
    manual = days_payload(3, 9000)  # older days without a source: no points
    for d in manual:
        d["date"] = (date.fromisoformat(d["date"]) - timedelta(days=20)).isoformat()
    await client.post("/v1/me/sync", headers=auth(h), json={"days": sensor + manual})
    first = (await client.get("/v1/rewards", headers=auth(h))).json()
    again = (await client.get("/v1/rewards", headers=auth(h))).json()
    assert first["balance"] == again["balance"] == 8 * 10 + 25  # goal days + 7-day streak bonus, counted once
    assert {e["kind"] for e in first["recent"]} == {"goal_day", "streak_7"}


async def test_redeeming_perks(client):
    limiter.reset()
    admin = await register(client, "admin@uni.edu", "Admin")
    await _make_admin("admin@uni.edu", "IIT Example")
    perk = (await client.post("/v1/admin/perks", headers=auth(admin), json={"title": "Gym day pass", "partner": "Campus Gym", "cost": 50, "stock": 1})).json()

    h = await register(client, "asha2@uni.edu", "Asha")
    await client.patch("/v1/me", headers=auth(h), json={"college": "IIT Example"})
    assert (await client.post("/v1/rewards/redeem", headers=auth(h), json={"perk_id": perk["id"]})).status_code == 409  # 0 points
    await client.post("/v1/me/sync", headers=auth(h), json={"days": [{**d, "steps_source": "phone_sensor"} for d in days_payload(6, 9000)]})
    r = await client.post("/v1/rewards/redeem", headers=auth(h), json={"perk_id": perk["id"]})
    assert r.status_code == 201 and len(r.json()["code"]) == 8
    rewards = (await client.get("/v1/rewards", headers=auth(h))).json()
    assert rewards["balance"] == 60 - 50 and rewards["perks"] == [] and rewards["redemptions"][0]["perk_title"] == "Gym day pass"

    other = await register(client, "far@uni.edu", "Far")  # another college can't see or redeem it
    await client.patch("/v1/me", headers=auth(other), json={"college": "Other College"})
    assert (await client.post("/v1/rewards/redeem", headers=auth(other), json={"perk_id": perk["id"]})).status_code == 404


async def test_admin_is_scoped_and_k_anonymous(client):
    limiter.reset()
    h = await register(client, "student@uni.edu", "S")
    assert (await client.get("/v1/admin/overview", headers=auth(h))).status_code == 403
    admin = await register(client, "admin@uni.edu", "Admin")
    await _make_admin("admin@uni.edu", "IIT Example")

    for i in range(9):
        limiter.reset()
        s = await register(client, f"st{i}@uni.edu", f"St{i}")
        await client.patch("/v1/me", headers=auth(s), json={"college": "iit  example", "hostel": "Hostel 4"})
        await client.post("/v1/me/sync", headers=auth(s), json={"days": days_payload(5, 9000 if i % 2 else 3000)})
    o = (await client.get("/v1/admin/overview", headers=auth(admin))).json()
    assert o["students"] == 9 and o["insufficient"] is True and o["hostels"] == []  # below k = 10

    limiter.reset()
    s = await register(client, "st9@uni.edu", "St9")
    await client.patch("/v1/me", headers=auth(s), json={"college": "IIT Example", "hostel": "Hostel 4"})
    await client.post("/v1/me/sync", headers=auth(s), json={"days": days_payload(5, 9000)})
    o = (await client.get("/v1/admin/overview", headers=auth(admin))).json()
    assert o["insufficient"] is False and o["students"] == 10 and o["hostels"] == ["Hostel 4"]
    assert o["active_students"][-1] == 10 and 0 < o["goal_rate"][-1] < 1
    body = str(o)
    assert "st0@uni.edu" not in body and "St0" not in body  # no individual data

    c = (await client.post("/v1/admin/challenges", headers=auth(admin), json={"name": "Freshers Step Week", "metric": "active_days", "days": 7})).json()
    official = (await client.get("/v1/challenges/official", headers=auth(s))).json()
    assert [x["code"] for x in official] == [c["code"]] and official[0]["joined"] is False
    await client.post("/v1/challenges/join", headers=auth(s), json={"code": c["code"]})
    assert (await client.get("/v1/challenges/official", headers=auth(s))).json()[0]["joined"] is True
    assert (await client.get("/v1/challenges/official", headers=auth(h))).json() == []  # no college → nothing

    page = await client.get("/admin")
    assert page.status_code == 200 and "Campus wellness dashboard" in page.text


async def test_hindi_preference_reaches_the_coach(client):
    h = await register(client)
    assert (await client.patch("/v1/me", headers=auth(h), json={"language": "hi"})).json()["language"] == "hi"
    assert (await client.patch("/v1/me", headers=auth(h), json={"language": "xx"})).status_code == 422
    r = (await client.post("/v1/coach/chat", headers=auth(h), json={"message": "What should I do today?"})).json()
    assert any("ऀ" <= ch <= "ॿ" for ch in r["reply"])  # offline coach answers in Hindi
