"""Deterministic Notification Policy Engine.

PHILOSOPHY & INVARIANTS:
1. The deterministic health pipeline owns: biological thresholds, anomaly detection,
   finding creation, severity, safety level, rule IDs, evidence, and notification eligibility.
2. The LLM may NEVER change, infer, override, or reinterpret these values.
3. Machine-readable 5-tier alert policy:
   - Level 0 (Info): Silent timeline entry only.
   - Level 1 (Insight): Staged for Daily Digest only; zero real-time interruption.
   - Level 2 (Attention): In-app notification feed; silent during quiet hours.
   - Level 3 (Important): In-app feed + FCM push; postponed during quiet hours.
   - Level 4 (Urgent): In-app feed + high-priority FCM push; STRICTLY OVERRIDES QUIET HOURS;
     mandatory emergency disclaimer attached.
"""

from dataclasses import dataclass
from enum import Enum, IntEnum
from typing import Any, Optional


class AlertTier(IntEnum):
    LEVEL_0_INFO = 0
    LEVEL_1_INSIGHT = 1
    LEVEL_2_ATTENTION = 2
    LEVEL_3_IMPORTANT = 3
    LEVEL_4_URGENT = 4


class DeliveryChannel(str, Enum):
    IN_APP = "in_app"
    FCM = "fcm"
    PUSH = "push"
    EMAIL = "email"
    WHATSAPP_FUTURE = "whatsapp_future"
    WEBSOCKET = "websocket"


NotificationChannel = DeliveryChannel


# Mandatory non-diagnostic emergency disclaimer for Level 4 Urgent alerts (Rule H1 / Safety Agent)
LEVEL_4_EMERGENCY_DISCLAIMER = (
    "SAFETY NOTICE: A significant physiological deviation was recorded. "
    "HealthAgent does not provide medical diagnoses. "
    "If you are experiencing acute chest pain, shortness of breath, dizziness, "
    "or severe distress, please seek emergency medical evaluation immediately."
)


@dataclass(frozen=True)
class NotificationPolicyResult:
    """Immutable deterministic evaluation result for an alert."""
    tier: AlertTier
    severity: str
    channels: list[DeliveryChannel]
    push_priority: str  # 'none', 'normal', 'high'
    fcm_channel_id: Optional[str]  # 'healthos_urgent', 'healthos_important', or None
    overrides_quiet_hours: bool
    requires_emergency_disclaimer: bool
    is_silent_timeline_only: bool
    is_digest_only: bool
    dedup_window_hours: int
    allow_escalation_bypass: bool


class NotificationPolicyEngine:
    """
    Deterministic Notification Policy Evaluator.
    Zero LLM involvement. Final authority on channel routing and quiet-hours override.
    """

    SEVERITY_TO_TIER: dict[str, AlertTier] = {
        "normal_variation": AlertTier.LEVEL_0_INFO,
        "info": AlertTier.LEVEL_0_INFO,
        "unusual": AlertTier.LEVEL_1_INSIGHT,
        "insight": AlertTier.LEVEL_1_INSIGHT,
        "worth_monitoring": AlertTier.LEVEL_2_ATTENTION,
        "attention": AlertTier.LEVEL_2_ATTENTION,
        "potentially_concerning": AlertTier.LEVEL_3_IMPORTANT,
        "important": AlertTier.LEVEL_3_IMPORTANT,
        "urgent": AlertTier.LEVEL_4_URGENT,
    }

    @classmethod
    def map_severity_to_tier(cls, severity: str) -> AlertTier:
        norm = severity.strip().lower()
        if norm in cls.SEVERITY_TO_TIER:
            return cls.SEVERITY_TO_TIER[norm]
        # Fallback to Attention if unrecognized non-empty string, fail safe
        return AlertTier.LEVEL_2_ATTENTION

    @classmethod
    def evaluate(
        cls,
        severity: str,
        rule_id: str,
        user_prefs: Optional[dict[str, Any]] = None,
        is_quiet_hours: bool = False
    ) -> NotificationPolicyResult:
        """
        Deterministically evaluates notification eligibility, channels, and delivery constraints.
        """
        tier = cls.map_severity_to_tier(severity)
        prefs = user_prefs or {}
        fcm_enabled = prefs.get("fcm_enabled", True)
        in_app_enabled = prefs.get("in_app_enabled", True)

        # Level 0: Info
        if tier == AlertTier.LEVEL_0_INFO:
            return NotificationPolicyResult(
                tier=tier,
                severity="normal_variation",
                channels=[],
                push_priority="none",
                fcm_channel_id=None,
                overrides_quiet_hours=False,
                requires_emergency_disclaimer=False,
                is_silent_timeline_only=True,
                is_digest_only=False,
                dedup_window_hours=12,
                allow_escalation_bypass=True,
            )

        # Level 1: Insight
        if tier == AlertTier.LEVEL_1_INSIGHT:
            return NotificationPolicyResult(
                tier=tier,
                severity="unusual",
                channels=[],
                push_priority="none",
                fcm_channel_id=None,
                overrides_quiet_hours=False,
                requires_emergency_disclaimer=False,
                is_silent_timeline_only=False,
                is_digest_only=True,
                dedup_window_hours=12,
                allow_escalation_bypass=True,
            )

        # Level 2: Attention
        if tier == AlertTier.LEVEL_2_ATTENTION:
            channels: list[DeliveryChannel] = []
            if in_app_enabled:
                channels.append(DeliveryChannel.IN_APP)
                channels.append(DeliveryChannel.WEBSOCKET)
            return NotificationPolicyResult(
                tier=tier,
                severity="worth_monitoring",
                channels=channels,
                push_priority="none",
                fcm_channel_id=None,
                overrides_quiet_hours=False,
                requires_emergency_disclaimer=False,
                is_silent_timeline_only=False,
                is_digest_only=False,
                dedup_window_hours=12,
                allow_escalation_bypass=True,
            )

        # Level 3: Important
        if tier == AlertTier.LEVEL_3_IMPORTANT:
            channels = []
            if in_app_enabled:
                channels.append(DeliveryChannel.IN_APP)
                channels.append(DeliveryChannel.WEBSOCKET)
            # Push is delivered if FCM is enabled and NOT quiet hours (or held during quiet hours)
            if fcm_enabled and not is_quiet_hours:
                channels.append(DeliveryChannel.FCM)

            return NotificationPolicyResult(
                tier=tier,
                severity="potentially_concerning",
                channels=channels,
                push_priority="normal",
                fcm_channel_id="healthos_important",
                overrides_quiet_hours=False,
                requires_emergency_disclaimer=False,
                is_silent_timeline_only=False,
                is_digest_only=False,
                dedup_window_hours=12,
                allow_escalation_bypass=True,
            )

        # Level 4: Urgent — CRITICAL SAFETY TIER
        # Quiet hours are strictly overridden.
        # FCM push is mandatory (cannot be disabled by normal preferences).
        channels = [DeliveryChannel.IN_APP, DeliveryChannel.WEBSOCKET, DeliveryChannel.FCM]
        return NotificationPolicyResult(
            tier=tier,
            severity="urgent",
            channels=channels,
            push_priority="high",
            fcm_channel_id="healthos_urgent",
            overrides_quiet_hours=True,
            requires_emergency_disclaimer=True,
            is_silent_timeline_only=False,
            is_digest_only=False,
            dedup_window_hours=12,
            allow_escalation_bypass=True,
        )
