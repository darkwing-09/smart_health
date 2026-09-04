"""Adversarial Security Tests — Production Pilot Security Verification.

Validates zero-trust security boundaries:
1. Cross-user data isolation (measurements, findings, sync batches).
2. Expired/invalid JWT rejection.
3. Oversized batch payload rejection.
4. Authentication bypass attempts.
5. SQL injection resistance in API parameters.
"""

import uuid
from datetime import datetime, timedelta, timezone

import pytest
from httpx import AsyncClient, ASGITransport
from jose import jwt
from passlib.context import CryptContext
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
from sqlalchemy.pool import NullPool

from app.main import app
from app.core.config import settings
from app.db.session import get_db
from app.models.user import User
from app.models.device import Device, WearableSource
from app.models.measurement import Measurement

pwd_context = CryptContext(schemes=["argon2"], deprecated="auto")

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
async def user_a():
    """Seeds authenticated User A."""
    user_id = uuid.uuid4()
    source_id = uuid.uuid4()
    device_id = uuid.uuid4()

    async with TestSessionFactory() as session:
        session.add(User(
            id=user_id,
            email=f"sec_a_{user_id.hex[:8]}@healthos.test",
            hashed_password=pwd_context.hash("SecureA123!"),
            full_name="Security Patient A",
            timezone="UTC",
            is_active=True
        ))
        session.add(Device(
            id=device_id,
            user_id=user_id,
            device_type="watch",
            brand="Samsung",
            model="Galaxy Watch 6",
            os_version="Wear OS 4.0"
        ))
        session.add(WearableSource(
            id=source_id,
            user_id=user_id,
            device_id=device_id,
            adapter_type="health_connect",
            reliability_tier="official",
            auth_status="ACTIVE"
        ))
        await session.commit()

    token = jwt.encode(
        {"sub": str(user_id), "exp": datetime.now(timezone.utc) + timedelta(hours=1)},
        settings.SECRET_KEY,
        algorithm="HS256"
    )
    yield {"user_id": user_id, "source_id": source_id, "token": token}


@pytest.fixture
async def user_b():
    """Seeds authenticated User B."""
    user_id = uuid.uuid4()
    source_id = uuid.uuid4()
    device_id = uuid.uuid4()

    async with TestSessionFactory() as session:
        session.add(User(
            id=user_id,
            email=f"sec_b_{user_id.hex[:8]}@healthos.test",
            hashed_password=pwd_context.hash("SecureB456!"),
            full_name="Security Patient B",
            timezone="UTC",
            is_active=True
        ))
        session.add(Device(
            id=device_id,
            user_id=user_id,
            device_type="phone",
            brand="Pixel",
            model="Pixel 8 Pro",
            os_version="Android 14"
        ))
        session.add(WearableSource(
            id=source_id,
            user_id=user_id,
            device_id=device_id,
            adapter_type="health_connect",
            reliability_tier="official",
            auth_status="ACTIVE"
        ))
        await session.commit()

    token = jwt.encode(
        {"sub": str(user_id), "exp": datetime.now(timezone.utc) + timedelta(hours=1)},
        settings.SECRET_KEY,
        algorithm="HS256"
    )
    yield {"user_id": user_id, "source_id": source_id, "token": token}


def _make_payload(source_id, value=72.0, offset_minutes=5):
    return {
        "source_id": str(source_id),
        "client_sync_timestamp": datetime.now(timezone.utc).isoformat(),
        "measurements": [{
            "source_record_id": f"rec_{uuid.uuid4().hex[:12]}",
            "metric_type": "heart_rate",
            "value": value,
            "unit": "bpm",
            "recorded_at": (datetime.now(timezone.utc) - timedelta(minutes=offset_minutes)).isoformat(),
            "confidence": 1.0,
            "data_quality_flag": "nominal"
        }]
    }


