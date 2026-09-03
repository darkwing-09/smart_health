# DataModel.md — Conceptual & Physical Data Model

This document establishes the conceptual entity model, relational schemas, indexing strategies, and immutability constraints for Personal Health OS, targeting PostgreSQL 16 with TimescaleDB (ADR-007).

---

## 1. Entity Relationship Overview

```
 [User] 1──* [Device] 1──* [WearableSource]
   │                              │
   │                              ▼
   ├──1──* [Measurement] (Timescale Hypertable, Append-Only)
   │             │
   │             ▼
   ├──1──* [Baseline] (Rolling Historical Snapshots)
   │             │
   │             ▼
   ├──1──* [Finding] 1──* [FindingExplanation]
   │             │
   │             ▼
   ├──1──* [Notification]
   │
   ├──1──* [UserApproval] ──* [AppointmentRequest]
   │                                 │
   │                                 ▼
   ├──1──* [Report]           [Hospital / Doctor Cache]
   │
   └──1──* [AuditLog] (Immutable Security Ledger)
```

---

## 2. Core Relational Schemas (DDL)

### 2.1 Users & Devices

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    hashed_password VARCHAR(255) NOT NULL,
    full_name VARCHAR(128),
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',
    notification_prefs JSONB NOT NULL DEFAULT '{"min_severity": "worth_monitoring", "fcm_enabled": true, "whatsapp_enabled": false}',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_type VARCHAR(32) NOT NULL, -- 'phone', 'watch'
    brand VARCHAR(64) NOT NULL,       -- 'Google', 'Samsung'
    model VARCHAR(128) NOT NULL,      -- 'Pixel Watch 2'
    os_version VARCHAR(64) NOT NULL,  -- 'Wear OS 4.0'
    fcm_token TEXT,
    paired_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_devices_user_id ON devices(user_id);

CREATE TABLE wearable_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    adapter_type VARCHAR(64) NOT NULL, -- 'health_connect', 'fitbit', 'garmin'
    reliability_tier VARCHAR(32) NOT NULL, -- 'official', 'partner_gated', 'best_effort_unofficial'
    auth_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_sync_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wearable_sources_user ON wearable_sources(user_id);
```

### 2.2 Measurements (Longitudinal Timeline Hypertable)

```sql
CREATE TABLE measurements (
    id UUID DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_id UUID NOT NULL REFERENCES wearable_sources(id),
    metric_type VARCHAR(64) NOT NULL, -- 'heart_rate', 'steps', 'sleep_stage', 'spo2'
    value DOUBLE PRECISION NOT NULL,
    unit VARCHAR(32) NOT NULL,        -- 'bpm', 'count', 'percentage'
    recorded_at TIMESTAMPTZ NOT NULL,  -- Device timestamp
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    data_quality_flag VARCHAR(32) NOT NULL DEFAULT 'nominal', -- 'nominal', 'estimated', 'gap_filled', 'missing'
    supersedes_id UUID REFERENCES measurements(id),
    PRIMARY KEY (recorded_at, id, user_id)
);

-- Enable TimescaleDB Hypertable partitioned by 7-day chunks
SELECT create_hypertable('measurements', 'recorded_at', chunk_time_interval => INTERVAL '7 days');

-- Composite unique index to prevent duplicate ingestion
CREATE UNIQUE INDEX idx_measurements_dedup 
ON measurements (user_id, source_id, metric_type, recorded_at);

CREATE INDEX idx_measurements_query 
ON measurements (user_id, metric_type, recorded_at DESC);
```

### 2.3 Baselines & Deterministic Findings

```sql
CREATE TABLE baselines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    metric_type VARCHAR(64) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    mean DOUBLE PRECISION NOT NULL,
    stddev DOUBLE PRECISION NOT NULL,
    seasonality_profile JSONB NOT NULL DEFAULT '{}', -- Hourly circadian distributions
    established BOOLEAN NOT NULL DEFAULT FALSE,
    rule_version VARCHAR(32) NOT NULL DEFAULT '1.0.0',
    computed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_baselines_user_metric ON baselines(user_id, metric_type, computed_at DESC);

CREATE TABLE findings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    metric_type VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL, -- 'normal_variation', 'unusual', 'worth_monitoring', 'potentially_concerning', 'urgent'
    rule_id VARCHAR(64) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    baseline_id UUID REFERENCES baselines(id),
    status VARCHAR(32) NOT NULL DEFAULT 'new', -- 'new', 'notified', 'acknowledged', 'escalated', 'resolved'
    first_detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    -- Analytical Provenance & Evidence
    observed_value DOUBLE PRECISION,
    baseline_value DOUBLE PRECISION,
    deviation DOUBLE PRECISION,
    standard_deviation DOUBLE PRECISION,
    reading_timestamp TIMESTAMPTZ,
    timezone VARCHAR(64),
    activity_context JSONB,
    data_quality VARCHAR(32) DEFAULT 'nominal',
    confidence DOUBLE PRECISION DEFAULT 1.0,
    source_measurement_ids JSONB DEFAULT '[]'::jsonb,
    evidence JSONB DEFAULT '{}'::jsonb
);
CREATE INDEX idx_findings_user_status ON findings(user_id, status, severity);
CREATE UNIQUE INDEX idx_findings_dedup ON findings(user_id, metric_type, rule_id, reading_timestamp);

CREATE TABLE finding_explanations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    finding_id UUID NOT NULL REFERENCES findings(id) ON DELETE CASCADE,
    agent_id VARCHAR(64) NOT NULL,
    what_changed TEXT NOT NULL,
    measurements_caused JSONB NOT NULL,
    baseline_difference TEXT NOT NULL,
    historical_context TEXT NOT NULL,
    confidence_and_data_quality TEXT NOT NULL,
    why_it_matters TEXT NOT NULL,
    next_steps JSONB NOT NULL,
    grounding_trace JSONB NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_finding_explanations_finding ON finding_explanations(finding_id);
