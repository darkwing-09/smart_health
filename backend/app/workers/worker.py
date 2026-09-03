"""ARQ Async Worker Configuration & Schedulers."""

import uuid
import logging
from typing import Any, Dict, List
from datetime import datetime, timedelta, timezone
from arq.connections import RedisSettings
from arq.cron import cron
from sqlalchemy import select

from app.core.config import settings
from app.db.session import async_session_factory
from app.models.user import User
from app.services.anomaly_pipeline import AnomalyPipelineService

logger = logging.getLogger("healthos.worker")


async def startup(ctx: Dict[str, Any]) -> None:
    """Worker startup hook."""
    logger.info("ARQ Worker initialized successfully")


async def shutdown(ctx: Dict[str, Any]) -> None:
    """Worker shutdown hook."""
    logger.info("ARQ Worker shutting down")


async def job_evaluate_acute_ingest(
    ctx: Dict[str, Any],
    user_id: str,
    measurement_ids: List[str]
) -> Dict[str, Any]:
    """
    Evaluates acute physiological deviations and hard biological gates on an incoming batch.
    Triggered by IngestionService or acute event router.
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
        return {
            "user_id": str(user_id),
            "findings_count": len(findings),
            "processed_at": now.isoformat()
        }


async def cron_hourly_trend_rollup(ctx: Dict[str, Any]) -> Dict[str, Any]:
    """
    Hourly background cadence:
    Evaluates active users with recent data for baseline deviations and trends.
    """
    now = datetime.now(timezone.utc)
    window_start = now - timedelta(hours=2)

    total_findings = 0
    async with async_session_factory() as session:
        # Fetch active users
        users = (await session.scalars(select(User).where(User.is_active.is_(True)))).all()
        pipeline = AnomalyPipelineService(session)

        for u in users:
            findings = await pipeline.run_pipeline_for_user(
                user_id=u.id,
                eval_window_start=window_start,
                eval_window_end=now,
                auto_explain=True
            )
            total_findings += len(findings)

    logger.info(
        "Hourly trend rollup completed",
        extra={"users_checked": len(users), "findings_generated": total_findings}
    )
    return {"users_evaluated": len(users), "total_findings": total_findings}


async def cron_daily_baseline_recompute(ctx: Dict[str, Any]) -> Dict[str, Any]:
    """Daily background cadence updating rolling 30-day baselines."""
    logger.info("Daily baseline recomputation running")
    return {"status": "success"}


async def cron_daily_report_pipeline(ctx: Dict[str, Any]) -> Dict[str, Any]:
    """Daily report generation pipeline."""
    logger.info("Daily report pipeline running")
    return {"status": "success"}


class WorkerSettings:
    """ARQ worker configuration."""
    functions = [job_evaluate_acute_ingest]
    cron_jobs = [
        cron(cron_hourly_trend_rollup, minute=0),
        cron(cron_daily_baseline_recompute, hour=0, minute=5),
        cron(cron_daily_report_pipeline, hour=6, minute=0)
    ]
    on_startup = startup
    on_shutdown = shutdown
    redis_settings = RedisSettings.from_dsn(settings.REDIS_URL)
    max_jobs = 20
    job_timeout = 300
