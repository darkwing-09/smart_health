"""End-to-End Integration Tests for Android Batch Sync, Idempotency, and Persistence.

Uses live PostgreSQL 16 + TimescaleDB instance. Does NOT mock the database.
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
from app.models.measurement import Measurement, SyncBatch

pwd_context = CryptContext(schemes=["argon2"], deprecated="auto")

# Use NullPool for tests so asyncpg connections do not leak across async event loops
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
    """Seeds an authenticated test user and wearable source in live PostgreSQL."""
    user_id = uuid.uuid4()
    source_id = uuid.uuid4()
    device_id = uuid.uuid4()

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"testuser_{user_id.hex[:8]}@healthos.test",
            hashed_password=pwd_context.hash("SecurePass123!"),
            full_name="Integration Test Patient",
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
            device_id=device.id,
            adapter_type="health_connect",
            reliability_tier="official",
            auth_status="ACTIVE"
        )
        session.add(source)
        await session.commit()

    # Generate JWT Bearer Token
    token_payload = {
        "sub": str(user_id),
        "exp": datetime.now(timezone.utc) + timedelta(minutes=60)
    }
    token = jwt.encode(token_payload, settings.SECRET_KEY, algorithm="HS256")

    yield {
        "user_id": user_id,
        "source_id": source_id,
        "token": token
    }


@pytest.mark.asyncio
async def test_sync_batch_persistence_and_provenance(test_user):
    """
    SLICE 4 VERIFICATION:
    Android Health Connect Batch -> FastAPI -> Pydantic -> IngestionService -> PostgreSQL/TimescaleDB
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        source_id = test_user["source_id"]
        token = test_user["token"]
        idempotency_key = str(uuid.uuid4())
        now = datetime.now(timezone.utc)

        # Realistic Android Health Connect batch
        payload = {
            "source_id": str(source_id),
            "client_sync_timestamp": now.isoformat(),
            "measurements": [
                {
                    "source_record_id": f"hc_hr_{uuid.uuid4()}",
                    "metric_type": "heart_rate",
                    "value": 68.0,
                    "unit": "bpm",
                    "recorded_at": (now - timedelta(minutes=15)).isoformat(),
                    "confidence": 0.99,
                    "data_quality_flag": "nominal"
                },
                {
                    "source_record_id": f"hc_steps_{uuid.uuid4()}",
                    "metric_type": "steps",
                    "value": 240.0,
                    "unit": "count",
                    "recorded_at": (now - timedelta(minutes=10)).isoformat(),
                    "confidence": 1.0,
                    "data_quality_flag": "nominal"
                },
                {
                    "source_record_id": f"hc_rhr_{uuid.uuid4()}",
                    "metric_type": "resting_heart_rate",
                    "value": 58.0,
                    "unit": "bpm",
                    "recorded_at": (now - timedelta(minutes=5)).isoformat(),
                    "confidence": 0.95,
                    "data_quality_flag": "nominal"
                }
            ]
        }

        # 1. Dispatch POST /v1/sync/batch
        headers = {
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": idempotency_key
        }
        response = await client.post("/v1/sync/batch", json=payload, headers=headers)
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "SUCCESS"
        assert data["batch_id"] == idempotency_key
        assert data["accepted_count"] == 3
        assert data["duplicate_count"] == 0

        # 2. Query Database Directly to Verify Persistence & Provenance
        async with TestSessionFactory() as session:
            stmt = select(Measurement).where(Measurement.user_id == test_user["user_id"])
            result = await session.execute(stmt)
            saved_records = result.scalars().all()
            assert len(saved_records) == 3

            # Verify individual metric values and provenance
            metrics_map = {m.metric_type: m for m in saved_records}
            assert "heart_rate" in metrics_map
            assert metrics_map["heart_rate"].value == 68.0
            assert metrics_map["heart_rate"].unit == "bpm"
            assert metrics_map["heart_rate"].source_id == source_id
            assert metrics_map["heart_rate"].data_quality_flag == "nominal"

            assert "steps" in metrics_map
            assert metrics_map["steps"].value == 240.0

            # Verify SyncBatch record
            batch_record = await session.get(SyncBatch, idempotency_key)
            assert batch_record is not None
            assert batch_record.accepted_count == 3
            assert batch_record.duplicate_count == 0


