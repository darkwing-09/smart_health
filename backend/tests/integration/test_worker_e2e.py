"""ARQ Worker Integration and Cadence Scheduling Tests against live Redis."""

import asyncio
import pytest
from arq.connections import create_pool, RedisSettings
from arq.worker import create_worker
from app.core.config import settings
from app.workers.worker import (
    WorkerSettings,
    job_evaluate_acute_ingest,
    cron_hourly_trend_rollup,
    cron_daily_baseline_recompute,
    cron_daily_report_pipeline
)


@pytest.mark.asyncio
async def test_worker_redis_connection_and_enqueue():
    """
    SLICE 7 VERIFICATION:
    Verifies ARQ worker connects to live Redis, enqueues a job, and executes it.
    """
    redis_settings = RedisSettings.from_dsn(settings.REDIS_URL)
    redis_pool = await create_pool(redis_settings)

    # 1. Verify Redis connectivity via ARQ pool
    pong = await redis_pool.ping()
    assert pong is True

    # 2. Enqueue acute evaluation job
    job = await redis_pool.enqueue_job(
        "job_evaluate_acute_ingest",
        user_id="00000000-0000-0000-0000-000000000001",
        measurement_ids=["m_01", "m_02"]
    )
    assert job is not None
    assert job.job_id is not None

    # 3. Create ARQ worker in burst mode to process queue and exit cleanly
    worker = create_worker(WorkerSettings, burst=True)
    await worker.main()

    # 4. Check job status in Redis
    info = await job.result_info()
    assert info is not None
    assert info.success is True

    await redis_pool.aclose()
    await worker.close()


def test_cadence_scheduling_configuration():
    """
    SLICE 7 VERIFICATION:
    Verifies cron jobs are scheduled for deterministic rollups and NOT unnecessary LLM calls.
    """
    cron_jobs = WorkerSettings.cron_jobs
    assert len(cron_jobs) == 4

    job_names = [cj.coroutine.__name__ for cj in cron_jobs]
    assert "cron_hourly_trend_rollup" in job_names
    assert "cron_daily_baseline_recompute" in job_names
    assert "cron_daily_report_pipeline" in job_names
    assert "cron_release_quiet_hour_notifications" in job_names
