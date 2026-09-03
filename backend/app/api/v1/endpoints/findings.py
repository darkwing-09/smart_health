"""Findings and Anomaly Explanation Endpoints."""

import uuid
from datetime import datetime, timezone
from typing import List, Optional
from fastapi import APIRouter, Depends, Query, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import selectinload
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.deps import get_current_user, get_db
from app.models.user import User
from app.models.finding import Finding
from app.schemas.finding import FindingResponse, FindingExplanationSchema, AcknowledgeResponse

router = APIRouter(prefix="/findings", tags=["findings"])


@router.get(
    "",
    response_model=List[FindingResponse],
    status_code=status.HTTP_200_OK,
    summary="List active or historical findings"
)
async def list_findings(
    status_filter: Optional[str] = Query(None, alias="status", description="new, notified, acknowledged, resolved"),
    limit: int = Query(default=50, ge=1, le=200),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> List[FindingResponse]:
    stmt = select(Finding).where(Finding.user_id == current_user.id).options(selectinload(Finding.explanations))
    if status_filter:
        stmt = stmt.where(Finding.status == status_filter)
    stmt = stmt.order_by(Finding.first_detected_at.desc()).limit(limit)

    result = await db.execute(stmt)
    findings = result.scalars().all()

    response = []
    for f in findings:
        explanation_dto = None
        if f.explanations:
            latest = f.explanations[-1]
            explanation_dto = FindingExplanationSchema(
                what_changed=latest.what_changed,
                measurements_caused=latest.measurements_caused,
                baseline_difference=latest.baseline_difference,
                historical_context=latest.historical_context,
                confidence_and_data_quality=latest.confidence_and_data_quality,
                why_it_matters=latest.why_it_matters,
                next_steps=latest.next_steps
            )
        response.append(
            FindingResponse(
                id=f.id,
                metric_type=f.metric_type,
                severity=f.severity,
                status=f.status,
                first_detected_at=f.first_detected_at,
                last_updated_at=f.last_updated_at,
                explanation=explanation_dto
            )
        )
    return response


@router.post(
    "/{finding_id}/acknowledge",
    response_model=AcknowledgeResponse,
    status_code=status.HTTP_200_OK,
    summary="Acknowledge finding alert"
)
async def acknowledge_finding(
    finding_id: uuid.UUID,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> AcknowledgeResponse:
    finding = await db.get(Finding, finding_id)
    if not finding or finding.user_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Finding not found")

    finding.status = "acknowledged"
    await db.commit()
    return AcknowledgeResponse(id=finding.id, status=finding.status, acknowledged_at=datetime.now(timezone.utc))
