"""whatsapp daily report

Revision ID: 5e1f0c3a9b77
Revises: 7d2b9502191a
Create Date: 2026-11-04 11:00:00

"""
from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = '5e1f0c3a9b77'
down_revision: str | Sequence[str] | None = '7d2b9502191a'
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.add_column(sa.Column('city', sa.String(length=80), nullable=True))
        batch_op.add_column(sa.Column('gender', sa.String(length=20), nullable=True))
        batch_op.add_column(sa.Column('whatsapp_number', sa.String(length=20), nullable=True))
        batch_op.add_column(sa.Column('whatsapp_opt_in', sa.Boolean(), server_default='0', nullable=False))
        batch_op.add_column(sa.Column('report_hour', sa.Integer(), server_default='21', nullable=False))
    op.create_table(
        'report_deliveries',
        sa.Column('user_id', sa.String(length=36), nullable=False),
        sa.Column('day', sa.Date(), nullable=False),
        sa.Column('status', sa.String(length=12), nullable=False),
        sa.Column('channel', sa.String(length=12), nullable=False),
        sa.Column('detail', sa.Text(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('user_id', 'day'),
    )


def downgrade() -> None:
    op.drop_table('report_deliveries')
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.drop_column('report_hour')
        batch_op.drop_column('whatsapp_opt_in')
        batch_op.drop_column('whatsapp_number')
        batch_op.drop_column('gender')
        batch_op.drop_column('city')
