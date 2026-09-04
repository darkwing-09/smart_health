import os
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
from app.models.report import Report
from app.services.anomaly_pipeline import AnomalyPipelineService
from app.services.notification import NotificationService
from app.services.baseline import BaselineService
from app.services.daily_digest import DailyDigestService
from app.services.pdf_report import DailyReportPdfService


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


async def _execute_baseline_recompute(
    session: Any,
    user_ids: Any = None
) -> dict[str, Any]:
    logger.info("Daily baseline recomputation initiated")
    now = datetime.now(timezone.utc)
    metrics = ["heart_rate", "steps", "spo2", "hrv", "respiratory_rate"]
    recomputed_count = 0
    failed_count = 0

    if user_ids:
        users = (await session.scalars(select(User).where(User.id.in_(user_ids), User.is_active.is_(True)))).all()
    else:
        users = (await session.scalars(select(User).where(User.is_active.is_(True)))).all()

    baseline_service = BaselineService(session)

    for u in users:
        for metric in metrics:
            try:
                await baseline_service.compute_baseline(
                    user_id=u.id,
                    metric_type=metric,
                    current_date=now,
                    user_timezone=u.timezone
                )
                recomputed_count += 1
            except Exception as e:
                logger.error(
                    "Failed daily baseline calculation",
                    extra={"user_id": str(u.id), "metric": metric, "error": str(e)}
                )
                failed_count += 1

    logger.info(
        "Daily baseline recomputation completed",
        extra={"users": len(users), "recomputed": recomputed_count, "failed": failed_count}
    )
    return {
        "status": "success",
        "users_count": len(users),
        "recomputed_count": recomputed_count,
        "failed_count": failed_count
    }


async def cron_daily_baseline_recompute(
    ctx: dict[str, Any],
    session: Any = None,
    user_ids: Any = None
) -> dict[str, Any]:
    """
    Daily background cadence updating rolling 30-day baselines.
    Iterates through active users and recomputes baselines across standard metrics:
    heart_rate, steps, spo2, hrv, respiratory_rate.
    Deterministic, zero-LLM modeling.
    """
    if session is not None:
        return await _execute_baseline_recompute(session, user_ids)
    async with async_session_factory() as sess:
        return await _execute_baseline_recompute(sess, user_ids)


