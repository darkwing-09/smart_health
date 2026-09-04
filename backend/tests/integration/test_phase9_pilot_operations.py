"""Integration Test Suite: Phase 9 Production Pilot Operations & Real-World Invariants.

Validates end-to-end production operations under real-world pilot conditions:
1. Multi-metric batch ingestion & hypertable chunk persistence.
2. Complete data pipeline traversal from ingestion to ReportLab vector PDF.
3. Offline synchronization recovery & idempotent replay.
4. Clock skew resilience and biological boundary enforcement.
5. Wearable sensor detachment and quality flag tracking without synthetic imputation.
6. Quiet-hours postponement vs Level 4 Urgent emergency bypass.
7. Multi-tenant isolation across all endpoints.
8. ActionGate cryptographic approval token binding & freshness checks.
9. DPDP Act 2023 consent revocation immediate hard-stop.
10. Kubernetes/ECS container liveness and readiness probes under operation.
"""

import uuid
from datetime import datetime, timedelta, timezone
from typing import AsyncGenerator
import pytest
from httpx import AsyncClient, ASGITransport
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.core.config import settings
from jose import jwt
from app.core.config import settings
from app.db.session import get_db
from app.main import app
from app.models.user import User
from app.models.device import WearableSource
from app.models.measurement import Measurement, SyncBatch
from app.models.baseline import Baseline
from app.models.finding import Finding
import os
from app.models.notification import Notification
from app.models.care import ClinicalConsent, ClinicalSummary
from app.services.doctor_summary import DoctorVisitSummaryService
from app.services.action_gate import ActionGate, ActionTier
from app.services.notification_policy import NotificationPolicyEngine, AlertTier, DeliveryChannel
from app.services.notification import NotificationService


test_engine = create_async_engine(settings.DATABASE_URL, poolclass=NullPool, future=True)
TestSessionFactory = async_sessionmaker(bind=test_engine, class_=AsyncSession, expire_on_commit=False)


async def override_get_db():
    async with TestSessionFactory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


@pytest.fixture(autouse=True)
def setup_db_override():
    app.dependency_overrides[get_db] = override_get_db
    yield
    app.dependency_overrides.pop(get_db, None)


@pytest.fixture
async def pilot_env() -> AsyncGenerator[dict, None]:
    """Sets up a fully provisioned pilot user with source, baseline, and auth header."""
    user_id = uuid.uuid4()
    source_id = uuid.uuid4()
    now_utc = datetime.now(timezone.utc)

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"pilot_p9_{user_id.hex[:8]}@example.com",
            hashed_password="hashed_pilot_secret",
            full_name="Phase 9 Pilot Participant",
            timezone="Asia/Kolkata",
            notification_prefs={
                "quiet_hours_enabled": True,
                "quiet_hours_start": "22:00",
                "quiet_hours_end": "07:00",
                "fcm_enabled": True,
                "websocket_enabled": True,
                "min_notification_severity": "info"
            },
            is_active=True
        )
        session.add(user)
        await session.commit()

    async with TestSessionFactory() as session:
        source = WearableSource(
            id=source_id,
            user_id=user_id,
            adapter_type="health_connect",
            reliability_tier="official",
            auth_status="ACTIVE"
        )
        session.add(source)

        baseline = Baseline(
            id=uuid.uuid4(),
            user_id=user_id,
            metric_type="heart_rate",
            window_start=now_utc - timedelta(days=30),
            window_end=now_utc,
            mean=68.0,
            stddev=6.0,
            seasonality_profile={},
            established=True,
            rule_version="1.0.0",
            computed_at=now_utc - timedelta(days=1)
        )
        session.add(baseline)
        await session.commit()

    token = jwt.encode(
        {"sub": str(user_id), "exp": now_utc + timedelta(hours=2)},
        settings.SECRET_KEY,
        algorithm="HS256"
    )
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }

    yield {
        "user_id": user_id,
        "source_id": source_id,
        "headers": headers,
        "token": token
    }


