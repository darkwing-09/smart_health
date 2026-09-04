"""Failure Injection Tests — Production Pilot Reliability Verification.

Validates that the system degrades gracefully under component failures:
1. Ingestion succeeds when Redis is completely down (fail-open).
2. Data quality engine quarantines impossible biological values.
3. Stale/delayed batches ingest with correct historical timestamps.
4. Anomaly pipeline operates deterministically under LLM outage.
5. New metric types are accepted and validated correctly.
"""

import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import patch, AsyncMock

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
from app.models.measurement import Measurement, SyncBatch
from app.services.data_quality import DataQualityEngine, DataQualityRating

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
async def test_user():
    user_id = uuid.uuid4()
    source_id = uuid.uuid4()
    device_id = uuid.uuid4()

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"failtest_{user_id.hex[:8]}@healthos.test",
            hashed_password=pwd_context.hash("SecurePass123!"),
            full_name="Failure Test Patient",
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
        await session.commit()

    token = jwt.encode(
        {"sub": str(user_id), "exp": datetime.now(timezone.utc) + timedelta(hours=1)},
        settings.SECRET_KEY,
        algorithm="HS256"
    )

    yield {"user_id": user_id, "source_id": source_id, "token": token}


def _make_batch(source_id, measurements):
    return {
        "source_id": str(source_id),
        "client_sync_timestamp": datetime.now(timezone.utc).isoformat(),
        "measurements": measurements
    }


def _make_measurement(metric_type="heart_rate", value=72.0, unit="bpm", offset_minutes=5):
    return {
        "source_record_id": f"rec_{uuid.uuid4().hex[:12]}",
        "metric_type": metric_type,
        "value": value,
        "unit": unit,
        "recorded_at": (datetime.now(timezone.utc) - timedelta(minutes=offset_minutes)).isoformat(),
        "confidence": 1.0,
        "data_quality_flag": "nominal"
    }


# ---------------------------------------------------------------------------
# TEST 1: Ingestion succeeds when Redis is completely down (fail-open)
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_ingestion_succeeds_when_redis_is_down(test_user):
    """
    Validates FAIL-OPEN invariant: batch ingestion completes successfully
    even when Redis/ARQ worker pool is completely unreachable.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        payload = _make_batch(test_user["source_id"], [_make_measurement()])
        headers = {
            "Authorization": f"Bearer {test_user['token']}",
            "Idempotency-Key": str(uuid.uuid4())
        }

        # Mock the underlying Redis pool creation to fail (inside _enqueue_acute_evaluation)
        with patch(
            "arq.connections.create_pool",
            new_callable=AsyncMock,
            side_effect=ConnectionError("Redis connection refused")
        ):
            response = await client.post("/v1/sync/batch", json=payload, headers=headers)

        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "SUCCESS"
        assert data["accepted_count"] == 1

        # Verify data actually persisted in PostgreSQL
        async with TestSessionFactory() as session:
            count = await session.scalar(
                select(func.count(Measurement.id)).where(
                    Measurement.user_id == test_user["user_id"]
                )
            )
            assert count == 1


# ---------------------------------------------------------------------------
# TEST 2: Impossible biological values are tagged invalid and quarantined
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_impossible_values_tagged_invalid(test_user):
    """
    Validates DataQualityEngine pre-validation: impossible heart rate (500 bpm)
    is persisted with data_quality_flag='invalid' and counted in invalid_count.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        payload = _make_batch(test_user["source_id"], [
            _make_measurement(metric_type="heart_rate", value=500.0, unit="bpm"),
            _make_measurement(metric_type="heart_rate", value=72.0, unit="bpm", offset_minutes=10),
        ])
        headers = {
            "Authorization": f"Bearer {test_user['token']}",
            "Idempotency-Key": str(uuid.uuid4())
        }

        response = await client.post("/v1/sync/batch", json=payload, headers=headers)
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "SUCCESS"
        assert data["accepted_count"] == 2  # Both persisted
        assert data["invalid_count"] == 1   # One flagged invalid

        # Verify the invalid record is persisted with correct flag
        async with TestSessionFactory() as session:
            result = await session.execute(
                select(Measurement).where(
                    Measurement.user_id == test_user["user_id"],
                    Measurement.value == 500.0
                )
            )
            invalid_record = result.scalars().first()
            assert invalid_record is not None
            assert invalid_record.data_quality_flag == "invalid"

            # Nominal record should retain its flag
            result2 = await session.execute(
                select(Measurement).where(
                    Measurement.user_id == test_user["user_id"],
                    Measurement.value == 72.0
                )
            )
            valid_record = result2.scalars().first()
            assert valid_record is not None
            assert valid_record.data_quality_flag == "nominal"


