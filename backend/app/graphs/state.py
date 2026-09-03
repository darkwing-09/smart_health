"""LangGraph Typed State Definitions."""

from typing import TypedDict, List, Optional, Dict, Any


class HealthIntelState(TypedDict, total=False):
    finding_id: str
    user_id: str
    metric_type: str
    severity: str
    observed_value: float
    unit: str
    recorded_at: str
    context: str
    baseline: Dict[str, Any]
    recent_history: Dict[str, Any]
    data_quality: Dict[str, Any]
    longitudinal_trend: Optional[Dict[str, Any]]
    activity_context: Optional[Dict[str, Any]]
    rule_id: Optional[str]
    rule_version: Optional[str]
    explanation: Optional[Dict[str, Any]]
    safety_approved: bool
    safety_violations: Optional[List[str]]
    notification_dispatched: bool


class DailyReportState(TypedDict):
    user_id: str
    report_date: str
    metrics_summary: List[Dict[str, Any]]
    findings_summary: List[Dict[str, Any]]
    narrative: Optional[str]
    closing_quote: Optional[Dict[str, str]]
    pdf_path: Optional[str]


class CareNavState(TypedDict, total=False):
    user_id: str
    finding_id: Optional[str]
    coordinates: Dict[str, float]
    radius_km: int
    recommended_specialty: Optional[str]
    specialty_routing: Optional[Dict[str, Any]]
    providers: List[Dict[str, Any]]
    summary_payload: Optional[Dict[str, Any]]
    visit_summary_text: Optional[str]
    clinician_note: Optional[str]
    patient_rationale: Optional[str]
    safety_disclaimer: Optional[str]
    user_approved_provider_id: Optional[str]
    user_approval_granted: bool
    approval_token: Optional[str]
    outreach_draft: Optional[str]
    # Backward compatibility fields
    biometric_context: Optional[Dict[str, Any]]
    user_location: Optional[Dict[str, float]]
    nearby_facilities: Optional[List[Dict[str, Any]]]
    user_approved: Optional[bool]
