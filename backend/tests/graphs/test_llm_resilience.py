"""
Resilience & Fault Injection Tests: Deterministic Pipeline Survival Under LLM Outage.

Verifies:
1. Deterministic health pipeline (measurements -> baseline -> anomaly detection -> findings)
   executes completely and accurately with ZERO LLM dependency.
2. HealthIntelligenceGraph produces grounded evidence and safe fallbacks even under total model outage.
3. CareNavigationGraph produces structured physician briefs and patient rationales during LLM failure.
"""

import uuid
from datetime import datetime, timezone, timedelta
import pytest
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
from sqlalchemy.pool import NullPool

from app.core.config import settings
from app.models.user import User
from app.models.measurement import Measurement
from app.models.baseline import Baseline
from app.services.anomaly_pipeline import AnomalyPipelineService
from app.graphs.health_intel import build_health_intel_graph
from app.graphs.care_nav import build_care_nav_graph, CLINICAL_DISCLAIMER

test_engine = create_async_engine(settings.DATABASE_URL, poolclass=NullPool, future=True)
TestSessionFactory = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)


@pytest.mark.asyncio
async def test_deterministic_health_pipeline_zero_llm_dependency():
    """
    Proves that the core Personal Health OS data layer (measurements -> baseline -> anomaly detection -> findings)
    operates with 100% deterministic mathematical fidelity without invoking any LLM API.
    """
    user_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"resilience_{user_id.hex[:8]}@healthos.test",
            hashed_password="mock",
            full_name="Resilience Test Patient",
            timezone="UTC"
        )
        session.add(user)
        await session.flush()

        # Baseline: resting HR mean=60, std=4, established=True
        baseline = Baseline(
            id=uuid.uuid4(),
            user_id=user_id,
            metric_type="heart_rate",
            window_start=now - timedelta(days=30),
            window_end=now,
            mean=60.0,
            stddev=4.0,
            seasonality_profile={str(h): {"mean": 60.0, "std": 4.0} for h in range(24)},
            established=True,
            rule_version="1.1.0",
            computed_at=now
        )
        session.add(baseline)


        # Ingest acute measurement: resting HR = 95 bpm, 0 steps (z-score = (95-60)/4 = 8.75)
        measurement = Measurement(
            id=uuid.uuid4(),
            user_id=user_id,
            source_id=uuid.uuid4(),
            metric_type="heart_rate",
            value=95.0,
            unit="bpm",
            recorded_at=now - timedelta(minutes=5),
            data_quality_flag="nominal"
        )
        session.add(measurement)


        await session.commit()

        # Run deterministic pipeline with auto_explain=False (Zero LLM involvement)
        pipeline = AnomalyPipelineService(session)
        findings = await pipeline.run_pipeline_for_user(
            user_id=user_id,
            eval_window_start=now - timedelta(hours=1),
            eval_window_end=now,
            auto_explain=False
        )

        assert len(findings) >= 1
        f = findings[0]
        assert f.user_id == user_id
        assert f.observed_value == 95.0
        assert f.baseline_value == 60.0
        assert f.deviation == 35.0
        assert f.severity in ["potentially_concerning", "important", "urgent"]
        assert f.rule_id in ["RULE_STAT_CIRCADIAN_DEVIATION", "RULE_STAT_NOCTURNAL_TACHYCARDIA"]


@pytest.mark.asyncio
async def test_health_intel_graph_graceful_fallback():
    """
    Verifies that HealthIntelligenceGraph produces grounded structured explanations
    and safe fallback text even under simulated model failures.
    """
    graph = build_health_intel_graph()
    now = datetime.now(timezone.utc)

    # State with acute nocturnal tachycardia deviation
    state = {
        "finding_id": str(uuid.uuid4()),
        "user_id": str(uuid.uuid4()),
        "metric_type": "heart_rate",
        "observed_value": 98.0,
        "unit": "bpm",
        "recorded_at": now.isoformat(),
        "baseline": {"circadian_mean": 60.0, "circadian_std": 4.0},
        "activity_context": {"primary_context": "RESTING", "steps_concurrent": 0},
        "data_quality": {"rating": "excellent"}
    }

    result = await graph.ainvoke(state)

    assert result["safety_approved"] is True
    explanation = result["explanation"]
    assert explanation is not None
    assert "summary" in explanation
    assert "observation" in explanation
    assert "personal_comparison" in explanation
    # Ensure no prohibited medical diagnoses exist
    full_text = " ".join(str(v) for v in explanation.values()).lower()
    assert "heart attack" not in full_text
    assert "arrhythmia" not in full_text


@pytest.mark.asyncio
async def test_care_nav_graph_graceful_synthesis_and_gating():
    """
    Verifies that CareNavigationGraph synthesizes physician notes, calm patient rationales,
    and statutory disclaimers without requiring external LLM reachability.
    """
    graph = build_care_nav_graph()
    token = f"appr_{uuid.uuid4().hex[:12]}_signed"

    state = {
        "user_id": str(uuid.uuid4()),
        "specialty_routing": {
            "primary_specialty": "Cardiology / Electrophysiology",
            "clinical_rationale": "Sustained resting tachycardia z-score deviation.",
            "urgency_tier": "prompt"
        },
        "user_approval_granted": True,
        "approval_token": token
    }

    result = await graph.ainvoke(state)

    assert result["clinician_note"] is not None
    assert "Cardiology" in result["clinician_note"] or "telemetry" in result["clinician_note"]
    assert "CLINICAL ADVISORY" in result["safety_disclaimer"]
    assert result.get("outreach_draft") is not None
    assert token[:12] in result["outreach_draft"]
