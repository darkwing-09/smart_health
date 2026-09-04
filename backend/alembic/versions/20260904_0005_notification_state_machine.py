"""Add notification state machine, retry tracking, and quiet hours fields.

Revision ID: 20260904_0005
Revises: 20260904_0004
Create Date: 2026-09-04 11:00:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa


revision: str = "20260904_0005"
down_revision: Union[str, None] = "20260904_0004"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "notifications",
        sa.Column("state", sa.String(length=32), server_default="DELIVERED", nullable=False)
    )
    op.add_column(
        "notifications",
        sa.Column("retry_count", sa.Integer(), server_default="0", nullable=False)
    )
    op.add_column(
        "notifications",
        sa.Column("max_retries", sa.Integer(), server_default="3", nullable=False)
    )
    op.add_column(
        "notifications",
        sa.Column("next_retry_at", sa.DateTime(timezone=True), nullable=True)
    )
    op.add_column(
        "notifications",
        sa.Column("delivered_at", sa.DateTime(timezone=True), nullable=True)
    )
    op.add_column(
        "notifications",
        sa.Column("dismissed_at", sa.DateTime(timezone=True), nullable=True)
    )
    op.add_column(
        "notifications",
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True)
    )
    op.add_column(
        "notifications",
        sa.Column("quiet_hours_held", sa.Boolean(), server_default=sa.text("false"), nullable=False)
    )

    op.create_index(
        "idx_notifications_user_state",
        "notifications",
        ["user_id", "state"]
    )
    op.create_index(
        "idx_notifications_held_retry",
        "notifications",
        ["state", "quiet_hours_held"]
    )


def downgrade() -> None:
    op.drop_index("idx_notifications_held_retry", table_name="notifications")
    op.drop_index("idx_notifications_user_state", table_name="notifications")
    op.drop_column("notifications", "quiet_hours_held")
    op.drop_column("notifications", "expires_at")
    op.drop_column("notifications", "dismissed_at")
    op.drop_column("notifications", "delivered_at")
    op.drop_column("notifications", "next_retry_at")
    op.drop_column("notifications", "max_retries")
    op.drop_column("notifications", "retry_count")
    op.drop_column("notifications", "state")
