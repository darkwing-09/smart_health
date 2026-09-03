"""Tests for Upgraded CareNavigationGraph.

Verifies evidence synthesis, Rule H1 safety guardrail, and human approval gating.
"""

import pytest
from app.graphs.care_nav import build_care_nav_graph, CLINICAL_DISCLAIMER


@pytest.mark.asyncio
async def test_care_nav_graph_evidence_synthesis():
    """Verifies that CareNavigationGraph synthesizes physician notes from structured summary evidence."""
    graph = build_care_nav_graph()
    state = {
        "user_id": "usr_test_patient",
        "finding_id": "fnd_test_01",
        "coordinates": {"lat": 17.3850, "lng": 78.4867},
        "radius_km": 10,
        "recommended_specialty": "Cardiology / Electrophysiology",
        "specialty_routing": {
            "primary_specialty": "Cardiology / Electrophysiology",
            "clinical_rationale": "Sustained resting vital deviation during nocturnal rest.",
            "urgency_tier": "prompt"
        },
        "providers": [],
        "summary_payload": {
            "reporting_period": {"start_date": "2026-08-28", "end_date": "2026-09-04"},
            "data_coverage": {"wear_adherence_percent": 95.0},
            "measurements_summary": [
                {"metric_name": "heart_rate", "observed_display": "78 bpm", "baseline_display": "60 bpm"}
            ],
            "findings": [{"finding_id": "fnd_1", "metric_type": "heart_rate"}]
        },
        "user_approved_provider_id": None,
        "user_approval_granted": False,
        "approval_token": None
    }

    result = await graph.ainvoke(state)

    assert "clinician_note" in result
    assert "patient_rationale" in result
    assert "Cardiology" in result["recommended_specialty"]
    assert "78 bpm" in result["clinician_note"]
    assert CLINICAL_DISCLAIMER in result["visit_summary_text"]
    # Without approval, outreach draft must be None
    assert result["outreach_draft"] is None


@pytest.mark.asyncio
async def test_care_nav_graph_safety_guardrail_catches_prohibited_terms():
    """Verifies Rule H1 guardrail intercepts prohibited diagnostic assertions in care notes."""
    graph = build_care_nav_graph()
    state = {
        "user_id": "usr_test_patient",
        "finding_id": "fnd_test_01",
        "coordinates": {"lat": 17.3850, "lng": 78.4867},
        "radius_km": 10,
        "recommended_specialty": "Cardiology",
        "specialty_routing": {
            "primary_specialty": "Cardiology",
            "clinical_rationale": "Patient is suffering from acute arrhythmia and heart attack.", # Prohibited!
            "urgency_tier": "urgent"
        },
        "providers": [],
        "summary_payload": {
            "reporting_period": {"start_date": "2026-08-28", "end_date": "2026-09-04"},
            "data_coverage": {"wear_adherence_percent": 90.0},
            "measurements_summary": [],
            "findings": []
        },
        "user_approved_provider_id": None,
        "user_approval_granted": False,
        "approval_token": None
    }

    result = await graph.ainvoke(state)

    # Assert fallback took effect and prohibited diagnostic terms were purged
    visit_text = result["visit_summary_text"].lower()
    assert "arrhythmia" not in visit_text
    assert "heart attack" not in visit_text
    assert "longitudinal wearable telemetry compiled" in visit_text


@pytest.mark.asyncio
async def test_care_nav_graph_human_approval_releases_outreach():
    """Verifies that outreach draft is released only when user approval is confirmed with token."""
    graph = build_care_nav_graph()
    state = {
        "user_id": "usr_test_patient",
        "finding_id": "fnd_test_01",
        "coordinates": {"lat": 17.3850, "lng": 78.4867},
        "radius_km": 10,
        "recommended_specialty": "Cardiology",
        "specialty_routing": {"primary_specialty": "Cardiology", "clinical_rationale": "Routine check"},
        "providers": [],
        "summary_payload": {},
        "user_approved_provider_id": "prov_apollo_01",
        "user_approval_granted": True,
        "approval_token": "appr_7f39b1a82c94"
    }

    result = await graph.ainvoke(state)

    assert result["outreach_draft"] is not None
    assert "appr_7f39b1" in result["outreach_draft"]
    assert "I would like to schedule a consultation" in result["outreach_draft"]
