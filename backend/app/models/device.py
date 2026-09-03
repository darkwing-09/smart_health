"""Device and Wearable Source ORM Models."""

import uuid
from datetime import datetime, timezone
from typing import Optional, List
from sqlalchemy import String, DateTime, ForeignKey
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True
    )
    device_type: Mapped[str] = mapped_column(String(32), nullable=False) # 'phone', 'watch'
    brand: Mapped[str] = mapped_column(String(64), nullable=False)       # 'Google', 'Samsung'
    model: Mapped[str] = mapped_column(String(128), nullable=False)      # 'Pixel Watch 2'
    os_version: Mapped[str] = mapped_column(String(64), nullable=False)  # 'Wear OS 4.0'
    fcm_token: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    paired_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
        nullable=False
    )

    user: Mapped["User"] = relationship("User", back_populates="devices")
    wearable_sources: Mapped[List["WearableSource"]] = relationship("WearableSource", back_populates="device")


class WearableSource(Base):
    __tablename__ = "wearable_sources"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True
    )
    device_id: Mapped[Optional[uuid.UUID]] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("devices.id", ondelete="SET NULL"),
        nullable=True
    )
    adapter_type: Mapped[str] = mapped_column(String(64), nullable=False) # 'health_connect', 'fitbit'
    reliability_tier: Mapped[str] = mapped_column(String(32), nullable=False) # 'official', 'best_effort_unofficial'
    auth_status: Mapped[str] = mapped_column(String(32), default="ACTIVE", nullable=False)
    last_sync_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )

    user: Mapped["User"] = relationship("User", back_populates="wearable_sources")
    device: Mapped[Optional["Device"]] = relationship("Device", back_populates="wearable_sources")


from app.models.user import User
