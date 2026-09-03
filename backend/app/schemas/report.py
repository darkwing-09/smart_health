"""Report and Care Navigation Pydantic Schemas."""

import uuid
from datetime import date, datetime
from typing import List, Optional, Dict, Any
from pydantic import BaseModel, Field


# Report Schemas
class ReportItemSchema(BaseModel):
    report_id: uuid.UUID
    date: date
    generation_status: str
    closing_quote: str
    pdf_download_url: str


class ReportListResponse(BaseModel):
    reports: List[ReportItemSchema]


# Care Navigation Schemas
class CareResearchRequest(BaseModel):
    latitude: float = Field(..., ge=-90.0, le=90.0)
    longitude: float = Field(..., ge=-180.0, le=180.0)
    radius_km: int = Field(default=10, ge=1, le=50)
    specialty_hint: Optional[str] = None
    finding_id: Optional[uuid.UUID] = None
    user_authorization: bool = Field(..., description="Explicit user authorization required")


class ProviderItemSchema(BaseModel):
    hospital_id: uuid.UUID
    name: str
    address: str
    distance_km: float
    phone: Optional[str] = None
    verified_source: str


class CareResearchResponse(BaseModel):
    request_id: uuid.UUID
    recommended_specialty: str
    providers: List[ProviderItemSchema]


class VisitSummaryResponse(BaseModel):
    summary_id: uuid.UUID
    patient_shareable_text: str
    pdf_export_url: str
