"""Deterministic Notification Service Abstraction (Agent 6: Notification Agent).

Manages alert routing across IN_APP, PUSH (FCM), and WEBSOCKET real-time channels.
Enforces notification deduplication, delivery idempotency, quiet hours, escalation bypass,
authoritative state machine transitions, and audit logging.

SAFETY INVARIANTS:
1. Finding severity originates strictly from the deterministic health pipeline.
2. Level 4 Urgent alerts override quiet hours and cannot be suppressed by preferences.
3. 12-hour deduplication window prevents notification fatigue while guaranteeing
   immediate bypass on severity escalation (e.g. Level 2 -> Level 4).
"""

import logging
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Optional
from sqlalchemy import and_, desc, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.audit import AuditLog
from app.models.device import Device
from app.models.finding import Finding
from app.models.notification import Notification
from app.services.connection_manager import ws_manager
from app.services.fcm import FcmNotificationService
from app.services.notification_metrics import metrics
from app.services.notification_policy import (
    LEVEL_4_EMERGENCY_DISCLAIMER,
    AlertTier,
    DeliveryChannel,
    NotificationChannel,
    NotificationPolicyEngine,
    NotificationPolicyResult,
)
from app.services.notification_state_machine import (
    NotificationState,
    NotificationStateMachine,
)
from app.services.quiet_hours import QuietHoursEvaluator

logger = logging.getLogger("healthos.notifications")


