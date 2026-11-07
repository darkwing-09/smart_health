"""Daily PDF report: download it, send it to your own WhatsApp now, see delivery status."""

from __future__ import annotations

from datetime import date

from fastapi import APIRouter, HTTPException, status
from fastapi.responses import Response
from pydantic import BaseModel
from sqlalchemy import select

from ..config import get_settings
from ..db import SessionLocal
from ..deps import CurrentUser, SessionDep, limiter
from ..models import ReportDelivery
from ..services.daily_report import deliver, filename_for, render_for
from ..services.leaderboard import user_today

router = APIRouter(prefix="/v1/me/report", tags=["report"])


class SendOut(BaseModel):
    status: str
    detail: str


class ReportStatusOut(BaseModel):
    whatsapp_configured: bool
    whatsapp_number: str | None
    opted_in: bool
    report_hour: int
    last_day: date | None = None
    last_status: str | None = None


def _day(user, day: date | None) -> date:
    today = user_today(user)
    d = day or today
    if d > today:
        raise HTTPException(422, "That day hasn't happened yet")
    return d


@router.get(".pdf", response_class=Response, responses={200: {"content": {"application/pdf": {}}}})
async def download(user: CurrentUser, day: date | None = None) -> Response:
    d = _day(user, day)
    report = await render_for(SessionLocal, user, d)
    return Response(report.pdf, media_type="application/pdf",
                    headers={"Content-Disposition": f'inline; filename="{filename_for(d)}"'})


@router.post("/send", response_model=SendOut)
async def send_now(user: CurrentUser, day: date | None = None) -> SendOut:
    limiter.check(f"report:{user.id}", 5, 3600)
    if not user.whatsapp_number:
        raise HTTPException(status.HTTP_409_CONFLICT, "Add your WhatsApp number in your profile first")
    r = await deliver(SessionLocal, user, _day(user, day))
    return SendOut(status=r.status, detail=r.detail)


@router.get("/status", response_model=ReportStatusOut)
async def report_status(user: CurrentUser, session: SessionDep) -> ReportStatusOut:
    last = await session.scalar(select(ReportDelivery).where(ReportDelivery.user_id == user.id)
                                .order_by(ReportDelivery.day.desc()).limit(1))
    return ReportStatusOut(whatsapp_configured=get_settings().whatsapp_configured, whatsapp_number=user.whatsapp_number,
                           opted_in=bool(user.whatsapp_opt_in), report_hour=user.report_hour or 21,
                           last_day=last.day if last else None, last_status=last.status if last else None)
