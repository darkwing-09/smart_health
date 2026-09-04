"""Measurement Ingestion & Deduplication Service."""

import uuid
import logging
from datetime import datetime, timezone
from typing import List
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.dialects.postgresql import insert

from app.models.measurement import Measurement, SyncBatch
from app.schemas.sync import BatchIngestRequest, BatchIngestResponse
from app.services.data_quality import DataQualityEngine, DataQualityRating

logger = logging.getLogger("healthos.ingestion")


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
                invalid_count=getattr(existing_batch, "invalid_count", 0),
                ingested_at=existing_batch.created_at
            )

        # 2. Pre-validate + Bulk Deduplicating Insert
        accepted = 0
        duplicates = 0
        invalid_count = 0
        accepted_measurement_ids: List[str] = []
        now = datetime.now(timezone.utc)

        for item in payload.measurements:
            # Pre-ingestion data quality gate
            rating, flags, reasons = DataQualityEngine.evaluate_point(
                metric_type=item.metric_type,
                value=item.value,
                unit=item.unit,
                recorded_at=item.recorded_at,
                confidence=item.confidence,
                data_quality_flag=item.data_quality_flag,
                reference_time=now
            )

            # Determine persisted quality flag
            if rating == DataQualityRating.INVALID:
                persist_flag = "invalid"
                invalid_count += 1
            else:
                persist_flag = item.data_quality_flag

            measurement_id = uuid.uuid4()
            stmt = insert(Measurement).values(
                id=measurement_id,
                user_id=user_id,
                source_id=payload.source_id,
                metric_type=item.metric_type,
                value=item.value,
                unit=item.unit,
                recorded_at=item.recorded_at,
                confidence=item.confidence,
                data_quality_flag=persist_flag
            ).on_conflict_do_nothing(
                index_elements=["user_id", "source_id", "metric_type", "recorded_at"]
            )
            result = await self.db.execute(stmt)
            if result.rowcount > 0:
                accepted += 1
                if persist_flag != "invalid":
                    accepted_measurement_ids.append(str(measurement_id))
            else:
                duplicates += 1

        # 3. Record SyncBatch metadata (using on_conflict_do_nothing for atomic concurrency safety)
        batch_stmt = insert(SyncBatch).values(
            id=idempotency_key,
            user_id=user_id,
            accepted_count=accepted,
            duplicate_count=duplicates,
            created_at=now
        ).on_conflict_do_nothing(index_elements=["id"])
        batch_result = await self.db.execute(batch_stmt)
        await self.db.commit()

        if batch_result.rowcount == 0:
            # Another concurrent request committed this exact idempotency_key first
            existing = await self.db.get(SyncBatch, idempotency_key)
            if existing:
                return BatchIngestResponse(
                    status="ALREADY_PROCESSED",
                    batch_id=idempotency_key,
                    accepted_count=existing.accepted_count,
                    duplicate_count=existing.duplicate_count,
                    invalid_count=getattr(existing, "invalid_count", 0),
                    ingested_at=existing.created_at
                )


        # 4. Enqueue real-time acute evaluation — FAIL-OPEN
        #    Ingestion must NEVER be blocked by Redis/worker failure.
        if accepted_measurement_ids:
            await self._enqueue_acute_evaluation(
                user_id=str(user_id),
                measurement_ids=accepted_measurement_ids
            )

        return BatchIngestResponse(
            status="SUCCESS",
            batch_id=idempotency_key,
            accepted_count=accepted,
            duplicate_count=duplicates,
            invalid_count=invalid_count,
            ingested_at=now
        )

    async def _enqueue_acute_evaluation(
        self, user_id: str, measurement_ids: List[str]
    ) -> None:
        """Enqueue anomaly evaluation to ARQ worker pool. Fail-open: log and continue."""
        try:
            from arq.connections import create_pool, RedisSettings
            from app.core.config import settings

            pool = await create_pool(RedisSettings.from_dsn(settings.REDIS_URL))
            await pool.enqueue_job(
                "job_evaluate_acute_ingest",
                user_id,
                measurement_ids
            )
            await pool.aclose()
            logger.info(
                "Enqueued acute evaluation job",
                extra={"user_id": user_id, "measurement_count": len(measurement_ids)}
            )
        except Exception as e:
            # FAIL-OPEN: Ingestion succeeds even if Redis is offline.
            # The hourly cron_hourly_trend_rollup will catch missed evaluations.
            logger.warning(
                "Failed to enqueue acute evaluation (fail-open): %s",
                str(e),
                extra={"user_id": user_id}
            )
