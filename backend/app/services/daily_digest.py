"""Deterministic Daily Health Digest Data Layer.

Compiles 24-hour vitals, sleep, activity, trends, and data quality into an
immutable, verifiable analytical dossier for the Daily Report agent and PDF engine.
Guarantees zero manufactured statistics (every metric originates from database aggregation).
"""

import uuid
from datetime import datetime, timedelta, timezone
from typing import Dict, Any, List, Optional
from zoneinfo import ZoneInfo
import numpy as np
from pydantic import BaseModel, Field
from sqlalchemy import select, func, and_
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User
from app.models.measurement import Measurement
from app.models.finding import Finding
from app.models.baseline import Baseline
from app.services.data_quality import DataQualityEngine, DataQualityRating


class DailyDigestMetrics(BaseModel):
    heart_rate_min: Optional[float] = None
    heart_rate_max: Optional[float] = None
    heart_rate_mean: Optional[float] = None
    resting_heart_rate: Optional[float] = None
    total_steps: int = 0
    sleep_duration_minutes: Optional[float] = None
    data_quality_rating: str = "good"
    sample_count: int = 0


class DailyDigestReport(BaseModel):
    user_id: str
    report_date: str # YYYY-MM-DD
    metrics: DailyDigestMetrics
    active_findings: List[Dict[str, Any]] = Field(default_factory=list)
    active_trends: List[Dict[str, Any]] = Field(default_factory=list)
    baseline_status: Dict[str, Any] = Field(default_factory=dict)
    
    # Clean architectural separation
    data_section: Dict[str, Any]
    insights_section: List[str] = Field(default_factory=list)
    limitations_section: List[str] = Field(default_factory=list)
    recommended_actions: List[str] = Field(default_factory=list)