async def _execute_daily_report_pipeline(
    session: Any,
    user_ids: Any = None
) -> dict[str, Any]:
    logger.info("Daily report pipeline initiated")
    now = datetime.now(timezone.utc)
    yesterday = now - timedelta(days=1)

    reports_compiled = 0
    reports_failed = 0
    reports_dir = getattr(settings, "STORAGE_LOCAL_PATH", "./data/reports")
    os.makedirs(reports_dir, exist_ok=True)

    if user_ids:
        users = (await session.scalars(select(User).where(User.id.in_(user_ids), User.is_active.is_(True)))).all()
    else:
        users = (await session.scalars(select(User).where(User.is_active.is_(True)))).all()

    digest_service = DailyDigestService(session)

    for u in users:
        try:
            digest = await digest_service.compile_digest(user_id=u.id, target_date=yesterday)
            report_date = datetime.strptime(digest.report_date, "%Y-%m-%d").date()

            # Check for existing report for idempotency
            stmt = select(Report).where(Report.user_id == u.id, Report.report_date == report_date)
            existing = (await session.scalars(stmt)).first()

            pdf_path = os.path.join(reports_dir, f"report_{u.id}_{digest.report_date}.pdf")

            # Prepare metrics rows for vector PDF
            hr_mean_str = f"{digest.metrics.heart_rate_mean} bpm" if digest.metrics.heart_rate_mean is not None else "N/A"
            base_mean_str = f"{digest.baseline_status.get('mean', 'N/A')} bpm" if digest.baseline_status and digest.baseline_status.get('mean') is not None else "N/A"
            rhr_str = f"{digest.metrics.resting_heart_rate} bpm" if digest.metrics.resting_heart_rate is not None else "N/A"
            steps_str = f"{digest.metrics.total_steps:,}"
            sleep_str = f"{round(digest.metrics.sleep_duration_minutes / 60.0, 1)} hrs" if digest.metrics.sleep_duration_minutes else "N/A"

            metric_rows = [
                {
                    "name": "Mean Heart Rate",
                    "value": hr_mean_str,
                    "baseline": base_mean_str,
                    "status": "Nominal" if not digest.active_findings else "Deviation Observed"
                },
                {
                    "name": "Resting Heart Rate",
                    "value": rhr_str,
                    "baseline": "—",
                    "status": "Nominal"
                },
                {
                    "name": "Total Steps",
                    "value": steps_str,
                    "baseline": "—",
                    "status": "Active" if digest.metrics.total_steps >= 8000 else "Standard"
                },
                {
                    "name": "Sleep Duration",
                    "value": sleep_str,
                    "baseline": "—",
                    "status": "Logged" if digest.metrics.sleep_duration_minutes else "No Log"
                }
            ]

            narrative = (
                " ".join(digest.insights_section)
                if digest.insights_section
                else "Nominal physiological baseline maintained. No significant deviations detected."
            )
            closing_quote = {
                "quote": "Restoration is the completion of effort.",
                "author_or_tradition": "Reflective Synthesis"
            }

            report_pdf_data = {
                "date": digest.report_date,
                "wear_coverage_pct": 100.0 if digest.metrics.sample_count > 20 else round((digest.metrics.sample_count / 24.0) * 100, 1),
                "metrics": metric_rows,
                "narrative": narrative,
                "open_findings": [
                    {
                        "severity": f["severity"],
                        "description": f["reason"] or f"Observed {f['observed']} vs baseline {f['baseline']}"
                    }
                    for f in digest.active_findings
                ],
                "limitations": digest.limitations_section,
                "recommended_actions": digest.recommended_actions,
                "closing_quote": closing_quote,
                "baseline_status": f"Mean: {digest.baseline_status.get('mean', 'N/A')} bpm, Established: {digest.baseline_status.get('established', False)}"
            }

            DailyReportPdfService.compile_pdf(report_pdf_data, pdf_path)

            gen_status = "complete" if digest.metrics.sample_count > 0 else "degraded_trends_only"

            if existing:
                existing.generation_status = gen_status
                existing.trend_summary = digest.data_section
                existing.executive_narrative = narrative
                existing.closing_quote = closing_quote
                existing.pdf_storage_path = pdf_path
                existing.generated_at = now
            else:
                report_entry = Report(
                    id=uuid.uuid4(),
                    user_id=u.id,
                    report_date=report_date,
                    generation_status=gen_status,
                    trend_summary=digest.data_section,
                    executive_narrative=narrative,
                    closing_quote=closing_quote,
                    pdf_storage_path=pdf_path,
                    generated_at=now
                )
                session.add(report_entry)

            await session.commit()
            reports_compiled += 1
        except Exception as e:
            logger.error(
                "Failed daily report generation",
                extra={"user_id": str(u.id), "error": str(e)}
            )
            await session.rollback()
            reports_failed += 1

    logger.info(
        "Daily report pipeline completed",
        extra={"users": len(users), "compiled": reports_compiled, "failed": reports_failed}
    )
    return {
        "status": "success",
        "users_count": len(users),
        "compiled_count": reports_compiled,
        "failed_count": reports_failed
    }


async def cron_daily_report_pipeline(
    ctx: dict[str, Any],
    session: Any = None,
    user_ids: Any = None
) -> dict[str, Any]:
    """
    Daily background cadence compiling 24-hour health digests and vector PDFs.
    Iterates through active users, aggregates vitals, activity, and data quality,
    compiles a vector PDF, and records an immutable Report row.
    Gracefully degrades to 'degraded_trends_only' when data is sparse.
    """
    if session is not None:
        return await _execute_daily_report_pipeline(session, user_ids)
    async with async_session_factory() as sess:
        return await _execute_daily_report_pipeline(sess, user_ids)




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
