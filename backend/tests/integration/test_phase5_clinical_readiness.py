"""Integration tests for Phase 5: Clinical Readiness & Human-Controlled Care Navigation.

Verifies:
1. Deterministic specialty routing (zero LLM, Rule H1 non-diagnostic assurance).
2. Granular consent lifecycle (DPDP 2023 grant, scope validation, expiration, revocation, audit trail).
3. Doctor Visit Summary lifecycle: DRAFT -> REVIEW -> REDACT -> APPROVE -> EXPORT.
4. Cryptographic checksums (SHA-256) and patient redactions.
5. ReportLab vector PDF compilation and filesystem storage.
6. Revocation defense (revoking consent blocks downstream PDF export).
7. Strict multi-user isolation across clinical summaries and consent records.
"""

import os
import uuid
import pytest
from datetime import datetime, timezone, timedelta
from sqlalchemy import select
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession, async_sessionmaker
from sqlalchemy.pool import NullPool
from fastapi import HTTPException

from app.core.config import settings
from app.models.user import User
from app.models.device import Device, WearableSource
from app.models.measurement import Measurement
from app.models.baseline import Baseline
from app.models.finding import Finding
from app.models.care import ClinicalConsent, ClinicalSummary
from app.models.audit import AuditLog
from app.services.consent_service import ConsentService
from app.services.specialty_router import SpecialtyRouter
from app.services.trend import LongitudinalTrendReport, FindingClassification, EvidenceStrength
from app.services.doctor_summary import DoctorVisitSummaryService

test_engine = create_async_engine(settings.DATABASE_URL, poolclass=NullPool, future=True)
TestSessionFactory = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)


@pytest.mark.asyncio
async def test_deterministic_specialty_routing():
    """
    Verifies that SpecialtyRouter strictly evaluates mathematical evidence
    without LLM inference, assigning appropriate clinical specialties with Rule H1 disclaimers.
    """
    # 1. Nocturnal tachycardia finding -> Cardiology
    f_cardio = Finding(
        id=uuid.uuid4(),
        user_id=uuid.uuid4(),
        metric_type="heart_rate",
        severity="potentially_concerning",
        rule_id="RULE_STAT_NOCTURNAL_TACHYCARDIA",
        rule_version="1.1.0",
        observed_value=98.0,
        baseline_value=58.0,
        deviation=40.0,
        reading_timestamp=datetime.now(timezone.utc),
        first_detected_at=datetime.now(timezone.utc),
        last_updated_at=datetime.now(timezone.utc),
        status="new"
    )
    decision_1 = SpecialtyRouter.evaluate_routing(findings=[f_cardio])
    assert "Cardiology" in decision_1.primary_specialty
    assert decision_1.urgency_tier == "prompt"
    assert "CLINICAL ADVISORY" in decision_1.disclaimer

    # 2. Multi-day upward trend drift -> General Practice / Internal Medicine
    trend_report = LongitudinalTrendReport(
        classification=FindingClassification.TREND,
        metric_type="resting_heart_rate",
        direction="increasing",
        evidence_strength=EvidenceStrength.STRONG,
        start_date=datetime.now(timezone.utc) - timedelta(days=14),
        end_date=datetime.now(timezone.utc),
        days_analyzed=14,
        sample_count=14,
        initial_mean=58.0,
        current_mean=64.0,
        total_change=6.0,
        slope_per_day=0.45,
        r_squared=0.88,
        is_statistically_significant=True,
        is_clinically_meaningful=True,
        summary="Upward trend in resting HR over 14 days",
        evidence={}
    )
    decision_2 = SpecialtyRouter.evaluate_routing(findings=[], trend_reports=[trend_report])
    assert "Internal Medicine" in decision_2.primary_specialty or "General Practice" in decision_2.primary_specialty
    assert decision_2.urgency_tier == "prompt"

    # 3. Sleep session finding -> Sleep Medicine
    f_sleep = Finding(
        id=uuid.uuid4(),
        user_id=uuid.uuid4(),
        metric_type="sleep_session",
        severity="attention",
        rule_id="RULE_STAT_SLEEP_DISRUPTION",
        rule_version="1.0.0",
        observed_value=240.0,
        baseline_value=480.0,
        deviation=-240.0,
        reading_timestamp=datetime.now(timezone.utc),
        first_detected_at=datetime.now(timezone.utc),
        last_updated_at=datetime.now(timezone.utc),
        status="new"
    )
    decision_3 = SpecialtyRouter.evaluate_routing(findings=[f_sleep])
    assert "Sleep Medicine" in decision_3.primary_specialty

    # 4. Nominal baseline -> Primary Care Routine
    decision_4 = SpecialtyRouter.evaluate_routing(findings=[], trend_reports=[])
    assert "Primary Care" in decision_4.primary_specialty
    assert decision_4.urgency_tier == "routine"


