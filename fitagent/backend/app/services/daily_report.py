"""Glue: load a user's data from the DB, build the PDF, deliver it, record the delivery."""

from __future__ import annotations

from datetime import date, datetime
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..config import get_settings
from ..models import DailyActivity, ReportDelivery, User
from .provider import DbDataProvider
from .report import DailyReport, ReportPerson, build_daily_report
from .whatsapp import SendResult, send_report


async def render_for(sessions: async_sessionmaker[AsyncSession], user: User, day: date) -> DailyReport:
    ctx = await DbDataProvider(sessions).get_context(user.id)
    async with sessions() as s:
        src = await s.scalar(select(DailyActivity.steps_source)
                             .where(DailyActivity.user_id == user.id, DailyActivity.day == day))
    return build_daily_report(ctx, day, ReportPerson(user.display_name, user.age, user.city, src))


def filename_for(day: date) -> str:
    return f"FitAgent-report-{day.isoformat()}.pdf"


async def deliver(sessions: async_sessionmaker[AsyncSession], user: User, day: date) -> SendResult:
    """Sends [day]'s report to the user's own WhatsApp number and records the attempt."""
    if not user.whatsapp_number:
        return SendResult("skipped", "No WhatsApp number on the profile")
    report = await render_for(sessions, user, day)
    result = await send_report(get_settings(), user.whatsapp_number, filename_for(day), report.pdf,
                               f"FitAgent daily report — {report.summary}")
    async with sessions() as s:
        row = await s.get(ReportDelivery, (user.id, day))
        if row is None:
            s.add(ReportDelivery(user_id=user.id, day=day, status=result.status, detail=result.detail[:500]))
        else:
            row.status, row.detail = result.status, result.detail[:500]
        await s.commit()
    return result


def local_now(user: User, now: datetime) -> datetime:
    try:
        return now.astimezone(ZoneInfo(user.timezone))
    except (ValueError, KeyError):
        return now.astimezone(ZoneInfo("UTC"))


async def due_users(sessions: async_sessionmaker[AsyncSession], now: datetime) -> list[tuple[User, date]]:
    """Opted-in users whose local report hour has passed today and who haven't had today's report yet."""
    async with sessions() as s:
        users = (await s.scalars(select(User).where(User.whatsapp_opt_in.is_(True),
                                                     User.whatsapp_number.is_not(None)))).all()
        out = []
        for u in users:
            ln = local_now(u, now)
            if ln.hour < (u.report_hour if u.report_hour is not None else 21):
                continue
            done = await s.get(ReportDelivery, (u.id, ln.date()))
            if done is None or done.status == "failed" and (done.detail or "").startswith("network"):
                out.append((u, ln.date()))
        return out
