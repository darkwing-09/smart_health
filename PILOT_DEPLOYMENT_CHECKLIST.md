# PILOT_DEPLOYMENT_CHECKLIST.md — Personal Health OS Production Pilot Launch Checklist

This checklist defines the authoritative, step-by-step pre-flight, deployment, and verification protocol required to transition Personal Health OS into a controlled real-world pilot. Every gate must be empirically verified and signed off before admitting participant data.

---

## Stage 0: Pre-Flight Hardware Reality & Dependency Gates

Before staging or production deployment, verify all physical and cloud dependencies against the zero-fabrication inventory:

- [ ] **GATE-0.1: Android Runtime / Device Verification**
  - Physical Android 14+ (API 34) device available: `adb devices` returns 1+ authorized devices.
  - *If Physical Device Unavailable (BLK-02)*: Local Android 34 Google Play AVD booted (`emulator -avd HealthOS_API34 -no-snapshot`).
  - *If Network Prevents SDK Image Download (BLK-01)*: Acknowledge software-only certification; physical device validation remains `BLOCKED`.
- [ ] **GATE-0.2: Health Connect Runtime & Permissions**
  - Verify Health Connect package (`com.google.android.apps.healthdata`) is installed and updated.
  - Verify user granted permissions for: `HEART_RATE`, `STEPS`, `SLEEP_STAGE`, `OXYGEN_SATURATION`, `RESPIRATORY_RATE`.
- [ ] **GATE-0.3: Bluetooth Wearable Companion Link**
  - Wearable paired to host phone via OEM app (Wear OS / Galaxy Wearable / Garmin Connect).
  - Health Connect data write verified from OEM companion app.
  - *If Physical Wearable Unavailable (BLK-04)*: Retain hardware wearable validation as `BLOCKED`.
- [ ] **GATE-0.4: Firebase Cloud Messaging (FCM) Credentials**
  - Valid `serviceAccountKey.json` placed in backend secret store (or `FCM_CREDENTIALS_JSON` environment variable).
  - Test FCM dry-run toggle: When unset, system operates deterministically in dry-run mode (logs FCM payload, returns dummy message ID). Never claim live push without production Google service account credentials.

---

## Stage 1: Infrastructure & Data Stores

Verify core data stores and time-series hypertables:

- [ ] **GATE-1.1: PostgreSQL 16 & TimescaleDB 2.14+ Verification**
  - Connect to database: `psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME`
  - Verify TimescaleDB extension: `SELECT extversion FROM pg_extension WHERE extname = 'timescaledb';`
  - Verify `measurements` is an active hypertable:
    ```sql
    SELECT hypertable_name, chunk_sizing_func 
    FROM timescaledb_information.hypertables 
    WHERE hypertable_name = 'measurements';
    ```
  - Verify 7-day chunk intervals and compression policies:
    ```sql
    SELECT chunk_name, range_start, range_end 
    FROM timescaledb_information.chunks 
    WHERE hypertable_name = 'measurements';
    ```
- [ ] **GATE-1.2: Database Migration Integrity**
  - Verify Alembic current head:
    ```bash
    alembic current
    # Output must be: 20260904_0005 (head)
    ```
  - Verify zero pending migrations: `alembic check` returns clean.
- [ ] **GATE-1.3: Redis 7.2+ Cluster / In-Memory Cache**
  - Ping Redis: `redis-cli -h $REDIS_HOST -p $REDIS_PORT ping` (returns `PONG`).
  - Verify persistence configuration: `redis-cli CONFIG GET appendonly` (returns `yes`).
  - Verify memory limits: `redis-cli CONFIG GET maxmemory` and eviction policy `noeviction` or `volatile-lru`.

---

## Stage 2: Security & Cryptographic Boundaries

Enforce multi-tenant privacy, cryptographic integrity, and compliance gates:

- [ ] **GATE-2.1: Production Secret Key Generation & Rotation**
  - Verify `SECRET_KEY` is high-entropy 256-bit string (min 64 chars) sourced from environment variable, not code default.
  - Verify `ACTION_GATE_SECRET` is set independently from `SECRET_KEY`.
- [ ] **GATE-2.2: Password Hashing Enforcement**
  - Verify passlib Argon2 / bcrypt implementation with minimum work factor 12.
- [ ] **GATE-2.3: Lock Screen Privacy (HIPAA / DPDP)**
  - Android notification builder configured with `NotificationCompat.VISIBILITY_PRIVATE`.
  - Verify lockscreen preview displays app identity without leaking heart rate, timestamps, or severity diagnostics.
- [ ] **GATE-2.4: Tenant Isolation & Non-Disclosure Verification**
  - Unauthorized resource access returns HTTP 404 (never HTTP 403) to prevent ID enumeration.
