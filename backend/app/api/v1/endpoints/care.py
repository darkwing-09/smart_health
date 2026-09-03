"""Care Navigation & Provider Research Endpoints."""

import uuid
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.deps import get_current_user, get_db
from app.models.user import User
from app.models.care import UserApproval
from app.schemas.report import CareResearchRequest, CareResearchResponse, ProviderItemSchema, VisitSummaryResponse
from app.services.care_nav import CareNavigationService

router = APIRouter(prefix="/care", tags=["care-navigation"])


@router.post(
    "/research",
    response_model=CareResearchResponse,
    status_code=status.HTTP_200_OK,
    summary="Research verified medical facilities (Requires explicit user authorization)"
)
async def research_providers(
    payload: CareResearchRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> CareResearchResponse:
    if not payload.user_authorization:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Explicit user authorization required to perform healthcare research"
        )

    # Record UserApproval
    approval = UserApproval(
        user_id=current_user.id,
        action_type="research_providers",
        finding_id=payload.finding_id
    )
    db.add(approval)
    await db.commit()

    service = CareNavigationService(db)
    return await service.search_verified_facilities(
        user_id=current_user.id,
        latitude=payload.latitude,
        longitude=payload.longitude,
        radius_km=payload.radius_km,
        specialty_hint=payload.specialty_hint
    )
