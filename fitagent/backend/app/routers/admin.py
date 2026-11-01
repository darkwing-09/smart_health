"""College admin (B1/B2): aggregated dashboards, official challenges and perks — scoped to the admin's college.

Privacy rules enforced here, not in the UI: no per-student data is ever returned; any aggregate covering fewer
than K_ANONYMITY students is withheld.
"""

from __future__ import annotations

import secrets
from collections import defaultdict
from datetime import timedelta
from pathlib import Path

from fastapi import APIRouter, HTTPException, Query, status
from fastapi.responses import HTMLResponse
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from ..deps import AdminUser, CurrentUser, SessionDep
from ..models import Challenge, ChallengeMember, DailyActivity, Perk, User
from ..schemas import AdminOverview, ChallengeCreate, OfficialChallengeOut, PerkIn, PerkOut, PerkPatch
from ..services.leaderboard import challenge_status, user_today
from .campus import group_key
from .rewards import perk_out

router = APIRouter(prefix="/v1/admin", tags=["admin"])
public = APIRouter(tags=["admin"], include_in_schema=False)
students_router = APIRouter(prefix="/v1/challenges", tags=["challenges"])

K_ANONYMITY = 10
_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
DASHBOARD = Path(__file__).resolve().parent.parent / "static" / "admin.html"


def _students(admin: User):
    key = group_key(admin.college)
    return lambda u: group_key(u.college) == key and u.role != "admin"


@router.get("/overview", response_model=AdminOverview)
async def overview(admin: AdminUser, session: SessionDep, days: int = Query(30, ge=7, le=90)) -> AdminOverview:
    mine = _students(admin)
    students = [u for u in (await session.scalars(select(User).where(User.college.is_not(None)))).all() if mine(u)]
    end = user_today(admin)
    day_list = [end - timedelta(days=i) for i in range(days - 1, -1, -1)]
    empty = AdminOverview(institution=admin.college, students=len(students), min_group_size=K_ANONYMITY, insufficient=True,
                          days=day_list, active_students=[], goal_rate=[], hostels=[], hostel_rates=[], steps_histogram=[],
                          funnel={})
    if len(students) < K_ANONYMITY:
        return empty
    by_id = {u.id: u for u in students}
    rows = (await session.scalars(select(DailyActivity).where(
        DailyActivity.user_id.in_(list(by_id)), DailyActivity.day >= day_list[0], DailyActivity.day <= end))).all()

    active: dict = defaultdict(set)
    met: dict = defaultdict(set)
    steps7: dict = defaultdict(list)
    for d in rows:
        u = by_id[d.user_id]
        active[d.day].add(u.id)
        if d.steps >= u.goal_steps or d.workout_minutes >= u.goal_workout_minutes:
            met[d.day].add(u.id)
        if d.day > end - timedelta(days=7):
            steps7[u.id].append(d.steps)

    hostel_members: dict = defaultdict(list)
    names: dict = {}
    for u in students:
        k = group_key(u.hostel)
        if k:
            hostel_members[k].append(u.id)
            names.setdefault(k, " ".join(u.hostel.split()))
    hostels = sorted(k for k, v in hostel_members.items() if len(v) >= K_ANONYMITY)

    def rate(day, ids):
        a = [i for i in ids if i in active[day]]
        return round(len([i for i in a if i in met[day]]) / len(a), 3) if len(a) >= K_ANONYMITY else None

    avgs = [sum(v) / len(v) for v in steps7.values() if v]
    bins = [(lo, lo + 2000) for lo in range(0, 20000, 2000)] + [(20000, None)]
    histogram = [{"from": lo, "to": hi, "students": sum(1 for a in avgs if a >= lo and (hi is None or a < hi))} for lo, hi in bins]
    if len(avgs) < K_ANONYMITY:
        histogram = []

    official = (await session.scalars(select(Challenge).where(Challenge.official.is_(True), Challenge.institution == admin.college))).all()
    joined = await session.scalar(select(func.count(func.distinct(ChallengeMember.user_id))).where(
        ChallengeMember.challenge_id.in_([c.id for c in official]), ChallengeMember.user_id.in_(list(by_id)))) if official else 0
    week_active = len({uid for day in day_list[-7:] for uid in active[day]})
    return AdminOverview(
        institution=admin.college, students=len(students), min_group_size=K_ANONYMITY, insufficient=False, days=day_list,
        active_students=[len(active[d]) if len(active[d]) >= K_ANONYMITY else 0 for d in day_list],
        goal_rate=[rate(d, list(by_id)) or 0.0 for d in day_list],
        hostels=[names[k] for k in hostels],
        hostel_rates=[[rate(d, hostel_members[k]) for d in day_list] for k in hostels],
        steps_histogram=histogram,
        funnel={"students": len(students), "active_this_week": week_active, "official_challenges": len(official),
                "joined_official": joined or 0},
    )


