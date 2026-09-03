"""Care Navigation & Appointment Request ORM Models."""

import uuid
from datetime import datetime, timezone
from typing import Optional
from sqlalchemy import String, Float, Text, DateTime, ForeignKey, Index, Boolean, JSON
from sqlalchemy.dialects.postgresql import UUID, INET
from sqlalchemy.orm import Mapped, mapped_column
from app.db.base import Base


class Hospital(Base):
    __tablename__ = "hospitals"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    address: Mapped[Text] = mapped_column(Text, nullable=False)
    latitude: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    longitude: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    phone: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    source_provider: Mapped[str] = mapped_column(String(64), nullable=False) # 'google_places', 'osm'
    source_record_id: Mapped[str] = mapped_column(String(128), nullable=False)
    fetched_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )

    __table_args__ = (
        Index("idx_hospitals_coords", "latitude", "longitude"),
    )


class UserApproval(Base):
    __tablename__ = "user_approvals"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    action_type: Mapped[str] = mapped_column(String(64), nullable=False) # 'share_visit_summary'
    finding_id: Mapped[Optional[uuid.UUID]] = mapped_column(UUID(as_uuid=True), ForeignKey("findings.id", ondelete="SET NULL"), nullable=True)
    approved_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )
    expires_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    ip_address: Mapped[Optional[str]] = mapped_column(INET, nullable=True)


class AppointmentRequest(Base):
    __tablename__ = "appointment_requests"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    hospital_id: Mapped[Optional[uuid.UUID]] = mapped_column(UUID(as_uuid=True), ForeignKey("hospitals.id", ondelete="SET NULL"), nullable=True)
    finding_id: Mapped[Optional[uuid.UUID]] = mapped_column(UUID(as_uuid=True), ForeignKey("findings.id", ondelete="SET NULL"), nullable=True)
    status: Mapped[str] = mapped_column(String(32), default="drafted", nullable=False) # 'drafted', 'user_sent', 'cancelled'
    shareable_summary: Mapped[str] = mapped_column(Text, nullable=False)
    drafted_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )
    user_action_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)


class ClinicalConsent(Base):
    """
    Granular, revocable patient consent for clinical data sharing (DPDP Act 2023 compliant).
    """
    __tablename__ = "clinical_consents"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    consent_version: Mapped[str] = mapped_column(String(32), default="1.0.0", nullable=False)
    purpose: Mapped[str] = mapped_column(String(64), nullable=False) # 'doctor_consultation', 'second_opinion', 'personal_archive'
    permitted_metrics: Mapped[list] = mapped_column(JSON, default=list, nullable=False)
    permitted_finding_ids: Mapped[list] = mapped_column(JSON, default=list, nullable=False)
    scope_date_start: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    scope_date_end: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    include_context: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    include_sensor_quality: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    include_ai_synthesis: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    recipient_name: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    recipient_facility: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    granted_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    status: Mapped[str] = mapped_column(String(32), default="active", nullable=False) # 'active', 'revoked', 'expired'
    ip_address: Mapped[Optional[str]] = mapped_column(INET, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )

    __table_args__ = (
        Index("idx_clinical_consents_user_status", "user_id", "status"),
    )


class ClinicalSummary(Base):
    """
    Evidence-grounded, patient-controlled Doctor Visit Summary.
    Lifecycle: draft -> reviewed -> redacted -> approved -> exported (or revoked).
    """
    __tablename__ = "clinical_summaries"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    consent_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("clinical_consents.id", ondelete="CASCADE"), nullable=False, index=True)
    status: Mapped[str] = mapped_column(String(32), default="draft", nullable=False) # 'draft', 'reviewed', 'redacted', 'approved', 'revoked'
    summary_payload: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    redaction_mask: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    recommended_specialties: Mapped[list] = mapped_column(JSON, default=list, nullable=False)
    routing_rationale: Mapped[str] = mapped_column(Text, default="", nullable=False)
    approval_token: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    approved_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    pdf_storage_path: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    checksum_sha256: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
        nullable=False
    )

    __table_args__ = (
        Index("idx_clinical_summaries_user_status", "user_id", "status"),
    )

