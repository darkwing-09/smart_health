"""Comprehensive Tests for LangGraph Workflows and LangSmith Tracing Configuration."""

import pytest
from app.graphs.health_intel import build_health_intel_graph
from app.graphs.daily_report import build_daily_report_graph
from app.graphs.care_nav import build_care_nav_graph
from app.core.config import settings


@pytest.mark.asyncio
async def test_health_intel_graph_approved_flow():
    """Verifies HealthIntelGraph execution with safe explanation."""
    graph = build_health_intel_graph()
    initial_state = {
        "finding_id": "find_01",
        "metric_type": "heart_rate",
        "observed_value": 115.0,
        "unit": "bpm",
        "recorded_at": "2026-09-04T00:00:00Z",
        "baseline": {"circadian_mean": 62.0},
        "explanation": None,
        "safety_approved": False,
        "safety_violations": []
    }

    result = await graph.ainvoke(initial_state)
    assert result["safety_approved"] is True
    assert len(result["safety_violations"]) == 0
    assert result["explanation"] is not None
    assert "what_changed" in result["explanation"]
    assert "why_it_matters" in result["explanation"]
    assert len(result["explanation"]["next_steps"]) >= 2


@pytest.mark.asyncio
async def test_health_intel_graph_safety_violation_triggers_fallback():
    """Verifies that prohibited clinical diagnostic phrases trigger Rule H1 safe fallback."""
    graph = build_health_intel_graph()
    initial_state = {
        "finding_id": "find_02",
        "metric_type": "heart_rate",
        "observed_value": 140.0,
        "unit": "bpm",
        "recorded_at": "2026-09-04T00:00:00Z",
        "baseline": {"circadian_mean": 60.0},
        # Pre-seed an explanation with prohibited diagnostic terms
        "explanation": {
            "what_changed": "User is suffering from arrhythmia and heart attack.",
            "measurements_caused": ["Observed: 140 bpm"],
            "baseline_difference": "Baseline exceeded",
            "historical_context": "None",
            "confidence_and_data_quality": "Nominal",
            "why_it_matters": "Active disease diagnosed",
            "next_steps": ["Take medication"]
        },
        "safety_approved": False,
        "safety_violations": []
    }

    result = await graph.ainvoke(initial_state)
    # The guardrail should have intercepted this
    # Either fallback was applied and prohibited terms are eliminated from explanation
    expl = result["explanation"]
    full_text = f"{expl['what_changed']} {expl['why_it_matters']} {' '.join(expl['next_steps'])}".lower()
    assert "arrhythmia" not in full_text
    assert "heart attack" not in full_text
    assert "statistical shift" in expl["what_changed"]


@pytest.mark.asyncio
async def test_daily_report_graph_execution():
    """Verifies DailyReportGraph compiles, runs, and outputs narrative and reflection quote."""
    graph = build_daily_report_graph()
    initial_state = {
        "user_id": "user_001",
        "report_date": "2026-09-03",
        "metrics_summary": [
            {"metric": "heart_rate", "avg": 64.0},
            {"metric": "steps", "total": 8420}
        ],
        "findings_summary": [],
        "narrative": None,
        "closing_quote": None
    }

    result = await graph.ainvoke(initial_state)
    assert result["narrative"] is not None
    assert "2026-09-03" in result["narrative"]
    assert result["closing_quote"] is not None
    assert "quote" in result["closing_quote"]
    assert "author_or_tradition" in result["closing_quote"]


@pytest.mark.asyncio
async def test_care_navigation_graph_execution():
    """Verifies CareNavGraph recommends specialty and formats doctor visit summary."""
    graph = build_care_nav_graph()
    initial_state = {
        "finding_id": "find_03",
        "biometric_context": {"metric_type": "heart_rate", "z_score": 4.1},
        "user_location": {"lat": 12.9716, "lng": 77.5946},
        "recommended_specialty": None,
        "nearby_facilities": [],
        "visit_summary_text": None,
        "user_approved": False
    }

    result = await graph.ainvoke(initial_state)
    assert result["recommended_specialty"] is not None
    assert "Cardiology" in result["recommended_specialty"]
    assert result["visit_summary_text"] is not None
    assert "PATIENT HEALTH VISIT SUMMARY" in result["visit_summary_text"]


def test_langsmith_tracing_configuration():
    """
    SLICE 9 VERIFICATION:
    Verifies LangSmith environment parameters are properly defined and read into settings.
    Distinguishes CODE CONFIGURATION VERIFIED from LIVE TRACE VERIFIED.
    """
    assert hasattr(settings, "LANGCHAIN_TRACING_V2")
    assert hasattr(settings, "LANGCHAIN_PROJECT")
    assert hasattr(settings, "LANGCHAIN_ENDPOINT")
    assert settings.LANGCHAIN_PROJECT == "personal-health-os"
    assert settings.LANGCHAIN_ENDPOINT == "https://api.smith.langchain.com"
