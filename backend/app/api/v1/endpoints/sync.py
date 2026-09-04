"""Synchronization and Batch Ingestion Endpoints."""

from fastapi import APIRouter, Depends, Header, Request, status
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.deps import get_current_user, get_db
from app.core.config import settings
from app.core.rate_limit import check_rate_limit
from app.models.user import User
from app.schemas.sync import BatchIngestRequest, BatchIngestResponse
from app.services.ingestion import IngestionService

router = APIRouter(prefix="/sync", tags=["synchronization"])


@router.post(
    "/batch",
    response_model=BatchIngestResponse,
    status_code=status.HTTP_200_OK,
    summary="Ingest batch of wearable measurements with idempotency"
)
async def batch_ingest(
    payload: BatchIngestRequest,
    request: Request,
    idempotency_key: str = Header(..., alias="Idempotency-Key", description="Unique client UUID for idempotency"),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> BatchIngestResponse:
    """
    Ingests up to 500 normalized measurements from Android Health Connect.
    Preserves raw provenance and deduplicates using (user_id, source_id, metric_type, recorded_at).
    """
    await check_rate_limit(
        request=request,
        scope="sync:batch",
        limit=settings.RATE_LIMIT_SYNC_PER_MIN,
        identifier=str(current_user.id)
    )

    service = IngestionService(db)
    return await service.process_batch(
        user_id=current_user.id,
        idempotency_key=idempotency_key,
        payload=payload
    )

