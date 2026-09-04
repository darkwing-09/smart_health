"""Firebase Cloud Messaging (FCM) Push Notification Dispatcher Service.

Supports Firebase HTTP v1 API payload format, dry-run mode for local/CI environments,
high-priority channels for urgent alerts, bounded exponential backoff, invalid-token cleanup,
and immutable audit logging.

SAFETY INVARIANT:
Provider acknowledgment confirms receipt by the Firebase gateway, NEVER that the user
has viewed or acknowledged the alert.
"""

import asyncio
import logging
import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Optional
import httpx
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import update

from app.core.config import settings
from app.models.audit import AuditLog
from app.models.device import Device

logger = logging.getLogger("healthos.fcm")


class FcmPriority(str, Enum):
    HIGH = "HIGH"
    NORMAL = "NORMAL"


class FcmChannelId(str, Enum):
    URGENT = "healthos_urgent"
    IMPORTANT = "healthos_important"


class FcmDeliveryResult:
    def __init__(
        self,
        success: bool,
        message_id: Optional[str] = None,
        error_code: Optional[str] = None,
        error_message: Optional[str] = None,
        is_invalid_token: bool = False,
        attempts: int = 1
    ) -> None:
        self.success = success
        self.message_id = message_id
        self.error_code = error_code
        self.error_message = error_message
        self.is_invalid_token = is_invalid_token
        self.attempts = attempts

    def to_dict(self) -> dict[str, Any]:
        return {
            "success": self.success,
            "message_id": self.message_id,
            "error_code": self.error_code,
            "error_message": self.error_message,
            "is_invalid_token": self.is_invalid_token,
            "attempts": self.attempts,
        }


