"""Care Navigation, Clinical Consent, and Doctor Visit Summary Endpoints."""

import os
import uuid
from typing import Optional, List
from fastapi import APIRouter, Depends, HTTPException, status, Request
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user, get_db
from app.models.user import User
from app.models.care import UserApproval, ClinicalConsent, ClinicalSummary
from app.models.finding import Finding
from app.schemas.report import (
    CareResearchRequest,
    CareResearchResponse,
    ClinicalConsentCreateRequest,
    ClinicalConsentResponse,
    ClinicalConsentRevokeResponse,
    DoctorVisitSummaryDraftRequest,
    DoctorVisitSummaryRedactRequest,
    DoctorVisitSummaryApproveRequest,
    DoctorVisitSummaryResponse,
    SpecialtyRoutingResponse
)
from app.services.care_nav import CareNavigationService
from app.services.consent_service import ConsentService
from app.services.doctor_summary import DoctorVisitSummaryService
from app.services.specialty_router import SpecialtyRouter

router = APIRouter(prefix="/care", tags=["care-navigation"])


# --- Verified Facility Research ---

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


# --- Granular Consent Lifecycle ---

@router.post(
    "/consent",
    response_model=ClinicalConsentResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Grant explicit, granular consent for clinical data sharing"
)
async def grant_clinical_consent(
    payload: ClinicalConsentCreateRequest,
    request: Request,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> ClinicalConsentResponse:
    service = ConsentService(db)
    client_ip = request.client.host if request.client else None

    consent = await service.grant_consent(
        user_id=current_user.id,
        purpose=payload.purpose,
        scope_date_start=payload.scope_date_start,
        scope_date_end=payload.scope_date_end,
        permitted_metrics=payload.permitted_metrics,
        permitted_finding_ids=payload.permitted_finding_ids,
        include_context=payload.include_context,
        include_sensor_quality=payload.include_sensor_quality,
        include_ai_synthesis=payload.include_ai_synthesis,
        recipient_name=payload.recipient_name,
        recipient_facility=payload.recipient_facility,
        duration_days=payload.duration_days,
        ip_address=client_ip
    )

    return ClinicalConsentResponse(
        consent_id=consent.id,
        user_id=consent.user_id,
        consent_version=consent.consent_version,
        purpose=consent.purpose,
        permitted_metrics=consent.permitted_metrics,
        scope_date_start=consent.scope_date_start,
        scope_date_end=consent.scope_date_end,
        granted_at=consent.granted_at,
        expires_at=consent.expires_at,
        status=consent.status,
        recipient_name=consent.recipient_name
    )


@router.get(
    "/consent/{consent_id}",
    response_model=ClinicalConsentResponse,
    status_code=status.HTTP_200_OK,
    summary="Inspect active clinical consent status and scope"
)
async def get_clinical_consent(
    consent_id: uuid.UUID,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> ClinicalConsentResponse:
    service = ConsentService(db)
    consent = await service.get_consent(user_id=current_user.id, consent_id=consent_id)

    return ClinicalConsentResponse(
        consent_id=consent.id,
        user_id=consent.user_id,
        consent_version=consent.consent_version,
        purpose=consent.purpose,
        permitted_metrics=consent.permitted_metrics,
        scope_date_start=consent.scope_date_start,
        scope_date_end=consent.scope_date_end,
        granted_at=consent.granted_at,
        expires_at=consent.expires_at,
        status=consent.status,
        recipient_name=consent.recipient_name
    )


@router.delete(
    "/consent/{consent_id}",
    response_model=ClinicalConsentRevokeResponse,
    status_code=status.HTTP_200_OK,
    summary="Immediately revoke an active clinical consent"
)
async def revoke_clinical_consent(
    consent_id: uuid.UUID,
    request: Request,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> ClinicalConsentRevokeResponse:
    service = ConsentService(db)
    client_ip = request.client.host if request.client else None

    consent = await service.revoke_consent(
        user_id=current_user.id,
        consent_id=consent_id,
        ip_address=client_ip
    )

    return ClinicalConsentRevokeResponse(
        consent_id=consent.id,
        status=consent.status,
        revoked_at=consent.revoked_at
    )


# --- Doctor Visit Summary & Redaction Lifecycle ---

@router.post(
    "/summary/draft",
    response_model=DoctorVisitSummaryResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Generate initial Doctor Visit Summary draft from longitudinal telemetry"
)
async def draft_doctor_visit_summary(
    payload: DoctorVisitSummaryDraftRequest,
    request: Request,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> DoctorVisitSummaryResponse:
    service = DoctorVisitSummaryService(db)
    client_ip = request.client.host if request.client else None

    summary = await service.generate_draft(
        user_id=current_user.id,
        consent_id=payload.consent_id,
        custom_date_start=payload.custom_date_start,
        custom_date_end=payload.custom_date_end,
        ip_address=client_ip
    )

    return DoctorVisitSummaryResponse(
        summary_id=summary.id,
        user_id=summary.user_id,
        consent_id=summary.consent_id,
        status=summary.status,
        approval_token=summary.approval_token,
        approved_at=summary.approved_at,
        checksum_sha256=summary.checksum_sha256,
        recommended_specialties=summary.recommended_specialties,
        routing_rationale=summary.routing_rationale,
        summary_payload=summary.summary_payload,
        created_at=summary.created_at
    )


@router.get(
    "/summary/{summary_id}",
    response_model=DoctorVisitSummaryResponse,
    status_code=status.HTTP_200_OK,
    summary="Retrieve and preview a Doctor Visit Summary"
)
async def get_doctor_visit_summary(
    summary_id: uuid.UUID,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> DoctorVisitSummaryResponse:
    stmt = select(ClinicalSummary).where(
        ClinicalSummary.id == summary_id,
        ClinicalSummary.user_id == current_user.id
    )
    summary = (await db.execute(stmt)).scalar_one_or_none()
    if not summary:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Clinical summary not found"
        )

    return DoctorVisitSummaryResponse(
        summary_id=summary.id,
        user_id=summary.user_id,
        consent_id=summary.consent_id,
        status=summary.status,
        approval_token=summary.approval_token,
        approved_at=summary.approved_at,
        checksum_sha256=summary.checksum_sha256,
        recommended_specialties=summary.recommended_specialties,
        routing_rationale=summary.routing_rationale,
        summary_payload=summary.summary_payload,
        created_at=summary.created_at
    )


@router.post(
    "/summary/{summary_id}/redact",
    response_model=DoctorVisitSummaryResponse,
    status_code=status.HTTP_200_OK,
    summary="Apply granular patient redactions to summary findings or metrics"
)
async def redact_doctor_visit_summary(
    summary_id: uuid.UUID,
    payload: DoctorVisitSummaryRedactRequest,
    request: Request,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> DoctorVisitSummaryResponse:
    service = DoctorVisitSummaryService(db)
    client_ip = request.client.host if request.client else None

    summary = await service.redact_summary(
        user_id=current_user.id,
        summary_id=summary_id,
        redaction_mask=payload.model_dump(),
        ip_address=client_ip
    )

    return DoctorVisitSummaryResponse(
        summary_id=summary.id,
        user_id=summary.user_id,
        consent_id=summary.consent_id,
        status=summary.status,
        approval_token=summary.approval_token,
        approved_at=summary.approved_at,
        checksum_sha256=summary.checksum_sha256,
        recommended_specialties=summary.recommended_specialties,
        routing_rationale=summary.routing_rationale,
        summary_payload=summary.summary_payload,
        created_at=summary.created_at
    )


@router.post(
    "/summary/{summary_id}/approve",
    response_model=DoctorVisitSummaryResponse,
    status_code=status.HTTP_200_OK,
    summary="Grant explicit patient approval to unlock vector PDF export"
)
async def approve_doctor_visit_summary(
    summary_id: uuid.UUID,
    payload: DoctorVisitSummaryApproveRequest,
    request: Request,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> DoctorVisitSummaryResponse:
    if not payload.confirm_approval:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Patient confirmation required to approve clinical summary"
        )

    service = DoctorVisitSummaryService(db)
    client_ip = request.client.host if request.client else None

    summary = await service.approve_summary(
        user_id=current_user.id,
        summary_id=summary_id,
        ip_address=client_ip
    )

    return DoctorVisitSummaryResponse(
        summary_id=summary.id,
        user_id=summary.user_id,
        consent_id=summary.consent_id,
        status=summary.status,
        approval_token=summary.approval_token,
        approved_at=summary.approved_at,
        checksum_sha256=summary.checksum_sha256,
        recommended_specialties=summary.recommended_specialties,
        routing_rationale=summary.routing_rationale,
        summary_payload=summary.summary_payload,
        created_at=summary.created_at
    )


@router.get(
    "/summary/{summary_id}/export/pdf",
    status_code=status.HTTP_200_OK,
    summary="Download approved Doctor Visit Summary as vector PDF"
)
async def export_doctor_visit_summary_pdf(
    summary_id: uuid.UUID,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
):
    service = DoctorVisitSummaryService(db)
    pdf_path = await service.export_pdf(
        user_id=current_user.id,
        summary_id=summary_id
    )

    if not os.path.exists(pdf_path):
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="PDF export failed to generate file on storage"
        )

    filename = os.path.basename(pdf_path)
    return FileResponse(
        path=pdf_path,
        media_type="application/pdf",
        filename=filename
    )


# --- Deterministic Specialty Routing ---

@router.get(
    "/routing",
    response_model=SpecialtyRoutingResponse,
    status_code=status.HTTP_200_OK,
    summary="Evaluate deterministic specialty routing recommendation for active user findings"
)
async def evaluate_specialty_routing(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> SpecialtyRoutingResponse:
    stmt = select(Finding).where(
        Finding.user_id == current_user.id
    ).order_by(Finding.reading_timestamp.desc()).limit(10)
    findings = (await db.scalars(stmt)).all()

    decision = SpecialtyRouter.evaluate_routing(findings=findings)

    return SpecialtyRoutingResponse(
        primary_specialty=decision.primary_specialty,
        secondary_specialties=decision.secondary_specialties,
        rule_id=decision.rule_id,
        clinical_rationale=decision.clinical_rationale,
        urgency_tier=decision.urgency_tier,
        disclaimer=decision.disclaimer
    )
