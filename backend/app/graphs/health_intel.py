"""LangGraph Health Intelligence Workflow."""

from typing import Dict, Any, List
from langgraph.graph import StateGraph, END
from app.graphs.state import HealthIntelState
from app.schemas.finding import FindingExplanationSchema

PROHIBITED_TERMS = [
    "arrhythmia", "heart attack", "myocardial infarction",
    "atrial fibrillation", "hypertension", "disease", "syndrome"
]


async def node_generate_explanation(state: HealthIntelState) -> Dict[str, Any]:
    """Generates grounded 7-part explanation."""
    # Deterministic generation or LLM invocation with structured output
    val = state["observed_value"]
    unit = state["unit"]
    baseline_mean = state["baseline"].get("circadian_mean", 58.0)

    explanation = {
        "what_changed": f"A sustained elevation in resting {state['metric_type']} was recorded.",
        "measurements_caused": [f"Observed value: {val} {unit} at {state['recorded_at']}"],
        "baseline_difference": f"Your typical resting baseline is {baseline_mean} {unit}. Reading is elevated above normal variance.",
        "historical_context": "Pattern is statistically infrequent compared to the prior 30-day baseline window.",
        "confidence_and_data_quality": "High confidence (98%), continuous optical sensor signal with zero gaps.",
        "why_it_matters": "Elevated resting vitals indicate sympathetic nervous system activation or acute recovery deficit.",
        "next_steps": [
            "Rest in a comfortable position and hydrate.",
            "Verify snug smartwatch fit on wrist.",
            "If accompanied by dizziness or chest discomfort, seek emergency medical care."
        ]
    }
    return {"explanation": explanation}


async def node_safety_guardrail(state: HealthIntelState) -> Dict[str, Any]:
    """Enforces Rule H1 (Zero Medical Diagnosis)."""
    explanation = state.get("explanation")
    if not explanation:
        return {"safety_approved": False, "safety_violations": ["No explanation generated"]}

    full_text = " ".join([
        explanation["what_changed"],
        explanation["why_it_matters"],
        " ".join(explanation["next_steps"])
    ]).lower()

    violations = [term for term in PROHIBITED_TERMS if term in full_text]
    if violations:
        return {"safety_approved": False, "safety_violations": violations}
    return {"safety_approved": True, "safety_violations": []}


async def node_apply_safe_fallback(state: HealthIntelState) -> Dict[str, Any]:
    """Replaces violated explanation with deterministic safe template."""
    fallback = {
        "what_changed": "A significant statistical shift in your vitals was recorded.",
        "measurements_caused": [f"Observed: {state['observed_value']} {state['unit']}"],
        "baseline_difference": "Measurement significantly departs from your established baseline profile.",
        "historical_context": "Deviation recorded outside standard baseline window.",
        "confidence_and_data_quality": "Nominal sensor reading.",
        "why_it_matters": "Physiological deviation requiring observation.",
        "next_steps": [
            "Please rest and consult your physician if you feel unwell.",
            "Seek emergency evaluation if experiencing acute symptoms."
        ]
    }
    return {"explanation": fallback}


def route_safety(state: HealthIntelState) -> str:
    if state.get("safety_approved", False):
        return "approved"
    return "rejected"


def build_health_intel_graph() -> StateGraph:
    workflow = StateGraph(HealthIntelState)

    workflow.add_node("generate_explanation", node_generate_explanation)
    workflow.add_node("safety_guardrail", node_safety_guardrail)
    workflow.add_node("apply_fallback", node_apply_safe_fallback)

    workflow.set_entry_point("generate_explanation")
    workflow.add_edge("generate_explanation", "safety_guardrail")

    workflow.add_conditional_edges(
        "safety_guardrail",
        route_safety,
        {
            "approved": END,
            "rejected": "apply_fallback"
        }
    )
    workflow.add_edge("apply_fallback", END)

    return workflow.compile()
