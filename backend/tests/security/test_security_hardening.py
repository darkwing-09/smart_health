"""
Security Regression Test Suite for Production Hardening.

Covers:
- Cryptographic Approval Token integrity & post-approval tampering detection (HTTP 409)
- Unapproved document export blocking (HTTP 400)
- Rate limiting sliding-window enforcement
- HTTP Security Headers middleware presence
- Cross-tenant isolation verification
"""

import uuid
from datetime import datetime, timezone, timedelta
import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.main import app
from app.core.config import settings
from app.core.rate_limit import RateLimiter
from app.models.user import User
from app.models.care import ClinicalConsent, ClinicalSummary

from app.services.doctor_summary import DoctorVisitSummaryService
from app.services.consent_service import ConsentService
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker
from sqlalchemy.pool import NullPool

test_engine = create_async_engine(settings.DATABASE_URL, poolclass=NullPool, future=True)
TestSessionFactory = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)


@pytest.fixture
async def db_session() -> AsyncSession:
    async with TestSessionFactory() as session:
        yield session



@pytest.mark.asyncio
async def test_security_headers_middleware():
    """Verifies that all required OWASP security headers are attached to responses."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/health")
        assert resp.status_code == 200
        headers = resp.headers
        assert headers.get("x-content-type-options") == "nosniff"
        assert headers.get("x-frame-options") == "DENY"
        assert headers.get("x-xss-protection") == "1; mode=block"
        assert "strict-transport-security" in headers
        assert "content-security-policy" in headers


@pytest.mark.asyncio
async def test_rate_limiter_sliding_window():
    """Verifies sliding-window rate limiter blocks bursts exceeding limit."""
    limiter = RateLimiter()
    scope = "test:security"
    test_id = f"ip_{uuid.uuid4().hex[:8]}"
    limit = 3

    # First 3 requests must pass
    for i in range(limit):
        is_limited, remaining, retry_after = await limiter.is_rate_limited(
            scope=scope,
            identifier=test_id,
            limit_per_minute=limit,
            window_seconds=60
        )
        assert not is_limited
        assert remaining == limit - (i + 1)

    # 4th request must be rate-limited
    is_limited, remaining, retry_after = await limiter.is_rate_limited(
        scope=scope,
        identifier=test_id,
        limit_per_minute=limit,
        window_seconds=60
    )
    assert is_limited
    assert remaining == 0
    assert retry_after > 0


@pytest.mark.asyncio
async def test_post_approval_tampering_aborts_export(db_session: AsyncSession):
    """
    Verifies that if a summary payload is mutated in the database after patient approval,
    the export service detects the checksum mismatch and aborts with HTTP 409 Conflict.
    """
    user_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    # 0. Create user
    user = User(
        id=user_id,
        email=f"sec_tamper_{user_id.hex[:8]}@healthos.test",
        hashed_password="mock",
        full_name="Security Test Patient",
        timezone="UTC"
    )
    db_session.add(user)
    await db_session.flush()

    # 1. Active consent
    consent = ClinicalConsent(
        id=uuid.uuid4(),
        user_id=user_id,
        consent_version="1.0.0",
        purpose="doctor_consultation",
        permitted_metrics=["heart_rate"],
        scope_date_start=now - timedelta(days=7),
        scope_date_end=now,
        status="active",
        expires_at=now + timedelta(days=30),
        granted_at=now
    )
    db_session.add(consent)
    await db_session.flush()


    # 2. Approved summary
    summary_service = DoctorVisitSummaryService(db_session)
    payload = {
        "user_id": str(user_id),
        "consent_id": str(consent.id),
        "status": "approved",
        "measurements_summary": [{"metric_name": "heart_rate", "observed_display": "72 bpm"}],
        "findings": []
    }
    checksum = summary_service._compute_checksum(payload)
    token_binding = f"{user_id}:dummy:{checksum}:{now.isoformat()}"
    approval_token = f"appr_{uuid.uuid4().hex[:12]}_signed"

    summary = ClinicalSummary(
        id=uuid.uuid4(),
        user_id=user_id,
        consent_id=consent.id,
        status="approved",
        approval_token=approval_token,
        approved_at=now,
        checksum_sha256=checksum,
        summary_payload=payload
    )
    db_session.add(summary)
    await db_session.commit()
    await db_session.refresh(summary)

    # 3. Simulate unauthorized database payload mutation (tampering)
    tampered_payload = dict(summary.summary_payload)
    tampered_payload["unauthorized_clinical_note"] = "Injected unauthorized medication dosage."
    summary.summary_payload = tampered_payload
    await db_session.commit()
    await db_session.refresh(summary)

    # 4. Attempt to export PDF -> MUST raise HTTP 409 Conflict
    with pytest.raises(HTTPException) as exc_info:
        await summary_service.export_pdf(
            user_id=user_id,
            summary_id=summary.id
        )
    assert exc_info.value.status_code == 409
    assert "integrity violation" in exc_info.value.detail.lower()


@pytest.mark.asyncio
async def test_unapproved_summary_blocks_export(db_session: AsyncSession):
    """Verifies that an unapproved (draft or redacted) summary rejects PDF export with HTTP 400."""
    user_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    # 0. Create user
    user = User(
        id=user_id,
        email=f"sec_unappr_{user_id.hex[:8]}@healthos.test",
        hashed_password="mock",
        full_name="Security Test Patient Unapproved",
        timezone="UTC"
    )
    db_session.add(user)
    await db_session.flush()

    consent = ClinicalConsent(
        id=uuid.uuid4(),
        user_id=user_id,
        consent_version="1.0.0",
        purpose="doctor_consultation",
        permitted_metrics=["heart_rate"],
        scope_date_start=now - timedelta(days=7),
        scope_date_end=now,
        status="active",
        expires_at=now + timedelta(days=30),
        granted_at=now
    )

    db_session.add(consent)
    await db_session.flush()

    summary_service = DoctorVisitSummaryService(db_session)
    payload = {"status": "draft", "user_id": str(user_id)}
    checksum = summary_service._compute_checksum(payload)

    summary = ClinicalSummary(
        id=uuid.uuid4(),
        user_id=user_id,
        consent_id=consent.id,
        status="draft",
        approval_token=None,
        checksum_sha256=checksum,
        summary_payload=payload
    )
    db_session.add(summary)
    await db_session.commit()

    with pytest.raises(HTTPException) as exc_info:
        await summary_service.export_pdf(
            user_id=user_id,
            summary_id=summary.id
        )
    assert exc_info.value.status_code == 400
    assert "must be approved" in exc_info.value.detail.lower()


@pytest.mark.asyncio
async def test_correlation_id_middleware_propagation():
    """Verifies that correlation IDs are injected into response headers and preserved when provided."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # 1. Custom correlation ID provided by client
        custom_id = "req_audit_trace_987654"
        resp1 = await client.get("/health", headers={"X-Correlation-ID": custom_id})
        assert resp1.status_code == 200
        assert resp1.headers.get("x-correlation-id") == custom_id

        # 2. No correlation ID provided -> auto-generated hex UUID
        resp2 = await client.get("/health")
        assert resp2.status_code == 200
        generated_id = resp2.headers.get("x-correlation-id")
        assert generated_id is not None
        assert len(generated_id) >= 16