@pytest.mark.asyncio
async def test_granular_consent_lifecycle():
    """
    Verifies ConsentService lifecycle: grant, scope specification, expiration,
    immediate revocation, and immutable audit logging.
    """
    user_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"consent_{user_id.hex[:8]}@healthos.test",
            hashed_password="mock",
            full_name="Consent Test Patient",
            timezone="UTC"
        )
        session.add(user)
        await session.commit()

        service = ConsentService(session)

        # 1. Grant Consent (7 days)
        start_dt = now - timedelta(days=7)
        end_dt = now
        consent = await service.grant_consent(
            user_id=user_id,
            purpose="doctor_consultation",
            scope_date_start=start_dt,
            scope_date_end=end_dt,
            permitted_metrics=["heart_rate", "steps"],
            recipient_name="Dr. Mehta",
            recipient_facility="Apollo Hospitals",
            duration_days=7
        )

        assert consent is not None
        assert consent.status == "active"
        assert consent.permitted_metrics == ["heart_rate", "steps"]
        assert consent.recipient_name == "Dr. Mehta"

        # Verify Audit Log
        audit_grant = (await session.scalars(
            select(AuditLog).where(AuditLog.user_id == user_id, AuditLog.action == "consent_granted")
        )).first()
        assert audit_grant is not None
        assert audit_grant.detail["recipient"] == "Dr. Mehta"

        # 2. Inspect Active Consent
        active = await service.validate_consent_active(user_id=user_id, consent_id=consent.id)
        assert active.id == consent.id

        # 3. Revoke Consent
        revoked = await service.revoke_consent(
            user_id=user_id,
            consent_id=consent.id,
            reason="Patient decided to reschedule appointment"
        )
        assert revoked.status == "revoked"
        assert revoked.revoked_at is not None

        # Verify Audit Log for Revocation
        audit_revoke = (await session.scalars(
            select(AuditLog).where(AuditLog.user_id == user_id, AuditLog.action == "consent_revoked")
        )).first()
        assert audit_revoke is not None

        # 4. Verify validate_consent_active now raises 403
        with pytest.raises(HTTPException) as exc_info:
            await service.validate_consent_active(user_id=user_id, consent_id=consent.id)
        assert exc_info.value.status_code == 403


