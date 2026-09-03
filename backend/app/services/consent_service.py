"""Granular Patient Consent & Disclosure Management Service.

Enforces India DPDP Act 2023 principles: purpose limitation, data minimization,
revocability, and immutable audit trails.
"""

import uuid
from datetime import datetime, timezone, timedelta
from typing import Optional, List, Dict, Any
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from fastapi import HTTPException, status
from app.models.care import ClinicalConsent
from app.models.audit import AuditLog


class ConsentService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def grant_consent(
        self,
        user_id: uuid.UUID,
        purpose: str,
        scope_date_start: datetime,
        scope_date_end: datetime,
        permitted_metrics: Optional[List[str]] = None,
        permitted_finding_ids: Optional[List[str]] = None,
        include_context: bool = True,
        include_sensor_quality: bool = True,
        include_ai_synthesis: bool = True,
        recipient_name: Optional[str] = None,
        recipient_facility: Optional[str] = None,
        duration_days: int = 7,
        ip_address: Optional[str] = None,
        consent_version: str = "1.0.0"
    ) -> ClinicalConsent:
        """
        Grants explicit, granular consent for clinical data sharing.
        """
        now = datetime.now(timezone.utc)
        expires_at = now + timedelta(days=duration_days)

        metrics = permitted_metrics or ["heart_rate", "steps", "sleep_session"]
        findings = permitted_finding_ids or ["*"]

        consent = ClinicalConsent(
            id=uuid.uuid4(),
            user_id=user_id,
            consent_version=consent_version,
            purpose=purpose,
            permitted_metrics=metrics,
            permitted_finding_ids=findings,
            scope_date_start=scope_date_start,
            scope_date_end=scope_date_end,
            include_context=include_context,
            include_sensor_quality=include_sensor_quality,
            include_ai_synthesis=include_ai_synthesis,
            recipient_name=recipient_name,
            recipient_facility=recipient_facility,
            granted_at=now,
            expires_at=expires_at,
            status="active",
            ip_address=ip_address,
            created_at=now
        )
        self.db.add(consent)

        # Write immutable audit log
        audit_entry = AuditLog(
            id=uuid.uuid4(),
            user_id=user_id,
            actor="patient:user",
            action="consent_granted",
            target_ref=f"consent:{consent.id}",
            detail={
                "purpose": purpose,
                "expires_at": expires_at.isoformat(),
                "permitted_metrics": metrics,
                "scope_start": scope_date_start.isoformat(),
                "scope_end": scope_date_end.isoformat(),
                "recipient": recipient_name
            },
            timestamp=now,
            ip_address=ip_address
        )
        self.db.add(audit_entry)
        await self.db.commit()
        await self.db.refresh(consent)
        return consent

    async def revoke_consent(
        self,
        user_id: uuid.UUID,
        consent_id: uuid.UUID,
        reason: Optional[str] = None,
        ip_address: Optional[str] = None
    ) -> ClinicalConsent:
        """
        Immediately revokes an active consent and invalidates downstream sharing.
        """
        now = datetime.now(timezone.utc)
        stmt = select(ClinicalConsent).where(
            ClinicalConsent.id == consent_id,
            ClinicalConsent.user_id == user_id
        )
        consent = (await self.db.execute(stmt)).scalar_one_or_none()
        if not consent:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Consent record not found"
            )

        consent.status = "revoked"
        consent.revoked_at = now

        audit_entry = AuditLog(
            id=uuid.uuid4(),
            user_id=user_id,
            actor="patient:user",
            action="consent_revoked",
            target_ref=f"consent:{consent.id}",
            detail={"reason": reason or "User requested revocation"},
            timestamp=now,
            ip_address=ip_address
        )
        self.db.add(audit_entry)
        await self.db.commit()
        await self.db.refresh(consent)
        return consent

    async def get_consent(
        self,
        user_id: uuid.UUID,
        consent_id: uuid.UUID
    ) -> ClinicalConsent:
        """
        Retrieves consent, auto-expiring if TTL elapsed.
        """
        stmt = select(ClinicalConsent).where(
            ClinicalConsent.id == consent_id,
            ClinicalConsent.user_id == user_id
        )
        consent = (await self.db.execute(stmt)).scalar_one_or_none()
        if not consent:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Consent record not found"
            )

        now = datetime.now(timezone.utc)
        if consent.status == "active" and now > consent.expires_at:
            consent.status = "expired"
            await self.db.commit()
            await self.db.refresh(consent)

        return consent

    async def validate_consent_active(
        self,
        user_id: uuid.UUID,
        consent_id: uuid.UUID
    ) -> ClinicalConsent:
        """
        Validates that a consent is currently active and not expired or revoked.
        """
        consent = await self.get_consent(user_id=user_id, consent_id=consent_id)
        if consent.status != "active":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Consent is no longer active (current status: {consent.status})"
            )
        return consent
