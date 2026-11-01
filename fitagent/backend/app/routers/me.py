from __future__ import annotations

from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Query, status
from sqlalchemy import delete, func, select

from ..deps import CurrentUser, SessionDep
from ..models import DailyActivity, User, Workout
from ..schemas import (
    DayOut,
    GoalsModel,
    ProfileOut,
    ProfileUpdate,
    ScheduleModel,
    SyncIn,
    SyncOut,
    SyncStatusOut,
    WorkoutOut,
)
from ..services.leaderboard import user_today

router = APIRouter(prefix="/v1/me", tags=["me"])


def profile_out(u: User) -> ProfileOut:
    return ProfileOut(
        id=u.id, email=u.email, display_name=u.display_name, timezone=u.timezone, age=u.age,
        height_cm=u.height_cm, weight_kg=u.weight_kg,
        goals=GoalsModel(daily_steps=u.goal_steps, daily_active_kcal=u.goal_active_kcal,
                         daily_workout_minutes=u.goal_workout_minutes, weekly_workouts=u.goal_weekly_workouts),
    )


@router.get("", response_model=ProfileOut)
async def get_me(user: CurrentUser) -> ProfileOut:
    return profile_out(user)


@router.patch("", response_model=ProfileOut)
async def update_me(body: ProfileUpdate, user: CurrentUser, session: SessionDep) -> ProfileOut:
    data = body.model_dump(exclude_unset=True)
    goals = data.pop("goals", None)
    for key, value in data.items():
        setattr(user, key, value.strip() if isinstance(value, str) else value)
    if goals:
        user.goal_steps = goals["daily_steps"]
        user.goal_active_kcal = goals["daily_active_kcal"]
        user.goal_workout_minutes = goals["daily_workout_minutes"]
        user.goal_weekly_workouts = goals["weekly_workouts"]
    await session.commit()
    return profile_out(user)


@router.delete("", status_code=status.HTTP_204_NO_CONTENT)
async def delete_me(user: CurrentUser, session: SessionDep) -> None:
    """Permanently deletes the account and all associated data (right to erasure)."""
    await session.delete(user)
    await session.commit()


@router.get("/schedule", response_model=ScheduleModel)
async def get_schedule(user: CurrentUser) -> ScheduleModel:
    return ScheduleModel.model_validate(user.schedule or {})


@router.put("/schedule", response_model=ScheduleModel)
async def put_schedule(body: ScheduleModel, user: CurrentUser, session: SessionDep) -> ScheduleModel:
    """Replaces the Exam mode timetable (free time and exam dates only). Send an empty body to clear it."""
    user.schedule = body.model_dump(mode="json") if (body.free_slots or body.exam_dates or body.bedtime) else None
    await session.commit()
    return body


@router.post("/sync", response_model=SyncOut)
async def sync(body: SyncIn, user: CurrentUser, session: SessionDep) -> SyncOut:
    """Idempotent upsert of daily summaries and workouts from the device."""
    if body.timezone:
        user.timezone = body.timezone

    existing_days = {}
    if body.days:
        rows = await session.scalars(select(DailyActivity).where(
            DailyActivity.user_id == user.id, DailyActivity.day.in_([d.date for d in body.days])))
        existing_days = {r.day: r for r in rows}
    for d in body.days:
        values = d.model_dump(exclude={"date"})
        for key in ("bed_time", "wake_time"):
            if values[key] is not None:
                values[key] = values[key].astimezone(UTC)
        row = existing_days.get(d.date)
        if row is None:
            session.add(DailyActivity(user_id=user.id, day=d.date, **values))
        else:
            for k, v in values.items():
                setattr(row, k, v)

    existing_workouts = {}
    if body.workouts:
        rows = await session.scalars(select(Workout).where(
            Workout.user_id == user.id, Workout.client_id.in_([w.client_id for w in body.workouts])))
        existing_workouts = {r.client_id: r for r in rows}
    for w in body.workouts:
        values = w.model_dump(exclude={"client_id"})
        values["start"] = w.start.astimezone(UTC)
        values["end"] = w.end.astimezone(UTC)
        row = existing_workouts.get(w.client_id)
        if row is None:
            session.add(Workout(user_id=user.id, client_id=w.client_id, **values))
        else:
            for k, v in values.items():
                setattr(row, k, v)

    deleted = 0
    if body.deleted_workout_ids:
        result = await session.execute(delete(Workout).where(
            Workout.user_id == user.id, Workout.client_id.in_(body.deleted_workout_ids)))
        deleted = result.rowcount or 0

    await session.commit()
    return SyncOut(days_upserted=len(body.days), workouts_upserted=len(body.workouts), workouts_deleted=deleted,
                   server_time=datetime.now(UTC))


@router.get("/activity", response_model=list[DayOut])
async def activity(user: CurrentUser, session: SessionDep, days: int = Query(30, ge=1, le=365)) -> list[DayOut]:
    since = user_today(user) - timedelta(days=days - 1)
    rows = await session.scalars(select(DailyActivity).where(
        DailyActivity.user_id == user.id, DailyActivity.day >= since).order_by(DailyActivity.day))
    return [DayOut(date=r.day, **{k: _utc(getattr(r, k)) for k in DayOut.model_fields if k != "date"}) for r in rows]


def _utc(value):
    """SQLite hands back naive datetimes (stored as UTC); make them aware so they serialise with an offset."""
    if isinstance(value, datetime) and value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value


@router.get("/sync/status", response_model=SyncStatusOut)
async def sync_status(user: CurrentUser, session: SessionDep) -> SyncStatusOut:
    """What the server holds for this user, so the app can show whether the cloud copy is up to date."""
    latest, days, updated = (await session.execute(
        select(func.max(DailyActivity.day), func.count(), func.max(DailyActivity.updated_at))
        .where(DailyActivity.user_id == user.id)
    )).one()
    workouts = await session.scalar(select(func.count()).select_from(Workout).where(Workout.user_id == user.id))
    return SyncStatusOut(latest_day=latest, days_stored=days, workouts_stored=workouts or 0,
                         last_synced_at=_utc(updated))


@router.get("/workouts", response_model=list[WorkoutOut])
async def workouts(user: CurrentUser, session: SessionDep, limit: int = Query(50, ge=1, le=500)) -> list[WorkoutOut]:
    rows = await session.scalars(
        select(Workout).where(Workout.user_id == user.id).order_by(Workout.start.desc()).limit(limit))
    out = []
    for r in rows:
        start, end = (t if t.tzinfo else t.replace(tzinfo=UTC) for t in (r.start, r.end))
        out.append(WorkoutOut(client_id=r.client_id, type=r.type, title=r.title, start=start, end=end,
                              active_seconds=r.active_seconds, kcal=r.kcal, source=r.source))
    return out
