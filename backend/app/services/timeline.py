"""Longitudinal Personal Health Timeline Abstraction.

Provides a unified domain query abstraction over measurements, findings,
baselines, and activity context without redundant table duplication.
Answers: 'What was happening with this user around the time this anomaly occurred?'
"""

import uuid
from enum import Enum
from datetime import datetime, timedelta, timezone
from typing import List, Dict, Any, Optional
from zoneinfo import ZoneInfo
from pydantic import BaseModel, Field
from sqlalchemy import select, and_, func
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User
from app.models.measurement import Measurement
from app.models.finding import Finding
from app.models.baseline import Baseline
from app.services.data_quality import DataQualityEngine, DataQualityReport, DataQualityRating
from app.services.context_engine import ContextEngine, ContextSnapshot, UserActivityContext


class TimelineEventType(str, Enum):
    OBSERVATION = "OBSERVATION"
    FINDING = "FINDING"
    BASELINE_SHIFT = "BASELINE_SHIFT"
    CONTEXT_PERIOD = "CONTEXT_PERIOD"
    USER_EVENT = "USER_EVENT"


class TimelineCategory(str, Enum):
    VITAL = "VITAL"
    ACTIVITY = "ACTIVITY"
    SLEEP = "SLEEP"
    ANOMALY = "ANOMALY"
    BASELINE = "BASELINE"
    SAFETY = "SAFETY"


class TimelineEvent(BaseModel):
    event_id: str
    event_type: TimelineEventType
    category: TimelineCategory
    timestamp: datetime
    local_timestamp: str
    title: str
    summary: str
    metric_type: Optional[str] = None
    value: Optional[float] = None
    unit: Optional[str] = None
    severity: Optional[str] = None
    data_quality: str = "nominal"
    provenance: Dict[str, Any] = Field(default_factory=dict)
    payload: Dict[str, Any] = Field(default_factory=dict)


class TimelineContextWindow(BaseModel):
    target_time: datetime
    local_target_time: str
    window_minutes: int
    activity_context: UserActivityContext
    concurrent_steps: int
    concurrent_heart_rate: Optional[float] = None
    active_baseline: Optional[Dict[str, Any]] = None
    data_quality_report: DataQualityReport
    surrounding_events: List[TimelineEvent] = Field(default_factory=list)
    active_findings: List[Dict[str, Any]] = Field(default_factory=list)
    context_narrative: str


