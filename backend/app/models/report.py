"""Daily Health Report ORM Model."""

import uuid
from datetime import date, datetime, timezone
from sqlalchemy import String, Date, Text, DateTime, JSON, ForeignKey, UniqueConstraint, Index
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column
from app.db.base import Base


class Report(Base):
    __tablename__ = "reports"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    report_date: Mapped[date] = mapped_column(Date, nullable=False)
    generation_status: Mapped[str] = mapped_column(String(32), default="complete", nullable=False) # 'complete', 'degraded_trends_only', 'failed'
    trend_summary: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    executive_narrative: Mapped[str] = mapped_column(Text, nullable=False)
    closing_quote: Mapped[dict] = mapped_column(JSON, nullable=False) # {"quote": "...", "author_or_tradition": "..."}
    pdf_storage_path: Mapped[str] = mapped_column(Text, nullable=False)
    generated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False
    )

    __table_args__ = (
        UniqueConstraint("user_id", "report_date", name="uq_user_report_date"),
        Index("idx_reports_user_date", "user_id", "report_date"),
    )
