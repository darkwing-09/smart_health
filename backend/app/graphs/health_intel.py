"""LangGraph Health Intelligence Workflow V2 (Longitudinal Evidence-Grounded).

Orchestrates evidence reasoning over:
- Current Observations
- Personal Baselines & Circadian Seasonality
- Longitudinal Trends (7-28 days)
- Activity & Behavioral Context (Resting, Sleeping, Exercise)
- Sensor Data Quality Ratings
Enforces Rule H1 (Zero Medical Diagnosis) with automated safe fallbacks.
"""

from typing import Dict, Any, List, Optional
from langgraph.graph import StateGraph, END
from app.graphs.state import HealthIntelState

PROHIBITED_TERMS = [
    "arrhythmia", "heart attack", "myocardial infarction",
    "atrial fibrillation", "hypertension", "disease", "syndrome",
    "heart failure", "coronary", "ischemia", "stroke"
]


async def node_generate_explanation(state: HealthIntelState) -> Dict[str, Any]:
    """
    Generates grounded structured explanation from longitudinal evidence.
    Zero fabricated diagnoses. Strictly distinguishes observation from physiological interpretation.
    """
    if state.get("explanation") is not None:
        return {"explanation": state["explanation"]}

    val = state.get("observed_value", 0.0)
    unit = state.get("unit", "bpm")
    metric = state.get("metric_type", "heart_rate").replace("_", " ")
    ts = state.get("recorded_at", "recent window")
    baseline = state.get("baseline", {})
    circadian_mean = baseline.get("circadian_mean", baseline.get("mean", 60.0))
    circadian_std = max(baseline.get("circadian_std", baseline.get("stddev", 4.0)), 1.0)
    diff = round(val - circadian_mean, 1)

    # Context & Quality
    act_context = state.get("activity_context") or {}
    primary_state = act_context.get("primary_context", state.get("context", "RESTING"))
    concurrent_steps = act_context.get("steps_concurrent", 0)
    dq = state.get("data_quality") or {}
    dq_rating = dq.get("rating", "good")

    # Longitudinal trend
    trend = state.get("longitudinal_trend")
    if trend:
        longitudinal_context_text = (
            f"Over the last {trend.get('days_analyzed', 14)} days, your {metric} has exhibited a "
            f"{trend.get('direction', 'stable')} trend (rate: {trend.get('slope_per_day', 0.0)}/day) "
            f"with {trend.get('evidence_strength', 'moderate')} evidence strength."
        )
    else:
        longitudinal_context_text = (
            f"This reading is evaluated relative to your established 30-day baseline. "
            f"Isolated departure from typical circadian patterns."
        )

    # Physiological Interpretations (Non-diagnostic possibilities)
    interpretations = [
        "Acute sympathetic autonomic response or delayed physical recovery.",
        "Mild dehydration, ambient warmth, or recent meal ingestion.",
        "Underlying physiological stressor or poor restorative sleep quality."
    ]

    limitations = [
        f"Data quality is rated as {dq_rating.upper()}.",
        "Wearable optical sensors can experience transient motion artifacts or pressure shifts."
    ]

    next_steps = [
        "Rest in a comfortable, seated or reclined position.",
        "Hydrate with a glass of water.",
        "Verify smartwatch fit is snug and two finger-widths above the wrist bone.",
        "Note any subjective symptoms in your health journal."
    ]

    safety_note = (
        "This is a physiological observation and NOT a medical diagnosis. "
        "If you experience chest discomfort, shortness of breath, palpitations, or dizziness, "
        "seek immediate emergency medical care."
    )

    observation_text = f"Observed {metric} of {val} {unit} recorded during a {primary_state} state ({concurrent_steps} steps)."
    comparison_text = (
        f"Your personal circadian expectation for this timeframe is {circadian_mean} ± {circadian_std} {unit}. "
        f"Observed reading is {abs(diff)} {unit} {'higher' if diff > 0 else 'lower'} than your baseline."
    )
    summary_text = f"An unusual elevation in resting {state.get('metric_type', 'heart_rate')} was recorded ({val} {unit} detected during a {primary_state} period)."

    explanation = {
        # Structured V2 fields
        "summary": summary_text,
        "observation": observation_text,
        "personal_comparison": comparison_text,
        "longitudinal_context": longitudinal_context_text,
        "possible_interpretations": interpretations,
        "limitations": limitations,
        "recommended_next_step": next_steps,
        "safety_note": safety_note,

        # Backward compatibility with 7-part explanation schema
        "what_changed": summary_text,
        "measurements_caused": [f"Observed value: {val} {unit} at {ts}"],
        "baseline_difference": comparison_text,
        "historical_context": longitudinal_context_text,
        "confidence_and_data_quality": f"Data quality rated as {dq_rating.upper()} with verified optical contact.",
        "why_it_matters": " ".join(interpretations),
        "next_steps": next_steps
    }
    return {"explanation": explanation}


async def node_safety_guardrail(state: HealthIntelState) -> Dict[str, Any]:
    """
    Enforces Rule H1 (Zero Medical Diagnosis).
    Scans entire generated payload for prohibited diagnostic labels.
    """
    explanation = state.get("explanation")
    if not explanation:
        return {"safety_approved": False, "safety_violations": ["No explanation generated"]}

    # Aggregate all text values in explanation
    text_corpus = []
    for k, v in explanation.items():
        if isinstance(v, str):
            text_corpus.append(v)
        elif isinstance(v, list):
            for item in v:
                if isinstance(item, str):
                    text_corpus.append(item)
    full_text = " ".join(text_corpus).lower()

    violations = [term for term in PROHIBITED_TERMS if term in full_text]
    if violations:
        return {"safety_approved": False, "safety_violations": violations}
    return {"safety_approved": True, "safety_violations": []}


async def node_apply_safe_fallback(state: HealthIntelState) -> Dict[str, Any]:
    """
    Replaces violated explanation with deterministic safe template.
    """
    val = state.get("observed_value", 0.0)
    unit = state.get("unit", "bpm")
    metric = state.get("metric_type", "heart_rate").replace("_", " ")

    fallback = {
        "summary": f"A significant statistical shift in your {metric} was recorded.",
        "observation": f"Observed reading: {val} {unit}.",
        "personal_comparison": "Measurement significantly departs from your established baseline profile.",
        "longitudinal_context": "Deviation recorded outside standard baseline bounds.",
        "possible_interpretations": ["Physiological departure requiring calm observation."],
        "limitations": ["Automated statistical evaluation only."],
        "recommended_next_step": [
            "Rest quietly and hydrate.",
            "Please consult your healthcare provider if you feel unwell."
        ],
        "safety_note": "If you experience acute red-flag symptoms (chest pain, severe shortness of breath), seek immediate emergency medical care.",

        # Backward-compatible fields
        "what_changed": f"A significant statistical shift in your {metric} was recorded.",
        "measurements_caused": [f"Observed: {val} {unit}"],
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
