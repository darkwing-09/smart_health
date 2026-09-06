"""Daily Health and Wellness Insights Endpoints."""

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user, get_db
from app.models.user import User
from app.services.insights import GeminiInsightsService, DailyInsightPayload

router = APIRouter(prefix="/insights", tags=["insights"])


@router.get(
    "/daily",
    response_model=DailyInsightPayload,
    status_code=status.HTTP_200_OK,
    summary="Retrieve personalized daily AI wellness insight"
)
async def get_daily_insight(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> DailyInsightPayload:
    """
    Synthesize the user's continuous biometric telemetry (resting HR, step counts,
    sleep architecture) into a calm, non-diagnostic wellness insight.
    """
    service = GeminiInsightsService(db)
    return await service.generate_daily_insight(user_id=current_user.id)