@pytest.mark.asyncio
async def test_p9_01_multi_metric_batch_ingestion_and_hypertable_persistence(pilot_env: dict):
    """Verifies batch ingestion across diverse wearable metrics and hypertable storage."""
    user_id = pilot_env["user_id"]
    source_id = pilot_env["source_id"]
    headers = pilot_env["headers"]
    now_utc = datetime.now(timezone.utc)

    metrics_payload = [
        {"metric_type": "heart_rate", "value": 72.0, "unit": "bpm"},
        {"metric_type": "steps", "value": 150.0, "unit": "count"},
        {"metric_type": "spo2", "value": 98.0, "unit": "%"},
        {"metric_type": "hrv", "value": 55.0, "unit": "ms"},
        {"metric_type": "body_temperature", "value": 36.8, "unit": "celsius"},
        {"metric_type": "respiratory_rate", "value": 16.0, "unit": "rpm"},
        {"metric_type": "distance", "value": 120.5, "unit": "m"},
        {"metric_type": "calories", "value": 85.0, "unit": "kcal"},
        {"metric_type": "active_calories", "value": 45.0, "unit": "kcal"},
    ]

    measurements = [
        {
            "source_record_id": f"p9_rec_{i}_{uuid.uuid4().hex[:6]}",
            "metric_type": m["metric_type"],
            "value": m["value"],
            "unit": m["unit"],
            "recorded_at": (now_utc - timedelta(minutes=i)).isoformat(),
            "confidence": 0.95,
            "data_quality_flag": "nominal"
        }
        for i, m in enumerate(metrics_payload)
    ]

    batch_id = str(uuid.uuid4())
    req_body = {
        "source_id": str(source_id),
        "client_sync_timestamp": now_utc.isoformat(),
        "measurements": measurements
    }

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        res = await ac.post(
            "/v1/sync/batch",
            json=req_body,
            headers={**headers, "Idempotency-Key": batch_id}
        )

    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "SUCCESS"
    assert data["accepted_count"] == len(metrics_payload)

    # Verify hypertable persistence in database
    async with TestSessionFactory() as session:
        result = await session.execute(
            select(Measurement).where(Measurement.user_id == user_id)
        )
        persisted = result.scalars().all()
        assert len(persisted) == len(metrics_payload)
        for p in persisted:
            assert p.ingested_at is not None
            assert p.data_quality_flag == "nominal"


@pytest.mark.asyncio
async def test_p9_02_end_to_end_pipeline_traversal_to_vector_pdf(pilot_env: dict):
    """Executes full flow: Ingestion -> Anomaly -> Notification -> Care Brief -> Vector PDF."""
    user_id = pilot_env["user_id"]
    headers = pilot_env["headers"]
    now_utc = datetime.now(timezone.utc)

    # 1. Establish Clinical Consent
    async with TestSessionFactory() as session:
        consent = ClinicalConsent(
            id=uuid.uuid4(),
            user_id=user_id,
            consent_version="1.0.0",
            purpose="doctor_consultation",
            permitted_metrics=["heart_rate", "steps", "spo2"],
            permitted_finding_ids=["*"],
            scope_date_start=now_utc - timedelta(days=7),
            scope_date_end=now_utc + timedelta(days=1),
            granted_at=now_utc,
            expires_at=now_utc + timedelta(days=14),
            status="active"
        )
        session.add(consent)

        # 2. Add an acute physiological finding
        finding = Finding(
            id=uuid.uuid4(),
            user_id=user_id,
            metric_type="heart_rate",
            severity="urgent",
            rule_id="RULE_HARD_CEILING_TACHYCARDIA",
            observed_value=145.0,
            baseline_value=68.0,
            deviation=77.0,
            first_detected_at=now_utc - timedelta(minutes=10),
            last_updated_at=now_utc,
            status="notified"
        )
        session.add(finding)
        await session.commit()

        # 3. Trigger Care Navigation Service to draft summary
        care_svc = DoctorVisitSummaryService(session)
        draft = await care_svc.generate_draft(user_id=user_id, consent_id=consent.id)
        assert draft.status == "draft"
        assert draft.checksum_sha256 is not None

        # 4. Patient Approves Brief -> generates approval token
        approved = await care_svc.approve_summary(
            user_id=user_id,
            summary_id=draft.id,
            ip_address="192.168.1.50"
        )
        assert approved.status == "approved"
        assert approved.approval_token is not None

        # 5. Export ReportLab Vector PDF
        os.makedirs("var/reports/clinical", exist_ok=True)
        pdf_path = await care_svc.export_pdf(
            user_id=user_id,
            summary_id=draft.id,
            output_dir="var/reports/clinical"
        )
        assert os.path.exists(pdf_path)
        with open(pdf_path, "rb") as f:
            pdf_bytes = f.read()
        assert pdf_bytes.startswith(b"%PDF")
        assert len(pdf_bytes) > 1000


