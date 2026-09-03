"""Add payload, created_at, failure_info, and idempotency to notifications table.

Revision ID: 20260904_0003
Revises: 20260904_0002
Create Date: 2026-09-04 01:10:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "20260904_0003"
down_revision: Union[str, None] = "20260904_0002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("notifications", sa.Column("payload", sa.JSON(), nullable=True))
    op.add_column(
        "notifications",
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("NOW()"),
            nullable=False
        )
    )
    op.add_column("notifications", sa.Column("failure_info", sa.JSON(), nullable=True))
    op.add_column("notifications", sa.Column("idempotency_key", sa.String(length=128), nullable=True))

    op.create_index(
        "idx_notifications_idempotency",
        "notifications",
        ["idempotency_key"],
        unique=True
    )
    op.create_index(
        "idx_notifications_finding_channel",
        "notifications",
        ["finding_id", "channel"]
    )


def downgrade() -> None:
    op.drop_index("idx_notifications_finding_channel", table_name="notifications")
    op.drop_index("idx_notifications_idempotency", table_name="notifications")
    op.drop_column("notifications", "idempotency_key")
    op.drop_column("notifications", "failure_info")
    op.drop_column("notifications", "created_at")
    op.drop_column("notifications", "payload")
