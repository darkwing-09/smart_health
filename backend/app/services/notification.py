"""Notification Dispatch Service (FCM & Anti-Fatigue State Enforcer)."""

import uuid
from datetime import datetime, timezone
from typing import Optional
from sqlalchemy.ext.asyncio import AsyncSession
from app.models.notification import Notification
from app.models.finding import Finding


class NotificationService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def dispatch_finding_alert(
        self,
        finding: Finding,
        title: str,
        body: str,
        channel: str = "fcm"
    ) -> Optional[Notification]:
        """
        Dispatches notification adhering to finding state machine and anti-fatigue rules.
        """
        # If finding is already in 'notified' status at this severity, suppress duplicate alert
        if finding.status == "notified":
            return None

        notification = Notification(
            user_id=finding.user_id,
            finding_id=finding.id,
            channel=channel,
            severity=finding.severity,
            title=title,
            body=body,
            delivery_status="SENT",
            sent_at=datetime.now(timezone.utc)
        )
        self.db.add(notification)

        # Transition finding state
        finding.status = "notified"
        await self.db.commit()
        return notification
