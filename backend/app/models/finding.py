"""Finding and FindingExplanation ORM Models."""

import uuid
from datetime import datetime, timezone
from typing import Optional, List
from sqlalchemy import String, DateTime, JSON, ForeignKey, Text, Index
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.base import Base


class Finding(Base):
    __tablename__ = "findings"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    metric_type: Mapped[str] = mapped_column(String(64), nullable=False)
    severity: Mapped[str] = mapped_column(String(32), nullable=False) # 'normal_variation', 'unusual', 'worth_monitoring', 'potentially_concerning', 'urgent'
    rule_id: Mapped[str] = mapped_column(String(64), nullable=False)
    rule_version: Mapped[str] = mapped_column(String(32), default="1.0.0", nullable=False)
    baseline_id: Mapped[Optional[uuid.UUID]] = mapped_column(UUID(as_uuid=True), ForeignKey("baselines.id", ondelete="SET NULL"), nullable=True)
    status: Mapped[str] = mapped_column(String(32), default="new", nullable=False) # 'new', 'notified', 'acknowledged', 'escalated', 'resolved'

    first_detected_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )
    last_updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
        nullable=False
    )
    resolved_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)

    explanations: Mapped[List["FindingExplanation"]] = relationship("FindingExplanation", back_populates="finding", cascade="all, delete-orphan")

    __table_args__ = (
        Index("idx_findings_user_status", "user_id", "status", "severity"),
    )


class FindingExplanation(Base):
    __tablename__ = "finding_explanations"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    finding_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("findings.id", ondelete="CASCADE"), nullable=False, index=True)
    agent_id: Mapped[str] = mapped_column(String(64), nullable=False) # 'agent:health_intel'

    # Mandatory 7-Part Structure
    what_changed: Mapped[str] = mapped_column(Text, nullable=False)
    measurements_caused: Mapped[list] = mapped_column(JSON, nullable=False)
    baseline_difference: Mapped[str] = mapped_column(Text, nullable=False)
    historical_context: Mapped[str] = mapped_column(Text, nullable=False)
    confidence_and_data_quality: Mapped[str] = mapped_column(Text, nullable=False)
    why_it_matters: Mapped[str] = mapped_column(Text, nullable=False)
    next_steps: Mapped[list] = mapped_column(JSON, nullable=False)

    grounding_trace: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    model_version: Mapped[str] = mapped_column(String(64), nullable=False)
    prompt_version: Mapped[str] = mapped_column(String(32), nullable=False)
    generated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )

    finding: Mapped["Finding"] = relationship("Finding", back_populates="explanations")
