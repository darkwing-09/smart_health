"""Timeline and Authentication Pydantic Schemas."""

import uuid
from datetime import datetime
from typing import List, Optional
from pydantic import BaseModel, Field


# Auth Schemas
class LoginRequest(BaseModel):
    email: str = Field(..., description="User account email")
    password: str = Field(..., description="Account password")


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int
    user_id: uuid.UUID


# Timeline Schemas
class MeasurementResponse(BaseModel):
    id: uuid.UUID
    metric_type: str
    value: float
    unit: str
    recorded_at: datetime
    confidence: float
    data_quality_flag: str


class TimelineQueryResponse(BaseModel):
    metric_type: str
    count: int
    measurements: List[MeasurementResponse]
