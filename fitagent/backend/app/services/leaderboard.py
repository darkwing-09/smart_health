from __future__ import annotations

from datetime import date, datetime
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from ..models import Challenge, ChallengeMember, DailyActivity, Kudos, User
from ..schemas import ChallengeOut, LeaderboardRow


def user_today(user: User) -> date:
    try:
        return datetime.now(ZoneInfo(user.timezone)).date()
    except (ZoneInfoNotFoundError, ValueError):  # bad tz stored before validation existed
        return datetime.now(ZoneInfo("UTC")).date()


def challenge_status(c: Challenge, today: date) -> str:
    if today < c.start_date:
        return "upcoming"
    return "active" if today <= c.end_date else "finished"


def _goal_met(d: DailyActivity, u: User) -> bool:
    return d.steps >= u.goal_steps or d.workout_minutes >= u.goal_workout_minutes


async def standings(session: AsyncSession, challenge: Challenge) -> list[tuple[User, float]]:
    """Members sorted by score (desc); ties broken by who joined first."""
    members = sorted(challenge.members, key=lambda m: m.joined_at)
    user_ids = [m.user_id for m in members]
    if not user_ids:
        return []
    rows = (await session.scalars(
        select(DailyActivity).where(
            DailyActivity.user_id.in_(user_ids),
            DailyActivity.day >= challenge.start_date,
            DailyActivity.day <= challenge.end_date,
        )
    )).all()
    users = {m.user_id: m.user for m in members}
    totals: dict[str, float] = dict.fromkeys(user_ids, 0.0)
    for d in rows:
        u = users[d.user_id]
        if challenge.metric == "steps":
            totals[d.user_id] += d.steps
        elif challenge.metric == "workout_minutes":
            totals[d.user_id] += d.workout_minutes
        else:
            totals[d.user_id] += 1 if _goal_met(d, u) else 0
    order = {uid: i for i, uid in enumerate(user_ids)}
    ranked = sorted(user_ids, key=lambda uid: (-totals[uid], order[uid]))
    return [(users[uid], totals[uid]) for uid in ranked]


async def todays_kudos(session: AsyncSession, challenge: Challenge, me: User) -> tuple[dict[str, dict[str, int]], dict[str, list[str]]]:
    """Reactions given today in this challenge: received counts per member, and what I sent to whom."""
    rows = (await session.scalars(select(Kudos).where(Kudos.challenge_id == challenge.id, Kudos.day == user_today(me)))).all()
    received: dict[str, dict[str, int]] = {}
    mine: dict[str, list[str]] = {}
    for k in rows:
        received.setdefault(k.to_user_id, {}).setdefault(k.emoji, 0)
        received[k.to_user_id][k.emoji] += 1
        if k.from_user_id == me.id:
            mine.setdefault(k.to_user_id, []).append(k.emoji)
    return received, mine


async def challenge_out(session: AsyncSession, challenge: Challenge, me: User, limit: int = 50) -> ChallengeOut:
    table = await standings(session, challenge)
    received, mine = await todays_kudos(session, challenge, me)
    rows: list[LeaderboardRow] = []
    my_rank = None
    rank = 0
    previous: float | None = None
    for i, (u, value) in enumerate(table, start=1):
        if value != previous:  # standard competition ranking: ties share a rank
            rank, previous = i, value
        if u.id == me.id:
            my_rank = rank
        if i <= limit or u.id == me.id:
            rows.append(LeaderboardRow(rank=rank, user_id=u.id, display_name=u.display_name, value=value,
                                       is_me=u.id == me.id, kudos=received.get(u.id, {}),
                                       my_kudos=sorted(mine.get(u.id, []))))
    return ChallengeOut(
        id=challenge.id, code=challenge.code, name=challenge.name, metric=challenge.metric,
        start_date=challenge.start_date, end_date=challenge.end_date, is_owner=challenge.owner_id == me.id,
        status=challenge_status(challenge, user_today(me)), participants=len(table), my_rank=my_rank,
        leaderboard=rows,
    )


def load_challenge_query():
    # populate_existing: membership changes within the same session must be visible on reload.
    return (select(Challenge)
            .options(selectinload(Challenge.members).selectinload(ChallengeMember.user))
            .execution_options(populate_existing=True))