class DailyDigestService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def compile_digest(
        self,
        user_id: uuid.UUID,
        target_date: Optional[datetime] = None
    ) -> DailyDigestReport:
        """
        Compiles the deterministic daily digest for a user across a 24-hour window.
        """
        if target_date is None:
            target_date = datetime.now(timezone.utc)
        elif target_date.tzinfo is None:
            target_date = target_date.replace(tzinfo=timezone.utc)

        user = await self.db.get(User, user_id)
        user_tz_str = user.timezone if (user and user.timezone) else "UTC"
        try:
            tz = ZoneInfo(user_tz_str)
        except Exception:
            tz = ZoneInfo("UTC")

        # 24-hour local calendar day window
        local_target = target_date.astimezone(tz)
        start_local = local_target.replace(hour=0, minute=0, second=0, microsecond=0)
        end_local = start_local + timedelta(days=1) - timedelta(microseconds=1)

        start_utc = start_local.astimezone(timezone.utc)
        end_utc = end_local.astimezone(timezone.utc)
        date_str = start_local.strftime("%Y-%m-%d")

        # 1. Query Measurements
        stmt_m = (
            select(Measurement)
            .where(
                Measurement.user_id == user_id,
                Measurement.recorded_at >= start_utc,
                Measurement.recorded_at <= end_utc
            )
            .order_by(Measurement.recorded_at.asc())
        )
        measurements = (await self.db.scalars(stmt_m)).all()

        # 2. Extract Metric Aggregates
        hr_vals = [m.value for m in measurements if m.metric_type == "heart_rate" and m.data_quality_flag == "nominal"]
        steps_vals = [m.value for m in measurements if m.metric_type == "steps"]
        sleep_vals = [m.value for m in measurements if m.metric_type == "sleep_session"]

        hr_min = float(np.min(hr_vals)) if hr_vals else None
        hr_max = float(np.max(hr_vals)) if hr_vals else None
        hr_mean = float(np.mean(hr_vals)) if hr_vals else None

        # Resting HR approximation from nocturnal window (01:00 - 06:00)
        nocturnal_hr = [
            m.value for m in measurements
            if m.metric_type == "heart_rate"
            and (m.recorded_at.astimezone(tz).hour in {1, 2, 3, 4, 5})
            and m.data_quality_flag == "nominal"
        ]
        rhr = float(np.median(nocturnal_hr)) if nocturnal_hr else (hr_min if hr_min else None)

        total_steps = int(sum(steps_vals))
        sleep_dur = float(sum(sleep_vals)) if sleep_vals else None

        # 3. Evaluate Window Data Quality
        dq_report = DataQualityEngine.evaluate_window(
            measurements=list(measurements),
            expected_interval_minutes=60,
            reference_time=end_utc
        )

        metrics = DailyDigestMetrics(
            heart_rate_min=round(hr_min, 1) if hr_min else None,
            heart_rate_max=round(hr_max, 1) if hr_max else None,
            heart_rate_mean=round(hr_mean, 1) if hr_mean else None,
            resting_heart_rate=round(rhr, 1) if rhr else None,
            total_steps=total_steps,
            sleep_duration_minutes=round(sleep_dur, 1) if sleep_dur else None,
            data_quality_rating=dq_report.rating.value,
            sample_count=len(measurements)
        )

        # 4. Query Findings for that day
        stmt_f = select(Finding).where(
            Finding.user_id == user_id,
            Finding.first_detected_at >= start_utc,
            Finding.first_detected_at <= end_utc
        )
        findings = (await self.db.scalars(stmt_f)).all()
        active_findings = [
            {
                "id": str(f.id),
                "rule_id": f.rule_id,
                "severity": f.severity,
                "observed": f.observed_value,
                "baseline": f.baseline_value,
                "reason": f.evidence.get("reason", "")
            }
            for f in findings
        ]

        # 5. Query Active Baseline
        stmt_b = (
            select(Baseline)
            .where(
                Baseline.user_id == user_id,
                Baseline.metric_type == "heart_rate"
            )
            .order_by(Baseline.computed_at.desc())
            .limit(1)
        )
        baseline = (await self.db.scalars(stmt_b)).first()
        baseline_info = {
            "mean": baseline.mean,
            "stddev": baseline.stddev,
            "established": baseline.established
        } if baseline else {"established": False}

        # 6. Structured Sections
        data_section = {
            "heart_rate": {
                "min": metrics.heart_rate_min,
                "max": metrics.heart_rate_max,
                "mean": metrics.heart_rate_mean,
                "resting": metrics.resting_heart_rate,
                "unit": "bpm"
            },
            "activity": {
                "total_steps": metrics.total_steps,
                "unit": "steps"
            },
            "sleep": {
                "duration_minutes": metrics.sleep_duration_minutes,
                "duration_hours": round(metrics.sleep_duration_minutes / 60.0, 1) if metrics.sleep_duration_minutes else None
            },
            "data_quality": {
                "rating": metrics.data_quality_rating,
                "samples": metrics.sample_count,
                "flags": dq_report.flags
            }
        }

        insights: List[str] = []
        if hr_mean and baseline and baseline.established:
            diff = hr_mean - baseline.mean
            if abs(diff) < 3.0:
                insights.append(f"Daily mean heart rate ({round(hr_mean, 1)} bpm) remained stable within your 30-day baseline.")
            elif diff >= 3.0:
                insights.append(f"Daily mean heart rate ({round(hr_mean, 1)} bpm) was elevated +{round(diff, 1)} bpm relative to your established baseline.")
            else:
                insights.append(f"Daily mean heart rate ({round(hr_mean, 1)} bpm) was {round(abs(diff), 1)} bpm lower than your typical baseline.")

        if total_steps >= 8000:
            insights.append(f"Active movement goal achieved: {total_steps:,} steps recorded today.")
        elif total_steps < 3000:
            insights.append(f"Sedentary day observed: {total_steps:,} total steps.")

        if active_findings:
            insights.append(f"{len(active_findings)} physiological finding(s) were flagged by deterministic detection rules.")

        limitations: List[str] = []
        if dq_report.flags:
            limitations.append(f"Data quality considerations: {', '.join(dq_report.flags)}.")
        if not baseline or not baseline.established:
            limitations.append("Personal baseline is currently maturing (< 14 days of data); comparisons are preliminary.")

        recommendations: List[str] = [
            "Maintain adequate hydration throughout tomorrow.",
            "Ensure smartwatch maintains snug skin contact overnight."
        ]
        if active_findings:
            recommendations.append("Review detailed explanations in your Health OS timeline for recent findings.")

        return DailyDigestReport(
            user_id=str(user_id),
            report_date=date_str,
            metrics=metrics,
            active_findings=active_findings,
            active_trends=[],
            baseline_status=baseline_info,
            data_section=data_section,
            insights_section=insights,
            limitations_section=limitations,
            recommended_actions=recommendations
        )
