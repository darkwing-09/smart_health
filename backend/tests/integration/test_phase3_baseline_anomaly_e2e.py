"""End-to-End Integration Tests for Phase 3: Baseline Intelligence & Anomaly Pipeline.

Tests:
1. 30-day synthetic telemetry generation.
2. Timezone-aware circadian baseline calculation against live TimescaleDB.
3. Baseline establishment rules (span >= 14 days, sample counts).
4. False-positive resistance: normal sleep and high-step workout produce 0 false findings.
5. Injected nocturnal resting tachycardia triggers a durable Finding with complete provenance.
6. Pipeline idempotency: rerunning produces 0 duplicate findings.
7. HealthIntelligenceGraph invocation generates grounded 7-part explanation.
8. Safety guardrail replaces prohibited diagnostic terms with safe fallback.
9. Hard biological safety gates (Rule H2 ceiling/floor).
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
from app.models.baseline import Baseline
from app.models.finding import Finding, FindingExplanation
from app.services.baseline import BaselineService
from app.services.anomaly import AnomalyDetector
from app.services.anomaly_pipeline import AnomalyPipelineService
from app.services.synthetic_data import SyntheticDataGenerator
from app.graphs.health_intel import build_health_intel_graph

test_engine = create_async_engine(settings.DATABASE_URL, poolclass=NullPool, future=True)
TestSessionFactory = async_sessionmaker(bind=test_engine, class_=AsyncSession, expire_on_commit=False)


@pytest.fixture
async def synthetic_patient():
    """Seeds a patient with 30 days of deterministic synthetic telemetry in live TimescaleDB."""
    user_id = uuid.uuid4()
    source_id = uuid.uuid4()
    device_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    generator = SyntheticDataGenerator(seed=42)
    measurements, meta = generator.generate_30_day_timeline(
        user_id=user_id,
        source_id=source_id,
        reference_time=now,
        user_timezone="Asia/Kolkata"
    )

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"patient30d_{user_id.hex[:8]}@healthos.test",
            hashed_password="argon2_hashed_password",
            full_name="Longitudinal Test Patient",
            timezone="Asia/Kolkata",
            is_active=True
        )
        session.add(user)

        device = Device(
            id=device_id,
            user_id=user_id,
            device_type="watch",
            brand="Samsung",
            model="Galaxy Watch 6",
            os_version="Wear OS 4.0"
        )
        session.add(device)

        source = WearableSource(
            id=source_id,
            user_id=user_id,
            device_id=device_id,
            adapter_type="health_connect",
            reliability_tier="official",
            auth_status="ACTIVE"
        )
        session.add(source)

        # Batch insert measurements into TimescaleDB
        session.add_all(measurements)
        await session.commit()

    yield {
        "user_id": user_id,
        "source_id": source_id,
        "reference_time": now,
        "injected_anomalies": meta["injected_anomalies"],
        "total_records": len(measurements)
    }


@pytest.mark.asyncio
async def test_baseline_calculation_and_circadian_seasonality(synthetic_patient):
    """
    Verifies that BaselineService computes rolling 30-day baseline, marks established=True,
    and accurately models circadian day/night difference in patient's local timezone.
    """
    user_id = synthetic_patient["user_id"]
    ref_time = synthetic_patient["reference_time"]

    async with TestSessionFactory() as session:
        service = BaselineService(session)
        baseline = await service.compute_baseline(
            user_id=user_id,
            metric_type="heart_rate",
            current_date=ref_time,
            user_timezone="Asia/Kolkata"
        )

        assert baseline is not None
        assert baseline.established is True
        assert baseline.metric_type == "heart_rate"
        assert 65.0 <= baseline.mean <= 80.0 # Overall 24-hour mean

        # Check circadian seasonality profile
        profile = baseline.seasonality_profile
        assert "3" in profile # 03:00 AM local time
        assert "14" in profile # 14:00 PM local time

        night_mean = profile["3"]["mean"]
        day_mean = profile["14"]["mean"]

        # Night mean should be substantially lower than daytime waking mean
        assert night_mean < day_mean
        assert 55.0 <= night_mean <= 62.0
        assert 70.0 <= day_mean <= 80.0


@pytest.mark.asyncio
async def test_baseline_unestablished_suppression():
    """
    Verifies that a user with only 3 days of data (< 14 days) has established=False,
    and statistical anomaly alerts are suppressed.
    """
    user_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"short_patient_{user_id.hex[:8]}@healthos.test",
            hashed_password="mock",
            full_name="Short Window Patient",
            timezone="UTC"
        )
        session.add(user)

        # Add 3 days of measurements
        records = []
        for i in range(24 * 3):
            records.append(
                Measurement(
                    id=uuid.uuid4(),
                    user_id=user_id,
                    source_id=uuid.uuid4(),
                    metric_type="heart_rate",
                    value=60.0,
                    unit="bpm",
                    recorded_at=now - timedelta(hours=i),
                    confidence=1.0,
                    data_quality_flag="nominal"
                )
            )
        session.add_all(records)
        await session.commit()

        service = BaselineService(session)
        baseline = await service.compute_baseline(user_id, "heart_rate", now)
        assert baseline.established is False

        # Statistical evaluation should return None (suppressed)
        result = AnomalyDetector.evaluate(
            current_value=95.0, # High value
            reading_hour=3,
            baseline=baseline,
            steps_recent=0
        )
        assert result is None


@pytest.mark.asyncio
async def test_false_positive_resistance(synthetic_patient):
    """
    Verifies that:
    1. Normal nocturnal heart rate (58 bpm, 0 steps) produces NO anomaly.
    2. Elevated heart rate during high physical activity (132 bpm, 1800 steps) is exertion-suppressed.
    """
    user_id = synthetic_patient["user_id"]
    ref_time = synthetic_patient["reference_time"]

    async with TestSessionFactory() as session:
        service = BaselineService(session)
        baseline = await service.compute_baseline(user_id, "heart_rate", ref_time)

        # Case 1: Normal nighttime sleep
        sleep_eval = AnomalyDetector.evaluate(
            current_value=59.0,
            reading_hour=3,
            baseline=baseline,
            steps_recent=0
        )
        assert sleep_eval is None

        # Case 2: High HR during active workout (132 bpm with 1800 steps)
        workout_eval = AnomalyDetector.evaluate(
            current_value=132.0,
            reading_hour=18,
            baseline=baseline,
            steps_recent=1800,
            is_active_workout=True
        )
        assert workout_eval is None


@pytest.mark.asyncio
async def test_nocturnal_resting_tachycardia_pipeline_and_idempotency(synthetic_patient):
    """
    Verifies:
    1. Injected nocturnal resting tachycardia (94 bpm at 03:00 AM, 0 steps) is flagged.
    2. Durable Finding is persisted with complete provenance and evidence.
    3. HealthIntelligenceGraph produces grounded 7-part explanation linked to Finding.
    4. Rerunning the pipeline against the same window is 100% idempotent (0 duplicate findings).
    """
    user_id = synthetic_patient["user_id"]
    ref_time = synthetic_patient["reference_time"]

    # Window covering the last 24 hours containing the injected anomaly at 03:00 AM
    eval_start = ref_time - timedelta(hours=24)
    eval_end = ref_time

    async with TestSessionFactory() as session:
        pipeline = AnomalyPipelineService(session)

        # 1. First Execution -> Should detect nocturnal resting tachycardia and create Finding
        findings_run_1 = await pipeline.run_pipeline_for_user(
            user_id=user_id,
            eval_window_start=eval_start,
            eval_window_end=eval_end,
            auto_explain=True
        )

        assert len(findings_run_1) >= 1
        finding = findings_run_1[0]

        # Verify Finding Domain Attributes
        assert finding.metric_type == "heart_rate"
        assert finding.rule_id == "RULE_STAT_NOCTURNAL_TACHYCARDIA"
        assert finding.severity in {"potentially_concerning", "urgent"}
        assert finding.observed_value == 94.0
        assert finding.baseline_value is not None
        assert finding.deviation is not None
        assert finding.deviation > 30.0 # 94 bpm vs ~58 bpm baseline
        assert finding.activity_context["steps_recent"] == 0
        assert finding.evidence["z_score"] >= settings.ANOMALY_ZSCORE_CONCERNING

        # Verify Explanation Attached
        stmt_expl = select(FindingExplanation).where(FindingExplanation.finding_id == finding.id)
        explanation = (await session.execute(stmt_expl)).scalar_one_or_none()
        assert explanation is not None
        assert explanation.agent_id == "agent:health_intel"
        assert "resting heart_rate" in explanation.what_changed
        assert "why_it_matters" in dir(explanation)
        assert len(explanation.next_steps) >= 2

        # Verify database record exists
        db_finding = await session.get(Finding, finding.id)
        assert db_finding is not None

        # 2. Second Execution (Idempotency Check) -> Running again against same window must NOT duplicate findings
        findings_run_2 = await pipeline.run_pipeline_for_user(
            user_id=user_id,
            eval_window_start=eval_start,
            eval_window_end=eval_end,
            auto_explain=True
        )
        assert len(findings_run_2) == 0 # 0 new findings created

        # Direct database count assertion
        total_user_findings = await session.scalar(
            select(func.count(Finding.id)).where(Finding.user_id == user_id)
        )
        assert total_user_findings == len(findings_run_1)


@pytest.mark.asyncio
async def test_hard_biological_safety_gates_and_guardrail():
    """
    Verifies Rule H2 hard biological gates (severe tachycardia >= 150 bpm, bradycardia <= 38 bpm)
    and verifies that Rule H1 safety guardrail intercepts medical diagnoses.
    """
    now = datetime.now(timezone.utc)
    baseline = Baseline(
        id=uuid.uuid4(),
        user_id=uuid.uuid4(),
        metric_type="heart_rate",
        window_start=now,
        window_end=now,
        mean=65.0,
        stddev=6.0,
        established=True,
        rule_version="1.1.0"
    )

    # Ceiling Gate
    eval_ceiling = AnomalyDetector.evaluate(
        current_value=158.0, # >= 150 bpm
        reading_hour=14,
        baseline=baseline,
        steps_recent=0
    )
    assert eval_ceiling is not None
    assert eval_ceiling["finding_type"] == "SAFETY_FINDING"
    assert eval_ceiling["rule_id"] == "RULE_H2_CEILING"
    assert eval_ceiling["severity"] == "urgent"

    # Floor Gate
    eval_floor = AnomalyDetector.evaluate(
        current_value=34.0, # <= 38 bpm
        reading_hour=3,
        baseline=baseline,
        steps_recent=0
    )
    assert eval_floor is not None
    assert eval_floor["finding_type"] == "SAFETY_FINDING"
    assert eval_floor["rule_id"] == "RULE_H2_FLOOR"
    assert eval_floor["severity"] == "urgent"

    # Safety Guardrail Intercept Test
    graph = build_health_intel_graph()
    unsafe_state = {
        "finding_id": "test_find",
        "metric_type": "heart_rate",
        "observed_value": 158.0,
        "unit": "bpm",
        "recorded_at": now.isoformat(),
        "baseline": {"circadian_mean": 65.0, "circadian_std": 6.0},
        "explanation": {
            "what_changed": "Patient is having a myocardial infarction and ventricular arrhythmia.",
            "measurements_caused": ["Observed: 158 bpm"],
            "baseline_difference": "Elevated",
            "historical_context": "None",
            "confidence_and_data_quality": "Nominal",
            "why_it_matters": "Active acute disease",
            "next_steps": ["Take beta blockers"]
        },
        "safety_approved": False,
        "safety_violations": []
    }
    result = await graph.ainvoke(unsafe_state)
    # Prohibited clinical diagnosis terms must be replaced by safe fallback
    full_text = str(result["explanation"]).lower()
    assert "myocardial infarction" not in full_text
    assert "arrhythmia" not in full_text
    assert "statistical shift" in result["explanation"]["what_changed"]
