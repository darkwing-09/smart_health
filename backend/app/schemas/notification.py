"""Pydantic Schemas for Notifications and User Preferences."""

import uuid
from datetime import datetime
from typing import Any, Optional
from pydantic import BaseModel, ConfigDict, Field


class NotificationResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    user_id: uuid.UUID
    finding_id: Optional[uuid.UUID] = None
    channel: str
    severity: str
    title: str
    body: str
    state: str
    delivery_status: str
    quiet_hours_held: bool = False
    sent_at: datetime
    delivered_at: Optional[datetime] = None
    acknowledged_at: Optional[datetime] = None
    dismissed_at: Optional[datetime] = None
    created_at: datetime
    payload: Optional[dict[str, Any]] = None


class NotificationListResponse(BaseModel):
    items: list[NotificationResponse]
    total: int
    unread_count: int


class NotificationAcknowledgeResponse(BaseModel):
    id: uuid.UUID
    state: str
    acknowledged_at: datetime


class NotificationDismissResponse(BaseModel):
    id: uuid.UUID
    state: str
    dismissed_at: datetime


class UserPreferencesResponse(BaseModel):
    user_id: uuid.UUID
    timezone: str
    preferences: dict[str, Any]


class UserPreferencesUpdateRequest(BaseModel):
    timezone: Optional[str] = None
    fcm_enabled: Optional[bool] = None
    in_app_enabled: Optional[bool] = None
    min_severity: Optional[str] = None
    quiet_hours_start: Optional[str] = Field(None, pattern=r"^\d{2}:\d{2}$")
    quiet_hours_end: Optional[str] = Field(None, pattern=r"^\d{2}:\d{2}$")


class FcmTokenRegisterRequest(BaseModel):
    fcm_token: str = Field(..., min_length=10, max_length=512)
    device_id: Optional[uuid.UUID] = None


class FcmTokenRegisterResponse(BaseModel):
    success: bool
    message: str