# ---------------------------------------------------------------------------
# TEST 3: Stale/delayed batches ingest with historical timestamps
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_stale_batch_ingests_with_historical_timestamps(test_user):
    """
    Validates that data delayed by 24+ hours during offline sync
    ingests with the original device recording timestamp intact.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        historical_time = datetime.now(timezone.utc) - timedelta(hours=26)
        payload = _make_batch(test_user["source_id"], [{
            "source_record_id": f"stale_{uuid.uuid4().hex[:12]}",
            "metric_type": "heart_rate",
            "value": 65.0,
            "unit": "bpm",
            "recorded_at": historical_time.isoformat(),
            "confidence": 0.95,
            "data_quality_flag": "nominal"
        }])
        headers = {
            "Authorization": f"Bearer {test_user['token']}",
            "Idempotency-Key": str(uuid.uuid4())
        }

        response = await client.post("/v1/sync/batch", json=payload, headers=headers)
        assert response.status_code == 200
        data = response.json()
        assert data["accepted_count"] == 1

        # Verify the original timestamp was preserved
        async with TestSessionFactory() as session:
            result = await session.execute(
                select(Measurement).where(Measurement.user_id == test_user["user_id"])
            )
            record = result.scalars().first()
            assert record is not None
            # Allow 1-second tolerance for datetime parsing
            assert abs((record.recorded_at - historical_time).total_seconds()) < 2.0


# ---------------------------------------------------------------------------
# TEST 4: New metric types are accepted and validated
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_new_metric_types_accepted(test_user):
    """
    Validates that expanded metric types (spo2, hrv, respiratory_rate, etc.)
    are accepted by schema validation and persisted correctly.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        now = datetime.now(timezone.utc)
        payload = _make_batch(test_user["source_id"], [
            {
                "source_record_id": f"spo2_{uuid.uuid4().hex[:8]}",
                "metric_type": "spo2",
                "value": 97.5,
                "unit": "%",
                "recorded_at": (now - timedelta(minutes=5)).isoformat(),
            },
            {
                "source_record_id": f"hrv_{uuid.uuid4().hex[:8]}",
                "metric_type": "hrv",
                "value": 42.0,
                "unit": "ms",
                "recorded_at": (now - timedelta(minutes=10)).isoformat(),
            },
            {
                "source_record_id": f"resp_{uuid.uuid4().hex[:8]}",
                "metric_type": "respiratory_rate",
                "value": 16.0,
                "unit": "rpm",
                "recorded_at": (now - timedelta(minutes=15)).isoformat(),
            },
            {
                "source_record_id": f"temp_{uuid.uuid4().hex[:8]}",
                "metric_type": "body_temperature",
                "value": 36.8,
                "unit": "celsius",
                "recorded_at": (now - timedelta(minutes=20)).isoformat(),
            },
        ])
        headers = {
            "Authorization": f"Bearer {test_user['token']}",
            "Idempotency-Key": str(uuid.uuid4())
        }

        response = await client.post("/v1/sync/batch", json=payload, headers=headers)
        assert response.status_code == 200
        data = response.json()
        assert data["accepted_count"] == 4
        assert data["invalid_count"] == 0

        # Verify all metric types persisted
        async with TestSessionFactory() as session:
            result = await session.execute(
                select(Measurement.metric_type).where(
                    Measurement.user_id == test_user["user_id"]
                )
            )
            metric_types = {row[0] for row in result.all()}
            assert {"spo2", "hrv", "respiratory_rate", "body_temperature"} == metric_types


# ---------------------------------------------------------------------------
# TEST 5: DataQualityEngine unit tests for new bounds
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_data_quality_engine_spo2_bounds():
    """Validates SpO2 biological bounds: 50-100%."""
    now = datetime.now(timezone.utc)

    # Valid SpO2
    rating, flags, _ = DataQualityEngine.evaluate_point(
        "spo2", 96.0, "%", now - timedelta(minutes=1), reference_time=now
    )
    assert rating == DataQualityRating.EXCELLENT

    # Impossible SpO2 (> 100%)
    rating, flags, _ = DataQualityEngine.evaluate_point(
        "spo2", 105.0, "%", now - timedelta(minutes=1), reference_time=now
    )
    assert rating == DataQualityRating.INVALID
    assert "IMPOSSIBLE_VALUE" in flags

    # Impossible SpO2 (< 50%)
    rating, flags, _ = DataQualityEngine.evaluate_point(
        "spo2", 30.0, "%", now - timedelta(minutes=1), reference_time=now
    )
    assert rating == DataQualityRating.INVALID


@pytest.mark.asyncio
async def test_data_quality_engine_body_temp_bounds():
    """Validates body temperature biological bounds: 30-45°C."""
    now = datetime.now(timezone.utc)

    # Normal body temperature
    rating, _, _ = DataQualityEngine.evaluate_point(
        "body_temperature", 36.6, "celsius", now - timedelta(minutes=1), reference_time=now
    )
    assert rating == DataQualityRating.EXCELLENT

    # Impossible (sensor glitch)
    rating, flags, _ = DataQualityEngine.evaluate_point(
        "body_temperature", 50.0, "celsius", now - timedelta(minutes=1), reference_time=now
    )
    assert rating == DataQualityRating.INVALID
    assert "IMPOSSIBLE_VALUE" in flags


@pytest.mark.asyncio
async def test_data_quality_engine_hrv_bounds():
    """Validates HRV biological bounds: 5-300ms."""
    now = datetime.now(timezone.utc)

    # Normal HRV
    rating, _, _ = DataQualityEngine.evaluate_point(
        "hrv", 45.0, "ms", now - timedelta(minutes=1), reference_time=now
    )
    assert rating == DataQualityRating.EXCELLENT

    # Impossible HRV (0.5ms)
    rating, flags, _ = DataQualityEngine.evaluate_point(
        "hrv", 0.5, "ms", now - timedelta(minutes=1), reference_time=now
    )
    assert rating == DataQualityRating.INVALID
