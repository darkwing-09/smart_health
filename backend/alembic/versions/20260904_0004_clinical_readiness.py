"""Add clinical_consents and clinical_summaries tables for Phase 5.

Revision ID: 20260904_0004
Revises: 20260904_0003
Create Date: 2026-09-04 02:00:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "20260904_0004"
down_revision: Union[str, None] = "20260904_0003"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Create clinical_consents table
    op.create_table(
        "clinical_consents",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("consent_version", sa.String(length=32), server_default="1.0.0", nullable=False),
        sa.Column("purpose", sa.String(length=64), nullable=False),
        sa.Column("permitted_metrics", sa.JSON(), nullable=False),
        sa.Column("permitted_finding_ids", sa.JSON(), nullable=False),
        sa.Column("scope_date_start", sa.DateTime(timezone=True), nullable=False),
        sa.Column("scope_date_end", sa.DateTime(timezone=True), nullable=False),
        sa.Column("include_context", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column("include_sensor_quality", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column("include_ai_synthesis", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column("recipient_name", sa.String(length=128), nullable=True),
        sa.Column("recipient_facility", sa.String(length=255), nullable=True),
        sa.Column("granted_at", sa.DateTime(timezone=True), server_default=sa.text("NOW()"), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("status", sa.String(length=32), server_default="active", nullable=False),
        sa.Column("ip_address", postgresql.INET(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("NOW()"), nullable=False),
    )
    op.create_index(
        "idx_clinical_consents_user_status",
        "clinical_consents",
        ["user_id", "status"]
    )

    # 2. Create clinical_summaries table
    op.create_table(
        "clinical_summaries",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("consent_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("clinical_consents.id", ondelete="CASCADE"), nullable=False),
        sa.Column("status", sa.String(length=32), server_default="draft", nullable=False),
        sa.Column("summary_payload", sa.JSON(), nullable=False),
        sa.Column("redaction_mask", sa.JSON(), nullable=False),
        sa.Column("recommended_specialties", sa.JSON(), nullable=False),
        sa.Column("routing_rationale", sa.Text(), server_default="", nullable=False),
        sa.Column("approval_token", sa.String(length=128), nullable=True),
        sa.Column("approved_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("pdf_storage_path", sa.Text(), nullable=True),
        sa.Column("checksum_sha256", sa.String(length=64), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("NOW()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("NOW()"), nullable=False),
    )
    op.create_index(
        "idx_clinical_summaries_user_status",
        "clinical_summaries",
        ["user_id", "status"]
    )


def downgrade() -> None:
    op.drop_index("idx_clinical_summaries_user_status", table_name="clinical_summaries")
    op.drop_table("clinical_summaries")
    op.drop_index("idx_clinical_consents_user_status", table_name="clinical_consents")
    op.drop_table("clinical_consents")
