"""Measurement Ingestion & Deduplication Service."""

import uuid
from datetime import datetime, timezone
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.dialects.postgresql import insert
from app.models.measurement import Measurement, SyncBatch
from app.schemas.sync import BatchIngestRequest, BatchIngestResponse


class IngestionService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def process_batch(
        self,
        user_id: uuid.UUID,
        idempotency_key: str,
        payload: BatchIngestRequest
    ) -> BatchIngestResponse:
        # 1. Idempotency Check
        existing_batch = await self.db.get(SyncBatch, idempotency_key)
        if existing_batch:
            return BatchIngestResponse(
                status="ALREADY_PROCESSED",
                batch_id=idempotency_key,
                accepted_count=existing_batch.accepted_count,
                duplicate_count=existing_batch.duplicate_count,
                ingested_at=existing_batch.created_at
            )

        # 2. Bulk Deduplicating Insert
        accepted = 0
        duplicates = 0

        for item in payload.measurements:
            stmt = insert(Measurement).values(
                id=uuid.uuid4(),
                user_id=user_id,
                source_id=payload.source_id,
                metric_type=item.metric_type,
                value=item.value,
                unit=item.unit,
                recorded_at=item.recorded_at,
                confidence=item.confidence,
                data_quality_flag=item.data_quality_flag
            ).on_conflict_do_nothing(
                index_elements=["user_id", "source_id", "metric_type", "recorded_at"]
            )
            result = await self.db.execute(stmt)
            if result.rowcount > 0:
                accepted += 1
            else:
                duplicates += 1

        # 3. Record SyncBatch metadata
        now = datetime.now(timezone.utc)
        batch = SyncBatch(
            id=idempotency_key,
            user_id=user_id,
            accepted_count=accepted,
            duplicate_count=duplicates,
            created_at=now
        )
        self.db.add(batch)
        await self.db.commit()

        return BatchIngestResponse(
            status="SUCCESS",
            batch_id=idempotency_key,
            accepted_count=accepted,
            duplicate_count=duplicates,
            ingested_at=now
        )
