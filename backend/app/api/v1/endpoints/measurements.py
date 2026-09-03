"""Timeline & Measurement Query Endpoints."""

from datetime import datetime
from typing import Optional
from fastapi import APIRouter, Depends, Query, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.deps import get_current_user, get_db
from app.models.user import User
from app.models.measurement import Measurement
from app.schemas.timeline import TimelineQueryResponse, MeasurementResponse

router = APIRouter(prefix="/measurements", tags=["measurements"])


@router.get(
    "/timeline",
    response_model=TimelineQueryResponse,
    status_code=status.HTTP_200_OK,
    summary="Query normalized health timeline"
)
async def query_timeline(
    metric_type: str = Query(..., description="e.g. heart_rate, steps, sleep_stage"),
    start_time: Optional[datetime] = Query(None, description="Start UTC timestamp"),
    end_time: Optional[datetime] = Query(None, description="End UTC timestamp"),
    limit: int = Query(default=100, ge=1, le=1000),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> TimelineQueryResponse:
    stmt = select(Measurement).where(
        Measurement.user_id == current_user.id,
        Measurement.metric_type == metric_type
    )
    if start_time:
        stmt = stmt.where(Measurement.recorded_at >= start_time)
    if end_time:
        stmt = stmt.where(Measurement.recorded_at <= end_time)
    stmt = stmt.order_by(Measurement.recorded_at.desc()).limit(limit)

    result = await db.execute(stmt)
    records = result.scalars().all()

    items = [
        MeasurementResponse(
            id=r.id,
            metric_type=r.metric_type,
            value=r.value,
            unit=r.unit,
            recorded_at=r.recorded_at,
            confidence=r.confidence,
            data_quality_flag=r.data_quality_flag
        )
        for r in records
    ]
    return TimelineQueryResponse(metric_type=metric_type, count=len(items), measurements=items)
