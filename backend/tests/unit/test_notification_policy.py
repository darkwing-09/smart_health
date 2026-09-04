"""Unit tests for deterministic 5-level notification policy and quiet hours."""

from datetime import datetime, time, timezone
import pytest

from app.services.notification_policy import (
    AlertTier,
    DeliveryChannel,
    LEVEL_4_EMERGENCY_DISCLAIMER,
    NotificationPolicyEngine,
    NotificationPolicyResult,
)
from app.services.quiet_hours import QuietHoursEvaluator


def test_alert_tier_deterministic_mapping():
    """Verifies that finding severities map deterministically to the 5 alert tiers."""
    assert NotificationPolicyEngine.map_severity_to_tier("normal_variation") == AlertTier.LEVEL_0_INFO
    assert NotificationPolicyEngine.map_severity_to_tier("unusual") == AlertTier.LEVEL_1_INSIGHT
    assert NotificationPolicyEngine.map_severity_to_tier("worth_monitoring") == AlertTier.LEVEL_2_ATTENTION
    assert NotificationPolicyEngine.map_severity_to_tier("potentially_concerning") == AlertTier.LEVEL_3_IMPORTANT
    assert NotificationPolicyEngine.map_severity_to_tier("urgent") == AlertTier.LEVEL_4_URGENT


def test_level_0_info_policy():
    """Level 0 Info is silent timeline only."""
    res = NotificationPolicyEngine.evaluate(severity="normal_variation", rule_id="RULE_NOMINAL")
    assert res.tier == AlertTier.LEVEL_0_INFO
    assert res.is_silent_timeline_only is True
    assert len(res.channels) == 0
    assert res.push_priority == "none"


def test_level_1_insight_policy():
    """Level 1 Insight is daily digest only; zero real-time interruption."""
    res = NotificationPolicyEngine.evaluate(severity="unusual", rule_id="RULE_STAT_DRIFT")
    assert res.tier == AlertTier.LEVEL_1_INSIGHT
    assert res.is_digest_only is True
    assert len(res.channels) == 0


def test_level_2_attention_policy():
    """Level 2 Attention delivers to in-app feed, without FCM push by default."""
    res = NotificationPolicyEngine.evaluate(severity="worth_monitoring", rule_id="RULE_Z_SCORE_28")
    assert res.tier == AlertTier.LEVEL_2_ATTENTION
    assert DeliveryChannel.IN_APP in res.channels
    assert DeliveryChannel.FCM not in res.channels
    assert res.push_priority == "none"


def test_level_3_important_policy():
    """Level 3 Important delivers to in-app feed and normal priority FCM push."""
    res = NotificationPolicyEngine.evaluate(
        severity="potentially_concerning",
        rule_id="RULE_Z_SCORE_38",
        user_prefs={"fcm_enabled": True},
        is_quiet_hours=False
    )
    assert res.tier == AlertTier.LEVEL_3_IMPORTANT
    assert DeliveryChannel.IN_APP in res.channels
    assert DeliveryChannel.FCM in res.channels
    assert res.push_priority == "normal"
    assert res.fcm_channel_id == "healthos_important"
    assert res.overrides_quiet_hours is False


def test_level_4_urgent_critical_safety_policy():
    """Level 4 Urgent strictly overrides quiet hours and attaches emergency disclaimer."""
    res = NotificationPolicyEngine.evaluate(
        severity="urgent",
        rule_id="RULE_H2_CEILING",
        user_prefs={"fcm_enabled": False},  # Even if user tried to disable FCM, Level 4 overrides!
        is_quiet_hours=True
    )
    assert res.tier == AlertTier.LEVEL_4_URGENT
    assert DeliveryChannel.FCM in res.channels
    assert res.push_priority == "high"
    assert res.fcm_channel_id == "healthos_urgent"
    assert res.overrides_quiet_hours is True
    assert res.requires_emergency_disclaimer is True


def test_quiet_hours_timezone_conversion():
    """Verifies that quiet hours are computed in local user time, not UTC."""
    # 23:30 in India is 18:00 UTC (same day)
    utc_time = datetime(2026, 9, 4, 18, 0, tzinfo=timezone.utc)
    is_quiet, release_utc = QuietHoursEvaluator.evaluate(
        user_timezone="Asia/Kolkata",
        quiet_start_str="22:00",
        quiet_end_str="07:00",
        current_time_utc=utc_time
    )
    # In Kolkata it is 23:30 -> Quiet hours active!
    assert is_quiet is True
    # In New York (EDT, UTC-4), 18:00 UTC is 14:00 (2 PM) -> Quiet hours NOT active!
    is_quiet_ny, _ = QuietHoursEvaluator.evaluate(
        user_timezone="America/New_York",
        quiet_start_str="22:00",
        quiet_end_str="07:00",
        current_time_utc=utc_time
    )
    assert is_quiet_ny is False
