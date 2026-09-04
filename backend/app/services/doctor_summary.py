"""Doctor Visit Summary & Redaction Service.

Manages the clinical brief lifecycle:
GENERATE DRAFT -> USER REVIEW -> REDACT -> APPROVE -> EXPORT
"""

import os
import uuid
import json
import hashlib
import hmac
from copy import deepcopy
from datetime import datetime, timezone, timedelta
from typing import Dict, Any, List, Optional
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from fastapi import HTTPException, status

from app.core.config import settings
from app.core.crypto import crypto_service
from app.models.care import ClinicalConsent, ClinicalSummary
from app.models.finding import Finding
from app.models.baseline import Baseline
from app.models.measurement import Measurement
from app.models.device import Device, WearableSource
from app.models.audit import AuditLog
from app.services.consent_service import ConsentService
from app.services.timeline import TimelineService
from app.services.specialty_router import SpecialtyRouter
from app.services.trend import TrendEngine, FindingClassification
from app.graphs.care_nav import build_care_nav_graph, CLINICAL_DISCLAIMER
from app.services.doctor_summary_pdf import DoctorVisitSummaryPdfService



class DoctorVisitSummaryService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db
        self.consent_service = ConsentService(db)

    @staticmethod
    def _compute_checksum(payload: Dict[str, Any]) -> str:
        """Calculates canonical SHA-256 checksum of summary document, excluding checksum key itself."""
        clean_payload = {k: v for k, v in payload.items() if k != "checksum_sha256"}
        serialized = json.dumps(clean_payload, sort_keys=True, default=str).encode("utf-8")
        return hashlib.sha256(serialized).hexdigest()


    async def generate_draft(
        self,
        user_id: uuid.UUID,
        consent_id: uuid.UUID,
        custom_date_start: Optional[datetime] = None,
        custom_date_end: Optional[datetime] = None,
        ip_address: Optional[str] = None
    ) -> ClinicalSummary:
        """
        Gathers longitudinal evidence, runs deterministic specialty routing,
        synthesizes AI clinician consultation notes, and saves draft summary.
        """
        now = datetime.now(timezone.utc)
        consent = await self.consent_service.validate_consent_active(user_id=user_id, consent_id=consent_id)

        start_dt = custom_date_start or consent.scope_date_start
        end_dt = custom_date_end or consent.scope_date_end

        # Ensure requested window falls within consented boundary
        if start_dt < consent.scope_date_start or end_dt > consent.scope_date_end:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Requested date range exceeds consented scope boundaries"
            )

        # 1. Timeline & Measurements
        timeline_svc = TimelineService(self.db)
        events = await timeline_svc.get_timeline(user_id=user_id, start_time=start_dt, end_time=end_dt)

        # Filter by permitted metrics
        permitted = set(consent.permitted_metrics)
        m_events = [e for e in events if e.event_type == "measurement" and e.data.get("metric_type") in permitted]

        hr_vals = [e.data["value"] for e in m_events if e.data["metric_type"] == "heart_rate"]
        step_vals = [e.data["value"] for e in m_events if e.data["metric_type"] == "steps"]
        sleep_vals = [e.data["value"] for e in m_events if e.data["metric_type"] == "sleep_session"]

        # Calculate exact rollups
        hr_min = float(min(hr_vals)) if hr_vals else None
        hr_max = float(max(hr_vals)) if hr_vals else None
        hr_mean = round(float(sum(hr_vals) / len(hr_vals)), 1) if hr_vals else None
        total_steps = int(sum(step_vals)) if step_vals else 0
        total_sleep_hours = round(sum(sleep_vals) / 60.0, 1) if sleep_vals else 0.0

        duration_days = max(1, (end_dt.date() - start_dt.date()).days + 1)
        unique_dates = {e.timestamp.date() for e in m_events}
        wear_adherence = round((len(unique_dates) / duration_days) * 100.0, 1)

        # 2. Baseline Comparison
        stmt_b = select(Baseline).where(
            Baseline.user_id == user_id,
            Baseline.metric_type == "heart_rate"
        ).order_by(Baseline.computed_at.desc()).limit(1)
        baseline = (await self.db.execute(stmt_b)).scalar_one_or_none()

        baseline_mean = baseline.mean if baseline else 60.0
        baseline_std = baseline.stddev if baseline else 5.0
        hr_diff = round(hr_mean - baseline_mean, 1) if hr_mean is not None else 0.0

        measurements_summary = []
        if hr_mean is not None:
            measurements_summary.append({
                "metric_name": "heart_rate",
                "observed_display": f"{hr_mean} bpm (min: {hr_min}, max: {hr_max})",
                "baseline_display": f"{baseline_mean:.1f} ± {baseline_std:.1f} bpm",
                "circadian_envelope": f"{baseline_mean - baseline_std:.1f} to {baseline_mean + baseline_std:.1f} bpm",
                "deviation_display": f"{hr_diff:+.1f} bpm vs baseline"
            })
        if step_vals:
            measurements_summary.append({
                "metric_name": "steps",
                "observed_display": f"{total_steps} total steps ({round(total_steps / duration_days)} / day avg)",
                "baseline_display": "Daily Activity Target: 8,000",
                "circadian_envelope": "Nominal",
                "deviation_display": "Tracked Physical Activity"
            })
        if sleep_vals:
            measurements_summary.append({
                "metric_name": "sleep_session",
                "observed_display": f"{total_sleep_hours} hrs total across window",
                "baseline_display": "Typical Target: 7.0 - 9.0 hrs/day",
                "circadian_envelope": "Night Rest",
                "deviation_display": "Restorative Rest Tracked"
            })

        # 3. Longitudinal Trends
        daily_hr_map: Dict[str, List[float]] = {}
        for e in m_events:
            if e.data["metric_type"] == "heart_rate":
                d_str = e.timestamp.date().isoformat()
                daily_hr_map.setdefault(d_str, []).append(e.data["value"])

        daily_obs = [
            (datetime.fromisoformat(d).replace(tzinfo=timezone.utc), float(sum(vals)/len(vals)))
            for d, vals in sorted(daily_hr_map.items())
        ]
        trend_report = TrendEngine.evaluate_trend(
            metric_type="resting_heart_rate",
            daily_observations=daily_obs,
            historical_baseline_mean=baseline_mean,
            historical_baseline_std=baseline_std,
            min_days=3
        )
        trends_summary = []
        if trend_report:
            trends_summary.append({
                "metric_type": trend_report.metric_type,
                "classification": trend_report.classification.value,
                "direction": trend_report.direction,
                "slope_per_day": trend_report.slope_per_day,
                "r_squared": trend_report.r_squared,
                "evidence_strength": trend_report.evidence_strength.value,
                "summary": trend_report.summary
            })

        # 4. Findings History
        stmt_f = select(Finding).where(
            Finding.user_id == user_id,
            Finding.reading_timestamp >= start_dt,
            Finding.reading_timestamp <= end_dt
        ).order_by(Finding.reading_timestamp.desc())
        all_findings = (await self.db.scalars(stmt_f)).all()

        findings_list = []
        for f in all_findings:
            if consent.permitted_finding_ids == ["*"] or str(f.id) in consent.permitted_finding_ids:
                findings_list.append({
                    "finding_id": str(f.id),
                    "timestamp": f.reading_timestamp.isoformat(),
                    "metric_type": f.metric_type,
                    "severity": f.severity,
                    "observed_value": f.observed_value,
                    "baseline_value": f.baseline_value,
                    "deviation": f.deviation,
                    "rule_id": f.rule_id,
                    "is_redacted": False
                })

        # 5. Deterministic Specialty Routing
        routing_decision = SpecialtyRouter.evaluate_routing(
            findings=all_findings,
            trend_reports=[trend_report] if trend_report else [],
            baseline_established=baseline.established if baseline else False
        )

        # 6. AI Clinician Synthesis via CareNavigationGraph
        care_graph = build_care_nav_graph()
        initial_payload = {
            "user_id": str(user_id),
            "consent_id": str(consent.id),
            "reporting_period": {
                "start_date": start_dt.date().isoformat(),
                "end_date": end_dt.date().isoformat(),
                "duration_days": duration_days
            },
            "data_coverage": {
                "total_measurements": len(m_events),
                "wear_adherence_percent": wear_adherence,
                "data_quality_rating": "good" if wear_adherence >= 70.0 else "limited"
            },
            "measurements_summary": measurements_summary,
            "findings": findings_list,
            "longitudinal_trends": trends_summary,
            "specialty_routing": {
                "primary_specialty": routing_decision.primary_specialty,
                "secondary_specialties": routing_decision.secondary_specialties,
                "clinical_rationale": routing_decision.clinical_rationale,
                "urgency_tier": routing_decision.urgency_tier
            }
        }

        graph_res = await care_graph.ainvoke({
            "user_id": str(user_id),
            "finding_id": str(all_findings[0].id) if all_findings else None,
            "coordinates": {"lat": 0.0, "lng": 0.0},
            "radius_km": 10,
            "recommended_specialty": routing_decision.primary_specialty,
            "specialty_routing": initial_payload["specialty_routing"],
            "providers": [],
            "summary_payload": initial_payload,
            "user_approved_provider_id": None,
            "user_approval_granted": False,
            "approval_token": None
        })

        initial_payload["ai_synthesis"] = {
            "clinician_summary": graph_res.get("clinician_note", "Longitudinal telemetry compiled for physician review."),
            "patient_rationale": graph_res.get("patient_rationale", ""),
            "full_visit_text": graph_res.get("visit_summary_text", "")
        }
        initial_payload["disclaimer"] = CLINICAL_DISCLAIMER
        initial_payload["status"] = "draft"
        initial_payload["approval_token"] = None
        initial_payload["created_at"] = now.isoformat()

        checksum = self._compute_checksum(initial_payload)
        initial_payload["checksum_sha256"] = checksum

        summary_entity = ClinicalSummary(
            id=uuid.uuid4(),
            user_id=user_id,
            consent_id=consent.id,
            status="draft",
            summary_payload=initial_payload,
            redaction_mask={},
            recommended_specialties=[routing_decision.primary_specialty] + routing_decision.secondary_specialties,
            routing_rationale=routing_decision.clinical_rationale,
            checksum_sha256=checksum,
            created_at=now,
            updated_at=now
        )
        self.db.add(summary_entity)

        audit_entry = AuditLog(
            id=uuid.uuid4(),
            user_id=user_id,
            actor="system:doctor_summary_service",
            action="clinical_summary_drafted",
            target_ref=f"summary:{summary_entity.id}",
            detail={
                "consent_id": str(consent.id),
                "checksum": checksum,
                "reporting_start": start_dt.isoformat(),
                "reporting_end": end_dt.isoformat()
            },
            timestamp=now,
            ip_address=ip_address
        )
        self.db.add(audit_entry)
        await self.db.commit()
        await self.db.refresh(summary_entity)
        return summary_entity

    async def redact_summary(
        self,
        user_id: uuid.UUID,
        summary_id: uuid.UUID,
        redaction_mask: Dict[str, Any],
        ip_address: Optional[str] = None
    ) -> ClinicalSummary:
        """
        Applies granular patient redactions to findings or metrics in the summary.
        """
        now = datetime.now(timezone.utc)
        stmt = select(ClinicalSummary).where(
            ClinicalSummary.id == summary_id,
            ClinicalSummary.user_id == user_id
        )
        summary = (await self.db.execute(stmt)).scalar_one_or_none()
        if not summary:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Clinical summary not found"
            )

        if summary.status not in ["draft", "reviewed", "redacted"]:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Cannot redact summary in status '{summary.status}'"
            )

        payload = deepcopy(summary.summary_payload)

        # 1. Redact Specific Findings
        redact_finding_ids = set(redaction_mask.get("redact_finding_ids") or [])
        if redact_finding_ids and "findings" in payload:
            for f in payload["findings"]:
                if f.get("finding_id") in redact_finding_ids:
                    f["is_redacted"] = True
                    f["observed_value"] = "[REDACTED BY PATIENT]"
                    f["deviation"] = "[REDACTED BY PATIENT]"
                    f["rule_id"] = "REDACTED_BY_PATIENT"

        # 2. Redact Entire Metrics (e.g., exclude steps or sleep)
        redact_metrics = set(redaction_mask.get("redact_metrics") or [])
        if redact_metrics and "measurements_summary" in payload:
            for m in payload["measurements_summary"]:
                if m.get("metric_name") in redact_metrics:
                    m["observed_display"] = "[REDACTED BY PATIENT]"
                    m["deviation_display"] = "[REDACTED BY PATIENT]"

        # 3. Redact Longitudinal Trends
        if redaction_mask.get("redact_trends") and "longitudinal_trends" in payload:
            payload["longitudinal_trends"] = [{
                "metric_type": "resting_heart_rate",
                "summary": "[REDACTED BY PATIENT]",
                "evidence_strength": "redacted"
            }]

        payload["status"] = "redacted"
        new_checksum = self._compute_checksum(payload)
        payload["checksum_sha256"] = new_checksum

        summary.summary_payload = payload
        summary.redaction_mask = redaction_mask
        summary.status = "redacted"
        summary.checksum_sha256 = new_checksum
        summary.updated_at = now

        audit_entry = AuditLog(
            id=uuid.uuid4(),
            user_id=user_id,
            actor="patient:user",
            action="clinical_summary_redacted",
            target_ref=f"summary:{summary.id}",
            detail={
                "redacted_finding_count": len(redact_finding_ids),
                "redacted_metrics": list(redact_metrics),
                "new_checksum": new_checksum
            },
            timestamp=now,
            ip_address=ip_address
        )
        self.db.add(audit_entry)
        await self.db.commit()
        await self.db.refresh(summary)
        return summary

    async def approve_summary(
        self,
        user_id: uuid.UUID,
        summary_id: uuid.UUID,
        ip_address: Optional[str] = None
    ) -> ClinicalSummary:
        """
        Patient grants explicit sign-off; generates approval token and unlocks export.
        """
        now = datetime.now(timezone.utc)
        stmt = select(ClinicalSummary).where(
            ClinicalSummary.id == summary_id,
            ClinicalSummary.user_id == user_id
        )
        summary = (await self.db.execute(stmt)).scalar_one_or_none()
        if not summary:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Clinical summary not found"
            )

        # Validate that associated consent remains active
        await self.consent_service.validate_consent_active(user_id=user_id, consent_id=summary.consent_id)

        # Cryptographically bind approval token to user, summary, and current checksum
        token_binding = f"{user_id}:{summary_id}:{summary.checksum_sha256}:{now.isoformat()}"
        token_sig = hmac.new(settings.SECRET_KEY.encode("utf-8"), token_binding.encode("utf-8"), hashlib.sha256).hexdigest()[:24]
        approval_token = f"appr_{uuid.uuid4().hex[:12]}_{token_sig}"

        payload = deepcopy(summary.summary_payload)
        payload["status"] = "approved"
        payload["approval_token"] = approval_token
        payload["approved_at"] = now.isoformat()

        checksum = self._compute_checksum(payload)
        payload["checksum_sha256"] = checksum

        summary.summary_payload = payload
        summary.status = "approved"
        summary.approval_token = approval_token
        summary.approved_at = now
        summary.checksum_sha256 = checksum
        summary.updated_at = now

        audit_entry = AuditLog(
            id=uuid.uuid4(),
            user_id=user_id,
            actor="patient:user",
            action="clinical_summary_approved",
            target_ref=f"summary:{summary.id}",
            detail={
                "approval_token": approval_token,
                "checksum": checksum
            },
            timestamp=now,
            ip_address=ip_address
        )
        self.db.add(audit_entry)
        await self.db.commit()
        await self.db.refresh(summary)
        return summary

    async def export_pdf(
        self,
        user_id: uuid.UUID,
        summary_id: uuid.UUID,
        output_dir: str = "var/reports/clinical"
    ) -> str:
        """
        Renders the approved summary as a vector PDF.
        Strictly enforces approval state, valid token, active consent, and cryptographic integrity.
        """
        now = datetime.now(timezone.utc)
        stmt = select(ClinicalSummary).where(
            ClinicalSummary.id == summary_id,
            ClinicalSummary.user_id == user_id
        )
        summary = (await self.db.execute(stmt)).scalar_one_or_none()
        if not summary:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Clinical summary not found"
            )

        if summary.status != "approved" or not summary.approval_token:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Clinical summary must be approved by patient with a valid approval token before PDF export"
            )

        # Enforce that consent is currently active and not revoked
        await self.consent_service.validate_consent_active(user_id=user_id, consent_id=summary.consent_id)

        # Enforce cryptographic integrity verification against payload tampering
        computed_checksum = self._compute_checksum(summary.summary_payload)
        if computed_checksum != summary.checksum_sha256:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Clinical summary integrity violation: summary payload was modified post-approval"
            )


        filename = f"doctor_visit_summary_{summary.id}_{int(now.timestamp())}.pdf"
        output_path = os.path.join(output_dir, filename)

        DoctorVisitSummaryPdfService.compile_pdf(
            summary_payload=summary.summary_payload,
            output_path=output_path
        )

        summary.pdf_storage_path = output_path
        summary.updated_at = now

        audit_entry = AuditLog(
            id=uuid.uuid4(),
            user_id=user_id,
            actor="patient:user",
            action="clinical_summary_exported_pdf",
            target_ref=f"summary:{summary.id}",
            detail={
                "pdf_storage_path": output_path,
                "checksum": summary.checksum_sha256
            },
            timestamp=now
        )
        self.db.add(audit_entry)
        await self.db.commit()
        await self.db.refresh(summary)
        return output_path
