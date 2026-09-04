"""User Notification Preference Service (Agent 11: Deterministic Profile Manager).

Manages user quiet hours, localized timezones, channel preferences, and device push tokens.
Enforces the critical safety rule: Emergency Level 4 alerts can NEVER be disabled.
"""

import uuid
from datetime import datetime, timezone
from typing import Any, Optional
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User
from app.models.device import Device
from app.models.audit import AuditLog

DEFAULT_NOTIFICATION_PREFERENCES: dict[str, Any] = {
    "fcm_enabled": True,
    "in_app_enabled": True,
    "min_severity": "worth_monitoring",  # Level 2+
    "quiet_hours_start": "22:00",
    "quiet_hours_end": "07:00",
    "emergency_override_enabled": True,  # Non-negotiable safety invariant
}


class UserPreferenceService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def get_preferences(self, user_id: uuid.UUID) -> dict[str, Any]:
        """Retrieves active user preferences merged with defaults."""
        user = await self.db.get(User, user_id)
        if not user:
            raise ValueError(f"User {user_id} not found")

        prefs = dict(DEFAULT_NOTIFICATION_PREFERENCES)
        if user.notification_prefs:
            prefs.update(user.notification_prefs)

        # Enforce safety invariant: emergency alerts can never be disabled
        prefs["emergency_override_enabled"] = True

        return {
            "user_id": str(user.id),
            "timezone": user.timezone,
            "preferences": prefs
        }

    async def update_preferences(
        self,
        user_id: uuid.UUID,
        timezone_str: Optional[str] = None,
        notification_prefs_update: Optional[dict[str, Any]] = None
    ) -> dict[str, Any]:
        """
        Updates user preferences and timezone.
        Guarantees that emergency Level 4 cannot be disabled.
        """
        user = await self.db.get(User, user_id)
        if not user:
            raise ValueError(f"User {user_id} not found")

        current_prefs = dict(DEFAULT_NOTIFICATION_PREFERENCES)
        if user.notification_prefs:
            current_prefs.update(user.notification_prefs)

        if notification_prefs_update:
            current_prefs.update(notification_prefs_update)

        # Invariant: Never allow disabling emergency override
        current_prefs["emergency_override_enabled"] = True

        if timezone_str:
            user.timezone = timezone_str.strip()

        user.notification_prefs = current_prefs

        # Write audit trail
        audit = AuditLog(
            id=uuid.uuid4(),
            user_id=user_id,
            actor=f"user:{user_id}",
            action="notification_preferences_updated",
            target_ref=f"users:{user_id}",
            detail={
                "updated_prefs": notification_prefs_update,
                "timezone": user.timezone
            },
            timestamp=datetime.now(timezone.utc)
        )
        self.db.add(audit)

        await self.db.commit()
        await self.db.refresh(user)

        return {
            "user_id": str(user.id),
            "timezone": user.timezone,
            "preferences": user.notification_prefs
        }

    async def register_fcm_token(
        self,
        user_id: uuid.UUID,
        fcm_token: str,
        device_id: Optional[uuid.UUID] = None
    ) -> bool:
        """
        Registers device FCM push token ensuring strict tenant isolation.
        Cannot register token against a device belonging to another user.
        """
        if device_id:
            stmt = select(Device).where(Device.id == device_id, Device.user_id == user_id)
            device = (await self.db.scalars(stmt)).first()
            if not device:
                raise PermissionError("Target device does not belong to the authenticated user.")
            device.fcm_token = fcm_token
            device.last_seen_at = datetime.now(timezone.utc)
        else:
            # Update most recent device or first device for user
            stmt_recent = (
                select(Device)
                .where(Device.user_id == user_id)
                .order_by(Device.last_seen_at.desc())
            )
            device = (await self.db.scalars(stmt_recent)).first()
            if device:
                device.fcm_token = fcm_token
                device.last_seen_at = datetime.now(timezone.utc)
            else:
                # Create a primary phone device
                device = Device(
                    id=uuid.uuid4(),
                    user_id=user_id,
                    device_type="phone",
                    brand="Generic",
                    model="Mobile",
                    os_version="Android",
                    fcm_token=fcm_token
                )
                self.db.add(device)

        await self.db.commit()
        return True
