"""User Preferences and Device Registration REST API Endpoints."""

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user, get_db
from app.models.user import User
from app.schemas.notification import (
    FcmTokenRegisterRequest,
    FcmTokenRegisterResponse,
    UserPreferencesResponse,
    UserPreferencesUpdateRequest,
)
from app.services.user_preference import UserPreferenceService

router = APIRouter()


@router.get("/users/preferences", response_model=UserPreferencesResponse)
async def get_user_preferences(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> UserPreferencesResponse:
    """Returns notification preferences and localized quiet hours configuration."""
    service = UserPreferenceService(db=db)
    result = await service.get_preferences(current_user.id)
    return UserPreferencesResponse(
        user_id=current_user.id,
        timezone=result["timezone"],
        preferences=result["preferences"]
    )


@router.put("/users/preferences", response_model=UserPreferencesResponse)
async def update_user_preferences(
    req: UserPreferencesUpdateRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> UserPreferencesResponse:
    """
    Updates user notification preferences and timezone.
    Emergency Level 4 override remains enabled permanently.
    """
    service = UserPreferenceService(db=db)
    updates = req.model_dump(exclude_unset=True)
    timezone_str = updates.pop("timezone", None)

    result = await service.update_preferences(
        user_id=current_user.id,
        timezone_str=timezone_str,
        notification_prefs_update=updates
    )
    return UserPreferencesResponse(
        user_id=current_user.id,
        timezone=result["timezone"],
        preferences=result["preferences"]
    )


@router.post("/devices/fcm-token", response_model=FcmTokenRegisterResponse)
async def register_device_fcm_token(
    req: FcmTokenRegisterRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> FcmTokenRegisterResponse:
    """Registers or refreshes Firebase Cloud Messaging push token for user's device."""
    service = UserPreferenceService(db=db)
    try:
        await service.register_fcm_token(
            user_id=current_user.id,
            fcm_token=req.fcm_token,
            device_id=req.device_id
        )
        return FcmTokenRegisterResponse(
            success=True,
            message="FCM token successfully registered."
        )
    except PermissionError as e:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=str(e))
