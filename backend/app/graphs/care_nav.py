"""LangGraph Care Navigation & Doctor Visit Summary Workflow.

Integrates deterministic specialty routing, clinician brief synthesis,
patient uncertainty explanation, Rule H1 safety guardrail, and human approval gating.
"""

import re
from typing import Dict, Any, List
from langgraph.graph import StateGraph, END
from app.graphs.state import CareNavState

# Rule H1 Blacklisted Diagnostic Assertions
PROHIBITED_DIAGNOSTIC_TERMS = [
    r"\barrhythmia\b",
    r"\bheart attack\b",
    r"\bmyocardial infarction\b",
    r"\batrial fibrillation\b",
    r"\bhypertension\b",
    r"\bheart failure\b",
    r"\bcoronary artery disease\b",
    r"\bischemia\b",
    r"\bstroke\b",
    r"\bcardiac arrest\b"
]

CLINICAL_DISCLAIMER = (
    "CLINICAL ADVISORY & STATUTORY DISCLAIMER: HealthAgent is a personal health data infrastructure "
    "and telemetry interpreter, NOT a diagnostic medical device or emergency dispatch service. "
    "This summary is compiled from consumer wearable sensors solely to assist patient-physician consultations. "
    "It does not constitute a medical diagnosis, clinical treatment plan, or electrocardiographic assessment. "
    "All clinical decisions must be made by a licensed healthcare provider."
)


async def node_evaluate_specialty_routing(state: CareNavState) -> Dict[str, Any]:
    """Ingests or standardizes deterministic specialty routing recommendations."""
    routing = state.get("specialty_routing") or {}
    primary = routing.get("primary_specialty") or state.get("recommended_specialty")
    
    if not primary:
        # Check backward-compatible biometric_context or finding context
        bio = state.get("biometric_context") or {}
        if bio.get("metric_type") == "heart_rate" and (bio.get("z_score", 0) >= 3.0 or bio.get("observed_value", 0) >= 90):
            primary = "Cardiology / Electrophysiology"
            routing = {
                "primary_specialty": primary,
                "clinical_rationale": "Elevated resting heart rate z-score exceeding threshold.",
                "urgency_tier": "prompt"
            }
        else:
            primary = "Primary Care / Routine Health Maintenance"
            routing = {
                "primary_specialty": primary,
                "clinical_rationale": "Nominal baseline vitals.",
                "urgency_tier": "routine"
            }

    return {
        "recommended_specialty": primary,
        "specialty_routing": routing
    }


async def node_synthesize_clinician_note(state: CareNavState) -> Dict[str, Any]:
    """Generates a concise, evidence-grounded clinical consultation note for the physician."""
    summary = state.get("summary_payload") or {}
    rep_period = summary.get("reporting_period", {})
    metrics = summary.get("measurements_summary", [])
    findings = summary.get("findings", [])
    specialty = state.get("recommended_specialty", "Primary Care / Routine Health Maintenance")

    # Deterministic evidence extraction
    hr_metric = next((m for m in metrics if m.get("metric_name") == "heart_rate"), {})
    obs_hr = hr_metric.get("observed_display", "nominal")
    base_hr = hr_metric.get("baseline_display", "established baseline")

    clinician_note = (
        f"PATIENT HEALTH VISIT SUMMARY / CONSULTATION BRIEF ({rep_period.get('start_date', 'N/A')} to {rep_period.get('end_date', 'N/A')})\n"
        f"Primary Clinical Consideration: {specialty}\n"
        f"Telemetry Profile: Resting vitals observed at {obs_hr} vs personal baseline of {base_hr}.\n"
        f"Documented Findings: {len(findings)} events evaluated during the consented reporting period.\n"
        f"Device Adherence: {summary.get('data_coverage', {}).get('wear_adherence_percent', 0.0)}% nominal wear."
    )
    return {"clinician_note": clinician_note}


