"""Campus leaderboards (G4): colleges, hostels or batches ranked by goal days per member.

Per-capita scoring lets a small hostel beat a big one; groups under MIN_GROUP_SIZE are hidden so no individual
can be singled out, and students can opt out with show_on_campus = false.
"""

from __future__ import annotations

from collections import Counter, defaultdict
from datetime import timedelta
from typing import Literal

from fastapi import APIRouter, Query
from sqlalchemy import select

from ..deps import CurrentUser, SessionDep
from ..models import DailyActivity, User
from ..schemas import CampusOut, CampusRow
from ..services.leaderboard import user_today

router = APIRouter(prefix="/v1/campus", tags=["campus"])

MIN_GROUP_SIZE = 5


def group_key(name: str | None) -> str | None:
    """Case/space-insensitive grouping so "Hostel 4", "hostel  4" and "HOSTEL 4" are one group."""
    return " ".join(name.split()).casefold() if name and name.strip() else None


@router.get("/leaderboard", response_model=CampusOut)
async def campus_leaderboard(
    user: CurrentUser,
    session: SessionDep,
    scope: Literal["college", "hostel", "batch"] = "hostel",
    days: int = Query(7, ge=1, le=90),
) -> CampusOut:
    users = (await session.scalars(select(User).where(getattr(User, scope).is_not(None)))).all()
    members: dict[str, list[User]] = defaultdict(list)
    names: dict[str, Counter] = defaultdict(Counter)
    for u in users:
        key = group_key(getattr(u, scope))
        if key and (u.show_on_campus is not False):
            members[key].append(u)
            names[key][" ".join(getattr(u, scope).split())] += 1
    eligible = {k: v for k, v in members.items() if len(v) >= MIN_GROUP_SIZE}

    end = user_today(user)
    start = end - timedelta(days=days - 1)
    ids = [u.id for group in eligible.values() for u in group]
    goal_days: Counter = Counter()
    if ids:
        by_id = {u.id: u for group in eligible.values() for u in group}
        rows = (await session.scalars(select(DailyActivity).where(
            DailyActivity.user_id.in_(ids), DailyActivity.day >= start, DailyActivity.day <= end))).all()
        for d in rows:
            u = by_id[d.user_id]
            if d.steps >= u.goal_steps or d.workout_minutes >= u.goal_workout_minutes:
                goal_days[d.user_id] += 1

    mine = group_key(getattr(user, scope))
    scored = sorted(
        ((key, sum(goal_days[u.id] for u in group) / len(group), len(group)) for key, group in eligible.items()),
        key=lambda t: (-t[1], -t[2], t[0]),
    )
    out: list[CampusRow] = []
    rank, previous = 0, None
    for i, (key, value, size) in enumerate(scored, start=1):
        value = round(value, 2)
        if value != previous:
            rank, previous = i, value
        out.append(CampusRow(rank=rank, name=names[key].most_common(1)[0][0], members=size, value=value, is_mine=key == mine))
    return CampusOut(scope=scope, days=days, my_group=getattr(user, scope), rows=out[:50], min_group_size=MIN_GROUP_SIZE)