- [ ] **GATE-2.5: Transport Layer Security (TLS 1.3)**
  - Ingress controller enforces HTTPS TLS 1.3 with HSTS enabled (`Strict-Transport-Security: max-age=31536000; includeSubDomains`).

---

## Stage 3: Container Runtime, Health Probes & Orchestration

Verify container liveness, readiness, and fault isolation:

- [ ] **GATE-3.1: Liveness Probe (`GET /health`)**
  - Responds with HTTP 200: `{"status": "healthy", "service": "personal-health-os-api"}` within <10ms.
  - Configured in Kubernetes / ECS: `initialDelaySeconds: 5`, `periodSeconds: 10`, `timeoutSeconds: 3`.
- [ ] **GATE-3.2: Readiness Probe (`GET /ready`)**
  - Evaluates both PostgreSQL and Redis connectivity:
    ```json
    {
      "status": "ready",
      "service": "personal-health-os-api",
      "checks": {
        "postgresql": {"status": "ok"},
        "redis": {"status": "ok"}
      }
    }
    ```
  - Configured in Kubernetes / ECS: `initialDelaySeconds: 10`, `periodSeconds: 15`, `failureThreshold: 3`.
- [ ] **GATE-3.3: Resource Bounds**
  - API container limits: CPU 1000m, Memory 1024Mi.
  - Worker container limits: CPU 1500m, Memory 2048Mi.

---

## Stage 4: Ingestion & Worker Setup

Configure real-time and background workers:

- [ ] **GATE-4.1: API Rate Limiting**
  - Sync batch limit: `RATE_LIMIT_SYNC_PER_MIN = 60` requests/minute per authenticated user.
  - Auth limit: `RATE_LIMIT_AUTH_PER_MIN = 10` requests/minute per IP address.
- [ ] **GATE-4.2: ARQ Background Worker Pool**
  - Start ARQ worker: `arq app.worker.WorkerSettings`
  - Verify registered functions: `evaluate_acute_measurements`, `compile_daily_report`, `dispatch_scheduled_notifications`.
  - Max concurrent jobs: 10; Job timeout: 300s.
  - Dead-letter routing verified for unhandled task exceptions.
- [ ] **GATE-4.3: Fail-Open Ingestion Invariant**
  - Ingestion API persists to PostgreSQL even if Redis is unreachable (`evaluate_acute_measurements` enqueued fail-open).

---

## Stage 5: Notification & Delivery Verification

Validate multi-channel alert delivery:

- [ ] **GATE-5.1: 5-Tier Policy Configuration**
  - Level 0 (Info): Silent timeline entry only.
  - Level 1 (Insight): Daily Digest staging.
  - Level 2 (Attention): In-app notification feed; suppressed during quiet hours.
  - Level 3 (Important): In-app feed + FCM push; postponed during quiet hours.
  - Level 4 (Urgent): In-app feed + high-priority FCM push + WebSocket stream.
- [ ] **GATE-5.2: Level 4 Quiet Hours Override**
  - Verify Level 4 alerts unconditionally bypass quiet hours (22:00–07:00).
  - Verify mandatory non-diagnostic emergency disclaimer is attached:
    > "SAFETY NOTICE: A significant physiological deviation was recorded. Personal Health OS does not provide medical diagnoses..."
- [ ] **GATE-5.3: WebSocket Streaming Ingestion**
  - Authenticated WebSocket endpoint (`/v1/notifications/ws`) verifies JWT and streams real-time findings with sequence replay.

---

## Stage 6: Pilot Participant Provisioning & Consent Gate

Admit pilot participants according to clinical safety protocols:

- [ ] **GATE-6.1: Informed Consent Signing**
  - Participant executes written and digital DPDP 2023 consent agreement.
  - Digital consent recorded in `clinical_consents` table with purpose, permitted metrics, and expiration date.
- [ ] **GATE-6.2: Non-Diagnostic Onboarding Communication**
  - Participant briefed on non-diagnostic boundaries: System is an investigational health tracker, NOT an ICU monitor or diagnostic medical device.
- [ ] **GATE-6.3: Audit Trail Baseline**
  - Initial enrollment logged in `audit_logs` table with admin user ID, timestamp, and SHA-256 hash.

---

## Stage 7: Post-Deployment Smoke Tests & Go/No-Go Gate

- [ ] **TEST-7.1: Automated End-to-End Regression Suite**
  - Execute full test suite: `pytest -v backend/tests/` (147/147 passing).
- [ ] **TEST-7.2: Android Unit & Lint Verification**
  - Run `./gradlew testDebugUnitTest` (8/8 passing).
  - Run `./gradlew lintDebug` (0 errors).
- [ ] **GO / NO-GO CRITERIA:**
  - **GO**: All Stage 0–6 gates passed, automated regression 100%, physical devices connected or documented blockers isolated.
  - **NO-GO**: Any failing test, missing emergency disclaimer, unmasked lockscreen PHI, or missing consent record.
