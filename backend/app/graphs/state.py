"""LangGraph Typed State Definitions."""

from typing import TypedDict, List, Optional, Dict, Any


class HealthIntelState(TypedDict):
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


class CareNavState(TypedDict):
    user_id: str
    finding_id: Optional[str]
    coordinates: Dict[str, float]
    radius_km: int
    recommended_specialty: Optional[str]
    providers: List[Dict[str, Any]]
    visit_summary_text: Optional[str]
    user_approved_provider_id: Optional[str]
    user_approval_granted: bool
    outreach_draft: Optional[str]
