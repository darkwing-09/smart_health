"""Concurrency & Race Condition Verification for Batch Ingestion.

Validates:
- High-concurrency simultaneous ingestion bursts with identical idempotency_key
- Zero database constraint crashes or unhandled 500 exceptions
- Exactly 1 batch accepted as SUCCESS, concurrent duplicates cleanly return ALREADY_PROCESSED
- Exactly 1 SyncBatch row persisted in PostgreSQL
"""

import asyncio
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.core.config import settings
from app.models.user import User
from app.models.device import WearableSource
from app.models.measurement import SyncBatch, Measurement
from app.schemas.sync import BatchIngestRequest, MeasurementItemSchema
from app.services.ingestion import IngestionService

test_engine = create_async_engine(settings.DATABASE_URL, poolclass=NullPool, future=True)
TestSessionFactory = async_sessionmaker(bind=test_engine, class_=AsyncSession, expire_on_commit=False)


@pytest.fixture
async def concurrency_user():
    user_id = uuid.uuid4()
    source_id = uuid.uuid4()
    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"concurrency_{user_id.hex[:6]}@example.com",
            hashed_password="test_hashed_password",
            full_name="Concurrency Audit User",
            timezone="UTC",
            is_active=True
        )
        source = WearableSource(
            id=source_id,
            user_id=user_id,
            adapter_type="health_connect",
            reliability_tier="official",
            auth_status="ACTIVE"
        )
        session.add_all([user, source])
        await session.commit()
    return user, source


@pytest.mark.asyncio
async def test_concurrent_burst_same_idempotency_key(concurrency_user):
    """
    Spawns 10 concurrent tasks submitting identical BatchIngestRequest with the same idempotency_key.
    Verifies that zero 500/IntegrityError exceptions escape, and DB retains exactly 1 SyncBatch record.
    """
    user, source = concurrency_user
    idempotency_key = f"sync_burst_{uuid.uuid4().hex}"
    now = datetime.now(timezone.utc)

    # Payload with 5 measurements at distinct timestamps
    measurements = [
        MeasurementItemSchema(
            source_record_id=f"rec_{idempotency_key}_{i}",
            metric_type="heart_rate",
            value=72.0 + i,
            unit="bpm",
            recorded_at=now + timedelta(seconds=i * 10),
            confidence=0.95,
            data_quality_flag="nominal"
        )
        for i in range(5)
    ]

    payload = BatchIngestRequest(
        source_id=source.id,
        client_sync_timestamp=now,
        measurements=measurements
    )


    async def submit_batch():
        async with TestSessionFactory() as session:
            service = IngestionService(db=session)
            return await service.process_batch(
                user_id=user.id,
                idempotency_key=idempotency_key,
                payload=payload
            )

    # Launch 10 simultaneous concurrent calls
    responses = await asyncio.gather(*[submit_batch() for _ in range(10)], return_exceptions=False)

    statuses = [r.status for r in responses]
    # Exactly one should be SUCCESS, the other 9 should be ALREADY_PROCESSED
    assert "SUCCESS" in statuses
    assert statuses.count("SUCCESS") == 1
    assert statuses.count("ALREADY_PROCESSED") == 9

    # Verify single SyncBatch in PostgreSQL
    async with TestSessionFactory() as session:
        batches = (await session.scalars(select(SyncBatch).where(SyncBatch.id == idempotency_key))).all()
        assert len(batches) == 1
        assert batches[0].accepted_count == 5
