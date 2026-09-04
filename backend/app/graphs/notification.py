"""LangGraph Notification Routing Graph (Agent 6: Notification Agent).

Orchestrates multi-channel delivery, alert hierarchy tier mapping, quiet-hours gating,
deduplication, and escalation bypass based strictly on deterministic inputs.

SAFETY INVARIANTS:
1. NotificationGraph is an ORCHESTRATION mechanism, NEVER a diagnostic or clinical authority.
2. Finding severity is immutable and comes directly from the finding layer.
3. Level 4 Urgent alerts override quiet hours and cannot be suppressed.
4. If any external LLM provider fails, notification delivery continues seamlessly with
   deterministic fallback content.
"""

from typing import Any, Optional, TypedDict
from langgraph.graph import StateGraph, END

from app.services.notification_policy import (
    AlertTier,
    DeliveryChannel,
    LEVEL_4_EMERGENCY_DISCLAIMER,
    NotificationPolicyEngine,
    NotificationPolicyResult,
)
from app.services.quiet_hours import QuietHoursEvaluator


class NotificationRouterState(TypedDict):
    """LangGraph state schema for notification routing."""
    finding_id: Optional[str]
    user_id: str
    metric_type: str
    severity: str  # Immutable finding severity from deterministic layer
    rule_id: str
    observed_value: Optional[float]
    baseline_value: Optional[float]
    deviation: Optional[float]
    user_prefs: dict[str, Any]
    user_timezone: str
    is_quiet_hours: bool
    alert_tier: int
    channels: list[str]
    is_deduplicated: bool
    is_escalation: bool
    delivery_decision: str  # 'DISPATCH', 'HOLD_QUIET_HOURS', 'SUPPRESS_DEDUP', 'SUPPRESS_SILENT'
    title: str
    body: str
    requires_emergency_disclaimer: bool
    dispatched: bool
    error: Optional[str]


def node_evaluate_tier(state: NotificationRouterState) -> dict[str, Any]:
    """Node 1: Deterministically maps finding severity to alert tier and evaluates quiet hours."""
    severity = state["severity"]
    rule_id = state.get("rule_id", "ANOMALY")
    user_prefs = state.get("user_prefs", {})
    user_tz = state.get("user_timezone", "Asia/Kolkata")

    # Timezone-aware quiet hours evaluation
    is_quiet, _ = QuietHoursEvaluator.evaluate(
        user_timezone=user_tz,
        quiet_start_str=user_prefs.get("quiet_hours_start", "22:00"),
        quiet_end_str=user_prefs.get("quiet_hours_end", "07:00")
    )

    policy: NotificationPolicyResult = NotificationPolicyEngine.evaluate(
        severity=severity,
        rule_id=rule_id,
        user_prefs=user_prefs,
        is_quiet_hours=is_quiet
    )

    channels = [c.value for c in policy.channels]
    return {
        "alert_tier": int(policy.tier),
        "is_quiet_hours": is_quiet,
        "channels": channels,
        "requires_emergency_disclaimer": policy.requires_emergency_disclaimer,
    }


def node_evaluate_deduplication(state: NotificationRouterState) -> dict[str, Any]:
    """Node 2: Evaluates deduplication rules and severity escalation bypass."""
    tier = state["alert_tier"]
    is_dedup = state.get("is_deduplicated", False)
    is_escalation = state.get("is_escalation", False)

    # Tier 0 (Info) is silent timeline only
    if tier == AlertTier.LEVEL_0_INFO:
        return {"delivery_decision": "SUPPRESS_SILENT"}

    # Tier 1 (Insight) is daily digest only
    if tier == AlertTier.LEVEL_1_INSIGHT:
        return {"delivery_decision": "SUPPRESS_SILENT"}

    # If deduplicated and NOT an escalation, suppress
    if is_dedup and not is_escalation:
        return {"delivery_decision": "SUPPRESS_DEDUP"}

    return {"delivery_decision": "PROCEED"}


def node_evaluate_quiet_hours(state: NotificationRouterState) -> dict[str, Any]:
    """Node 3: Evaluates quiet-hours gating with emergency Level 4 override."""
    decision = state.get("delivery_decision")
    if decision != "PROCEED":
        return {}

    tier = state["alert_tier"]
    is_quiet = state.get("is_quiet_hours", False)

    # Level 4 Urgent STRICTLY overrides quiet hours
    if tier == AlertTier.LEVEL_4_URGENT:
        return {"delivery_decision": "DISPATCH"}

    # Level 2 & 3: If quiet hours active, hold FCM and mark for deferred release
    if is_quiet and tier in [AlertTier.LEVEL_2_ATTENTION, AlertTier.LEVEL_3_IMPORTANT]:
        return {"delivery_decision": "HOLD_QUIET_HOURS"}

    return {"delivery_decision": "DISPATCH"}


def node_format_message(state: NotificationRouterState) -> dict[str, Any]:
    """Node 4: Synthesizes calm, grounded notification text using deterministic fallback."""
    tier = state["alert_tier"]
    metric = state.get("metric_type", "metric").replace("_", " ")
    obs = state.get("observed_value")
    val_str = f" ({obs:.0f} bpm)" if obs else ""

    if tier == AlertTier.LEVEL_4_URGENT:
        title = "Urgent Physiological Observation"
        body = f"A significant resting deviation was observed{val_str}. Tap to review details."
        if state.get("requires_emergency_disclaimer"):
            body = f"{body}\n\n{LEVEL_4_EMERGENCY_DISCLAIMER}"
    elif tier == AlertTier.LEVEL_3_IMPORTANT:
        title = "Notable Baseline Deviation"
        body = f"An unusual {metric} reading was observed{val_str}."
    elif tier == AlertTier.LEVEL_2_ATTENTION:
        title = "Health Trend Update"
        body = f"A mild variation in your {metric} was observed."
    else:
        title = "Health Information"
        body = f"Standard {metric} observation recorded."

    return {
        "title": title,
        "body": body,
        "dispatched": state.get("delivery_decision") in ["DISPATCH", "HOLD_QUIET_HOURS"],
    }


def build_notification_graph() -> StateGraph:
    """Builds and compiles the LangGraph Notification Router."""
    graph = StateGraph(NotificationRouterState)

    graph.add_node("evaluate_tier", node_evaluate_tier)
    graph.add_node("evaluate_deduplication", node_evaluate_deduplication)
    graph.add_node("evaluate_quiet_hours", node_evaluate_quiet_hours)
    graph.add_node("format_message", node_format_message)

    graph.set_entry_point("evaluate_tier")
    graph.add_edge("evaluate_tier", "evaluate_deduplication")
    graph.add_edge("evaluate_deduplication", "evaluate_quiet_hours")
    graph.add_edge("evaluate_quiet_hours", "format_message")
    graph.add_edge("format_message", END)

    return graph.compile()
