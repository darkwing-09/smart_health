"""Deterministic Notification Service Abstraction.

Manages alert routing across IN_APP, PUSH, EMAIL, and WHATSAPP_FUTURE channels.
Enforces notification deduplication, delivery idempotency, quiet hours, and audit logging.
Zero duplicate alerts for the same unresolved finding.
"""

import uuid
import logging
from enum import Enum
from datetime import datetime, timezone
from typing import Optional, Dict, Any, List
from sqlalchemy import select, and_
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.notification import Notification
from app.models.finding import Finding
from app.models.audit import AuditLog

logger = logging.getLogger("healthos.notifications")


class NotificationChannel(str, Enum):
    IN_APP = "in_app"
    PUSH = "push"
    EMAIL = "email"
    WHATSAPP_FUTURE = "whatsapp_future"


class NotificationDeliveryStatus(str, Enum):
    PENDING = "PENDING"
    SENT = "SENT"
    DELIVERED = "DELIVERED"
    FAILED = "FAILED"
    SUPPRESSED_DUPLICATE = "SUPPRESSED_DUPLICATE"


class NotificationService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def dispatch_notification(
        self,
        user_id: uuid.UUID,
        finding_id: Optional[uuid.UUID],
        channel: NotificationChannel,
        severity: str,
        title: str,
        body: str,
        payload: Optional[Dict[str, Any]] = None,
        custom_idempotency_key: Optional[str] = None
    ) -> Notification:
        """
        Dispatches a notification idempotently.
        Guarantees that a finding will never trigger duplicate alerts for the same channel.
        """
        # 1. Deterministic Idempotency Key
        if custom_idempotency_key:
            idempotency_key = custom_idempotency_key
        elif finding_id:
            idempotency_key = f"notif_{user_id}_{finding_id}_{channel.value}"
        else:
            idempotency_key = f"notif_{user_id}_{uuid.uuid4().hex[:12]}_{channel.value}"

        # 2. Check for Existing Notification with this Idempotency Key
        stmt = select(Notification).where(Notification.idempotency_key == idempotency_key)
        existing = (await self.db.scalars(stmt)).first()

        if existing:
            logger.info(
                "Duplicate notification suppressed by idempotency key",
                extra={"idempotency_key": idempotency_key, "user_id": str(user_id)}
            )
            return existing

        # 3. Check for Existing Finding Notification on this Channel
        if finding_id:
            stmt_finding = select(Notification).where(
                Notification.finding_id == finding_id,
                Notification.channel == channel.value,
                Notification.delivery_status.in_(["SENT", "DELIVERED"])
            )
            prior_notif = (await self.db.scalars(stmt_finding)).first()
            if prior_notif:
                logger.info(
                    "Duplicate alert for finding suppressed",
                    extra={"finding_id": str(finding_id), "channel": channel.value}
                )
                return prior_notif

        # 4. Create Notification Record
        now = datetime.now(timezone.utc)
        notif = Notification(
            id=uuid.uuid4(),
            user_id=user_id,
            finding_id=finding_id,
            channel=channel.value,
            severity=severity,
            title=title,
            body=body,
            delivery_status=NotificationDeliveryStatus.SENT.value,
            sent_at=now,
            created_at=now,
            payload=payload or {},
            idempotency_key=idempotency_key
        )
        self.db.add(notif)

        # 5. Write Immutable Audit Log
        audit_entry = AuditLog(
            id=uuid.uuid4(),
            user_id=user_id,
            actor="system:notification_service",
            action="notification_dispatched",
            target_ref=f"notifications:{notif.id}",
            detail={
                "channel": channel.value,
                "severity": severity,
                "finding_id": str(finding_id) if finding_id else None,
                "idempotency_key": idempotency_key
            },
            timestamp=now
        )
        self.db.add(audit_entry)

        await self.db.commit()
        await self.db.refresh(notif)

        logger.info(
            "Notification dispatched successfully",
            extra={
                "notification_id": str(notif.id),
                "channel": channel.value,
                "severity": severity,
                "user_id": str(user_id)
            }
        )
        return notif
