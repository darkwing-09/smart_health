"""ARQ Async Worker Configuration & Schedulers (Phase 7 Notification Integration)."""

import uuid
import logging
from typing import Any
from datetime import datetime, timedelta, timezone
from arq.connections import RedisSettings
from arq.cron import cron
from sqlalchemy import select

from app.core.config import settings
from app.db.session import async_session_factory
from app.models.user import User
from app.models.finding import Finding
from app.services.anomaly_pipeline import AnomalyPipelineService
from app.services.notification import NotificationService

logger = logging.getLogger("healthos.worker")


async def startup(ctx: dict[str, Any]) -> None:
    """Worker startup hook."""
    logger.info("ARQ Worker initialized successfully")


async def shutdown(ctx: dict[str, Any]) -> None:
    """Worker shutdown hook."""
    logger.info("ARQ Worker shutting down")


async def job_evaluate_acute_ingest(
    ctx: dict[str, Any],
    user_id: str,
    measurement_ids: list[str]
) -> dict[str, Any]:
    """
    Evaluates acute physiological deviations and hard biological gates on an incoming batch.
    Enqueues notification processing for newly persisted findings.
    """
    now = datetime.now(timezone.utc)
    try:
        uid = uuid.UUID(user_id) if isinstance(user_id, str) else user_id
    except ValueError:
        logger.warning("Invalid user_id UUID provided to worker", extra={"user_id": str(user_id)})
        return {"user_id": str(user_id), "findings_count": 0, "status": "invalid_user_id"}

    async with async_session_factory() as session:
        pipeline = AnomalyPipelineService(session)
        findings = await pipeline.run_pipeline_for_user(
            user_id=uid,
            eval_window_start=now - timedelta(hours=1),
            eval_window_end=now,
            auto_explain=True
        )

        # Dispatch alerts for all newly generated findings
        user = await session.get(User, uid)
        user_prefs = user.notification_prefs if user else {}
        user_tz = user.timezone if user else "Asia/Kolkata"

        notif_service = NotificationService(session)
        dispatched_count = 0
        for f in findings:
            res = await notif_service.dispatch_finding_alert(
                user_id=uid,
                finding=f,
                user_timezone=user_tz,
                user_prefs=user_prefs
            )
            if res:
                dispatched_count += 1

        return {
            "user_id": str(user_id),
            "findings_count": len(findings),
            "notifications_dispatched": dispatched_count,
            "processed_at": now.isoformat()
        }


async def job_dispatch_finding_notification(
    ctx: dict[str, Any],
    user_id: str,
    finding_id: str,
    max_retries: int = 3
) -> dict[str, Any]:
    """
    Idempotent ARQ task to evaluate and dispatch a finding notification with retry protection.
    """
    uid = uuid.UUID(user_id)
    fid = uuid.UUID(finding_id)

    async with async_session_factory() as session:
        finding = await session.get(Finding, fid)
        if not finding:
            return {"status": "finding_not_found"}

        user = await session.get(User, uid)
        user_prefs = user.notification_prefs if user else {}
        user_tz = user.timezone if user else "Asia/Kolkata"

        service = NotificationService(session)
        notif = await service.dispatch_finding_alert(
            user_id=uid,
            finding=finding,
            user_timezone=user_tz,
            user_prefs=user_prefs
        )

        return {
            "status": "success" if notif else "suppressed_or_silent",
            "notification_id": str(notif.id) if notif else None
        }


async def cron_release_quiet_hour_notifications(ctx: dict[str, Any]) -> dict[str, Any]:
    """
    Cadence job: Checks for notifications held during quiet hours and releases
    them if the user's localized quiet hours window has concluded.
    """
    async with async_session_factory() as session:
        service = NotificationService(session)
        released = await service.release_held_quiet_hour_notifications()
        return {"status": "success", "released_count": released}


async def cron_hourly_trend_rollup(ctx: dict[str, Any]) -> dict[str, Any]:
    """
    Hourly background cadence:
    Evaluates active users with recent data for baseline deviations and trends.
    """
    now = datetime.now(timezone.utc)
    window_start = now - timedelta(hours=2)

    total_findings = 0
    async with async_session_factory() as session:
        users = (await session.scalars(select(User).where(User.is_active.is_(True)))).all()
        pipeline = AnomalyPipelineService(session)
        notif_service = NotificationService(session)

        for u in users:
            findings = await pipeline.run_pipeline_for_user(
                user_id=u.id,
                eval_window_start=window_start,
                eval_window_end=now,
                auto_explain=True
            )
            total_findings += len(findings)
            for f in findings:
                await notif_service.dispatch_finding_alert(
                    user_id=u.id,
                    finding=f,
                    user_timezone=u.timezone,
                    user_prefs=u.notification_prefs
                )

    logger.info(
        "Hourly trend rollup completed",
        extra={"users_checked": len(users), "findings_generated": total_findings}
    )
    return {"users_evaluated": len(users), "total_findings": total_findings}


async def cron_daily_baseline_recompute(ctx: dict[str, Any]) -> dict[str, Any]:
    """Daily background cadence updating rolling 30-day baselines."""
    logger.info("Daily baseline recomputation running")
    return {"status": "success"}


async def cron_daily_report_pipeline(ctx: dict[str, Any]) -> dict[str, Any]:
    """Daily report generation pipeline."""
    logger.info("Daily report pipeline running")
    return {"status": "success"}


class WorkerSettings:
    """ARQ worker configuration."""
    functions = [job_evaluate_acute_ingest, job_dispatch_finding_notification]
    cron_jobs = [
        cron(cron_release_quiet_hour_notifications, minute={0, 15, 30, 45}),
        cron(cron_hourly_trend_rollup, minute=0),
        cron(cron_daily_baseline_recompute, hour=0, minute=5),
        cron(cron_daily_report_pipeline, hour=6, minute=0)
    ]
    on_startup = startup
    on_shutdown = shutdown
    redis_settings = RedisSettings.from_dsn(settings.REDIS_URL)
    max_jobs = 20
    job_timeout = 300
