"""Rewards & partner perks (G5)."""

from __future__ import annotations

import secrets
from datetime import timedelta

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import or_, select, update

from ..deps import CurrentUser, SessionDep, limiter
from ..models import Perk, PointsEntry, Redemption
from ..schemas import PerkOut, RedeemIn, RedemptionOut, RewardsOut, WeekPoints
from ..services.leaderboard import user_today
from ..services.rewards import award, balance

router = APIRouter(prefix="/v1/rewards", tags=["rewards"])
_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def perk_out(p: Perk) -> PerkOut:
    return PerkOut(id=p.id, title=p.title, description=p.description, partner=p.partner, cost=p.cost, stock=p.stock,
                   active=p.active, institution=p.institution)


@router.get("", response_model=RewardsOut)
async def rewards(user: CurrentUser, session: SessionDep) -> RewardsOut:
    await award(session, user)
    await session.refresh(user)
    entries = (await session.scalars(select(PointsEntry).where(PointsEntry.user_id == user.id)
                                     .order_by(PointsEntry.day.desc(), PointsEntry.id.desc()))).all()
    today = user_today(user)
    week_start = today - timedelta(days=today.weekday())
    weeks = [WeekPoints(week_start=week_start - timedelta(weeks=i),
                        points=sum(e.points for e in entries if e.points > 0 and
                                   week_start - timedelta(weeks=i) <= e.day < week_start - timedelta(weeks=i - 1)))
             for i in range(7, -1, -1)]
    perks = (await session.scalars(select(Perk).where(Perk.active.is_(True), Perk.stock > 0,
                                                      or_(Perk.institution.is_(None), Perk.institution == user.college))
                                   .order_by(Perk.cost))).all()
    redemptions = (await session.execute(select(Redemption, Perk.title).join(Perk, Perk.id == Redemption.perk_id)
                                         .where(Redemption.user_id == user.id).order_by(Redemption.created_at.desc()))).all()
    return RewardsOut(
        balance=sum(e.points for e in entries),
        earned_total=sum(e.points for e in entries if e.points > 0),
        weeks=weeks,
        recent=[{"kind": e.kind, "points": e.points, "day": e.day} for e in entries[:20]],
        perks=[perk_out(p) for p in perks],
        redemptions=[RedemptionOut(code=r.code, perk_title=t, points=r.points, created_at=r.created_at) for r, t in redemptions],
    )


@router.post("/redeem", response_model=RedemptionOut, status_code=status.HTTP_201_CREATED)
async def redeem(body: RedeemIn, user: CurrentUser, session: SessionDep) -> RedemptionOut:
    limiter.check(f"redeem:{user.id}", 10, 3600)
    await award(session, user)
    await session.refresh(user)
    perk = await session.get(Perk, body.perk_id)
    if perk is None or not perk.active or (perk.institution not in (None, user.college)):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Perk not found")
    if await balance(session, user.id) < perk.cost:
        raise HTTPException(status.HTTP_409_CONFLICT, "Not enough points yet")
    # Atomic stock decrement: concurrent redemptions can't oversell.
    taken = await session.execute(update(Perk).where(Perk.id == perk.id, Perk.stock > 0).values(stock=Perk.stock - 1))
    if taken.rowcount != 1:
        await session.rollback()
        raise HTTPException(status.HTTP_409_CONFLICT, "Sold out")
    code = "".join(secrets.choice(_CODE_ALPHABET) for _ in range(8))
    r = Redemption(user_id=user.id, perk_id=perk.id, code=code, points=perk.cost)
    session.add(r)
    await session.flush()
    session.add(PointsEntry(user_id=user.id, kind="redeem", ref=r.id, points=-perk.cost, day=user_today(user)))
    title, created = perk.title, r.created_at
    await session.commit()
    return RedemptionOut(code=code, perk_title=title, points=perk.cost, created_at=created)
