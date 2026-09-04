"""
Data Retention & Lifecycle Management Service.

Enforces statutory health data retention rules:
1. Measurements: TimescaleDB chunk retention policy (e.g. compress after 30 days, drop after configured window).
2. Audit Logs: Strictly immutable append-only trail; 7-year regulatory retention (cannot be purged).
3. Clinical Consents: Never deleted; transitions to 'revoked' or 'expired' for forensic traceability.
4. Clinical Summaries: Never deleted once approved; archived when superseding consent is revoked.
"""

import uuid
from datetime import datetime, timezone, timedelta
from typing import Dict, Any, Optional
import structlog
from sqlalchemy import select, update, func, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.measurement import Measurement
from app.models.audit import AuditLog
from app.models.care import ClinicalConsent, ClinicalSummary

logger = structlog.get_logger("healthos.retention")


class RetentionService:
    """Manages retention, compression, and pruning policies across personal health data tiers."""

    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def get_retention_audit_summary(self) -> Dict[str, Any]:
        """Returns row counts and oldest timestamps across all sensitive data tables."""
        # 1. Measurements
        meas_res = await self.db.execute(
            select(
                func.count(Measurement.id),
                func.min(Measurement.recorded_at),
                func.max(Measurement.recorded_at)
            )
        )
        meas_count, meas_min, meas_max = meas_res.one()

        # 2. Audit Logs (immutable)
        audit_res = await self.db.execute(
            select(
                func.count(AuditLog.id),
                func.min(AuditLog.timestamp),
                func.max(AuditLog.timestamp)
            )
        )
        audit_count, audit_min, audit_max = audit_res.one()

        # 3. Consents
        consent_res = await self.db.execute(
            select(
                func.count(ClinicalConsent.id),
                func.count().filter(ClinicalConsent.status == "active"),
                func.count().filter(ClinicalConsent.status == "revoked"),
                func.count().filter(ClinicalConsent.status == "expired")
            )
        )
        consent_total, consent_active, consent_revoked, consent_expired = consent_res.one()

        # 4. Summaries
        summary_res = await self.db.execute(
            select(
                func.count(ClinicalSummary.id),
                func.count().filter(ClinicalSummary.status == "approved"),
                func.count().filter(ClinicalSummary.status == "draft")
            )
        )
        summary_total, summary_approved, summary_draft = summary_res.one()

        return {
            "measurements": {
                "total_count": meas_count or 0,
                "oldest_timestamp": meas_min.isoformat() if meas_min else None,
                "newest_timestamp": meas_max.isoformat() if meas_max else None,
                "retention_policy": "TimescaleDB 7-day chunks; hypertable compression enabled"
            },
            "audit_logs": {
                "total_count": audit_count or 0,
                "oldest_timestamp": audit_min.isoformat() if audit_min else None,
                "immutable": True,
                "retention_policy": "7-year regulatory retention; zero truncation permitted"
            },
            "clinical_consents": {
                "total": consent_total or 0,
                "active": consent_active or 0,
                "revoked": consent_revoked or 0,
                "expired": consent_expired or 0,
                "retention_policy": "Permanent forensic record; never deleted"
            },
            "clinical_summaries": {
                "total": summary_total or 0,
                "approved": summary_approved or 0,
                "draft": summary_draft or 0,
                "retention_policy": "Permanent approved summaries with SHA-256 integrity"
            }
        }

    async def expire_outdated_consents(self) -> int:
        """Finds all active consents whose expires_at has passed and marks them expired."""
        now = datetime.now(timezone.utc)
        stmt = (
            update(ClinicalConsent)
            .where(
                ClinicalConsent.status == "active",
                ClinicalConsent.expires_at <= now
            )
            .values(status="expired")
        )
        result = await self.db.execute(stmt)
        await self.db.commit()
        expired_count = result.rowcount or 0
        if expired_count > 0:
            logger.info("Expired outdated clinical consents", count=expired_count)
        return expired_count
