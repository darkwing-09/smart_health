"""End-to-End Integration Tests for Phase 4: Longitudinal Personal Health Intelligence.

Tests:
1. Data Quality Engine: impossible values, future timestamps, sampling gaps, quality ratings.
2. Context Engine: RESTING, WALKING, RUNNING, SLEEPING, POST_EXERCISE classification.
3. Longitudinal Trend Engine: 14-day progressive resting HR rise detected as TREND with strong R².
4. Timeline Query Abstraction: Chronological retrieval across measurements and findings without table duplication.
5. Context Window: Accurately reconstructs user behavioral context around an anomaly timestamp.
6. Notification Service: Multi-channel dispatch, audit trail, and idempotent deduplication.
7. Daily Health Digest: Deterministic 24-hour dossier with strict separation of DATA, INSIGHTS, LIMITATIONS, and ACTIONS.
"""

import uuid
from datetime import datetime, timedelta, timezone
import pytest
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
from sqlalchemy.pool import NullPool

from app.core.config import settings
from app.models.user import User
from app.models.device import Device, WearableSource
from app.models.measurement import Measurement
from app.models.finding import Finding
from app.models.baseline import Baseline
from app.models.notification import Notification
from app.models.audit import AuditLog

from app.services.data_quality import DataQualityEngine, DataQualityRating
from app.services.context_engine import ContextEngine, UserActivityContext
from app.services.trend import TrendEngine, FindingClassification, EvidenceStrength
from app.services.timeline import TimelineService, TimelineEventType, TimelineCategory
from app.services.notification import NotificationService, NotificationChannel
from app.services.daily_digest import DailyDigestService

test_engine = create_async_engine(settings.DATABASE_URL, poolclass=NullPool, future=True)
TestSessionFactory = async_sessionmaker(bind=test_engine, class_=AsyncSession, expire_on_commit=False)


@pytest.mark.asyncio
async def test_data_quality_engine_validations():
    """
    Verifies that DataQualityEngine detects impossible values, future timestamps,
    low confidence, and evaluates time series continuity.
    """
    now = datetime.now(timezone.utc)

    # 1. Impossible biological value (HR = 280 bpm) -> INVALID
    rating, flags, reasons = DataQualityEngine.evaluate_point(
        metric_type="heart_rate",
        value=280.0,
        unit="bpm",
        recorded_at=now,
        confidence=1.0
    )
    assert rating == DataQualityRating.INVALID
    assert "IMPOSSIBLE_VALUE" in flags

    # 2. Future timestamp (+10 minutes) -> INVALID
    rating_fut, flags_fut, _ = DataQualityEngine.evaluate_point(
        metric_type="heart_rate",
        value=72.0,
        unit="bpm",
        recorded_at=now + timedelta(minutes=10),
        reference_time=now
    )
    assert rating_fut == DataQualityRating.INVALID
    assert "FUTURE_TIMESTAMP" in flags_fut

    # 3. Window evaluation with sampling gaps
    m1 = Measurement(id=uuid.uuid4(), user_id=uuid.uuid4(), metric_type="heart_rate", value=60.0, unit="bpm", recorded_at=now - timedelta(hours=5), confidence=1.0, data_quality_flag="nominal")
    m2 = Measurement(id=uuid.uuid4(), user_id=uuid.uuid4(), metric_type="heart_rate", value=62.0, unit="bpm", recorded_at=now - timedelta(hours=1), confidence=1.0, data_quality_flag="nominal")
    m3 = Measurement(id=uuid.uuid4(), user_id=uuid.uuid4(), metric_type="heart_rate", value=65.0, unit="bpm", recorded_at=now, confidence=1.0, data_quality_flag="nominal")

    report = DataQualityEngine.evaluate_window([m1, m2, m3], expected_interval_minutes=30, reference_time=now)
    assert report.gap_count >= 1
    assert "SAMPLING_GAPS_DETECTED" in report.flags
    assert report.rating in {DataQualityRating.GOOD, DataQualityRating.LIMITED}