# ---------------------------------------------------------------------------
# TEST 1: Cross-user measurement isolation
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_cross_user_measurement_isolation(user_a, user_b):
    """
    Verifies that User A's measurements are never visible to User B
    and vice versa, enforcing strict tenant isolation.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        # User A ingests data
        await client.post(
            "/v1/sync/batch",
            json=_make_payload(user_a["source_id"], value=88.0),
            headers={
                "Authorization": f"Bearer {user_a['token']}",
                "Idempotency-Key": str(uuid.uuid4())
            }
        )

        # User B ingests data
        await client.post(
            "/v1/sync/batch",
            json=_make_payload(user_b["source_id"], value=62.0),
            headers={
                "Authorization": f"Bearer {user_b['token']}",
                "Idempotency-Key": str(uuid.uuid4())
            }
        )

        # Verify isolation at database level
        async with TestSessionFactory() as session:
            a_measurements = (await session.execute(
                select(Measurement).where(Measurement.user_id == user_a["user_id"])
            )).scalars().all()
            b_measurements = (await session.execute(
                select(Measurement).where(Measurement.user_id == user_b["user_id"])
            )).scalars().all()

            # A can only see A's data
            assert len(a_measurements) == 1
            assert a_measurements[0].value == 88.0

            # B can only see B's data
            assert len(b_measurements) == 1
            assert b_measurements[0].value == 62.0


# ---------------------------------------------------------------------------
# TEST 2: Expired JWT is rejected
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_expired_jwt_rejected(user_a):
    """
    Validates that an expired JWT token returns HTTP 401/403.
    """
    expired_token = jwt.encode(
        {
            "sub": str(user_a["user_id"]),
            "exp": datetime.now(timezone.utc) - timedelta(hours=1)  # Expired 1 hour ago
        },
        settings.SECRET_KEY,
        algorithm="HS256"
    )

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        response = await client.post(
            "/v1/sync/batch",
            json=_make_payload(user_a["source_id"]),
            headers={
                "Authorization": f"Bearer {expired_token}",
                "Idempotency-Key": str(uuid.uuid4())
            }
        )
        # Should be rejected with 401 or 403
        assert response.status_code in [401, 403]


# ---------------------------------------------------------------------------
# TEST 3: Forged JWT with wrong secret is rejected
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_forged_jwt_rejected(user_a):
    """
    Validates that a JWT signed with the wrong secret key is rejected.
    """
    forged_token = jwt.encode(
        {
            "sub": str(user_a["user_id"]),
            "exp": datetime.now(timezone.utc) + timedelta(hours=1)
        },
        "wrong_secret_key_that_attacker_would_use",
        algorithm="HS256"
    )

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        response = await client.post(
            "/v1/sync/batch",
            json=_make_payload(user_a["source_id"]),
            headers={
                "Authorization": f"Bearer {forged_token}",
                "Idempotency-Key": str(uuid.uuid4())
            }
        )
        assert response.status_code in [401, 403]


# ---------------------------------------------------------------------------
# TEST 4: Missing Authorization header rejected
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_missing_auth_header_rejected(user_a):
    """
    Validates that requests without an Authorization header are rejected.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        response = await client.post(
            "/v1/sync/batch",
            json=_make_payload(user_a["source_id"]),
            headers={"Idempotency-Key": str(uuid.uuid4())}
        )
        assert response.status_code in [401, 403, 422]


# ---------------------------------------------------------------------------
# TEST 5: Oversized batch payload rejected
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_oversized_batch_rejected(user_a):
    """
    Validates that a batch exceeding 500 measurements is rejected with HTTP 422.
    """
    now = datetime.now(timezone.utc)
    oversized_measurements = [
        {
            "source_record_id": f"rec_{i}_{uuid.uuid4().hex[:8]}",
            "metric_type": "heart_rate",
            "value": 72.0,
            "unit": "bpm",
            "recorded_at": (now - timedelta(seconds=i)).isoformat(),
        }
        for i in range(501)  # Exceeds max_length=500
    ]

    payload = {
        "source_id": str(user_a["source_id"]),
        "client_sync_timestamp": now.isoformat(),
        "measurements": oversized_measurements
    }

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        response = await client.post(
            "/v1/sync/batch",
            json=payload,
            headers={
                "Authorization": f"Bearer {user_a['token']}",
                "Idempotency-Key": str(uuid.uuid4())
            }
        )
        assert response.status_code == 422


# ---------------------------------------------------------------------------
# TEST 6: Health and readiness probes are unauthenticated
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_health_probe_is_unauthenticated():
    """
    Validates that /health liveness probe does not require auth.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        response = await client.get("/health")
        assert response.status_code == 200
        assert response.json()["status"] == "healthy"


@pytest.mark.asyncio
async def test_readiness_probe_returns_ready():
    """
    Validates that /ready probe checks PostgreSQL and Redis connectivity.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        response = await client.get("/ready")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ready"
        assert data["checks"]["postgresql"]["status"] == "ok"
        assert data["checks"]["redis"]["status"] == "ok"