@router.post("/challenges", response_model=OfficialChallengeOut, status_code=status.HTTP_201_CREATED)
async def create_official(body: ChallengeCreate, admin: AdminUser, session: SessionDep) -> OfficialChallengeOut:
    start = body.start_date or user_today(admin)
    for _ in range(5):
        c = Challenge(code="".join(secrets.choice(_ALPHABET) for _ in range(6)), name=body.name.strip(), metric=body.metric,
                      start_date=start, end_date=start + timedelta(days=body.days - 1), owner_id=admin.id,
                      official=True, institution=admin.college)
        session.add(c)
        try:
            await session.commit()
            break
        except IntegrityError:
            await session.rollback()
    else:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Could not allocate a code, retry")
    return OfficialChallengeOut(id=c.id, code=c.code, name=c.name, metric=c.metric, start_date=c.start_date,
                                end_date=c.end_date, participants=0, joined=False)


@router.get("/perks", response_model=list[PerkOut])
async def list_perks(admin: AdminUser, session: SessionDep) -> list[PerkOut]:
    return [perk_out(p) for p in (await session.scalars(select(Perk).where(Perk.institution == admin.college)
                                                        .order_by(Perk.created_at.desc()))).all()]


@router.post("/perks", response_model=PerkOut, status_code=status.HTTP_201_CREATED)
async def create_perk(body: PerkIn, admin: AdminUser, session: SessionDep) -> PerkOut:
    p = Perk(institution=admin.college, created_by=admin.id, **body.model_dump())
    session.add(p)
    await session.commit()
    return perk_out(p)


@router.patch("/perks/{perk_id}", response_model=PerkOut)
async def patch_perk(perk_id: str, body: PerkPatch, admin: AdminUser, session: SessionDep) -> PerkOut:
    p = await session.get(Perk, perk_id)
    if p is None or p.institution != admin.college:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Perk not found")
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(p, k, v)
    await session.commit()
    return perk_out(p)


@students_router.get("/official", response_model=list[OfficialChallengeOut])
async def official_for_me(user: CurrentUser, session: SessionDep) -> list[OfficialChallengeOut]:
    """Official challenges published by the student's college (join with the usual code)."""
    if not user.college:
        return []
    today = user_today(user)
    out = []
    for c in (await session.scalars(select(Challenge).where(Challenge.official.is_(True)))).all():
        if group_key(c.institution) != group_key(user.college) or challenge_status(c, today) == "finished":
            continue
        members = (await session.scalars(select(ChallengeMember.user_id).where(ChallengeMember.challenge_id == c.id))).all()
        out.append(OfficialChallengeOut(id=c.id, code=c.code, name=c.name, metric=c.metric, start_date=c.start_date,
                                        end_date=c.end_date, participants=len(members), joined=user.id in members))
    return out


@public.get("/admin", response_class=HTMLResponse)
async def dashboard() -> HTMLResponse:
    """Static dashboard shell; it signs in with the normal login and calls the endpoints above."""
    return HTMLResponse(DASHBOARD.read_text(encoding="utf-8"))
