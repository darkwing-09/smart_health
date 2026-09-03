"""LangGraph Daily Report Synthesis Workflow."""

from typing import Dict, Any
from langgraph.graph import StateGraph, END
from app.graphs.state import DailyReportState


async def node_synthesize_narrative(state: DailyReportState) -> Dict[str, Any]:
    metrics = state.get("metrics_summary", [])
    findings = state.get("findings_summary", [])

    narrative = (
        f"Daily analysis for {state['report_date']} concluded with {len(metrics)} primary metrics evaluated. "
        f"Resting baseline stability was maintained with {len(findings)} noteworthy deviations recorded. "
        "Sleep and physical recovery aligned with standard circadian seasonality."
    )
    return {"narrative": narrative}


async def node_generate_quote(state: DailyReportState) -> Dict[str, Any]:
    # Non-cliche stoic reflection
    quote = {
        "quote": "To cultivate calm in the body is to prepare the mind for clarity; restoration is not the absence of effort, but its completion.",
        "author_or_tradition": "Stoic Reflection (Mindful Recovery)"
    }
    return {"closing_quote": quote}


def build_daily_report_graph() -> StateGraph:
    workflow = StateGraph(DailyReportState)

    workflow.add_node("synthesize_narrative", node_synthesize_narrative)
    workflow.add_node("generate_quote", node_generate_quote)

    workflow.set_entry_point("synthesize_narrative")
    workflow.add_edge("synthesize_narrative", "generate_quote")
    workflow.add_edge("generate_quote", END)

    return workflow.compile()