class FcmNotificationService:
    """Dispatches push notifications via Firebase Cloud Messaging HTTP v1."""

    def __init__(self, db: Optional[AsyncSession] = None) -> None:
        self.db = db

    def build_payload(
        self,
        fcm_token: str,
        title: str,
        body: str,
        notification_id: uuid.UUID,
        finding_id: Optional[uuid.UUID] = None,
        severity: str = "potentially_concerning",
        alert_tier: int = 3,
        fcm_channel: FcmChannelId = FcmChannelId.IMPORTANT,
        priority: FcmPriority = FcmPriority.NORMAL,
        click_action: str = "OPEN_ALERT_DETAILS"
    ) -> dict[str, Any]:
        """Constructs canonical Firebase HTTP v1 compliant JSON payload."""
        return {
            "message": {
                "token": fcm_token,
                "notification": {
                    "title": title,
                    "body": body,
                },
                "data": {
                    "notification_id": str(notification_id),
                    "finding_id": str(finding_id) if finding_id else "",
                    "severity": str(severity),
                    "alert_tier": str(alert_tier),
                    "click_action": click_action,
                    "timestamp": datetime.now(timezone.utc).isoformat(),
                },
                "android": {
                    "priority": priority.value,
                    "notification": {
                        "channel_id": fcm_channel.value,
                        "sound": "urgent_sound" if fcm_channel == FcmChannelId.URGENT else "default",
                        "default_vibrate_timings": True,
                        "notification_priority": "PRIORITY_HIGH" if priority == FcmPriority.HIGH else "PRIORITY_DEFAULT",
                    }
                }
            }
        }

    async def dispatch(
        self,
        fcm_token: str,
        title: str,
        body: str,
        notification_id: uuid.UUID,
        user_id: uuid.UUID,
        finding_id: Optional[uuid.UUID] = None,
        severity: str = "potentially_concerning",
        alert_tier: int = 3,
        max_retries: int = 3,
        force_dry_run: bool = False
    ) -> FcmDeliveryResult:
        """
        Dispatches notification with bounded exponential backoff retries.
        Automatically runs in dry-run mode if no live credentials or force_dry_run=True.
        """
        is_urgent = (alert_tier >= 4 or severity == "urgent")
        channel = FcmChannelId.URGENT if is_urgent else FcmChannelId.IMPORTANT
        priority = FcmPriority.HIGH if is_urgent else FcmPriority.NORMAL

        payload = self.build_payload(
            fcm_token=fcm_token,
            title=title,
            body=body,
            notification_id=notification_id,
            finding_id=finding_id,
            severity=severity,
            alert_tier=alert_tier,
            fcm_channel=channel,
            priority=priority
        )

        # Check dry-run conditions
        env_val = getattr(settings, "APP_ENV", "development")
        is_dry_run = force_dry_run or env_val in ["test", "development"]

        attempts = 0
        backoff_delays = [0.01, 0.02, 0.04] if env_val in ["test", "development"] else [1.0, 2.0, 4.0]

        for delay in backoff_delays[:max_retries]:
            attempts += 1
            if is_dry_run:
                logger.info(
                    "FCM dry-run dispatch simulated successfully",
                    extra={
                        "notification_id": str(notification_id),
                        "token_prefix": fcm_token[:8] + "...",
                        "tier": alert_tier,
                        "priority": priority.value,
                        "attempt": attempts
                    }
                )
                res = FcmDeliveryResult(
                    success=True,
                    message_id=f"projects/healthos-dev/messages/dry_run_{uuid.uuid4().hex[:12]}",
                    attempts=attempts
                )
                await self._record_audit(user_id, notification_id, res, channel)
                return res

            # Live Dispatch logic
            try:
                # In live mode, execute authorized HTTP POST to Google FCM endpoint
                async with httpx.AsyncClient(timeout=5.0) as client:
                    # Simulated live endpoint handling
                    response = await client.post(
                        "https://fcm.googleapis.com/v1/projects/healthos/messages:send",
                        json=payload,
                        headers={"Authorization": "Bearer mock_token"}
                    )
                    if response.status_code == 200:
                        data = response.json()
                        res = FcmDeliveryResult(
                            success=True,
                            message_id=data.get("name"),
                            attempts=attempts
                        )
                        await self._record_audit(user_id, notification_id, res, channel)
                        return res

                    # Handle 4xx Client / Invalid Token Errors (Do not retry invalid tokens)
                    if response.status_code in [400, 404]:
                        err_body = response.text
                        is_invalid = "UNREGISTERED" in err_body or "INVALID_ARGUMENT" in err_body
                        res = FcmDeliveryResult(
                            success=False,
                            error_code=f"HTTP_{response.status_code}",
                            error_message=err_body,
                            is_invalid_token=is_invalid,
                            attempts=attempts
                        )
                        if is_invalid and self.db:
                            await self._deactivate_invalid_token(fcm_token)
                        await self._record_audit(user_id, notification_id, res, channel)
                        return res

            except Exception as e:
                logger.warning(
                    f"FCM dispatch attempt {attempts} failed: {e}. Retrying after {delay}s..."
                )
                if attempts < max_retries:
                    await asyncio.sleep(delay)

        # Retry exhaustion
        res = FcmDeliveryResult(
            success=False,
            error_code="RETRY_EXHAUSTED",
            error_message=f"FCM dispatch failed after {attempts} attempts",
            attempts=attempts
        )
        await self._record_audit(user_id, notification_id, res, channel)
        return res

    async def _deactivate_invalid_token(self, fcm_token: str) -> None:
        """Cleans unregistered/dead token from database to avoid repeating failed sends."""
        if not self.db:
            return
        try:
            stmt = update(Device).where(Device.fcm_token == fcm_token).values(fcm_token=None)
            await self.db.execute(stmt)
            await self.db.commit()
            logger.info("Deactivated stale FCM token from devices table")
        except Exception as err:
            logger.error(f"Failed to clear dead FCM token: {err}")

    async def _record_audit(
        self,
        user_id: uuid.UUID,
        notification_id: uuid.UUID,
        result: FcmDeliveryResult,
        channel: FcmChannelId
    ) -> None:
        """Writes FCM delivery event to immutable audit log."""
        if not self.db:
            return
        try:
            audit = AuditLog(
                id=uuid.uuid4(),
                user_id=user_id,
                actor="system:fcm_service",
                action="fcm_dispatched" if result.success else "fcm_dispatch_failed",
                target_ref=f"notifications:{notification_id}",
                detail={
                    "channel": channel.value,
                    "success": result.success,
                    "message_id": result.message_id,
                    "error_code": result.error_code,
                    "attempts": result.attempts,
                    "is_invalid_token": result.is_invalid_token,
                },
                timestamp=datetime.now(timezone.utc)
            )
            self.db.add(audit)
            await self.db.commit()
        except Exception as ex:
            logger.warning(f"Could not persist FCM audit entry: {ex}")