class NotificationService:
    def __init__(self, db: AsyncSession, redis_client: Optional[Any] = None) -> None:
        self.db = db
        self.redis = redis_client
        self.fcm_service = FcmNotificationService(db=db)

    async def dispatch_notification(
        self,
        user_id: uuid.UUID,
        finding_id: Optional[uuid.UUID],
        channel: Any,
        severity: str,
        title: str,
        body: str,
        payload: Optional[dict[str, Any]] = None,
        custom_idempotency_key: Optional[str] = None
    ) -> Notification:
        """Dispatches notification idempotently for backwards compatibility with Phase 4."""
        ch_str = channel.value if hasattr(channel, "value") else str(channel)
        if custom_idempotency_key:
            idempotency_key = custom_idempotency_key
        elif finding_id:
            idempotency_key = f"notif_{user_id}_{finding_id}_{ch_str}"
        else:
            idempotency_key = f"notif_{user_id}_{uuid.uuid4().hex[:12]}_{ch_str}"

        stmt = select(Notification).where(Notification.idempotency_key == idempotency_key)
        existing = (await self.db.scalars(stmt)).first()
        if existing:
            return existing

        if finding_id:
            stmt_finding = select(Notification).where(
                Notification.finding_id == finding_id,
                Notification.channel == ch_str,
                Notification.delivery_status.in_(["SENT", "DELIVERED"])
            )
            prior_notif = (await self.db.scalars(stmt_finding)).first()
            if prior_notif:
                return prior_notif

        now = datetime.now(timezone.utc)
        notif = Notification(
            id=uuid.uuid4(),
            user_id=user_id,
            finding_id=finding_id,
            channel=ch_str,
            severity=severity,
            title=title,
            body=body,
            delivery_status="SENT",
            state=NotificationState.DELIVERED.value,
            sent_at=now,
            delivered_at=now,
            created_at=now,
            payload=payload or {},
            idempotency_key=idempotency_key
        )
        self.db.add(notif)
        audit_entry = AuditLog(
            id=uuid.uuid4(),
            user_id=user_id,
            actor="system:notification_service",
            action="notification_dispatched",
            target_ref=f"notifications:{notif.id}",
            detail={
                "channel": ch_str,
                "severity": severity,
                "finding_id": str(finding_id) if finding_id else None,
                "idempotency_key": idempotency_key
            },
            timestamp=now
        )
        self.db.add(audit_entry)
        await self.db.commit()
        await self.db.refresh(notif)
        return notif

    async def check_deduplication(
        self,
        user_id: uuid.UUID,
        finding_id: Optional[uuid.UUID],
        channel: DeliveryChannel,
        current_severity: str,
        dedup_window_hours: int = 12
    ) -> tuple[bool, bool, Optional[Notification]]:
        """
        Atomic deduplication evaluation with severity escalation bypass.

        Returns:
            Tuple of:
            - is_suppressed: bool (True if duplicate alert should be suppressed)
            - is_escalation: bool (True if previous notification was lower severity and is now escalated)
            - prior_notification: Optional[Notification]
        """
        if not finding_id:
            return False, False, None

        cutoff = datetime.now(timezone.utc) - timedelta(hours=dedup_window_hours)
        stmt = (
            select(Notification)
            .where(
                Notification.user_id == user_id,
                Notification.finding_id == finding_id,
                Notification.channel == channel.value,
                Notification.created_at >= cutoff,
                Notification.state.in_([
                    NotificationState.QUEUED.value,
                    NotificationState.DISPATCHING.value,
                    NotificationState.DELIVERED.value,
                    NotificationState.ACKNOWLEDGED.value,
                ])
            )
            .order_by(desc(Notification.created_at))
        )
        prior_notif = (await self.db.scalars(stmt)).first()

        if not prior_notif:
            return False, False, None

        current_tier = NotificationPolicyEngine.map_severity_to_tier(current_severity)
        prior_tier = NotificationPolicyEngine.map_severity_to_tier(prior_notif.severity)

        # Severity Escalation Bypass: If new alert is higher severity than prior alert, allow dispatch!
        if current_tier > prior_tier:
            logger.info(
                f"Deduplication bypassed due to severity escalation: Tier {prior_tier} -> Tier {current_tier}",
                extra={
                    "user_id": str(user_id),
                    "finding_id": str(finding_id),
                    "prior_severity": prior_notif.severity,
                    "new_severity": current_severity,
                }
            )
            return False, True, prior_notif

        # Duplicate detected with equal or lower severity within 12h -> Suppress
        logger.info(
            f"Notification suppressed by 12h deduplication window for finding {finding_id} ({channel.value})",
            extra={"user_id": str(user_id), "finding_id": str(finding_id)}
        )
        metrics.record_dedup(str(user_id), str(finding_id))
        return True, False, prior_notif

    async def dispatch_finding_alert(
        self,
        user_id: uuid.UUID,
        finding: Finding,
        user_timezone: Optional[str] = None,
        user_prefs: Optional[dict[str, Any]] = None,
        custom_title: Optional[str] = None,
        custom_body: Optional[str] = None
    ) -> Optional[Notification]:
        """
        Primary entry point for dispatching alerts generated by the deterministic finding layer.
        Executes policy, quiet-hours evaluation, deduplication, state transitions, and channel delivery.
        """
        now = datetime.now(timezone.utc)
        severity = finding.severity
        rule_id = finding.rule_id

        # 1. Quiet Hours Check
        is_quiet, release_time_utc = QuietHoursEvaluator.evaluate(
            user_timezone=user_timezone or finding.timezone or "Asia/Kolkata",
            quiet_start_str=user_prefs.get("quiet_hours_start") if user_prefs else "22:00",
            quiet_end_str=user_prefs.get("quiet_hours_end") if user_prefs else "07:00",
            current_time_utc=now
        )

        # 2. Policy Evaluation
        policy: NotificationPolicyResult = NotificationPolicyEngine.evaluate(
            severity=severity,
            rule_id=rule_id,
            user_prefs=user_prefs,
            is_quiet_hours=is_quiet
        )

        # Level 0 (Silent timeline only) or Level 1 (Daily digest only): zero real-time notification
        if policy.is_silent_timeline_only or policy.is_digest_only:
            logger.info(f"Finding {finding.id} (Tier {policy.tier}) requires no real-time notification.")
            return None

        # 3. Deduplication Check (against in-app / primary channel)
        primary_channel = DeliveryChannel.IN_APP
        is_suppressed, is_escalation, prior_notif = await self.check_deduplication(
            user_id=user_id,
            finding_id=finding.id,
            channel=primary_channel,
            current_severity=severity,
            dedup_window_hours=policy.dedup_window_hours
        )

        if is_suppressed and not is_escalation:
            return prior_notif

        # 4. Generate Calm, Grounded Plain-Language Message (Deterministic Fallback)
        title, body = self._generate_deterministic_content(finding, policy, custom_title, custom_body)

        # Determine if notification should be held during quiet hours
        # Level 4 NEVER gets held. Levels 2 & 3 get stored in in-app with quiet_hours_held=True.
        should_hold = is_quiet and not policy.overrides_quiet_hours

        # 5. Create Notification Record with State Machine
        window_bucket = int(now.timestamp() // (policy.dedup_window_hours * 3600))
        idempotency_key = (
            f"notif_{user_id}_{finding.id}_{severity}_{int(now.timestamp())}"
            if is_escalation else
            f"notif_{user_id}_{finding.id}_{primary_channel.value}_{window_bucket}"
        )

        notif = Notification(
            id=uuid.uuid4(),
            user_id=user_id,
            finding_id=finding.id,
            channel=primary_channel.value,
            severity=severity,
            title=title,
            body=body,
            state=NotificationState.CREATED.value,
            delivery_status="PENDING",
            quiet_hours_held=should_hold,
            next_retry_at=release_time_utc if should_hold else None,
            sent_at=now,
            created_at=now,
            idempotency_key=idempotency_key,
            payload={
                "alert_tier": int(policy.tier),
                "metric_type": finding.metric_type,
                "observed_value": finding.observed_value,
                "baseline_value": finding.baseline_value,
                "rule_id": finding.rule_id,
                "is_escalation": is_escalation,
                "quiet_hours_held": should_hold,
                "release_time_utc": release_time_utc.isoformat() if should_hold else None,
            }
        )
        try:
            self.db.add(notif)
            await self.db.flush()
        except IntegrityError:
            await self.db.rollback()
            stmt_exist = select(Notification).where(Notification.idempotency_key == idempotency_key)
            existing_notif = (await self.db.scalars(stmt_exist)).first()
            if existing_notif:
                logger.info(f"Concurrent race condition handled: returning existing notification {existing_notif.id}")
                return existing_notif
            raise

        # State transition: CREATED -> POLICY_EVALUATED -> DEDUP_CHECKED -> QUEUED -> DISPATCHING
        NotificationStateMachine.validate_transition(notif.state, NotificationState.POLICY_EVALUATED)
        notif.state = NotificationState.POLICY_EVALUATED.value

        NotificationStateMachine.validate_transition(notif.state, NotificationState.DEDUP_CHECKED)
        notif.state = NotificationState.DEDUP_CHECKED.value

        NotificationStateMachine.validate_transition(notif.state, NotificationState.QUEUED)
        notif.state = NotificationState.QUEUED.value

        NotificationStateMachine.validate_transition(notif.state, NotificationState.DISPATCHING)
        notif.state = NotificationState.DISPATCHING.value

        # 6. Channel Dispatch
        # A. Real-Time WebSocket broadcast
        await ws_manager.broadcast_event(
            user_id=user_id,
            event_type="notification.dispatched",
            data={
                "notification_id": str(notif.id),
                "finding_id": str(finding.id),
                "severity": severity,
                "alert_tier": int(policy.tier),
                "title": title,
                "body": body,
                "quiet_hours_held": should_hold,
                "created_at": now.isoformat()
            }
        )

        # B. FCM Push Dispatch (if enabled for channel and not held)
        if DeliveryChannel.FCM in policy.channels and not should_hold:
            stmt_token = (
                select(Device.fcm_token)
                .where(Device.user_id == user_id, Device.fcm_token.is_not(None))
                .order_by(desc(Device.last_seen_at))
            )
            fcm_token = (await self.db.scalars(stmt_token)).first()
            if fcm_token:
                try:
                    await self.fcm_service.dispatch(
                        fcm_token=fcm_token,
                        title=title,
                        body=body,
                        notification_id=notif.id,
                        user_id=user_id,
                        finding_id=finding.id,
                        severity=severity,
                        alert_tier=int(policy.tier)
                    )
                except Exception as e:
                    logger.error(f"FCM push dispatch failed (non-fatal for in-app delivery): {e}")

        # 7. Finalize State -> DELIVERED
        NotificationStateMachine.validate_transition(notif.state, NotificationState.DELIVERED)
        notif.state = NotificationState.DELIVERED.value
        notif.delivery_status = "SENT"
        notif.delivered_at = now

        # Update Finding status to 'notified' or 'escalated'
        finding.status = "escalated" if is_escalation else "notified"

        # 8. Immutable Audit Log
        audit = AuditLog(
            id=uuid.uuid4(),
            user_id=user_id,
            actor="system:notification_service",
            action="notification_dispatched",
            target_ref=f"notifications:{notif.id}",
            detail={
                "finding_id": str(finding.id),
                "severity": severity,
                "tier": int(policy.tier),
                "channels": [c.value for c in policy.channels],
                "quiet_hours_held": should_hold,
                "is_escalation": is_escalation,
                "state": notif.state
            },
            timestamp=now
        )
        self.db.add(audit)

        await self.db.commit()
        await self.db.refresh(notif)

        metrics.record_dispatch(
            user_id=str(user_id),
            tier=int(policy.tier),
            severity=severity,
            held_quiet_hours=should_hold,
            escalated=is_escalation
        )

        logger.info(
            f"Notification {notif.id} successfully delivered for finding {finding.id} (Tier {policy.tier})",
            extra={"user_id": str(user_id), "state": notif.state}
        )
        return notif

    def _generate_deterministic_content(
        self,
        finding: Finding,
        policy: NotificationPolicyResult,
        custom_title: Optional[str] = None,
        custom_body: Optional[str] = None
    ) -> tuple[str, str]:
        """Generates grounded, non-alarmist notification content with mandatory disclaimers."""
        if custom_title and custom_body:
            title = custom_title
            body = custom_body
        elif policy.tier == AlertTier.LEVEL_4_URGENT:
            val_str = f" ({finding.observed_value:.0f} bpm)" if finding.observed_value else ""
            title = "Urgent Physiological Observation"
            body = f"A significant resting deviation was observed{val_str}. Tap to review details."
        elif policy.tier == AlertTier.LEVEL_3_IMPORTANT:
            val_str = f" ({finding.observed_value:.0f} bpm)" if finding.observed_value else ""
            title = "Notable Baseline Deviation"
            body = f"An unusual {finding.metric_type.replace('_', ' ')} reading was observed{val_str}."
        else:
            title = "Health Trend Update"
            body = f"A mild variation in your {finding.metric_type.replace('_', ' ')} was observed."

        # Attach emergency disclaimer for Level 4
        if policy.requires_emergency_disclaimer:
            body = f"{body}\n\n{LEVEL_4_EMERGENCY_DISCLAIMER}"

        return title, body

    async def get_user_notifications(
        self,
        user_id: uuid.UUID,
        limit: int = 50,
        offset: int = 0,
        unread_only: bool = False,
        min_tier: Optional[int] = None
    ) -> list[Notification]:
        """Queries paginated notification history with strict multi-tenant isolation."""
        stmt = (
            select(Notification)
            .where(Notification.user_id == user_id)
            .order_by(desc(Notification.created_at))
            .limit(min(limit, 100))
            .offset(offset)
        )
        if unread_only:
            stmt = stmt.where(Notification.acknowledged_at.is_(None))

        return list((await self.db.scalars(stmt)).all())

    async def get_notification_by_id(
        self,
        user_id: uuid.UUID,
        notification_id: uuid.UUID
    ) -> Optional[Notification]:
        """Retrieves a single notification enforcing tenant isolation."""
        stmt = select(Notification).where(
            Notification.id == notification_id,
            Notification.user_id == user_id
        )
        return (await self.db.scalars(stmt)).first()

    async def acknowledge_notification(
        self,
        user_id: uuid.UUID,
        notification_id: uuid.UUID
    ) -> Optional[Notification]:
        """Acknowledges a notification and transitions associated Finding state."""
        notif = await self.get_notification_by_id(user_id, notification_id)
        if not notif:
            return None

        now = datetime.now(timezone.utc)
        NotificationStateMachine.validate_transition(notif.state, NotificationState.ACKNOWLEDGED)
        notif.state = NotificationState.ACKNOWLEDGED.value
        notif.acknowledged_at = now

        # Also acknowledge underlying finding if present
        if notif.finding_id:
            stmt_f = select(Finding).where(Finding.id == notif.finding_id)
            finding = (await self.db.scalars(stmt_f)).first()
            if finding and finding.status in ["new", "notified", "escalated"]:
                finding.status = "acknowledged"

        # Broadcast update over WebSocket
        await ws_manager.broadcast_event(
            user_id=user_id,
            event_type="notification.updated",
            data={"notification_id": str(notif.id), "state": notif.state, "acknowledged_at": now.isoformat()}
        )

        await self.db.commit()
        await self.db.refresh(notif)
        return notif

    async def dismiss_notification(
        self,
        user_id: uuid.UUID,
        notification_id: uuid.UUID
    ) -> Optional[Notification]:
        """Dismisses a notification."""
        notif = await self.get_notification_by_id(user_id, notification_id)
        if not notif:
            return None

        now = datetime.now(timezone.utc)
        NotificationStateMachine.validate_transition(notif.state, NotificationState.DISMISSED)
        notif.state = NotificationState.DISMISSED.value
        notif.dismissed_at = now

        await ws_manager.broadcast_event(
            user_id=user_id,
            event_type="notification.updated",
            data={"notification_id": str(notif.id), "state": notif.state, "dismissed_at": now.isoformat()}
        )

        await self.db.commit()
        await self.db.refresh(notif)
        return notif

    async def expire_notification(
        self,
        user_id: uuid.UUID,
        notification_id: uuid.UUID
    ) -> Optional[Notification]:
        """Transitions an unacknowledged/stale notification to EXPIRED."""
        notif = await self.get_notification_by_id(user_id, notification_id)
        if not notif:
            return None

        NotificationStateMachine.validate_transition(notif.state, NotificationState.EXPIRED)
        notif.state = NotificationState.EXPIRED.value
        notif.expires_at = datetime.now(timezone.utc)

        await self.db.commit()
        await self.db.refresh(notif)
        return notif

    async def release_held_quiet_hour_notifications(self) -> int:
        """
        Background cadence job: releases notifications that were held during quiet hours
        once the user's local quiet hours have concluded.
        """
        now = datetime.now(timezone.utc)
        stmt = (
            select(Notification)
            .where(
                Notification.quiet_hours_held.is_(True),
                Notification.next_retry_at <= now,
                Notification.state == NotificationState.DELIVERED.value
            )
        )
        held_notifs = (await self.db.scalars(stmt)).all()
        released_count = 0

        for notif in held_notifs:
            notif.quiet_hours_held = False
            # Dispatch FCM push now that quiet hours have ended
            stmt_token = (
                select(Device.fcm_token)
                .where(Device.user_id == notif.user_id, Device.fcm_token.is_not(None))
                .order_by(desc(Device.last_seen_at))
            )
            fcm_token = (await self.db.scalars(stmt_token)).first()
            if fcm_token:
                await self.fcm_service.dispatch(
                    fcm_token=fcm_token,
                    title=notif.title,
                    body=notif.body,
                    notification_id=notif.id,
                    user_id=notif.user_id,
                    finding_id=notif.finding_id,
                    severity=notif.severity
                )
            released_count += 1

        if released_count > 0:
            await self.db.commit()
            logger.info(f"Released {released_count} notifications from quiet hours hold.")

        return released_count