@pytest.mark.asyncio
async def test_doctor_visit_summary_draft_review_redact_approve_export():
    """
    Verifies end-to-end Doctor Visit Summary workflow:
    DRAFT -> REVIEW -> REDACT -> APPROVE -> EXPORT (ReportLab vector PDF).
    """
    user_id = uuid.uuid4()
    now = datetime.now(timezone.utc)
    start_dt = now - timedelta(days=7)
    end_dt = now

    async with TestSessionFactory() as session:
        # 1. Setup Patient, Device, and Source
        user = User(
            id=user_id,
            email=f"doc_{user_id.hex[:8]}@healthos.test",
            hashed_password="mock",
            full_name="Clinical Brief Patient",
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

        # 2. Seed Baseline
        baseline = Baseline(
            id=uuid.uuid4(),
            user_id=user_id,
            metric_type="heart_rate",
            window_start=now - timedelta(days=30),
            window_end=now,
            mean=60.0,
            stddev=4.0,
            established=True,
            computed_at=now - timedelta(days=1)
        )
        session.add(baseline)

        # 3. Seed Measurements (3 HR points, 2 Step points)
        m_hr1 = Measurement(id=uuid.uuid4(), user_id=user_id, source_id=source.id, metric_type="heart_rate", value=58.0, unit="bpm", recorded_at=now - timedelta(days=3), confidence=1.0, data_quality_flag="nominal")
        m_hr2 = Measurement(id=uuid.uuid4(), user_id=user_id, source_id=source.id, metric_type="heart_rate", value=62.0, unit="bpm", recorded_at=now - timedelta(days=2), confidence=1.0, data_quality_flag="nominal")
        m_hr3 = Measurement(id=uuid.uuid4(), user_id=user_id, source_id=source.id, metric_type="heart_rate", value=92.0, unit="bpm", recorded_at=now - timedelta(days=1), confidence=0.98, data_quality_flag="nominal")
        m_st1 = Measurement(id=uuid.uuid4(), user_id=user_id, source_id=source.id, metric_type="steps", value=5000.0, unit="count", recorded_at=now - timedelta(days=3), confidence=1.0, data_quality_flag="nominal")
        m_st2 = Measurement(id=uuid.uuid4(), user_id=user_id, source_id=source.id, metric_type="steps", value=6200.0, unit="count", recorded_at=now - timedelta(days=2), confidence=1.0, data_quality_flag="nominal")
        session.add_all([m_hr1, m_hr2, m_hr3, m_st1, m_st2])

        # 4. Seed Nocturnal Tachycardia Finding
        finding_id = uuid.uuid4()
        finding = Finding(
            id=finding_id,
            user_id=user_id,
            metric_type="heart_rate",
            severity="potentially_concerning",
            rule_id="RULE_STAT_NOCTURNAL_TACHYCARDIA",
            rule_version="1.1.0",
            observed_value=92.0,
            baseline_value=60.0,
            deviation=32.0,
            reading_timestamp=now - timedelta(days=1),
            first_detected_at=now - timedelta(days=1),
            last_updated_at=now - timedelta(days=1),
            status="new"
        )
        session.add(finding)
        await session.commit()

        # 5. Grant Consent
        consent_svc = ConsentService(session)
        consent = await consent_svc.grant_consent(
            user_id=user_id,
            purpose="doctor_consultation",
            scope_date_start=start_dt,
            scope_date_end=end_dt,
            permitted_metrics=["heart_rate", "steps"],
            recipient_name="Dr. Rao",
            duration_days=7
        )

        doc_svc = DoctorVisitSummaryService(session)

        # 6. STEP 1: GENERATE DRAFT
        summary = await doc_svc.generate_draft(
            user_id=user_id,
            consent_id=consent.id
        )
        assert summary.status == "draft"
        assert summary.checksum_sha256 is not None
        assert len(summary.checksum_sha256) == 64
        assert "Cardiology" in summary.summary_payload["specialty_routing"]["primary_specialty"]
        assert summary.summary_payload["reporting_period"]["duration_days"] >= 7
        assert len(summary.summary_payload["findings"]) == 1

        initial_checksum = summary.checksum_sha256

        # 7. STEP 2: VERIFY EXPORT BLOCKED BEFORE APPROVAL
        with pytest.raises(HTTPException) as exc_export:
            await doc_svc.export_pdf(user_id=user_id, summary_id=summary.id)
        assert exc_export.value.status_code == 400
        assert "must be approved" in exc_export.value.detail

        # 8. STEP 3: PATIENT REDACTION
        # Patient redacts steps metric and the specific tachycardia finding
        redaction_mask = {
            "redact_finding_ids": [str(finding_id)],
            "redact_metrics": ["steps"]
        }
        redacted_summary = await doc_svc.redact_summary(
            user_id=user_id,
            summary_id=summary.id,
            redaction_mask=redaction_mask
        )
        assert redacted_summary.status == "redacted"
        assert redacted_summary.checksum_sha256 != initial_checksum
        redacted_finding = next(f for f in redacted_summary.summary_payload["findings"] if f["finding_id"] == str(finding_id))
        assert redacted_finding["is_redacted"] is True
        assert redacted_finding["observed_value"] == "[REDACTED BY PATIENT]"

        # 9. STEP 4: PATIENT APPROVAL
        approved_summary = await doc_svc.approve_summary(
            user_id=user_id,
            summary_id=summary.id
        )
        assert approved_summary.status == "approved"
        assert approved_summary.approval_token is not None
        assert approved_summary.approval_token.startswith("appr_")

        # 10. STEP 5: EXPORT VECTOR PDF
        pdf_path = await doc_svc.export_pdf(
            user_id=user_id,
            summary_id=summary.id,
            output_dir="/tmp/healthos_test_reports"
        )
        assert os.path.exists(pdf_path)
        assert os.path.getsize(pdf_path) > 1000 # Valid vector PDF file generated

        # Clean up temporary test PDF
        if os.path.exists(pdf_path):
            os.remove(pdf_path)


@pytest.mark.asyncio
async def test_consent_revocation_blocks_export():
    """
    Verifies that revoking patient consent immediately terminates export capabilities,
    even if the summary was previously approved (DPDP Act right to revoke).
    """
    user_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"revoke_{user_id.hex[:8]}@healthos.test",
            hashed_password="mock",
            full_name="Revocation Patient",
            timezone="UTC"
        )
        session.add(user)
        await session.commit()

        consent_svc = ConsentService(session)
        consent = await consent_svc.grant_consent(
            user_id=user_id,
            purpose="second_opinion",
            scope_date_start=now - timedelta(days=7),
            scope_date_end=now
        )

        doc_svc = DoctorVisitSummaryService(session)
        summary = await doc_svc.generate_draft(user_id=user_id, consent_id=consent.id)
        approved = await doc_svc.approve_summary(user_id=user_id, summary_id=summary.id)
        assert approved.status == "approved"

        # Patient Revokes Consent
        await consent_svc.revoke_consent(user_id=user_id, consent_id=consent.id)

        # Export must now fail with 403 Forbidden!
        with pytest.raises(HTTPException) as exc_info:
            await doc_svc.export_pdf(user_id=user_id, summary_id=summary.id)
        assert exc_info.value.status_code == 403
        assert "no longer active" in exc_info.value.detail