@pytest.mark.asyncio
async def test_sync_batch_idempotency(test_user):
    """
    SLICE 5 VERIFICATION:
    Sending identical batch with same Idempotency-Key must return ALREADY_PROCESSED and create 0 duplicates.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        source_id = test_user["source_id"]
        token = test_user["token"]
        idempotency_key = str(uuid.uuid4())
        now = datetime.now(timezone.utc)

        payload = {
            "source_id": str(source_id),
            "client_sync_timestamp": now.isoformat(),
            "measurements": [
                {
                    "source_record_id": f"hc_hr_{uuid.uuid4()}",
                    "metric_type": "heart_rate",
                    "value": 72.0,
                    "unit": "bpm",
                    "recorded_at": (now - timedelta(minutes=2)).isoformat(),
                    "confidence": 1.0,
                    "data_quality_flag": "nominal"
                }
            ]
        }
        headers = {
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": idempotency_key
        }

        # First dispatch -> SUCCESS
        r1 = await client.post("/v1/sync/batch", json=payload, headers=headers)
        assert r1.status_code == 200
        assert r1.json()["status"] == "SUCCESS"
        assert r1.json()["accepted_count"] == 1

        # Second dispatch with SAME Idempotency-Key -> ALREADY_PROCESSED
        r2 = await client.post("/v1/sync/batch", json=payload, headers=headers)
        assert r2.status_code == 200
        assert r2.json()["status"] == "ALREADY_PROCESSED"
        assert r2.json()["batch_id"] == idempotency_key

        # Assert database record count did not increase
        async with TestSessionFactory() as session:
            count = await session.scalar(
                select(func.count(Measurement.id)).where(Measurement.user_id == test_user["user_id"])
            )
            assert count == 1


@pytest.mark.asyncio
async def test_malformed_batch_rejected_without_persistence(test_user):
    """
    SLICE 6 VERIFICATION:
    Malformed payload is rejected by Pydantic validation with HTTP 422 and persists zero records.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        source_id = test_user["source_id"]
        token = test_user["token"]
        idempotency_key = str(uuid.uuid4())

        # Payload with an unallowed metric_type
        bad_payload = {
            "source_id": str(source_id),
            "client_sync_timestamp": datetime.now(timezone.utc).isoformat(),
            "measurements": [
                {
                    "source_record_id": "bad_rec_001",
                    "metric_type": "unsupported_cosmic_frequency",
                    "value": 999.0,
                    "unit": "hertz",
                    "recorded_at": datetime.now(timezone.utc).isoformat()
                }
            ]
        }
        headers = {
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": idempotency_key
        }

        response = await client.post("/v1/sync/batch", json=bad_payload, headers=headers)
        assert response.status_code == 422

        # Assert no measurements or sync batch records were inserted
        async with TestSessionFactory() as session:
            batch = await session.get(SyncBatch, idempotency_key)
            assert batch is None
            meas_count = await session.scalar(
                select(func.count(Measurement.id)).where(Measurement.user_id == test_user["user_id"])
            )
            assert meas_count == 0


