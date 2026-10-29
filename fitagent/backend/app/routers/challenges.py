from __future__ import annotations

import secrets
from datetime import timedelta

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from ..deps import CurrentUser, SessionDep
from ..models import Challenge, ChallengeMember
from ..schemas import ChallengeCreate, ChallengeOut, JoinIn
from ..services.leaderboard import challenge_out, load_challenge_query, user_today

router = APIRouter(prefix="/v1/challenges", tags=["challenges"])

_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O/1/I — easy to read aloud in a lecture hall
MAX_ACTIVE_MEMBERSHIPS = 20


def _new_code() -> str:
    return "".join(secrets.choice(_ALPHABET) for _ in range(6))


async def _get_for_member(session, challenge_id: str, user_id: str) -> Challenge:
    c = await session.scalar(load_challenge_query().where(Challenge.id == challenge_id))
    if c is None or all(m.user_id != user_id for m in c.members):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Challenge not found")
    return c


@router.get("", response_model=list[ChallengeOut])
async def my_challenges(user: CurrentUser, session: SessionDep) -> list[ChallengeOut]:
    ids = select(ChallengeMember.challenge_id).where(ChallengeMember.user_id == user.id)
    rows = (await session.scalars(load_challenge_query().where(Challenge.id.in_(ids))
                                  .order_by(Challenge.end_date.desc()))).all()
    return [await challenge_out(session, c, user, limit=10) for c in rows]


@router.post("", response_model=ChallengeOut, status_code=status.HTTP_201_CREATED)
async def create_challenge(body: ChallengeCreate, user: CurrentUser, session: SessionDep) -> ChallengeOut:
    start = body.start_date or user_today(user)
    if start < user_today(user) - timedelta(days=1):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Challenges cannot start in the past")
    for _ in range(5):
        c = Challenge(code=_new_code(), name=body.name.strip(), metric=body.metric, start_date=start,
                      end_date=start + timedelta(days=body.days - 1), owner_id=user.id)
        c.members.append(ChallengeMember(user_id=user.id))
        session.add(c)
        try:
            await session.commit()
            break
        except IntegrityError:  # code collision
            await session.rollback()
    else:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Could not allocate a join code, retry")
    return await challenge_out(session, await _get_for_member(session, c.id, user.id), user)


@router.post("/join", response_model=ChallengeOut)
async def join(body: JoinIn, user: CurrentUser, session: SessionDep) -> ChallengeOut:
    c = await session.scalar(load_challenge_query().where(Challenge.code == body.code.strip().upper()))
    if c is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No challenge with that code")
    if c.end_date < user_today(user):
        raise HTTPException(status.HTTP_409_CONFLICT, "This challenge has already finished")
    if all(m.user_id != user.id for m in c.members):
        count = len((await session.scalars(
            select(ChallengeMember.challenge_id).where(ChallengeMember.user_id == user.id))).all())
        if count >= MAX_ACTIVE_MEMBERSHIPS:
            raise HTTPException(status.HTTP_409_CONFLICT, "You're in too many challenges — leave one first")
        session.add(ChallengeMember(challenge_id=c.id, user_id=user.id))
        await session.commit()
        c = await _get_for_member(session, c.id, user.id)
    return await challenge_out(session, c, user)


@router.get("/{challenge_id}", response_model=ChallengeOut)
async def get_challenge(challenge_id: str, user: CurrentUser, session: SessionDep) -> ChallengeOut:
    return await challenge_out(session, await _get_for_member(session, challenge_id, user.id), user)


@router.delete("/{challenge_id}/membership", status_code=status.HTTP_204_NO_CONTENT)
async def leave(challenge_id: str, user: CurrentUser, session: SessionDep) -> None:
    """Leave a challenge. When the owner leaves, the challenge is deleted for everyone."""
    c = await _get_for_member(session, challenge_id, user.id)
    if c.owner_id == user.id:
        await session.delete(c)
    else:
        member = next(m for m in c.members if m.user_id == user.id)
        await session.delete(member)
    await session.commit()