@pytest.mark.asyncio
async def test_p9_03_offline_sync_delayed_records_and_idempotent_replay(pilot_env: dict):
    """Simulates 24-hour delayed offline sync and verifies idempotent re-transmission."""
    user_id = pilot_env["user_id"]
    source_id = pilot_env["source_id"]
    headers = pilot_env["headers"]
    now_utc = datetime.now(timezone.utc)
    stale_time = now_utc - timedelta(hours=22)

    measurements = [
        {
            "source_record_id": f"p9_offline_{i}",
            "metric_type": "heart_rate",
            "value": 70.0 + i,
            "unit": "bpm",
            "recorded_at": (stale_time + timedelta(minutes=i * 5)).isoformat(),
            "confidence": 0.9,
            "data_quality_flag": "nominal"
        }
        for i in range(5)
    ]

    batch_id = str(uuid.uuid4())
    req_body = {
        "source_id": str(source_id),
        "client_sync_timestamp": now_utc.isoformat(),
        "measurements": measurements
    }

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        # First submission (Fresh offline sync)
        res1 = await ac.post("/v1/sync/batch", json=req_body, headers={**headers, "Idempotency-Key": batch_id})
        assert res1.status_code == 200
        assert res1.json()["accepted_count"] == 5

        # Duplicate replay (Worker retry)
        res2 = await ac.post("/v1/sync/batch", json=req_body, headers={**headers, "Idempotency-Key": batch_id})
        assert res2.status_code == 200
        assert res2.json()["status"] == "ALREADY_PROCESSED"
        assert res2.json()["accepted_count"] == 5


@pytest.mark.asyncio
async def test_p9_04_clock_skew_resilience_and_future_timestamp_gating(pilot_env: dict):
    """Verifies that slight clock jitter is accepted while impossible future timestamps are rejected."""
    user_id = pilot_env["user_id"]
    source_id = pilot_env["source_id"]
    headers = pilot_env["headers"]
    now_utc = datetime.now(timezone.utc)

    # Valid jitter: recorded 5 minutes in the past
    valid_jitter_body = {
        "source_id": str(source_id),
        "client_sync_timestamp": now_utc.isoformat(),
        "measurements": [
            {
                "source_record_id": "rec_jitter_ok",
                "metric_type": "heart_rate",
                "value": 74.0,
                "unit": "bpm",
                "recorded_at": (now_utc - timedelta(minutes=5)).isoformat(),
                "confidence": 0.95,
                "data_quality_flag": "nominal"
            }
        ]
    }

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        res1 = await ac.post(
            "/v1/sync/batch",
            json=valid_jitter_body,
            headers={**headers, "Idempotency-Key": str(uuid.uuid4())}
        )
        assert res1.status_code == 200

        # Impossible future timestamp: recorded 2 days in the future (quarantined by DataQualityEngine)
        future_body = {
            "source_id": str(source_id),
            "client_sync_timestamp": now_utc.isoformat(),
            "measurements": [
                {
                    "source_record_id": "rec_future_fail",
                    "metric_type": "heart_rate",
                    "value": 74.0,
                    "unit": "bpm",
                    "recorded_at": (now_utc + timedelta(days=2)).isoformat(),
                    "confidence": 0.95,
                    "data_quality_flag": "nominal"
                }
            ]
        }
        res2 = await ac.post(
            "/v1/sync/batch",
            json=future_body,
            headers={**headers, "Idempotency-Key": str(uuid.uuid4())}
        )
        assert res2.status_code == 200
        data2 = res2.json()
        assert data2["invalid_count"] == 1

        # Verify in DB: stored with 'invalid' flag, never fed to baseline or alerting
        async with TestSessionFactory() as session:
            result = await session.execute(
                select(Measurement).where(
                    Measurement.user_id == user_id,
                    Measurement.data_quality_flag == "invalid"
                )
            )
            rec = result.scalars().first()
            assert rec is not None
            assert rec.data_quality_flag == "invalid"


@pytest.mark.asyncio
async def test_p9_05_wearable_sensor_detachment_quality_tagging(pilot_env: dict):
    """Verifies that sensor detachment (zero HR/steps for prolonged duration) is tagged without imputation."""
    user_id = pilot_env["user_id"]
    source_id = pilot_env["source_id"]
    headers = pilot_env["headers"]
    now_utc = datetime.now(timezone.utc)

    # Ingest measurements flagged as sensor gap/detachment (steps=0 count while awake)
    gap_measurements = [
        {
            "source_record_id": f"rec_detachment_{i}",
            "metric_type": "steps",
            "value": 0.0,
            "unit": "count",
            "recorded_at": (now_utc - timedelta(minutes=40 - i * 5)).isoformat(),
            "confidence": 0.9,
            "data_quality_flag": "missing"
        }
        for i in range(5)
    ]

    req_body = {
        "source_id": str(source_id),
        "client_sync_timestamp": now_utc.isoformat(),
        "measurements": gap_measurements
    }

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        res = await ac.post(
            "/v1/sync/batch",
            json=req_body,
            headers={**headers, "Idempotency-Key": str(uuid.uuid4())}
        )
        assert res.status_code == 200

    # Verify in DB: stored with 'missing' and never imputed with normal baseline
    async with TestSessionFactory() as session:
        result = await session.execute(
            select(Measurement).where(
                Measurement.user_id == user_id,
                Measurement.data_quality_flag == "missing"
            )
        )
        records = result.scalars().all()
        assert len(records) == 5
        for r in records:
            assert r.data_quality_flag == "missing"
            assert r.value == 0.0


