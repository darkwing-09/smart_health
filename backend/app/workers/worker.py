"""ARQ Async Worker Configuration & Schedulers."""

from typing import Any, Dict
from arq.connections import RedisSettings
from arq.cron import cron
from app.core.config import settings


async def startup(ctx: Dict[str, Any]) -> None:
    """Worker startup hook."""
    pass


async def shutdown(ctx: Dict[str, Any]) -> None:
    """Worker shutdown hook."""
    pass


# Job stubs
async def job_evaluate_acute_ingest(ctx: Dict[str, Any], user_id: str, measurement_ids: list) -> None:
    """Evaluates acute physiological hard gates on incoming batch."""
    pass


async def cron_hourly_trend_rollup(ctx: Dict[str, Any]) -> None:
    """Hourly background cadence calculating trend rollups."""
    pass


async def cron_daily_baseline_recompute(ctx: Dict[str, Any]) -> None:
    """Daily background cadence updating rolling baselines."""
    pass


async def cron_daily_report_pipeline(ctx: Dict[str, Any]) -> None:
    """Daily report generation pipeline."""
    pass


class WorkerSettings:
    """ARQ worker configuration."""
    functions = [job_evaluate_acute_ingest]
    cron_jobs = [
        cron(cron_hourly_trend_rollup, minute=0),
        cron(cron_daily_baseline_recompute, hour=0, minute=5),
        cron(cron_daily_report_pipeline, hour=23, minute=50),
    ]
    on_startup = startup
    on_shutdown = shutdown
    redis_settings = RedisSettings.from_dsn(settings.REDIS_URL)
    max_jobs = 20
    job_timeout = 300