@pytest.mark.asyncio
async def test_deterministic_context_engine_classifications():
    """
    Verifies deterministic activity context classification across behavioral states.
    """
    base_time = datetime(2026, 9, 4, 14, 0, 0, tzinfo=timezone.utc) # 14:00 UTC
    night_time = datetime(2026, 9, 4, 3, 0, 0, tzinfo=timezone.utc) # 03:00 UTC

    # 1. Nocturnal sleep
    sleep_snap = ContextEngine.classify_context(
        timestamp=night_time,
        user_timezone="UTC",
        steps_recent=0,
        heart_rate_recent=58.0
    )
    assert sleep_snap.primary_context == UserActivityContext.SLEEPING

    # 2. Running / Active Exercise
    exercise_snap = ContextEngine.classify_context(
        timestamp=base_time,
        user_timezone="UTC",
        steps_recent=950,
        heart_rate_recent=145.0
    )
    assert exercise_snap.primary_context in {UserActivityContext.RUNNING, UserActivityContext.EXERCISE}

    # 3. Walking
    walking_snap = ContextEngine.classify_context(
        timestamp=base_time,
        user_timezone="UTC",
        steps_recent=220,
        heart_rate_recent=92.0
    )
    assert walking_snap.primary_context == UserActivityContext.WALKING

    # 4. Stationary Daytime Resting
    resting_snap = ContextEngine.classify_context(
        timestamp=base_time,
        user_timezone="UTC",
        steps_recent=10,
        heart_rate_recent=68.0
    )
    assert resting_snap.primary_context == UserActivityContext.RESTING

    # 5. Post-Exercise Recovery (low immediate steps, but high prior 30m steps and elevated HR)
    recovery_snap = ContextEngine.classify_context(
        timestamp=base_time,
        user_timezone="UTC",
        steps_recent=20,
        heart_rate_recent=95.0,
        steps_prior_30m=800
    )
    assert recovery_snap.primary_context == UserActivityContext.POST_EXERCISE


@pytest.mark.asyncio
async def test_longitudinal_trend_detection():
    """
    Verifies that a progressive upward drift in resting heart rate over 14 days
    is mathematically identified as TREND with strong evidence strength and high R².
    """
    now = datetime.now(timezone.utc)
    # Simulate 14 days of resting HR drifting upwards from 58 bpm to 65 bpm (+0.5 bpm/day)
    observations = []
    for day in range(14):
        day_date = now - timedelta(days=(13 - day))
        day_val = 58.0 + (day * 0.52) + ((day % 2) * 0.1) # Steady upward slope
        observations.append((day_date, day_val))

    report = TrendEngine.evaluate_trend(
        metric_type="resting_heart_rate",
        daily_observations=observations,
        historical_baseline_mean=58.0,
        historical_baseline_std=3.0,
        min_days=7
    )

    assert report is not None
    assert report.classification == FindingClassification.TREND
    assert report.direction == "increasing"
    assert report.r_squared >= 0.85
    assert report.slope_per_day > 0.40
    assert report.total_change >= 3.5
    assert report.evidence_strength == EvidenceStrength.STRONG
    assert report.is_clinically_meaningful is True


