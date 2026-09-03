"""Add analytical provenance and deduplication to findings table.

Revision ID: 20260904_0002
Revises: 20260904_0001
Create Date: 2026-09-04 01:00:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "20260904_0002"
down_revision: Union[str, None] = "20260904_0001"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Add analytical provenance columns to findings
    op.add_column("findings", sa.Column("observed_value", sa.Float(), nullable=True))
    op.add_column("findings", sa.Column("baseline_value", sa.Float(), nullable=True))
    op.add_column("findings", sa.Column("deviation", sa.Float(), nullable=True))
    op.add_column("findings", sa.Column("standard_deviation", sa.Float(), nullable=True))
    op.add_column("findings", sa.Column("reading_timestamp", sa.DateTime(timezone=True), nullable=True))
    op.add_column("findings", sa.Column("timezone", sa.String(length=64), nullable=True))
    op.add_column("findings", sa.Column("activity_context", sa.JSON(), nullable=True))
    op.add_column("findings", sa.Column("data_quality", sa.String(length=32), nullable=True))
    op.add_column("findings", sa.Column("confidence", sa.Float(), nullable=True))
    op.add_column("findings", sa.Column("source_measurement_ids", sa.JSON(), nullable=True))
    op.add_column("findings", sa.Column("evidence", sa.JSON(), nullable=True))

    # 2. Add composite unique index for finding idempotency
    op.create_index(
        "idx_findings_dedup",
        "findings",
        ["user_id", "metric_type", "rule_id", "reading_timestamp"],
        unique=True
    )


def downgrade() -> None:
    op.drop_index("idx_findings_dedup", table_name="findings")
    op.drop_column("findings", "evidence")
    op.drop_column("findings", "source_measurement_ids")
    op.drop_column("findings", "confidence")
    op.drop_column("findings", "data_quality")
    op.drop_column("findings", "activity_context")
    op.drop_column("findings", "timezone")
    op.drop_column("findings", "reading_timestamp")
    op.drop_column("findings", "standard_deviation")
    op.drop_column("findings", "deviation")
    op.drop_column("findings", "baseline_value")
    op.drop_column("findings", "observed_value")