class TimelineService:
    """
    Timeline Domain Query Service.
    Aggregates measurements, baselines, and findings into a coherent longitudinal timeline.
    """

    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def get_timeline(
        self,
        user_id: uuid.UUID,
        start_time: datetime,
        end_time: datetime,
        categories: Optional[List[TimelineCategory]] = None,
        limit: int = 150
    ) -> List[TimelineEvent]:
        """
        Retrieves a chronological sequence of timeline events within a time range.
        """
        user = await self.db.get(User, user_id)
        user_tz_str = user.timezone if (user and user.timezone) else "UTC"
        try:
            tz = ZoneInfo(user_tz_str)
        except Exception:
            tz = ZoneInfo("UTC")

        events: List[TimelineEvent] = []

        # 1. Query Measurements
        stmt_m = (
            select(Measurement)
            .where(
                Measurement.user_id == user_id,
                Measurement.recorded_at >= start_time,
                Measurement.recorded_at <= end_time
            )
            .order_by(Measurement.recorded_at.asc())
            .limit(limit)
        )
        measurements = (await self.db.scalars(stmt_m)).all()

        for m in measurements:
            loc_time = m.recorded_at.astimezone(tz).strftime("%Y-%m-%d %H:%M:%S")
            cat = (
                TimelineCategory.ACTIVITY if m.metric_type == "steps"
                else TimelineCategory.SLEEP if m.metric_type == "sleep_session"
                else TimelineCategory.VITAL
            )

            if categories and cat not in categories:
                continue

            events.append(
                TimelineEvent(
                    event_id=str(m.id),
                    event_type=TimelineEventType.OBSERVATION,
                    category=cat,
                    timestamp=m.recorded_at,
                    local_timestamp=loc_time,
                    title=f"{m.metric_type.replace('_', ' ').title()} Reading",
                    summary=f"{m.value} {m.unit} recorded via wearable.",
                    metric_type=m.metric_type,
                    value=m.value,
                    unit=m.unit,
                    data_quality=m.data_quality_flag,
                    provenance={"source_id": str(m.source_id), "confidence": m.confidence}
                )
            )

        # 2. Query Findings
        stmt_f = (
            select(Finding)
            .where(
                Finding.user_id == user_id,
                Finding.first_detected_at >= start_time,
                Finding.first_detected_at <= end_time
            )
            .order_by(Finding.first_detected_at.asc())
        )
        findings = (await self.db.scalars(stmt_f)).all()

        for f in findings:
            loc_time = f.first_detected_at.astimezone(tz).strftime("%Y-%m-%d %H:%M:%S")
            cat = TimelineCategory.SAFETY if f.severity == "urgent" else TimelineCategory.ANOMALY
            if categories and cat not in categories:
                continue

            events.append(
                TimelineEvent(
                    event_id=str(f.id),
                    event_type=TimelineEventType.FINDING,
                    category=cat,
                    timestamp=f.first_detected_at,
                    local_timestamp=loc_time,
                    title=f"Finding: {f.rule_id}",
                    summary=f"Severity: {f.severity}. Observed: {f.observed_value} vs Baseline: {f.baseline_value}.",
                    metric_type=f.metric_type,
                    value=f.observed_value,
                    severity=f.severity,
                    data_quality=f.data_quality or "nominal",
                    provenance={"rule_id": f.rule_id, "rule_version": f.rule_version, "evidence": f.evidence}
                )
            )

        # Sort all unified events chronologically
        events.sort(key=lambda e: e.timestamp)
        return events[:limit]

    async def get_context_window(
        self,
        user_id: uuid.UUID,
        target_time: datetime,
        window_minutes: int = 60
    ) -> TimelineContextWindow:
        """
        Answers: 'What was happening with this user around the time this anomaly occurred?'
        Retrieves context in +/- window_minutes around target_time.
        """
        user = await self.db.get(User, user_id)
        user_tz_str = user.timezone if (user and user.timezone) else "UTC"
        try:
            tz = ZoneInfo(user_tz_str)
        except Exception:
            tz = ZoneInfo("UTC")

        if target_time.tzinfo is None:
            target_time = target_time.replace(tzinfo=timezone.utc)

        w_start = target_time - timedelta(minutes=window_minutes)
        w_end = target_time + timedelta(minutes=window_minutes)

        # 1. Fetch surrounding measurements
        stmt_m = (
            select(Measurement)
            .where(
                Measurement.user_id == user_id,
                Measurement.recorded_at >= w_start,
                Measurement.recorded_at <= w_end
            )
            .order_by(Measurement.recorded_at.asc())
        )
        measurements = (await self.db.scalars(stmt_m)).all()

        # 2. Evaluate Data Quality of the surrounding window
        quality_report = DataQualityEngine.evaluate_window(
            measurements=list(measurements),
            expected_interval_minutes=15,
            reference_time=target_time
        )

        # 3. Aggregate concurrent steps and heart rate
        concurrent_steps = 0
        recent_hr: Optional[float] = None

        for m in measurements:
            if m.metric_type == "steps":
                concurrent_steps += int(m.value)
            elif m.metric_type == "heart_rate":
                recent_hr = m.value

        # 4. Context Classification
        context_snap = ContextEngine.classify_context(
            timestamp=target_time,
            user_timezone=user_tz_str,
            steps_recent=concurrent_steps,
            heart_rate_recent=recent_hr
        )

        # 5. Fetch baseline active at that time
        stmt_b = (
            select(Baseline)
            .where(
                Baseline.user_id == user_id,
                Baseline.metric_type == "heart_rate",
                Baseline.computed_at <= target_time
            )
            .order_by(Baseline.computed_at.desc())
            .limit(1)
        )
        baseline = (await self.db.scalars(stmt_b)).first()
        baseline_dict = {
            "mean": baseline.mean,
            "stddev": baseline.stddev,
            "established": baseline.established
        } if baseline else None

        # 6. Fetch findings in window
        stmt_f = (
            select(Finding)
            .where(
                Finding.user_id == user_id,
                Finding.first_detected_at >= w_start,
                Finding.first_detected_at <= w_end
            )
        )
        findings = (await self.db.scalars(stmt_f)).all()
        findings_summary = [
            {
                "id": str(f.id),
                "rule_id": f.rule_id,
                "severity": f.severity,
                "observed": f.observed_value,
                "baseline": f.baseline_value
            }
            for f in findings
        ]

        # 7. Surrounding Events
        surrounding_events = await self.get_timeline(user_id, w_start, w_end, limit=20)

        # 8. Synthesize deterministic narrative answering the question
        loc_time_str = target_time.astimezone(tz).strftime("%H:%M on %Y-%m-%d")
        narrative = (
            f"At {loc_time_str}, the user was in a verified {context_snap.primary_context.value} state "
            f"({concurrent_steps} steps recorded across the {window_minutes}-minute window). "
            f"Data quality is rated as {quality_report.rating.value.upper()} with {len(measurements)} samples. "
        )
        if baseline_dict:
            narrative += f"Active baseline for heart rate was {baseline_dict['mean']} ± {baseline_dict['stddev']} bpm. "
        if findings_summary:
            narrative += f"{len(findings_summary)} finding(s) were active in this timeframe."
        else:
            narrative += "No conflicting findings were present in this timeframe."

        return TimelineContextWindow(
            target_time=target_time,
            local_target_time=loc_time_str,
            window_minutes=window_minutes,
            activity_context=context_snap.primary_context,
            concurrent_steps=concurrent_steps,
            concurrent_heart_rate=recent_hr,
            active_baseline=baseline_dict,
            data_quality_report=quality_report,
            surrounding_events=surrounding_events,
            active_findings=findings_summary,
            context_narrative=narrative
        )