@pytest.mark.asyncio
async def test_user_isolation_on_clinical_summaries():
    """
    Verifies multi-tenant security: User B cannot access, redact, approve, or export User A's summary.
    """
    user_a = uuid.uuid4()
    user_b = uuid.uuid4()
    now = datetime.now(timezone.utc)

    async with TestSessionFactory() as session:
        u_a = User(id=user_a, email=f"usera_{user_a.hex[:8]}@healthos.test", hashed_password="mock", full_name="User A", timezone="UTC")
        u_b = User(id=user_b, email=f"userb_{user_b.hex[:8]}@healthos.test", hashed_password="mock", full_name="User B", timezone="UTC")
        session.add_all([u_a, u_b])
        await session.commit()

        consent_svc = ConsentService(session)
        consent_a = await consent_svc.grant_consent(user_id=user_a, purpose="doctor_consultation", scope_date_start=now - timedelta(days=7), scope_date_end=now)

        doc_svc = DoctorVisitSummaryService(session)
        summary_a = await doc_svc.generate_draft(user_id=user_a, consent_id=consent_a.id)

        # User B attempts to redact User A's summary -> 404
        with pytest.raises(HTTPException) as exc_redact:
            await doc_svc.redact_summary(user_id=user_b, summary_id=summary_a.id, redaction_mask={})
        assert exc_redact.value.status_code == 404

        # User B attempts to approve User A's summary -> 404
        with pytest.raises(HTTPException) as exc_approve:
            await doc_svc.approve_summary(user_id=user_b, summary_id=summary_a.id)
        assert exc_approve.value.status_code == 404

        # User B attempts to export User A's summary -> 404
        with pytest.raises(HTTPException) as exc_export:
            await doc_svc.export_pdf(user_id=user_b, summary_id=summary_a.id)
        assert exc_export.value.status_code == 404


