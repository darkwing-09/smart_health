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


# Phase 5 Clinical Readiness Schemas
class ClinicalConsentCreateRequest(BaseModel):
    purpose: str = Field(default="doctor_consultation", description="Purpose of data disclosure")
    scope_date_start: datetime
    scope_date_end: datetime
    permitted_metrics: Optional[List[str]] = Field(default=["heart_rate", "steps", "sleep_session"])
    permitted_finding_ids: Optional[List[str]] = Field(default=["*"])
    include_context: bool = True
    include_sensor_quality: bool = True
    include_ai_synthesis: bool = True
    recipient_name: Optional[str] = None
    recipient_facility: Optional[str] = None
    duration_days: int = Field(default=7, ge=1, le=90)


class ClinicalConsentResponse(BaseModel):
    consent_id: uuid.UUID
    user_id: uuid.UUID
    consent_version: str
    purpose: str
    permitted_metrics: List[str]
    scope_date_start: datetime
    scope_date_end: datetime
    granted_at: datetime
    expires_at: datetime
    status: str
    recipient_name: Optional[str] = None


class ClinicalConsentRevokeResponse(BaseModel):
    consent_id: uuid.UUID
    status: str
    revoked_at: datetime


class DoctorVisitSummaryDraftRequest(BaseModel):
    consent_id: uuid.UUID
    custom_date_start: Optional[datetime] = None
    custom_date_end: Optional[datetime] = None


class DoctorVisitSummaryRedactRequest(BaseModel):
    redact_finding_ids: Optional[List[str]] = None
    redact_metrics: Optional[List[str]] = None
    redact_trends: Optional[bool] = False
    redact_ai_synthesis: Optional[bool] = False


class DoctorVisitSummaryApproveRequest(BaseModel):
    confirm_approval: bool = Field(..., description="Patient explicit approval confirmation")


class DoctorVisitSummaryResponse(BaseModel):
    summary_id: uuid.UUID
    user_id: uuid.UUID
    consent_id: uuid.UUID
    status: str
    approval_token: Optional[str] = None
    approved_at: Optional[datetime] = None
    checksum_sha256: Optional[str] = None
    recommended_specialties: List[str]
    routing_rationale: str
    summary_payload: Dict[str, Any]
    created_at: datetime


class SpecialtyRoutingResponse(BaseModel):
    primary_specialty: str
    secondary_specialties: List[str]
    rule_id: str
    clinical_rationale: str
    urgency_tier: str
    disclaimer: str