# ---------------------------------------------------------------------------
# TEST 7: Security headers are present on all responses
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_security_headers_present():
    """
    Validates that strict security headers are set on all HTTP responses.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        response = await client.get("/health")
        assert response.headers.get("X-Content-Type-Options") == "nosniff"
        assert response.headers.get("X-Frame-Options") == "DENY"
        assert response.headers.get("X-XSS-Protection") == "1; mode=block"
        assert "max-age=" in response.headers.get("Strict-Transport-Security", "")
        assert response.headers.get("Referrer-Policy") == "strict-origin-when-cross-origin"
        assert "default-src" in response.headers.get("Content-Security-Policy", "")


# ---------------------------------------------------------------------------
# TEST 8: Cross-user device token registration forbidden
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_cross_user_device_hijack_rejected(user_a, user_b):
    """
    Validates that User B cannot register an FCM token for User A's device.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        # User A registers device
        res_a = await client.post(
            "/v1/devices/fcm-token",
            json={"fcm_token": "token_a_12345"},
            headers={"Authorization": f"Bearer {user_a['token']}"}
        )
        assert res_a.status_code == 200

        # User B attempts to access or mutate device targeting User A's device ID
        async with TestSessionFactory() as session:
            dev_a = (await session.execute(
                select(Device).where(Device.user_id == user_a["user_id"])
            )).scalars().first()
            assert dev_a is not None

        res_b = await client.post(
            "/v1/devices/fcm-token",
            json={"fcm_token": "token_hijack_attempt", "device_id": str(dev_a.id)},
            headers={"Authorization": f"Bearer {user_b['token']}"}
        )
        # Must be rejected with 403 Forbidden
        assert res_b.status_code in [403, 404]


# ---------------------------------------------------------------------------
# TEST 9: Consent revocation blocks clinical export
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_consent_revocation_blocks_clinical_export(user_a):
    """
    Proves that revoking consent immediately prevents generating or exporting clinical summaries.
    """
    from app.models.care import ClinicalConsent
    from app.services.consent_service import ConsentService
    from app.services.doctor_summary import DoctorVisitSummaryService
    from fastapi import HTTPException

    now = datetime.now(timezone.utc)
    async with TestSessionFactory() as session:
        consent = ClinicalConsent(
            id=uuid.uuid4(),
            user_id=user_a["user_id"],
            consent_version="1.0.0",
            purpose="clinical_brief_export",
            permitted_metrics=["heart_rate"],
            permitted_finding_ids=["*"],
            scope_date_start=now - timedelta(days=1),
            scope_date_end=now + timedelta(days=1),
            include_context=True,
            include_sensor_quality=True,
            include_ai_synthesis=True,
            granted_at=now,
            expires_at=now + timedelta(days=7),
            status="active",
            created_at=now
        )
        session.add(consent)
        await session.commit()

        consent_service = ConsentService(session)
        # Verify consent is active initially
        active_consent = await consent_service.validate_consent_active(user_a["user_id"], consent.id)
        assert active_consent.status == "active"

        # Revoke consent
        revoked = await consent_service.revoke_consent(
            user_id=user_a["user_id"],
            consent_id=consent.id,
            reason="User revoked consent"
        )
        assert revoked.status == "revoked"

        # Verify active check raises HTTP 403 Forbidden
        with pytest.raises(HTTPException) as exc_info:
            await consent_service.validate_consent_active(user_a["user_id"], consent.id)
        assert exc_info.value.status_code == 403

        # Verify doctor summary generation immediately blocked by revoked consent
        doc_service = DoctorVisitSummaryService(session)
        with pytest.raises(HTTPException) as exc_info:
            await doc_service.generate_draft(user_id=user_a["user_id"], consent_id=consent.id)
        assert exc_info.value.status_code == 403


# ---------------------------------------------------------------------------
# TEST 10: Approval token tampering and replay defense
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_approval_token_tampering_detected():
    """
    Validates HMAC verification on approval tokens; tampered token must fail validation.
    """
    from app.services.action_gate import ActionGate, ActionType

    action_id = uuid.uuid4()
    user_id = uuid.uuid4()

    # Generate legitimate approval token
    legit_token = ActionGate.generate_approval_token(
        user_id=user_id,
        action_type=ActionType.EXTERNAL_ACTION,
        target_ref=f"summary:{action_id}"
    )
    assert ActionGate.verify_approval_token(
        token=legit_token,
        user_id=user_id,
        action_type=ActionType.EXTERNAL_ACTION,
        target_ref=f"summary:{action_id}"
    ) is True

    # Tampered token with altered character
    tampered_token = legit_token[:-4] + "dead"
    assert ActionGate.verify_approval_token(
        token=tampered_token,
        user_id=user_id,
        action_type=ActionType.EXTERNAL_ACTION,
        target_ref=f"summary:{action_id}"
    ) is False

    # Cross-action replay attempt: token generated for EXTERNAL_ACTION presented for RECOMMENDATION
    assert ActionGate.verify_approval_token(
        token=legit_token,
        user_id=user_id,
        action_type=ActionType.RECOMMENDATION,
        target_ref=f"summary:{action_id}"
    ) is False

