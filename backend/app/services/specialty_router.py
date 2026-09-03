"""Deterministic Specialty Routing Engine for Clinical Care Navigation.

Rule-based clinical specialty recommendation without diagnostic assertions or LLM inference.
"""

from typing import List, Dict, Any, Optional
from dataclasses import dataclass, field
from app.models.finding import Finding
from app.services.trend import LongitudinalTrendReport, FindingClassification


@dataclass
class SpecialtyRoutingDecision:
    primary_specialty: str
    secondary_specialties: List[str]
    rule_id: str
    clinical_rationale: str
    urgency_tier: str  # 'routine', 'prompt', 'urgent_medical_evaluation'
    disclaimer: str
    evidence_summary: Dict[str, Any] = field(default_factory=dict)


class SpecialtyRouter:
    """
    Deterministic clinical specialty router based strictly on mathematical telemetry deviations.
    Zero LLM involvement. Zero medical diagnosis.
    """

    NON_DIAGNOSTIC_DISCLAIMER = (
        "CLINICAL ADVISORY: Recommended specialties are deterministic routing suggestions "
        "derived from objective wearable sensor deviations to assist patient-physician discussions. "
        "They do not constitute a medical diagnosis, triage judgment, or clinical prescription."
    )

    @classmethod
    def evaluate_routing(
        cls,
        findings: List[Finding],
        trend_reports: Optional[List[LongitudinalTrendReport]] = None,
        baseline_established: bool = True
    ) -> SpecialtyRoutingDecision:
        """
        Evaluates findings and trends to recommend relevant clinical specialties.
        """
        trend_reports = trend_reports or []

        # 1. Check for Hard Biological Gate or Urgent Findings (Urgent Tier)
        for finding in findings:
            if finding.severity == "urgent" or finding.rule_id == "RULE_BIO_CEILING_TACHYCARDIA":
                return SpecialtyRoutingDecision(
                    primary_specialty="Cardiology / Electrophysiology",
                    secondary_specialties=["Emergency Medicine", "Internal Medicine"],
                    rule_id="RULE_SPEC_URGENT_CARDIO",
                    clinical_rationale=(
                        f"Significant acute vital elevation observed: {finding.observed_value} bpm "
                        f"(baseline: {finding.baseline_value} bpm). Prompt in-person clinical assessment advised."
                    ),
                    urgency_tier="urgent_medical_evaluation",
                    disclaimer=cls.NON_DIAGNOSTIC_DISCLAIMER,
                    evidence_summary={
                        "finding_id": str(finding.id),
                        "metric_type": finding.metric_type,
                        "observed_value": finding.observed_value,
                        "severity": finding.severity
                    }
                )

        # 2. Check for Nocturnal Resting Tachycardia or Potentially Concerning HR Findings
        for finding in findings:
            if finding.metric_type == "heart_rate" and finding.severity == "potentially_concerning":
                return SpecialtyRoutingDecision(
                    primary_specialty="Cardiology / Electrophysiology",
                    secondary_specialties=["Internal Medicine", "General Practice"],
                    rule_id="RULE_SPEC_NOCTURNAL_CARDIO",
                    clinical_rationale=(
                        f"Observed sustained resting heart rate deviation of {finding.observed_value} bpm "
                        f"(+{finding.deviation:.1f} bpm above baseline) during resting/sleep hours with zero step activity."
                    ),
                    urgency_tier="prompt",
                    disclaimer=cls.NON_DIAGNOSTIC_DISCLAIMER,
                    evidence_summary={
                        "finding_id": str(finding.id),
                        "rule_id": finding.rule_id,
                        "deviation": finding.deviation
                    }
                )

        # 3. Check for Multi-Day Longitudinal Trend Drift
        for trend in trend_reports:
            if (
                trend.classification == FindingClassification.TREND
                and trend.metric_type == "resting_heart_rate"
                and trend.direction == "increasing"
                and trend.r_squared >= 0.70
            ):
                return SpecialtyRoutingDecision(
                    primary_specialty="General Practice / Internal Medicine",
                    secondary_specialties=["Cardiology", "Preventive Medicine / Lifestyle Medicine"],
                    rule_id="RULE_SPEC_TREND_DRIFT",
                    clinical_rationale=(
                        f"Observed a sustained multi-day upward drift in resting vitals over {trend.days_analyzed} days "
                        f"(rate: +{trend.slope_per_day:.2f} unit/day, R²={trend.r_squared:.2f})."
                    ),
                    urgency_tier="prompt" if trend.evidence_strength.value == "strong" else "routine",
                    disclaimer=cls.NON_DIAGNOSTIC_DISCLAIMER,
                    evidence_summary={
                        "days_analyzed": trend.days_analyzed,
                        "slope_per_day": trend.slope_per_day,
                        "r_squared": trend.r_squared,
                        "total_change": trend.total_change
                    }
                )

        # 4. Check for Sleep Metric Disruption
        for finding in findings:
            if finding.metric_type == "sleep_session":
                return SpecialtyRoutingDecision(
                    primary_specialty="Sleep Medicine / Pulmonology",
                    secondary_specialties=["Internal Medicine", "Neurology"],
                    rule_id="RULE_SPEC_SLEEP_DISRUPTION",
                    clinical_rationale=(
                        f"Observed marked variation in sleep duration or continuity: "
                        f"{finding.observed_value:.1f} min vs typical baseline {finding.baseline_value:.1f} min."
                    ),
                    urgency_tier="routine",
                    disclaimer=cls.NON_DIAGNOSTIC_DISCLAIMER,
                    evidence_summary={"finding_id": str(finding.id)}
                )

        # 5. Default Routine / Primary Care
        return SpecialtyRoutingDecision(
            primary_specialty="Primary Care / Routine Health Maintenance",
            secondary_specialties=["General Practice", "Preventive Medicine"],
            rule_id="RULE_SPEC_ROUTINE_PRIMARY",
            clinical_rationale=(
                "Wearable telemetry demonstrates overall baseline stability across analyzed physiological indicators. "
                "Routine periodic health checkup recommended."
            ),
            urgency_tier="routine",
            disclaimer=cls.NON_DIAGNOSTIC_DISCLAIMER,
            evidence_summary={"findings_count": len(findings), "trends_count": len(trend_reports)}
        )
