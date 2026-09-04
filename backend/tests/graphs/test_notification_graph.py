"""Tests for LangGraph Notification Routing Graph."""

import pytest
from app.graphs.notification import build_notification_graph, NotificationRouterState
from app.services.notification_policy import AlertTier


@pytest.mark.asyncio
async def test_notification_graph_urgent_alert_overrides_quiet_hours():
    """Verifies that NotificationGraph routes Level 4 Urgent finding with immediate dispatch."""
    graph = build_notification_graph()

    state: NotificationRouterState = {
        "finding_id": "f_urgent_001",
        "user_id": "u_test_001",
        "metric_type": "heart_rate",
        "severity": "urgent",
        "rule_id": "RULE_H2_CEILING",
        "observed_value": 158.0,
        "baseline_value": 72.0,
        "deviation": 86.0,
        "user_prefs": {"fcm_enabled": True, "quiet_hours_start": "00:00", "quiet_hours_end": "23:59"}, # Always quiet
        "user_timezone": "Asia/Kolkata",
        "is_quiet_hours": True,
        "alert_tier": 0,
        "channels": [],
        "is_deduplicated": False,
        "is_escalation": False,
        "delivery_decision": "",
        "title": "",
        "body": "",
        "requires_emergency_disclaimer": False,
        "dispatched": False,
        "error": None
    }

    result = await graph.ainvoke(state)
    assert result["alert_tier"] == AlertTier.LEVEL_4_URGENT
    assert result["delivery_decision"] == "DISPATCH"  # Overridden!
    assert result["requires_emergency_disclaimer"] is True
    assert "SAFETY NOTICE" in result["body"]
    assert "Urgent" in result["title"]


@pytest.mark.asyncio
async def test_notification_graph_important_alert_held_during_quiet_hours():
    """Verifies that Level 3 Important finding is held during quiet hours."""
    graph = build_notification_graph()

    state: NotificationRouterState = {
        "finding_id": "f_imp_001",
        "user_id": "u_test_002",
        "metric_type": "heart_rate",
        "severity": "potentially_concerning",
        "rule_id": "RULE_Z_SCORE_38",
        "observed_value": 115.0,
        "baseline_value": 72.0,
        "deviation": 43.0,
        "user_prefs": {"fcm_enabled": True, "quiet_hours_start": "00:00", "quiet_hours_end": "23:59"},
        "user_timezone": "Asia/Kolkata",
        "is_quiet_hours": True,
        "alert_tier": 0,
        "channels": [],
        "is_deduplicated": False,
        "is_escalation": False,
        "delivery_decision": "",
        "title": "",
        "body": "",
        "requires_emergency_disclaimer": False,
        "dispatched": False,
        "error": None
    }

    result = await graph.ainvoke(state)
    assert result["alert_tier"] == AlertTier.LEVEL_3_IMPORTANT
    assert result["delivery_decision"] == "HOLD_QUIET_HOURS"


@pytest.mark.asyncio
async def test_notification_graph_deduplication_suppression():
    """Verifies that an already notified finding is suppressed by deduplication."""
    graph = build_notification_graph()

    state: NotificationRouterState = {
        "finding_id": "f_dedup_001",
        "user_id": "u_test_003",
        "metric_type": "heart_rate",
        "severity": "worth_monitoring",
        "rule_id": "RULE_Z_28",
        "observed_value": 95.0,
        "baseline_value": 70.0,
        "deviation": 25.0,
        "user_prefs": {},
        "user_timezone": "Asia/Kolkata",
        "is_quiet_hours": False,
        "alert_tier": 0,
        "channels": [],
        "is_deduplicated": True,  # Already sent within 12h
        "is_escalation": False,
        "delivery_decision": "",
        "title": "",
        "body": "",
        "requires_emergency_disclaimer": False,
        "dispatched": False,
        "error": None
    }

    result = await graph.ainvoke(state)
    assert result["delivery_decision"] == "SUPPRESS_DEDUP"
    assert result["dispatched"] is False


@pytest.mark.asyncio
async def test_notification_graph_escalation_bypass():
    """Verifies that if a finding escalates to urgent, deduplication is bypassed."""
    graph = build_notification_graph()

    state: NotificationRouterState = {
        "finding_id": "f_escalate_001",
        "user_id": "u_test_004",
        "metric_type": "heart_rate",
        "severity": "urgent",  # Escalated!
        "rule_id": "RULE_H2_CEILING",
        "observed_value": 160.0,
        "baseline_value": 70.0,
        "deviation": 90.0,
        "user_prefs": {},
        "user_timezone": "Asia/Kolkata",
        "is_quiet_hours": False,
        "alert_tier": 0,
        "channels": [],
        "is_deduplicated": True,  # Prior notification exists
        "is_escalation": True,    # But severity escalated!
        "delivery_decision": "",
        "title": "",
        "body": "",
        "requires_emergency_disclaimer": False,
        "dispatched": False,
        "error": None
    }

    result = await graph.ainvoke(state)
    assert result["delivery_decision"] == "DISPATCH"
    assert result["dispatched"] is True
