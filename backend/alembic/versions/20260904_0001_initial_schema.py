"""Initial TimescaleDB and Personal Health OS schema.

Revision ID: 20260904_0001
Revises: None
Create Date: 2026-09-04 00:00:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "20260904_0001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Ensure TimescaleDB & UUID extensions
    op.execute('CREATE EXTENSION IF NOT EXISTS "uuid-ossp";')
    op.execute('CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;')

    # 2. Users table
    op.create_table(
        "users",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("email", sa.String(255), nullable=False, unique=True),
        sa.Column("hashed_password", sa.String(255), nullable=False),
        sa.Column("full_name", sa.String(128), nullable=True),
        sa.Column("timezone", sa.String(64), nullable=False, server_default="Asia/Kolkata"),
        sa.Column("notification_prefs", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default='{}'),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default="true"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
    )
    op.create_index("idx_users_email", "users", ["email"])

    # 3. Devices table
    op.create_table(
        "devices",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("device_type", sa.String(32), nullable=False),
        sa.Column("brand", sa.String(64), nullable=False),
        sa.Column("model", sa.String(128), nullable=False),
        sa.Column("os_version", sa.String(64), nullable=False),
        sa.Column("fcm_token", sa.String(512), nullable=True),
        sa.Column("paired_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
    )
    op.create_index("idx_devices_user_id", "devices", ["user_id"])

    # 4. Wearable Sources table
    op.create_table(
        "wearable_sources",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("device_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("devices.id", ondelete="SET NULL"), nullable=True),
        sa.Column("adapter_type", sa.String(64), nullable=False),
        sa.Column("reliability_tier", sa.String(32), nullable=False),
        sa.Column("auth_status", sa.String(32), nullable=False, server_default="ACTIVE"),
        sa.Column("last_sync_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
    )

    # 5. Sync Batches table
    op.create_table(
        "sync_batches",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("accepted_count", sa.Integer(), nullable=False),
        sa.Column("duplicate_count", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
    )

    # 6. Measurements table & TimescaleDB hypertable
    op.create_table(
        "measurements",
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("source_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("metric_type", sa.String(64), nullable=False),
        sa.Column("value", sa.Float(), nullable=False),
        sa.Column("unit", sa.String(32), nullable=False),
        sa.Column("ingested_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("confidence", sa.Float(), nullable=False, server_default="1.0"),
        sa.Column("data_quality_flag", sa.String(32), nullable=False, server_default="nominal"),
        sa.Column("supersedes_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.PrimaryKeyConstraint("recorded_at", "id", "user_id")
    )
    op.create_index("idx_measurements_dedup", "measurements", ["user_id", "source_id", "metric_type", "recorded_at"], unique=True)
    op.create_index("idx_measurements_query", "measurements", ["user_id", "metric_type", "recorded_at"])

    # Create TimescaleDB hypertable chunked by 7 days
    op.execute("SELECT create_hypertable('measurements', 'recorded_at', chunk_time_interval => INTERVAL '7 days', if_not_exists => TRUE);")

    # 7. Baselines table
    op.create_table(
        "baselines",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("metric_type", sa.String(64), nullable=False),
        sa.Column("window_start", sa.DateTime(timezone=True), nullable=False),
        sa.Column("window_end", sa.DateTime(timezone=True), nullable=False),
        sa.Column("mean", sa.Float(), nullable=False),
        sa.Column("stddev", sa.Float(), nullable=False),
        sa.Column("seasonality_profile", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default='{}'),
        sa.Column("established", sa.Boolean(), nullable=False, server_default="false"),
        sa.Column("rule_version", sa.String(32), nullable=False, server_default="1.0.0"),
        sa.Column("computed_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
    )
    op.create_index("idx_baselines_user_metric", "baselines", ["user_id", "metric_type", "computed_at"])

    # 8. Findings table
    op.create_table(
        "findings",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("metric_type", sa.String(64), nullable=False),
        sa.Column("severity", sa.String(32), nullable=False),
        sa.Column("rule_id", sa.String(64), nullable=False),
        sa.Column("rule_version", sa.String(32), nullable=False, server_default="1.0.0"),
        sa.Column("baseline_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("baselines.id", ondelete="SET NULL"), nullable=True),
        sa.Column("status", sa.String(32), nullable=False, server_default="new"),
        sa.Column("first_detected_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("last_updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("idx_findings_user_status", "findings", ["user_id", "status", "severity"])

    # 9. Finding Explanations table
    op.create_table(
        "finding_explanations",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("finding_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("findings.id", ondelete="CASCADE"), nullable=False),
        sa.Column("agent_id", sa.String(64), nullable=False),
        sa.Column("what_changed", sa.Text(), nullable=False),
        sa.Column("measurements_caused", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("baseline_difference", sa.Text(), nullable=False),
        sa.Column("historical_context", sa.Text(), nullable=False),
        sa.Column("confidence_and_data_quality", sa.Text(), nullable=False),
        sa.Column("why_it_matters", sa.Text(), nullable=False),
        sa.Column("next_steps", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("grounding_trace", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default='{}'),
        sa.Column("model_version", sa.String(64), nullable=False),
        sa.Column("prompt_version", sa.String(32), nullable=False),
        sa.Column("generated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
    )

    # 10. Notifications table
    op.create_table(
        "notifications",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("finding_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("findings.id", ondelete="SET NULL"), nullable=True),
        sa.Column("channel", sa.String(32), nullable=False),
        sa.Column("severity", sa.String(32), nullable=False),
        sa.Column("title", sa.Text(), nullable=False),
        sa.Column("body", sa.Text(), nullable=False),
        sa.Column("delivery_status", sa.String(32), nullable=False, server_default="SENT"),
        sa.Column("sent_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("acknowledged_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("idx_notifications_user_sent", "notifications", ["user_id", "sent_at"])

    # 11. Reports table
    op.create_table(
        "reports",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("report_date", sa.Date(), nullable=False),
        sa.Column("generation_status", sa.String(32), nullable=False, server_default="complete"),
        sa.Column("trend_summary", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default='{}'),
        sa.Column("executive_narrative", sa.Text(), nullable=False),
        sa.Column("closing_quote", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("pdf_storage_path", sa.Text(), nullable=False),
        sa.Column("generated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.UniqueConstraint("user_id", "report_date", name="uq_user_report_date")
    )

    # 12. Hospitals table
    op.create_table(
        "hospitals",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("address", sa.Text(), nullable=False),
        sa.Column("latitude", sa.Float(), nullable=True),
        sa.Column("longitude", sa.Float(), nullable=True),
        sa.Column("phone", sa.String(64), nullable=True),
        sa.Column("source_provider", sa.String(64), nullable=False),
        sa.Column("source_record_id", sa.String(128), nullable=False),
        sa.Column("fetched_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
    )

    # 13. User Approvals table
    op.create_table(
        "user_approvals",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("action_type", sa.String(64), nullable=False),
        sa.Column("finding_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("findings.id", ondelete="SET NULL"), nullable=True),
        sa.Column("approved_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("ip_address", postgresql.INET(), nullable=True),
    )

    # 14. Appointment Requests table
    op.create_table(
        "appointment_requests",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("hospital_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("hospitals.id", ondelete="SET NULL"), nullable=True),
        sa.Column("finding_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("findings.id", ondelete="SET NULL"), nullable=True),
        sa.Column("status", sa.String(32), nullable=False, server_default="drafted"),
        sa.Column("shareable_summary", sa.Text(), nullable=False),
        sa.Column("drafted_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("user_action_at", sa.DateTime(timezone=True), nullable=True),
    )

    # 15. Agent Executions table
    op.create_table(
        "agent_executions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("agent_name", sa.String(64), nullable=False),
        sa.Column("triggered_by", sa.String(32), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("input_payload", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("output_payload", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("tool_calls", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default='[]'),
        sa.Column("latency_ms", sa.Integer(), nullable=False),
        sa.Column("model_version", sa.String(64), nullable=True),
        sa.Column("prompt_version", sa.String(32), nullable=True),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.Column("executed_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
    )
    op.create_index("idx_agent_executions_user", "agent_executions", ["user_id", "executed_at"])

    # 16. Audit Logs table
    op.create_table(
        "audit_logs",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("uuid_generate_v4()")),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("actor", sa.String(64), nullable=False),
        sa.Column("action", sa.String(64), nullable=False),
        sa.Column("target_ref", sa.String(128), nullable=False),
        sa.Column("timestamp", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("ip_address", postgresql.INET(), nullable=True),
        sa.Column("detail", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default='{}'),
    )
    op.create_index("idx_audit_logs_user_time", "audit_logs", ["user_id", "timestamp"])


def downgrade() -> None:
    op.drop_table("audit_logs")
    op.drop_table("agent_executions")
    op.drop_table("appointment_requests")
    op.drop_table("user_approvals")
    op.drop_table("hospitals")
    op.drop_table("reports")
    op.drop_table("notifications")
    op.drop_table("finding_explanations")
    op.drop_table("findings")
    op.drop_table("baselines")
    op.drop_table("measurements")
    op.drop_table("sync_batches")
    op.drop_table("wearable_sources")
    op.drop_table("devices")
    op.drop_table("users")
