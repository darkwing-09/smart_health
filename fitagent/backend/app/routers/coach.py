from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import delete, select

from ..config import get_settings
from ..deps import CurrentUser, SessionDep, limiter
from ..models import CoachMessage, WeeklyPlanRecord
from ..schemas import ChatIn, ChatOut, MessageOut, PlanOut, ReadinessOut
from ..services.agents import get_runtime
from ..services.leaderboard import user_today

router = APIRouter(prefix="/v1/coach", tags=["coach"])

HISTORY_TURNS = 20


@router.post("/chat", response_model=ChatOut)
async def chat(body: ChatIn, user: CurrentUser, session: SessionDep) -> ChatOut:
    limiter.check(f"coach:{user.id}", get_settings().rate_limit_coach_per_min, 60)
    recent = (await session.scalars(
        select(CoachMessage).where(CoachMessage.user_id == user.id)
        .order_by(CoachMessage.id.desc()).limit(HISTORY_TURNS)
    )).all()
    history = [(m.role, m.content) for m in reversed(recent) if m.safety is None]

    reply = await get_runtime().chat(user.id, history, body.message)

    session.add(CoachMessage(user_id=user.id, role="user", content=body.message, safety=reply.safety))
    session.add(CoachMessage(user_id=user.id, role="assistant", content=reply.text, safety=reply.safety))
    await session.commit()
    return ChatOut(reply=reply.text, safety=reply.safety, tools_used=reply.tools_used, ai_powered=reply.used_llm)


@router.get("/messages", response_model=list[MessageOut])
async def messages(user: CurrentUser, session: SessionDep, limit: int = Query(50, ge=1, le=200)) -> list[MessageOut]:
    rows = (await session.scalars(
        select(CoachMessage).where(CoachMessage.user_id == user.id).order_by(CoachMessage.id.desc()).limit(limit)
    )).all()
    return [MessageOut.model_validate(m) for m in reversed(rows)]


@router.delete("/messages", status_code=status.HTTP_204_NO_CONTENT)
async def clear_messages(user: CurrentUser, session: SessionDep) -> None:
    await session.execute(delete(CoachMessage).where(CoachMessage.user_id == user.id))
    await session.commit()


def _plan_out(rec: WeeklyPlanRecord) -> PlanOut:
    return PlanOut(
        id=rec.id, starts_on=rec.starts_on, created_at=rec.created_at, source=rec.source,
        readiness=ReadinessOut(score=rec.readiness_score, band=rec.readiness_band,
                               components=rec.readiness_detail.get("components", {}),
                               reasons=rec.readiness_detail.get("reasons", [])),
        plan=rec.plan,
    )


@router.post("/plan", response_model=PlanOut, status_code=status.HTTP_201_CREATED)
async def generate_plan(user: CurrentUser, session: SessionDep) -> PlanOut:
    limiter.check(f"plan:{user.id}", get_settings().rate_limit_plan_per_hour, 3600)
    result = await get_runtime().plan(user.id)
    rec = WeeklyPlanRecord(user_id=user.id, starts_on=user_today(user), plan=result.plan.model_dump(),
                           source=result.source, readiness_score=result.readiness.score,
                           readiness_band=result.readiness.band.value,
                           readiness_detail={"components": result.readiness.components,
                                             "reasons": result.readiness.reasons})
    session.add(rec)
    await session.commit()
    return _plan_out(rec)


@router.get("/plan", response_model=PlanOut)
async def latest_plan(user: CurrentUser, session: SessionDep) -> PlanOut:
    rec = await session.scalar(select(WeeklyPlanRecord).where(WeeklyPlanRecord.user_id == user.id)
                               .order_by(WeeklyPlanRecord.created_at.desc()).limit(1))
    if rec is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No plan yet")
    return _plan_out(rec)
