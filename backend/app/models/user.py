"""User and Account ORM Models."""

import uuid
from typing import List, Optional
from sqlalchemy import String, Boolean, JSON
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base, TimestampMixin


class User(Base, TimestampMixin):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4
    )
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True, nullable=False)
    hashed_password: Mapped[str] = mapped_column(String(255), nullable=False)
    full_name: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    timezone: Mapped[str] = mapped_column(String(64), default="Asia/Kolkata", nullable=False)
    notification_prefs: Mapped[dict] = mapped_column(
        JSON,
        default=lambda: {
            "min_severity": "worth_monitoring",
            "fcm_enabled": True,
            "whatsapp_enabled": False
        },
        nullable=False
    )
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    devices: Mapped[List["Device"]] = relationship("Device", back_populates="user", cascade="all, delete-orphan")
    wearable_sources: Mapped[List["WearableSource"]] = relationship("WearableSource", back_populates="user", cascade="all, delete-orphan")


from app.models.device import Device, WearableSource
