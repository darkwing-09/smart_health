"""Measurement and SyncBatch ORM Models."""

import uuid
from datetime import datetime, timezone
from typing import Optional
from sqlalchemy import String, Float, DateTime, ForeignKey, Index
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column
from app.db.base import Base


class SyncBatch(Base):
    __tablename__ = "sync_batches"

    id: Mapped[str] = mapped_column(String(64), primary_key=True) # Idempotency key UUID
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    accepted_count: Mapped[int] = mapped_column(nullable=False)
    duplicate_count: Mapped[int] = mapped_column(nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )


class Measurement(Base):
    """
    Append-only longitudinal measurement.
    Configured as a TimescaleDB hypertable partitioned by 7 days on recorded_at.
    """
    __tablename__ = "measurements"

    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), primary_key=True)
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, index=True)
    source_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)

    metric_type: Mapped[str] = mapped_column(String(64), nullable=False, index=True) # 'heart_rate', 'steps', etc.
    value: Mapped[float] = mapped_column(Float, nullable=False)
    unit: Mapped[str] = mapped_column(String(32), nullable=False) # 'bpm', 'count', 'm'

    ingested_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )
    confidence: Mapped[float] = mapped_column(Float, default=1.0, nullable=False)
    data_quality_flag: Mapped[str] = mapped_column(String(32), default="nominal", nullable=False) # 'nominal', 'estimated', 'gap_filled', 'missing'
    supersedes_id: Mapped[Optional[uuid.UUID]] = mapped_column(UUID(as_uuid=True), nullable=True)

    __table_args__ = (
        Index("idx_measurements_dedup", "user_id", "source_id", "metric_type", "recorded_at", unique=True),
        Index("idx_measurements_query", "user_id", "metric_type", "recorded_at"),
    )