@pytest.mark.asyncio
async def test_measurement_level_deduplication(test_user):
    """
    SLICE 5 VERIFICATION:
    New batch with different idempotency key but duplicate measurements (same user, source, metric, timestamp)
    is caught by the unique deduplication index ON CONFLICT DO NOTHING.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        source_id = test_user["source_id"]
        token = test_user["token"]
        recorded_at_str = (datetime.now(timezone.utc) - timedelta(minutes=30)).isoformat()

        batch_1_key = str(uuid.uuid4())
        batch_2_key = str(uuid.uuid4())

        payload_1 = {
            "source_id": str(source_id),
            "client_sync_timestamp": datetime.now(timezone.utc).isoformat(),
            "measurements": [
                {
                    "source_record_id": "hr_rec_dedup_01",
                    "metric_type": "heart_rate",
                    "value": 75.0,
                    "unit": "bpm",
                    "recorded_at": recorded_at_str,
                    "confidence": 1.0,
                    "data_quality_flag": "nominal"
                }
            ]
        }

        # 1. Dispatch first batch
        r1 = await client.post(
            "/v1/sync/batch",
            json=payload_1,
            headers={"Authorization": f"Bearer {token}", "Idempotency-Key": batch_1_key}
        )
        assert r1.status_code == 200
        assert r1.json()["accepted_count"] == 1
        assert r1.json()["duplicate_count"] == 0

        # 2. Dispatch second batch with NEW batch key, but SAME measurement tuple
        payload_2 = {
            "source_id": str(source_id),
            "client_sync_timestamp": datetime.now(timezone.utc).isoformat(),
            "measurements": [
                {
                    "source_record_id": "hr_rec_dedup_02",
                    "metric_type": "heart_rate",
                    "value": 75.0,
                    "unit": "bpm",
                    "recorded_at": recorded_at_str,
                    "confidence": 1.0,
                    "data_quality_flag": "nominal"
                }
            ]
        }
        r2 = await client.post(
            "/v1/sync/batch",
            json=payload_2,
            headers={"Authorization": f"Bearer {token}", "Idempotency-Key": batch_2_key}
        )
        assert r2.status_code == 200
        assert r2.json()["accepted_count"] == 0
        assert r2.json()["duplicate_count"] == 1

        # 3. Database row count must still be exactly 1
        async with TestSessionFactory() as session:
            count = await session.scalar(
                select(func.count(Measurement.id)).where(Measurement.user_id == test_user["user_id"])
            )
            assert count == 1


@pytest.mark.asyncio
async def test_user_data_isolation(test_user):
    """
    SLICE 6 VERIFICATION:
    Verifies multi-tenant isolation. Measurements ingested for User A are not visible to User B.
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://localhost:8000") as client:
        # Create User B
        user_b_id = uuid.uuid4()
        device_b_id = uuid.uuid4()
        source_b_id = uuid.uuid4()

        async with TestSessionFactory() as session:
            user_b = User(
                id=user_b_id,
                email=f"user_b_{user_b_id.hex[:8]}@healthos.test",
                hashed_password=pwd_context.hash("Password123!"),
                full_name="Patient B",
                timezone="UTC",
                is_active=True
            )
            session.add(user_b)
            dev_b = Device(
                id=device_b_id,
                user_id=user_b_id,
                device_type="phone",
                brand="Pixel",
                model="Pixel 8",
                os_version="Android 14"
            )
            session.add(dev_b)
            src_b = WearableSource(
                id=source_b_id,
                user_id=user_b_id,
                device_id=device_b_id,
                adapter_type="health_connect",
                reliability_tier="official",
                auth_status="ACTIVE"
            )
            session.add(src_b)
            await session.commit()

        token_b = jwt.encode(
            {"sub": str(user_b_id), "exp": datetime.now(timezone.utc) + timedelta(hours=1)},
            settings.SECRET_KEY,
            algorithm="HS256"
        )

        now = datetime.now(timezone.utc)
        # Ingest for User A
        await client.post(
            "/v1/sync/batch",
            json={
                "source_id": str(test_user["source_id"]),
                "client_sync_timestamp": now.isoformat(),
                "measurements": [{
                    "source_record_id": f"rec_a_{uuid.uuid4()}",
                    "metric_type": "heart_rate",
                    "value": 88.0,
                    "unit": "bpm",
                    "recorded_at": (now - timedelta(minutes=5)).isoformat()
                }]
            },
            headers={"Authorization": f"Bearer {test_user['token']}", "Idempotency-Key": str(uuid.uuid4())}
        )

        # Ingest for User B
        await client.post(
            "/v1/sync/batch",
            json={
                "source_id": str(source_b_id),
                "client_sync_timestamp": now.isoformat(),
                "measurements": [{
                    "source_record_id": f"rec_b_{uuid.uuid4()}",
                    "metric_type": "heart_rate",
                    "value": 62.0,
                    "unit": "bpm",
                    "recorded_at": (now - timedelta(minutes=5)).isoformat()
                }]
            },
            headers={"Authorization": f"Bearer {token_b}", "Idempotency-Key": str(uuid.uuid4())}
        )

        # Query Database directly for isolation check
        async with TestSessionFactory() as session:
            count_a = await session.scalar(
                select(func.count(Measurement.id)).where(Measurement.user_id == test_user["user_id"])
            )
            count_b = await session.scalar(
                select(func.count(Measurement.id)).where(Measurement.user_id == user_b_id)
            )
            assert count_a == 1
            assert count_b == 1