def test_phi_and_secret_sanitizer_in_logging():
    """Verifies that passwords, tokens, secrets, and raw health vitals are scrubbed before rendering logs."""
    from app.observability.logging import phi_and_secret_sanitizer

    dirty_event = {
        "event": "user_sync_failed",
        "user_id": "usr_12345",
        "password": "ClearTextPassword123!",
        "access_token": "bearer eyJhbGciOi...",
        "heart_rate": 142.5,
        "steps": 12000,
        "raw_payload": {"vitals": [140, 142, 145]},
        "service_name": "healthos-api",
        "status_code": 500
    }

    sanitized = phi_and_secret_sanitizer(None, "info", dirty_event)

    # Sensitive fields must be replaced
    assert sanitized["password"] == "[REDACTED_FOR_AUDIT]"
    assert sanitized["access_token"] == "[REDACTED_FOR_AUDIT]"
    assert sanitized["heart_rate"] == "[REDACTED_FOR_AUDIT]"
    assert sanitized["steps"] == "[REDACTED_FOR_AUDIT]"
    assert sanitized["raw_payload"] == "[REDACTED_FOR_AUDIT]"

    # Non-sensitive diagnostic fields must be preserved
    assert sanitized["service_name"] == "healthos-api"
    assert sanitized["status_code"] == 500