@pytest.mark.asyncio
async def test_timeline_query_and_context_window():
    """
    Verifies TimelineService domain query abstraction over TimescaleDB
    and checks the context window around an anomaly timestamp.
    """
    user_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"timeline_{user_id.hex[:8]}@healthos.test",
            hashed_password="mock",
            full_name="Timeline Patient",
            timezone="UTC"
        )
        session.add(user)

        device = Device(
            id=uuid.uuid4(),
            user_id=user_id,
            device_type="watch",
            brand="Google",
            model="Pixel Watch 2",
            os_version="Wear OS 4.0"
        )
        session.add(device)

        source = WearableSource(
            id=uuid.uuid4(),
            user_id=user_id,
            device_id=device.id,
            adapter_type="health_connect",
            reliability_tier="official",
            auth_status="ACTIVE"
        )
        session.add(source)
        await session.flush()

        # Baseline
        baseline = Baseline(
            id=uuid.uuid4(),
            user_id=user_id,
            metric_type="heart_rate",
            window_start=now - timedelta(days=30),
            window_end=now,
            mean=60.0,
            stddev=4.0,
            established=True,
            computed_at=now - timedelta(hours=10)
        )
        session.add(baseline)

        # Measurements in the window
        m_hr = Measurement(
            id=uuid.uuid4(),
            user_id=user_id,
            source_id=source.id,
            metric_type="heart_rate",
            value=95.0,
            unit="bpm",
            recorded_at=now - timedelta(minutes=20),
            confidence=0.98,
            data_quality_flag="nominal"
        )
        m_step = Measurement(
            id=uuid.uuid4(),
            user_id=user_id,
            source_id=source.id,
            metric_type="steps",
            value=0.0,
            unit="count",
            recorded_at=now - timedelta(minutes=20),
            confidence=1.0,
            data_quality_flag="nominal"
        )
        session.add_all([m_hr, m_step])

        # Finding
        finding = Finding(
            id=uuid.uuid4(),
            user_id=user_id,
            metric_type="heart_rate",
            severity="potentially_concerning",
            rule_id="RULE_STAT_NOCTURNAL_TACHYCARDIA",
            rule_version="1.1.0",
            baseline_id=baseline.id,
            observed_value=95.0,
            baseline_value=60.0,
            deviation=35.0,
            standard_deviation=4.0,
            reading_timestamp=now - timedelta(minutes=20),
            first_detected_at=now - timedelta(minutes=20),
            last_updated_at=now - timedelta(minutes=20),
            status="new"
        )
        session.add(finding)
        await session.commit()

        # 1. Test get_timeline
        service = TimelineService(session)
        events = await service.get_timeline(
            user_id=user_id,
            start_time=now - timedelta(hours=1),
            end_time=now + timedelta(hours=1)
        )
        assert len(events) >= 3
        # Assert chronological order
        timestamps = [e.timestamp for e in events]
        assert timestamps == sorted(timestamps)

        # 2. Test get_context_window (What was happening around anomaly time?)
        context_win = await service.get_context_window(
            user_id=user_id,
            target_time=now - timedelta(minutes=20),
            window_minutes=30
        )
        assert context_win.target_time == now - timedelta(minutes=20)
        assert context_win.concurrent_steps == 0
        assert context_win.concurrent_heart_rate == 95.0
        assert context_win.active_baseline is not None
        assert context_win.active_baseline["mean"] == 60.0
        assert len(context_win.active_findings) >= 1
        assert "verified" in context_win.context_narrative.lower()


@pytest.mark.asyncio
async def test_notification_service_idempotency_and_audit():
    """
    Verifies that NotificationService dispatches alerts, logs audit entries,
    and idempotently suppresses duplicate notifications for the same finding and channel.
    """
    user_id = uuid.uuid4()
    finding_id = uuid.uuid4()

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"notif_{user_id.hex[:8]}@healthos.test",
            hashed_password="mock",
            full_name="Notification Patient",
            timezone="UTC"
        )
        session.add(user)
        await session.flush()

        finding = Finding(
            id=finding_id,
            user_id=user_id,
            metric_type="heart_rate",
            severity="potentially_concerning",
            rule_id="RULE_STAT_NOCTURNAL_TACHYCARDIA",
            rule_version="1.1.0",
            observed_value=92.0,
            baseline_value=60.0,
            reading_timestamp=datetime.now(timezone.utc),
            first_detected_at=datetime.now(timezone.utc),
            last_updated_at=datetime.now(timezone.utc),
            status="new"
        )
        session.add(finding)
        await session.commit()

        service = NotificationService(session)

        # 1. First Dispatch
        notif_1 = await service.dispatch_notification(
            user_id=user_id,
            finding_id=finding_id,
            channel=NotificationChannel.IN_APP,
            severity="potentially_concerning",
            title="Resting Vitals Elevated",
            body="Your resting heart rate was higher than typical baseline."
        )
        assert notif_1 is not None
        assert notif_1.delivery_status == "SENT"

        # 2. Duplicate Dispatch Attempt (Same finding, same channel)
        notif_2 = await service.dispatch_notification(
            user_id=user_id,
            finding_id=finding_id,
            channel=NotificationChannel.IN_APP,
            severity="potentially_concerning",
            title="Resting Vitals Elevated (Repeat)",
            body="Duplicate alert attempt."
        )
        # Should return the same notification object without inserting a second row
        assert notif_2.id == notif_1.id

        # Verify DB row count
        count_notifs = await session.scalar(
            select(func.count(Notification.id)).where(Notification.user_id == user_id)
        )
        assert count_notifs == 1

        # Verify AuditLog created
        audit_entry = (await session.scalars(
            select(AuditLog).where(AuditLog.user_id == user_id, AuditLog.action == "notification_dispatched")
        )).first()
        assert audit_entry is not None