@pytest.mark.asyncio
async def test_clinical_care_api_endpoints_e2e():
    """
    Verifies full HTTP REST API lifecycle via FastAPI:
    1. POST /v1/care/consent -> 201 Created
    2. GET /v1/care/consent/{id} -> 200 OK
    3. POST /v1/care/summary/draft -> 201 Created
    4. GET /v1/care/summary/{id} -> 200 OK
    5. POST /v1/care/summary/{id}/redact -> 200 OK
    6. POST /v1/care/summary/{id}/approve -> 200 OK
    7. GET /v1/care/summary/{id}/export/pdf -> 200 OK (application/pdf)
    8. GET /v1/care/routing -> 200 OK
    9. DELETE /v1/care/consent/{id} -> 200 OK (revoked)
    10. GET /v1/care/summary/{id}/export/pdf -> 403 Forbidden (revocation defense)
    """
    from httpx import AsyncClient, ASGITransport
    from jose import jwt
    from app.main import app
    from app.db.session import get_db

    user_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    # Seed User in database
    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"api_{user_id.hex[:8]}@healthos.test",
            hashed_password="mock",
            full_name="API Test Patient",
            timezone="UTC"
        )
        session.add(user)
        await session.commit()

    async def override_get_db():
        async with TestSessionFactory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db

    token = jwt.encode({"sub": str(user_id)}, settings.SECRET_KEY, algorithm="HS256")
    headers = {"Authorization": f"Bearer {token}"}

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        # 1. Grant Consent
        consent_payload = {
            "purpose": "doctor_consultation",
            "scope_date_start": (now - timedelta(days=7)).isoformat(),
            "scope_date_end": now.isoformat(),
            "permitted_metrics": ["heart_rate", "steps"],
            "recipient_name": "Dr. Sharma",
            "duration_days": 7
        }
        res_consent = await client.post("/v1/care/consent", json=consent_payload, headers=headers)
        assert res_consent.status_code == 201, res_consent.text
        consent_data = res_consent.json()
        consent_id = consent_data["consent_id"]

        # 2. Get Consent
        res_get_c = await client.get(f"/v1/care/consent/{consent_id}", headers=headers)
        assert res_get_c.status_code == 200
        assert res_get_c.json()["status"] == "active"

        # 3. Draft Summary
        draft_payload = {"consent_id": consent_id}
        res_draft = await client.post("/v1/care/summary/draft", json=draft_payload, headers=headers)
        assert res_draft.status_code == 201, res_draft.text
        summary_data = res_draft.json()
        summary_id = summary_data["summary_id"]
        assert summary_data["status"] == "draft"

        # 4. Preview Summary
        res_prev = await client.get(f"/v1/care/summary/{summary_id}", headers=headers)
        assert res_prev.status_code == 200
        assert res_prev.json()["summary_id"] == summary_id

        # 5. Redact Summary
        redact_payload = {"redact_metrics": ["steps"]}
        res_redact = await client.post(f"/v1/care/summary/{summary_id}/redact", json=redact_payload, headers=headers)
        assert res_redact.status_code == 200
        assert res_redact.json()["status"] == "redacted"

        # 6. Approve Summary
        approve_payload = {"confirm_approval": True}
        res_approve = await client.post(f"/v1/care/summary/{summary_id}/approve", json=approve_payload, headers=headers)
        assert res_approve.status_code == 200
        assert res_approve.json()["status"] == "approved"
        assert res_approve.json()["approval_token"] is not None

        # 7. Export Vector PDF
        res_pdf = await client.get(f"/v1/care/summary/{summary_id}/export/pdf", headers=headers)
        assert res_pdf.status_code == 200
        assert res_pdf.headers["content-type"] == "application/pdf"
        assert len(res_pdf.content) > 1000

        # 8. Specialty Routing
        res_route = await client.get("/v1/care/routing", headers=headers)
        assert res_route.status_code == 200
        assert "Primary Care" in res_route.json()["primary_specialty"]

        # 9. Revoke Consent
        res_rev = await client.delete(f"/v1/care/consent/{consent_id}", headers=headers)
        assert res_rev.status_code == 200
        assert res_rev.json()["status"] == "revoked"

        # 10. Verify PDF export blocked after revocation
        res_pdf_blocked = await client.get(f"/v1/care/summary/{summary_id}/export/pdf", headers=headers)
        assert res_pdf_blocked.status_code == 403

    app.dependency_overrides.pop(get_db, None)

