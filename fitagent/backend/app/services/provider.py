"""DataProvider implementation that feeds the agents from the database."""

from __future__ import annotations

from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo

from fitagent_agents import ActivityDay, ChallengeStanding, Goals, Profile, UserContext, WorkoutRecord
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..models import Challenge, ChallengeMember, DailyActivity, User, Workout
from .leaderboard import challenge_status, load_challenge_query, standings, user_today

HISTORY_DAYS = 42


class DbDataProvider:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]):
        self._sessions = session_factory

    async def get_context(self, user_id: str) -> UserContext:
        async with self._sessions() as session:
            user = await session.get(User, user_id)
            if user is None:
                raise LookupError(user_id)
            today = user_today(user)
            since = today - timedelta(days=HISTORY_DAYS)
            tz = ZoneInfo(user.timezone)

            days = (await session.scalars(
                select(DailyActivity)
                .where(DailyActivity.user_id == user_id, DailyActivity.day >= since)
                .order_by(DailyActivity.day)
            )).all()
            workouts = (await session.scalars(
                select(Workout)
                .where(Workout.user_id == user_id, Workout.start >= datetime.combine(since, time.min, tz).astimezone(UTC))
                .order_by(Workout.start)
            )).all()

            challenge_ids = (await session.scalars(
                select(ChallengeMember.challenge_id).where(ChallengeMember.user_id == user_id)
            )).all()
            challenges = []
            if challenge_ids:
                query = load_challenge_query().where(Challenge.id.in_(challenge_ids))
                for c in (await session.scalars(query)).all():
                    if challenge_status(c, today) == "finished" and c.end_date < today - timedelta(days=7):
                        continue
                    table = await standings(session, c)
                    rank = next(i for i, (u, _) in enumerate(table, start=1) if u.id == user_id)
                    challenges.append(ChallengeStanding(
                        name=c.name, metric=c.metric, rank=rank, participants=len(table),
                        my_value=table[rank - 1][1], leader_value=table[0][1], ends_on=c.end_date,
                    ))

            return UserContext(
                today=today,
                profile=Profile(
                    display_name=user.display_name, age=user.age, height_cm=user.height_cm, weight_kg=user.weight_kg,
                    goals=Goals(user.goal_steps, user.goal_active_kcal, user.goal_workout_minutes,
                                user.goal_weekly_workouts),
                ),
                days=[ActivityDay(day=d.day, steps=d.steps, distance_m=d.distance_m, active_kcal=d.active_kcal,
                                  workout_minutes=d.workout_minutes, resting_hr=d.resting_hr, avg_hr=d.avg_hr,
                                  sleep_minutes=d.sleep_minutes, sleep_deep_min=d.sleep_deep_min,
                                  sleep_rem_min=d.sleep_rem_min, sleep_light_min=d.sleep_light_min,
                                  sleep_awake_min=d.sleep_awake_min, hrv_ms=d.hrv_ms, spo2_avg=d.spo2_avg,
                                  resp_rate=d.resp_rate, skin_temp_delta=d.skin_temp_delta, vo2max=d.vo2max,
                                  hr_min=d.hr_min, hr_max=d.hr_max) for d in days],
                workouts=[WorkoutRecord(type=w.type, start=_local(w.start, tz),
                                        active_minutes=w.active_seconds // 60, kcal=w.kcal, title=w.title)
                          for w in workouts],
                challenges=challenges,
            )


def _local(dt, tz: ZoneInfo):
    # SQLite returns naive datetimes (stored as UTC); Postgres returns aware ones.
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.astimezone(tz)