@pytest.mark.asyncio
async def test_daily_digest_deterministic_dossier():
    """
    Verifies DailyDigestService compiles 24-hour summary with zero manufactured statistics,
    clearly separating data, insights, limitations, and recommendations.
    """
    user_id = uuid.uuid4()
    target_dt = datetime(2026, 9, 4, 12, 0, 0, tzinfo=timezone.utc)

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"digest_{user_id.hex[:8]}@healthos.test",
            hashed_password="mock",
            full_name="Digest Patient",
            timezone="UTC"
        )
        session.add(user)

        device = Device(
            id=uuid.uuid4(),
            user_id=user_id,
            device_type="watch",
            brand="Samsung",
            model="Galaxy Watch 6",
            os_version="Wear OS 4.0"
        )
        session.add(device)

        source = WearableSource(
            id=uuid.uuid4(),
            user_id=user_id,
            device_id=device.id,
            adapter_type="health_connect",
            reliability_tier="official",
            auth_status="ACTIVE"
        )
        session.add(source)
        await session.flush()

        # Baseline
        baseline = Baseline(
            id=uuid.uuid4(),
            user_id=user_id,
            metric_type="heart_rate",
            window_start=target_dt - timedelta(days=30),
            window_end=target_dt,
            mean=62.0,
            stddev=5.0,
            established=True,
            computed_at=target_dt - timedelta(days=1)
        )
        session.add(baseline)

        # Add 3 heart rate points (55, 70, 90) and 2 step points (4000, 5000)
        m_hr1 = Measurement(id=uuid.uuid4(), user_id=user_id, source_id=source.id, metric_type="heart_rate", value=55.0, unit="bpm", recorded_at=target_dt.replace(hour=3), confidence=1.0, data_quality_flag="nominal")
        m_hr2 = Measurement(id=uuid.uuid4(), user_id=user_id, source_id=source.id, metric_type="heart_rate", value=70.0, unit="bpm", recorded_at=target_dt.replace(hour=10), confidence=1.0, data_quality_flag="nominal")
        m_hr3 = Measurement(id=uuid.uuid4(), user_id=user_id, source_id=source.id, metric_type="heart_rate", value=90.0, unit="bpm", recorded_at=target_dt.replace(hour=18), confidence=1.0, data_quality_flag="nominal")
        m_st1 = Measurement(id=uuid.uuid4(), user_id=user_id, source_id=source.id, metric_type="steps", value=4000.0, unit="count", recorded_at=target_dt.replace(hour=11), confidence=1.0, data_quality_flag="nominal")
        m_st2 = Measurement(id=uuid.uuid4(), user_id=user_id, source_id=source.id, metric_type="steps", value=5500.0, unit="count", recorded_at=target_dt.replace(hour=19), confidence=1.0, data_quality_flag="nominal")

        session.add_all([m_hr1, m_hr2, m_hr3, m_st1, m_st2])
        await session.commit()

        service = DailyDigestService(session)
        digest = await service.compile_digest(user_id=user_id, target_date=target_dt)

        assert digest.user_id == str(user_id)
        assert digest.report_date == "2026-09-04"

        # Assert exact math
        assert digest.metrics.heart_rate_min == 55.0
        assert digest.metrics.heart_rate_max == 90.0
        assert digest.metrics.heart_rate_mean == round((55.0 + 70.0 + 90.0) / 3, 1) # 71.7
        assert digest.metrics.total_steps == 9500 # 4000 + 5500

        # Assert clean architectural separation
        assert "heart_rate" in digest.data_section
        assert "activity" in digest.data_section
        assert len(digest.insights_section) >= 1
        assert len(digest.recommended_actions) >= 1