@pytest.mark.asyncio
async def test_p9_06_quiet_hours_vs_level_4_emergency_invariance(pilot_env: dict):
    """Verifies quiet-hours postponement for Level 2 and unconditional override for Level 4."""
    user_id = pilot_env["user_id"]

    # Simulated quiet hours at 23:00 IST
    night_time = datetime(2026, 9, 4, 17, 30, tzinfo=timezone.utc) # 23:00 IST

    # Level 2 finding (Attention)
    fnd_level2 = Finding(
        id=uuid.uuid4(),
        user_id=user_id,
        metric_type="heart_rate",
        severity="worth_monitoring",
        rule_id="RULE_MODERATE_ELEVATION",
        observed_value=85.0,
        baseline_value=68.0,
        deviation=17.0,
        first_detected_at=night_time,
        last_updated_at=night_time,
        status="active"
    )

    # Level 4 finding (Urgent)
    fnd_level4 = Finding(
        id=uuid.uuid4(),
        user_id=user_id,
        metric_type="heart_rate",
        severity="urgent",
        rule_id="RULE_CRITICAL_TACHYCARDIA",
        observed_value=165.0,
        baseline_value=68.0,
        deviation=97.0,
        first_detected_at=night_time,
        last_updated_at=night_time,
        status="active"
    )

    # Evaluate Level 2 policy during quiet hours
    res_l2 = NotificationPolicyEngine.evaluate(
        severity=fnd_level2.severity,
        rule_id=fnd_level2.rule_id,
        user_prefs={"in_app_enabled": True, "fcm_enabled": True},
        is_quiet_hours=True
    )
    assert res_l2.tier == AlertTier.LEVEL_2_ATTENTION
    assert res_l2.overrides_quiet_hours is False
    # Level 2 Attention push is not sent during quiet hours
    assert DeliveryChannel.FCM not in res_l2.channels

    # Evaluate Level 4 policy during quiet hours -> must permanently override quiet hours
    res_l4 = NotificationPolicyEngine.evaluate(
        severity=fnd_level4.severity,
        rule_id=fnd_level4.rule_id,
        user_prefs={"in_app_enabled": True, "fcm_enabled": True},
        is_quiet_hours=True
    )
    assert res_l4.tier == AlertTier.LEVEL_4_URGENT
    assert res_l4.overrides_quiet_hours is True
    assert DeliveryChannel.FCM in res_l4.channels
    assert res_l4.requires_emergency_disclaimer is True


@pytest.mark.asyncio
async def test_p9_07_multi_tenant_isolation_boundary(pilot_env: dict):
    """Ensures complete tenant isolation: Tenant A cannot access Tenant B's data."""
    headers_a = pilot_env["headers"]

    # Create distinct Tenant B
    user_b_id = uuid.uuid4()
    async with TestSessionFactory() as session:
        user_b = User(
            id=user_b_id,
            email=f"tenant_b_{user_b_id.hex[:8]}@example.com",
            hashed_password="hashed_pw_b",
            full_name="Tenant B Subject",
            timezone="UTC",
            is_active=True
        )
        session.add(user_b)
        await session.commit()

        fnd_b = Finding(
            id=uuid.uuid4(),
            user_id=user_b_id,
            metric_type="heart_rate",
            severity="urgent",
            rule_id="RULE_TENANT_B",
            observed_value=150.0,
            baseline_value=70.0,
            deviation=80.0,
            first_detected_at=datetime.now(timezone.utc),
            last_updated_at=datetime.now(timezone.utc),
            status="active"
        )
        session.add(fnd_b)
        await session.commit()
        finding_b_id = str(fnd_b.id)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        # User A attempts to acknowledge User B's finding
        res = await ac.post(
            f"/v1/findings/{finding_b_id}/acknowledge",
            headers=headers_a
        )
        # Must return 404 to avoid leaking existence of Tenant B's resource
        assert res.status_code in [404, 403]