```

### 2.4 Notifications & Anti-Fatigue Dispatch

```sql
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    finding_id UUID REFERENCES findings(id) ON DELETE SET NULL,
    channel VARCHAR(32) NOT NULL, -- 'in_app', 'push', 'email', 'whatsapp_future'
    severity VARCHAR(32) NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'SENT', -- 'SENT', 'DELIVERED', 'FAILED'
    sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    acknowledged_at TIMESTAMPTZ,
    payload JSONB NOT NULL DEFAULT '{}',
    failure_info TEXT,
    idempotency_key VARCHAR(128)
);
CREATE INDEX idx_notifications_user_sent ON notifications(user_id, sent_at DESC);
CREATE UNIQUE INDEX idx_notifications_idempotency ON notifications(idempotency_key);
CREATE INDEX idx_notifications_finding_channel ON notifications(finding_id, channel);
```

### 2.5 Daily Reports

```sql
CREATE TABLE reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    report_date DATE NOT NULL,
    generation_status VARCHAR(32) NOT NULL DEFAULT 'complete', -- 'complete', 'degraded_trends_only', 'failed'
    trend_summary JSONB NOT NULL DEFAULT '{}',
    executive_narrative TEXT NOT NULL,
    closing_quote JSONB NOT NULL, -- {"quote": "...", "author_or_tradition": "..."}
    pdf_storage_path TEXT NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_report_date UNIQUE (user_id, report_date)
);
CREATE INDEX idx_reports_user_date ON reports(user_id, report_date DESC);
```

### 2.6 Care Navigation & Doctor Visit Summaries

```sql
CREATE TABLE hospitals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    address TEXT NOT NULL,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    phone VARCHAR(64),
    source_provider VARCHAR(64) NOT NULL, -- 'google_places', 'osm'
    source_record_id VARCHAR(128) NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_hospitals_location ON hospitals(latitude, longitude);

CREATE TABLE user_approvals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action_type VARCHAR(64) NOT NULL, -- 'share_visit_summary', 'research_providers'
    finding_id UUID REFERENCES findings(id),
    approved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    ip_address INET
);

CREATE TABLE appointment_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    hospital_id UUID REFERENCES hospitals(id),
    finding_id UUID REFERENCES findings(id),
    status VARCHAR(32) NOT NULL DEFAULT 'drafted', -- 'drafted', 'user_sent', 'cancelled'
    shareable_summary TEXT NOT NULL,
    drafted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    user_action_at TIMESTAMPTZ
);
```

### 2.7 Agent Executions & Immutable Audit Ledger

```sql
CREATE TABLE agent_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_name VARCHAR(64) NOT NULL,
    triggered_by VARCHAR(32) NOT NULL, -- 'event', 'hourly', 'daily', 'user_request'
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    input_payload JSONB NOT NULL,
    output_payload JSONB,
    tool_calls JSONB NOT NULL DEFAULT '[]',
    latency_ms INTEGER NOT NULL,
    model_version VARCHAR(64),
    prompt_version VARCHAR(32),
    status VARCHAR(32) NOT NULL, -- 'success', 'failure'
    error_message TEXT,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_agent_executions_user ON agent_executions(user_id, executed_at DESC);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    actor VARCHAR(64) NOT NULL, -- 'agent:health_intel', 'user', 'system:cadence'
    action VARCHAR(64) NOT NULL, -- 'finding_notified', 'approval_granted', 'account_deleted'
    target_ref VARCHAR(128) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ip_address INET,
    detail JSONB NOT NULL
);
CREATE INDEX idx_audit_logs_user_time ON audit_logs(user_id, timestamp DESC);
```

### 2.8 Clinical Consent & Doctor Visit Summaries (Phase 5)

```sql
CREATE TABLE clinical_consents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    consent_version VARCHAR(32) NOT NULL DEFAULT '1.0.0',
    purpose VARCHAR(64) NOT NULL, -- 'doctor_consultation', 'second_opinion', 'personal_archive'
    permitted_metrics JSONB NOT NULL DEFAULT '[]',
    permitted_finding_ids JSONB NOT NULL DEFAULT '["*"]',
    scope_date_start TIMESTAMPTZ NOT NULL,
    scope_date_end TIMESTAMPTZ NOT NULL,
    include_context BOOLEAN NOT NULL DEFAULT TRUE,
    include_sensor_quality BOOLEAN NOT NULL DEFAULT TRUE,
    include_ai_synthesis BOOLEAN NOT NULL DEFAULT TRUE,
    recipient_name VARCHAR(128),
    recipient_facility VARCHAR(255),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL DEFAULT 'active', -- 'active', 'revoked', 'expired'
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_clinical_consents_user_status ON clinical_consents(user_id, status);

CREATE TABLE clinical_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    consent_id UUID NOT NULL REFERENCES clinical_consents(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'draft', -- 'draft', 'reviewed', 'redacted', 'approved', 'revoked'
    summary_payload JSONB NOT NULL DEFAULT '{}',
    redaction_mask JSONB NOT NULL DEFAULT '{}',
    recommended_specialties JSONB NOT NULL DEFAULT '[]',
    routing_rationale TEXT NOT NULL DEFAULT '',
    approval_token VARCHAR(128),
    approved_at TIMESTAMPTZ,
    pdf_storage_path TEXT,
    checksum_sha256 VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_clinical_summaries_user_status ON clinical_summaries(user_id, status);
```

