"""Tests for LangGraph Health Intelligence graph and safety guardrails."""

import asyncio
import pytest
from app.graphs.health_intel import build_health_intel_graph, node_safety_guardrail
from app.graphs.state import HealthIntelState


def test_health_intel_graph_execution() -> None:
    async def _run():
        graph = build_health_intel_graph()
        initial_state: HealthIntelState = {
            "finding_id": "fnd_123",
            "user_id": "usr_456",
            "metric_type": "heart_rate",
            "severity": "potentially_concerning",
            "observed_value": 104.0,
            "unit": "bpm",
            "recorded_at": "2026-09-04T02:15:00Z",
            "context": "Sleep window",
            "baseline": {"circadian_mean": 58.0, "circadian_std": 4.0},
            "recent_history": {},
            "data_quality": {"sensor_status": "nominal"},
            "explanation": None,
            "safety_approved": False,
            "safety_violations": None,
            "notification_dispatched": False
        }

        result = await graph.ainvoke(initial_state)

        assert result["explanation"] is not None
        assert "what_changed" in result["explanation"]
        assert "measurements_caused" in result["explanation"]
        assert "baseline_difference" in result["explanation"]
        assert "historical_context" in result["explanation"]
        assert "confidence_and_data_quality" in result["explanation"]
        assert "why_it_matters" in result["explanation"]
        assert "next_steps" in result["explanation"]
        assert result["safety_approved"] is True

    asyncio.run(_run())


def test_safety_guardrail_catches_prohibited_terms() -> None:
    async def _run():
        violating_state: HealthIntelState = {
            "finding_id": "fnd_bad",
            "user_id": "usr_1",
            "metric_type": "heart_rate",
            "severity": "urgent",
            "observed_value": 120.0,
            "unit": "bpm",
            "recorded_at": "2026-09-04T00:00:00Z",
            "context": "",
            "baseline": {},
            "recent_history": {},
            "data_quality": {},
            "explanation": {
                "what_changed": "You have a dangerous arrhythmia and are suffering from atrial fibrillation.",
                "why_it_matters": "Heart disease detected.",
                "next_steps": ["Go to hospital immediately."]
            },
            "safety_approved": False,
            "safety_violations": None,
            "notification_dispatched": False
        }

        guardrail_result = await node_safety_guardrail(violating_state)
        assert guardrail_result["safety_approved"] is False
        assert "arrhythmia" in guardrail_result["safety_violations"]
        assert "atrial fibrillation" in guardrail_result["safety_violations"]

    asyncio.run(_run())