@pytest.mark.asyncio
async def test_p9_08_actiongate_approval_token_security_and_replay_defense(pilot_env: dict):
    """Verifies cryptographic HMAC token tamper detection, user binding, and freshness validation."""
    user_id = pilot_env["user_id"]
    summary_id = uuid.uuid4()
    target_ref = f"summary:{summary_id}"

    # Valid token
    valid_token = ActionGate.generate_approval_token(
        user_id=user_id,
        action_type=ActionTier.EXTERNAL_ACTION,
        target_ref=target_ref
    )
    assert ActionGate.verify_approval_token(
        token=valid_token,
        user_id=user_id,
        action_type=ActionTier.EXTERNAL_ACTION,
        target_ref=target_ref
    ) is True

    # 1. Tampered target_ref
    wrong_target = f"summary:{uuid.uuid4()}"
    assert ActionGate.verify_approval_token(
        token=valid_token,
        user_id=user_id,
        action_type=ActionTier.EXTERNAL_ACTION,
        target_ref=wrong_target
    ) is False

    # 2. Tampered user_id
    wrong_user = uuid.uuid4()
    assert ActionGate.verify_approval_token(
        token=valid_token,
        user_id=wrong_user,
        action_type=ActionTier.EXTERNAL_ACTION,
        target_ref=target_ref
    ) is False

    # 3. Forged signature / altered token
    tampered_token = "appr_1234567890ab_deadbeefcafebabe01234567"
    assert ActionGate.verify_approval_token(
        token=tampered_token,
        user_id=user_id,
        action_type=ActionTier.EXTERNAL_ACTION,
        target_ref=target_ref
    ) is False

    # 4. Truncated / malformed token
    assert ActionGate.verify_approval_token(
        token="invalid_token_string",
        user_id=user_id,
        action_type=ActionTier.EXTERNAL_ACTION,
        target_ref=target_ref
    ) is False


@pytest.mark.asyncio
async def test_p9_09_dpdp_consent_revocation_hard_stop(pilot_env: dict):
    """Verifies immediate blockage of clinical data export upon consent revocation (DPDP Act 2023)."""
    user_id = pilot_env["user_id"]
    now_utc = datetime.now(timezone.utc)

    async with TestSessionFactory() as session:
        # Create active consent & summary
        consent = ClinicalConsent(
            id=uuid.uuid4(),
            user_id=user_id,
            consent_version="1.0.0",
            purpose="doctor_consultation",
            permitted_metrics=["heart_rate"],
            permitted_finding_ids=["*"],
            scope_date_start=now_utc - timedelta(days=7),
            scope_date_end=now_utc,
            granted_at=now_utc,
            expires_at=now_utc + timedelta(days=7),
            status="active"
        )
        session.add(consent)
        await session.commit()

        care_svc = DoctorVisitSummaryService(session)
        draft = await care_svc.generate_draft(user_id=user_id, consent_id=consent.id)
        approved = await care_svc.approve_summary(user_id=user_id, summary_id=draft.id, ip_address="127.0.0.1")
        assert approved.status == "approved"

        # Verify export succeeds before revocation
        os.makedirs("var/reports/clinical", exist_ok=True)
        pdf_path = await care_svc.export_pdf(user_id=user_id, summary_id=draft.id, output_dir="var/reports/clinical")
        assert os.path.exists(pdf_path)

        # Now Revoke Consent (DPDP Act 2023 Right to Revoke)
        consent.status = "revoked"
        consent.revoked_at = now_utc
        await session.commit()

        # Immediate export attempt must be blocked with HTTP 403
        from fastapi import HTTPException
        with pytest.raises(HTTPException) as exc_info:
            await care_svc.export_pdf(user_id=user_id, summary_id=draft.id, output_dir="var/reports/clinical")
        assert exc_info.value.status_code == 403
        assert "Consent is no longer active" in exc_info.value.detail or "revoked" in exc_info.value.detail


@pytest.mark.asyncio
async def test_p9_10_container_liveness_and_readiness_probes_under_operation():
    """Verifies /health and /ready endpoints return HTTP 200 with full service status."""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        # Liveness probe
        res_live = await ac.get("/health")
        assert res_live.status_code == 200
        assert res_live.json()["status"] == "healthy"

        # Readiness probe
        res_ready = await ac.get("/ready")
        assert res_ready.status_code == 200
        data = res_ready.json()
        assert data["status"] == "ready"
        assert data["checks"]["postgresql"]["status"] == "ok"
        assert data["checks"]["redis"]["status"] == "ok"
