"""Integration Test Suite: Data Quality Under Real-World Wearable Conditions.

Validates that messy, delayed, missing, out-of-order, or impossible wearable telemetry
is deterministically handled, quarantined, or ingested without corrupting the personal baseline.
Executes against live PostgreSQL (TimescaleDB) with NullPool isolation.
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
from app.db.session import get_db
from app.main import app
from app.models.user import User
from app.models.device import WearableSource
from app.models.measurement import Measurement, SyncBatch
from app.services.data_quality import DataQualityEngine, DataQualityRating


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
async def pilot_user() -> AsyncGenerator[tuple[User, WearableSource, dict[str, str]], None]:
    user_id = uuid.uuid4()
    source_id = uuid.uuid4()
    now_utc = datetime.now(timezone.utc)

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"dq_user_{user_id.hex[:8]}@example.com",
            hashed_password="test_hashed_password",
            full_name="Data Quality Pilot Subject",
            timezone="Asia/Kolkata",
            is_active=True
        )
        session.add(user)
        source = WearableSource(
            id=source_id,
            user_id=user_id,
            adapter_type="health_connect",
            reliability_tier="official",
            auth_status="ACTIVE"
        )
        session.add(source)
        await session.commit()

    from jose import jwt
    token = jwt.encode(
        {"sub": str(user_id), "exp": datetime.now(timezone.utc) + timedelta(hours=2)},
        settings.SECRET_KEY,
        algorithm="HS256"
    )
    headers = {"Authorization": f"Bearer {token}"}
    yield user, source, headers


@pytest.mark.asyncio
async def test_sensor_detachment_quality_evaluation():
    """Proves that sensor detachment (zero HR or unwearable flag) is classified as POOR/INVALID and cannot pollute baseline."""
    now = datetime.now(timezone.utc)
    
    # 1. Zero heart rate is biologically impossible (below 30.0 bpm)
    rating, flags, reasons = DataQualityEngine.evaluate_point(
        metric_type="heart_rate",
        value=0.0,
        unit="bpm",
        recorded_at=now
    )
    assert rating == DataQualityRating.INVALID
    assert "IMPOSSIBLE_VALUE" in flags

    # 2. Telemetry tagged as 'unwearable' during off-wrist periods
    rating_off, flags_off, _ = DataQualityEngine.evaluate_point(
        metric_type="heart_rate",
        value=72.0,
        unit="bpm",
        recorded_at=now,
        data_quality_flag="unwearable"
    )
    assert rating_off == DataQualityRating.POOR
    assert "QUALITY_FLAG_UNWEARABLE" in flags_off


@pytest.mark.asyncio
async def test_impossible_biological_values_quarantined_via_api(pilot_user):
    """Submits impossible biological values via /v1/sync/batch and asserts they are flagged 'invalid'."""
    user, source, headers = pilot_user
    transport = ASGITransport(app=app)
    now = datetime.now(timezone.utc)

    impossible_batch = {
        "source_id": str(source.id),
        "client_sync_timestamp": now.isoformat(),
        "measurements": [
            # Impossible HR > 240 bpm
            {
                "source_record_id": f"hr_impossible_{uuid.uuid4().hex[:6]}",
                "metric_type": "heart_rate",
                "value": 380.0,
                "unit": "bpm",
                "recorded_at": (now - timedelta(minutes=5)).isoformat(),
                "confidence": 1.0,
                "data_quality_flag": "nominal"
            },
            # Impossible SpO2 < 50%
            {
                "source_record_id": f"spo2_impossible_{uuid.uuid4().hex[:6]}",
                "metric_type": "spo2",
                "value": 25.0,
                "unit": "%",
                "recorded_at": (now - timedelta(minutes=4)).isoformat(),
                "confidence": 1.0,
                "data_quality_flag": "nominal"
            },
            # Valid steps sample
            {
                "source_record_id": f"steps_valid_{uuid.uuid4().hex[:6]}",
                "metric_type": "steps",
                "value": 150.0,
                "unit": "count",
                "recorded_at": (now - timedelta(minutes=3)).isoformat(),
                "confidence": 1.0,
                "data_quality_flag": "nominal"
            }
        ]
    }

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        req_headers = {**headers, "Idempotency-Key": str(uuid.uuid4())}
        res = await client.post("/v1/sync/batch", json=impossible_batch, headers=req_headers)
        assert res.status_code == 200
        data = res.json()
        assert data["accepted_count"] == 3

    # Verify in DB: impossible samples must have data_quality_flag = 'invalid'
    async with TestSessionFactory() as session:
        rows = (await session.execute(
            text("SELECT metric_type, value, data_quality_flag FROM measurements WHERE user_id = :uid"),
            {"uid": user.id}
        )).fetchall()

        by_metric = {r[0]: (r[1], r[2]) for r in rows}
        assert by_metric["heart_rate"][1] == "invalid"
        assert by_metric["spo2"][1] == "invalid"
        assert by_metric["steps"][1] == "nominal"


@pytest.mark.asyncio
async def test_delayed_and_out_of_order_sync_handling(pilot_user):
    """Verifies that samples delayed by 36 hours from an offline wearable ingest with true historical timestamps."""
    user, source, headers = pilot_user
    transport = ASGITransport(app=app)
    now = datetime.now(timezone.utc)
    t_36h_ago = now - timedelta(hours=36)
    t_35h_ago = now - timedelta(hours=35)

    delayed_batch = {
        "source_id": str(source.id),
        "client_sync_timestamp": now.isoformat(),
        "measurements": [
            {
                "source_record_id": f"delayed_hr_1_{uuid.uuid4().hex[:6]}",
                "metric_type": "heart_rate",
                "value": 68.0,
                "unit": "bpm",
                "recorded_at": t_36h_ago.isoformat(),
                "confidence": 0.95,
                "data_quality_flag": "nominal"
            },
            {
                "source_record_id": f"delayed_hr_2_{uuid.uuid4().hex[:6]}",
                "metric_type": "heart_rate",
                "value": 72.0,
                "unit": "bpm",
                "recorded_at": t_35h_ago.isoformat(),
                "confidence": 0.95,
                "data_quality_flag": "nominal"
            }
        ]
    }

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        req_headers = {**headers, "Idempotency-Key": str(uuid.uuid4())}
        res = await client.post("/v1/sync/batch", json=delayed_batch, headers=req_headers)
        assert res.status_code == 200
        assert res.json()["accepted_count"] == 2

    # Verify timestamps preserved in hypertable
    async with TestSessionFactory() as session:
        db_pts = (await session.execute(
            text("SELECT recorded_at, value FROM measurements WHERE user_id = :uid ORDER BY recorded_at ASC"),
            {"uid": user.id}
        )).fetchall()
        assert len(db_pts) == 2
        # Assert timestamp matches historical time, not current ingestion time
        assert db_pts[0][0].year == t_36h_ago.year
        assert db_pts[0][0].hour == t_36h_ago.hour


@pytest.mark.asyncio
async def test_future_timestamp_clock_drift_rejection():
    """Checks that clock drift > 5 minutes in the future is tagged INVALID."""
    now = datetime.now(timezone.utc)
    way_in_future = now + timedelta(hours=2)

    rating, flags, reasons = DataQualityEngine.evaluate_point(
        metric_type="heart_rate",
        value=72.0,
        unit="bpm",
        recorded_at=way_in_future,
        reference_time=now
    )
    assert rating == DataQualityRating.INVALID
    assert "FUTURE_TIMESTAMP" in flags


@pytest.mark.asyncio
async def test_repeated_sync_deduplication_isolation(pilot_user):
    """Ensures duplicate batch resubmission yields 0 duplicate rows in hypertable."""
    user, source, headers = pilot_user
    transport = ASGITransport(app=app)
    now = datetime.now(timezone.utc)

    idempotency_key = str(uuid.uuid4())
    batch_payload = {
        "source_id": str(source.id),
        "client_sync_timestamp": now.isoformat(),
        "measurements": [
            {
                "source_record_id": "fixed_source_id_001",
                "metric_type": "heart_rate",
                "value": 75.0,
                "unit": "bpm",
                "recorded_at": (now - timedelta(minutes=10)).isoformat(),
                "confidence": 0.99,
                "data_quality_flag": "nominal"
            }
        ]
    }

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # First submission
        res1 = await client.post("/v1/sync/batch", json=batch_payload, headers={**headers, "Idempotency-Key": idempotency_key})
        assert res1.status_code == 200
        assert res1.json()["accepted_count"] == 1

        # Identical submission with same idempotency key (network retry simulation)
        res2 = await client.post("/v1/sync/batch", json=batch_payload, headers={**headers, "Idempotency-Key": idempotency_key})
        assert res2.status_code == 200
        assert res2.json()["status"] == "ALREADY_PROCESSED"

        # Resubmission with new idempotency key but same telemetry tuple (replay simulation)
        res3 = await client.post("/v1/sync/batch", json=batch_payload, headers={**headers, "Idempotency-Key": str(uuid.uuid4())})
        assert res3.status_code == 200
        # Should be recognized as duplicate row by unique index
        assert res3.json()["accepted_count"] == 0
        assert res3.json()["duplicate_count"] == 1

    # Exactly 1 row must exist in DB
    async with TestSessionFactory() as session:
        count = (await session.execute(
            text("SELECT count(*) FROM measurements WHERE user_id = :uid"),
            {"uid": user.id}
        )).scalar()
        assert count == 1