async def node_explain_patient_rationale(state: CareNavState) -> Dict[str, Any]:
    """Formulates a calm, transparent rationale explaining why this specialty was suggested."""
    routing = state.get("specialty_routing") or {}
    specialty = state.get("recommended_specialty", "Primary Care")
    rationale = routing.get("clinical_rationale", "Recommended routine review based on overall vital indicators.")

    patient_text = (
        f"We have suggested consulting a specialist in {specialty}.\n"
        f"Context: {rationale}\n"
        "Important Note: Consumer wearable sensors track physiological shifts but cannot identify root clinical causes. "
        "Your doctor can perform standard clinical evaluations to understand what these patterns mean for your health."
    )
    return {"patient_rationale": patient_text}


async def node_enforce_safety_and_disclaimer(state: CareNavState) -> Dict[str, Any]:
    """Enforces Rule H1 Zero-Diagnosis guardrail and attaches mandatory disclaimers."""
    clinician_note = state.get("clinician_note") or ""
    patient_rationale = state.get("patient_rationale") or ""

    # Check for prohibited diagnostic phrases
    for text_to_check in [clinician_note, patient_rationale]:
        for pattern in PROHIBITED_DIAGNOSTIC_TERMS:
            if re.search(pattern, text_to_check, re.IGNORECASE):
                # Fallback to safe deterministic output
                clinician_note = (
                    "Longitudinal wearable telemetry compiled for clinical review. "
                    "Resting physiological variation recorded outside personal baseline. "
                    "Independent clinical assessment recommended."
                )
                patient_rationale = (
                    "Your personal health telemetry recorded values outside your typical baseline. "
                    "Please discuss these observations with your physician."
                )
                break

    full_summary = f"{clinician_note}\n\n{patient_rationale}\n\n{CLINICAL_DISCLAIMER}"
    return {
        "clinician_note": clinician_note,
        "patient_rationale": patient_rationale,
        "safety_disclaimer": CLINICAL_DISCLAIMER,
        "visit_summary_text": full_summary
    }


async def node_verify_human_approval(state: CareNavState) -> Dict[str, Any]:
    """Human-in-the-Loop Gating: releases outreach draft only if explicit patient approval is granted."""
    is_approved = state.get("user_approval_granted", False)
    token = state.get("approval_token")

    if is_approved and token:
        outreach_draft = (
            f"Dear Doctor / Clinic Team,\n\n"
            f"I would like to schedule a consultation regarding my health telemetry patterns.\n"
            f"Reason: Discussion of recent resting vital observations outside my 30-day personal baseline.\n"
            f"I have compiled an approved clinical consultation brief from HealthAgent (Ref Token: {token[:12]}).\n\n"
            f"Thank you,\nPatient"
        )
        return {"outreach_draft": outreach_draft}
    else:
        return {
            "outreach_draft": None,
            "patient_rationale": (
                state.get("patient_rationale", "") +
                "\n\n[Human Approval Required: Summary drafted and waiting for your review and explicit approval.]"
            )
        }


def build_care_nav_graph() -> StateGraph:
    """Builds and compiles the upgraded CareNavigationGraph."""
    workflow = StateGraph(CareNavState)

    workflow.add_node("evaluate_specialty_routing", node_evaluate_specialty_routing)
    workflow.add_node("synthesize_clinician_note", node_synthesize_clinician_note)
    workflow.add_node("explain_patient_rationale", node_explain_patient_rationale)
    workflow.add_node("enforce_safety_and_disclaimer", node_enforce_safety_and_disclaimer)
    workflow.add_node("verify_human_approval", node_verify_human_approval)

    workflow.set_entry_point("evaluate_specialty_routing")
    workflow.add_edge("evaluate_specialty_routing", "synthesize_clinician_note")
    workflow.add_edge("synthesize_clinician_note", "explain_patient_rationale")
    workflow.add_edge("explain_patient_rationale", "enforce_safety_and_disclaimer")
    workflow.add_edge("enforce_safety_and_disclaimer", "verify_human_approval")
    workflow.add_edge("verify_human_approval", END)

    return workflow.compile()
