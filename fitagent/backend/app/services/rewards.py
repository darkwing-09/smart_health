"""Rewards (G5): points for sensor-backed consistency, spent on partner perks.

Points are only awarded for data a device measured (steps with a source app/sensor) so a typed-in workout can't
farm them. Awarding is idempotent — re-running it never double-counts — so it simply runs on every read.
"""

from __future__ import annotations

from datetime import date, timedelta

from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import Challenge, ChallengeMember, DailyActivity, PointsEntry, User
from .leaderboard import challenge_status, standings, user_today

GOAL_DAY_POINTS = 10
STREAK_POINTS = {7: 25, 30: 100}
PODIUM_POINTS = {1: 50, 2: 30, 3: 20}
LOOKBACK_DAYS = 60
MIN_PODIUM_FIELD = 3


def sensor_goal_day(d: DailyActivity, u: User) -> bool:
    return d.steps_source is not None and d.steps >= u.goal_steps


def streak_runs(days: list[date]) -> list[tuple[date, int]]:
    """Consecutive runs in sorted unique dates → (start, length)."""
    runs: list[tuple[date, int]] = []
    for d in sorted(set(days)):
        if runs and runs[-1][0] + timedelta(days=runs[-1][1]) == d:
            runs[-1] = (runs[-1][0], runs[-1][1] + 1)
        else:
            runs.append((d, 1))
    return runs


async def award(session: AsyncSession, user: User) -> None:
    today = user_today(user)
    rows = (await session.scalars(select(DailyActivity).where(
        DailyActivity.user_id == user.id, DailyActivity.day >= today - timedelta(days=LOOKBACK_DAYS)))).all()
    goal_days = [d.day for d in rows if sensor_goal_day(d, user)]
    entries = [("goal_day", d.isoformat(), GOAL_DAY_POINTS, d) for d in goal_days]
    for start, length in streak_runs(goal_days):
        for need, pts in STREAK_POINTS.items():
            if length >= need:
                entries.append((f"streak_{need}", start.isoformat(), pts, start + timedelta(days=need - 1)))

    ids = select(ChallengeMember.challenge_id).where(ChallengeMember.user_id == user.id)
    from .leaderboard import load_challenge_query  # local import keeps module import order simple

    for c in (await session.scalars(load_challenge_query().where(Challenge.id.in_(ids)))).all():
        if challenge_status(c, today) != "finished":
            continue
        table = await standings(session, c)
        if len(table) < MIN_PODIUM_FIELD:
            continue
        rank, previous = 0, None
        for i, (u, value) in enumerate(table, start=1):
            if value != previous:
                rank, previous = i, value
            if u.id == user.id and rank in PODIUM_POINTS and value > 0:
                entries.append(("challenge_podium", c.id, PODIUM_POINTS[rank], c.end_date))

    if not entries:
        return
    values = [{"user_id": user.id, "kind": k, "ref": r, "points": p, "day": d} for k, r, p, d in entries]
    dialect = session.bind.dialect.name if session.bind else "sqlite"
    insert = pg_insert if dialect == "postgresql" else sqlite_insert
    await session.execute(insert(PointsEntry).values(values).on_conflict_do_nothing(index_elements=["user_id", "kind", "ref"]))
    await session.commit()


async def balance(session: AsyncSession, user_id: str) -> int:
    return int(await session.scalar(select(func.coalesce(func.sum(PointsEntry.points), 0)).where(PointsEntry.user_id == user_id)) or 0)
