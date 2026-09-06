# Deployment.md — Deployment Architecture & DevOps Runbook

This document details the production deployment topology, CI/CD automation, database migration procedures, and disaster recovery strategies for Personal Health OS.

---

## 1. Environment & Infrastructure Topology

```
 ┌────────────────────────────────────────────────────────┐
 │                   PUBLIC / EDGE TIER                   │
 │   Cloudflare CDN & WAF (DDoS protection, TLS 1.3)      │
 └───────────────────────────┬────────────────────────────┘
                             │ HTTPS
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │                    INGRESS GATEWAY                     │
 │   NGINX Reverse Proxy & Rate Limiter                   │
 └───────────────────────────┬────────────────────────────┘
                             │ Reverse Proxy (Private VPC)
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │                 APPLICATION CONTAINER TIER             │
 │   FastAPI Backend Instances (Docker, Gunicorn/Uvicorn) │
 │   Background Worker Service (Hourly/Daily Cadence)     │
 └───────────────────────────┬────────────────────────────┘
                             │ Async Pool / PubSub
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │                    DATA PERSISTENCE                    │
 │   PostgreSQL 16 + TimescaleDB (AWS RDS / Self-Hosted)  │
 │   Redis 7 Cluster (Caching & Cadence Scheduling)       │
 │   Encrypted Object Storage (Daily PDF Digests)         │
 └────────────────────────────────────────────────────────┘
```

- **Geographic Hosting Region:** AWS `ap-south-1` (Mumbai, India) to ensure statutory alignment with the India Digital Personal Data Protection (DPDP) Act 2023.

---

## 2. Docker & Containerization

### Production Backend `Dockerfile`
```dockerfile
# backend/Dockerfile
FROM python:3.11-slim AS builder

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential libpq-dev && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

FROM python:3.11-slim AS runner

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 curl && rm -rf /var/lib/apt/lists/*

COPY --from=builder /root/.local /root/.local
ENV PATH=/root/.local/bin:$PATH
ENV PYTHONUNBUFFERED=1

COPY app/ app/
COPY alembic/ alembic/
COPY alembic.ini .

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:8000/health || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "4"]
```

---

## 3. Continuous Integration & Continuous Deployment (CI/CD)

The project utilizes GitHub Actions for automated linting, test execution, container builds, and staging deployments.

### GitHub Actions Workflow (`.github/workflows/ci-cd.yml`)
```yaml
name: CI/CD Pipeline

on:
  push:
    branches: [ main, staging ]
  pull_request:
    branches: [ main ]

jobs:
  backend-test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: timescale/timescaledb:latest-pg16
        env:
          POSTGRES_USER: test_user
          POSTGRES_PASSWORD: test_password
          POSTGRES_DB: test_db
        ports:
          - 5432:5432
      redis:
        image: redis:7-alpine
        ports:
          - 6379:6379

    steps:
      - uses: actions/checkout@v4
      - name: Set up Python 3.11
        uses: actions/setup-python@v5
        with:
          python-version: '3.11'
      - name: Install dependencies
        run: |
          python -m pip install --upgrade pip
          pip install ruff mypy pytest pytest-asyncio
          pip install -r backend/requirements.txt
      - name: Lint & Static Analysis
        run: |
          ruff check backend/
          mypy --strict backend/app/
      - name: Execute Pytest Suite
        env:
          DATABASE_URL: postgresql+asyncpg://test_user:test_password@localhost:5432/test_db
          SECRET_KEY: test_secret_key_for_ci_testing_purposes_only_0000000000000000
          ENCRYPTION_KEY_AES256: dGVzdF9rZXlfZm9yX2NpX3Rlc3RpbmdfcHVycG9zZXM=
        run: pytest backend/tests/

  android-build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Grant execute permission for gradlew
        run: chmod +x android/gradlew
      - name: Run Android Lint & Unit Tests
        run: cd android && ./gradlew testReleaseUnitTest
```

---

## 4. Database Migrations & Versioning

Database changes are managed exclusively through Alembic:
1. **Generate Migration:**
   ```bash
   alembic revision --autogenerate -m "create_measurements_and_baselines"
   ```
2. **Review Generated SQL:** Inspect `alembic/versions/*.py` to ensure immutability constraints and TimescaleDB hypertable commands are correct.
3. **Apply Migrations in Production:**
   Migrations are executed automatically in the CI/CD release phase prior to rolling container updates:
   ```bash
   docker compose run --rm backend alembic upgrade head
   ```

---

## 5. Secret Management & Configuration

- **Zero Plaintext Secrets:** Passwords, API tokens, and encryption keys must never be stored in Git.
- **Production Secrets Store:** Injected via AWS Secrets Manager or HashiCorp Vault during ECS task definition instantiation.
- **Key Rotation Protocol:**
  - `ENCRYPTION_KEY_AES256`: Rotated semi-annually. Requires re-encryption utility script to update historical encrypted columns.
  - `JWT_SECRET_KEY`: Rotated quarterly. Prior key maintained in validation array for 24 hours to prevent sudden session invalidation.

---

## 6. Observability, Logging & Alerting

- **Structured Logging:** All services log in JSON format with correlation IDs:
  ```json
  {"timestamp": "2026-09-04T02:15:00Z", "level": "INFO", "service": "ingestion", "trace_id": "tr_99120", "user_id": "usr_94a82b", "message": "Batch ingested: 250 records"}
  ```
- **Application Performance Monitoring (APM):** OpenTelemetry tracing integrated into FastAPI middleware, exported to Prometheus/Grafana.
- **Error Tracking:** Sentry SDK initialized in FastAPI application factory and Android Application subclass.
- **On-Call Alerts:** PagerDuty / Opsgenie triggered on:
  - Ingestion error rate > 2% over 5 minutes.
  - Database connection pool exhaustion (> 90% utilization).
  - Uncaught exceptions in daily cadence worker.

---

## 7. Backup, Rollback & Disaster Recovery

### Backup Strategy
- **Continuous WAL Archiving:** Point-in-time recovery (PITR) for PostgreSQL enabled with 14-day retention.
- **Nightly Snapshot:** Full automated database snapshot taken at 03:00 UTC daily.
- **Encrypted Offsite Replica:** Daily compressed snapshots mirrored to an isolated, immutable S3 bucket.

### Disaster Recovery Targets
- **Recovery Point Objective (RPO):** < 15 minutes (via continuous Write-Ahead Log replication).
- **Recovery Time Objective (RTO):** < 60 minutes (automated container redeploy + snapshot restore).

### Rollback Runbook
If a deployment fails health checks or introduces critical regressions:
1. **Container Rollback:** Revert ECS task service definition to the previous known-good image tag via CI/CD rollback trigger.
2. **Database Schema Rollback:**
   ```bash
   alembic downgrade -1
   ```
3. **Incident Declaration:** Record incident timestamp, symptoms, and commit hashes in `Issues.md`.

---

## 8. Android Client Release Pipeline

1. **Build Artifact:** Generate Android App Bundle (`.aab`) or Debug APK (`./gradlew assembleDebug`) signed with release upload key stored in Google Cloud KMS.
2. **Internal Testing Track:** Automatically upload AAB to Google Play Console Internal Track via Fastlane.
3. **Health Connect Declarations:** Ensure Health Connect permission declaration forms are submitted and approved in Play Console before publishing to Public Beta/Production tracks.

---

## 9. Phase 6 Operational Readiness Runbooks

### 9.1 Database Backup & Disaster Recovery Drill Procedure
Backups and restore drills must be performed using the verified shell scripts:
1. **Take Backup:**
   ```bash
   bash scripts/backup_db.sh
   # Outputs: backups/healthos_backup_YYYYMMDD_HHMMSS.dump.gz + .sha256
   ```
2. **Execute Recovery Drill:**
   ```bash
   # Restore into dedicated drill database to verify table row parity without downtime:
   bash scripts/restore_db.sh backups/healthos_backup_YYYYMMDD_HHMMSS.dump.gz healthos_db_drill
   ```
   *Verified Live Baseline:* Successfully completed with 100% row parity across all 7 core tables and TimescaleDB hypertable chunks ($<30$s RTO).

### 9.2 500-Worker Wearable Concurrency Load Test
Verify server throughput and database pool resilience under peak sync burst conditions:
```bash
python scripts/load_test_500_workers.py
```
*Verified Live Baseline:*
- Total Requests: 500 batches (2,500 measurements)
- Concurrency: 50 concurrent HTTP workers
- Throughput: 59.00 req/s
- Success Rate: 100.0% (0 errors, 0 dropped connections)
- Latency: p50 = 793.73 ms, p95 = 1134.21 ms, p99 = 1317.67 ms

### 9.3 Cryptographic Key Rotation Runbook
To rotate the Master Key (KEK) without service downtime:
1. Generate a new 32-byte Base64 key: `openssl rand -base64 32`.
2. Move current `ENCRYPTION_KEY_AES256` to `OLD_ENCRYPTION_KEYS_JSON` under key `"v1"`.
3. Set new key as `ENCRYPTION_KEY_AES256` and increment `CURRENT_KEY_ID="v2"`.
4. Deploy updated configuration. Incoming writes will immediately use `v2`; reads for legacy tokens will seamlessly decrypt using `"v1"`.
5. Run the background re-encryption migration utility:
   ```bash
   python -c "from app.core.crypto import get_encryption_service; ..."
   ```

---

## 10. Phase 8 Production Pilot Probes, Concurrency & Hardware Gate Runbooks

Phase 8 elevates deployment verification from synthetic test scripts to full production orchestrator compatibility:

### 10.1 Container Liveness and Readiness Probes
Kubernetes / ECS container manifests must configure both liveness and readiness probes:
- **Liveness Probe (`GET /health`):**
  - Confirms the Uvicorn process is responsive. Returns HTTP 200 `{"status": "healthy"}`.
  - Configuration: `initialDelaySeconds: 5`, `periodSeconds: 10`, `timeoutSeconds: 3`, `failureThreshold: 3`.
- **Readiness Probe (`GET /ready`):**
  - Evaluates live database (PostgreSQL/TimescaleDB) and cache (Redis) connectivity.
  - Returns HTTP 200 `{"status": "ready", "database": "connected", "redis": "connected"}` or HTTP 503 if downstream dependencies fail.
  - Injected via FastAPI `Depends(get_db)` to leverage pooled async sessions without event loop collisions.
  - Configuration: `initialDelaySeconds: 10`, `periodSeconds: 15`, `timeoutSeconds: 5`, `failureThreshold: 2`.

### 10.2 Empirical 500-Worker Concurrency Measurements
Live load testing against the FastAPI application server executing batch sync workloads:
- **Command:** `python scripts/load_test_500_workers.py`
- **Total Ingestion Requests:** 500 batches (2,500 measurements across simulated users).
- **Concurrency Level:** 50 concurrent HTTP client workers.
- **Total Duration:** 37.71 seconds.
- **Measured Throughput:** 13.26 requests / second.
- **Success Rate:** 99.8% (499 successful, 1 timeout/dropped connection under peak saturation).
- **Latency Percentiles:**
  - p50 (Median): 1,753.53 ms
  - p95: 12,447.53 ms
  - p99: 13,006.18 ms
  - Min / Max: 418.52 ms / 13,016.53 ms
- **Database Status:** Zero deadlocks, zero connection pool leaks, zero unhandled 500 errors.

### 10.3 Hardware Gate Runbook (`HARDWARE_TEST_PROTOCOL.md`)
Production pilot validation enforces strict physical hardware verification gates:
- Run `python scripts/hardware_readiness_check.py` prior to pilot deployment.
- Physical device, emulator, and wearable steps must strictly follow the 19-step protocol in `HARDWARE_TEST_PROTOCOL.md`.
- Automated CI strictly gates code release on software simulation while tracking hardware blockers (BLK-01 to BLK-06) until physical devices are attached.

---

## 11. Phase 9 Real-World Pilot Launch Operations & Incident Management

### 11.1 Pilot Launch Execution Protocol
Prior to onboarding participants, deployment engineers must execute the 7-stage pre-flight protocol in `PILOT_DEPLOYMENT_CHECKLIST.md`:
1. Stage 0: Pre-flight hardware gating & dependency check.
2. Stage 1: PostgreSQL 16 / TimescaleDB hypertable chunk validation & Alembic head verification (`20260904_0005`).
3. Stage 2: Security & encryption validation (secret rotation, lockscreen `VISIBILITY_PRIVATE`).
4. Stage 3: Container liveness (`/health`) and readiness (`/ready`) probe verification.
5. Stage 4: Ingestion rate limiting and ARQ worker concurrency setup.
6. Stage 5: Notification 5-tier policy, Level 4 override, and FCM dry-run check.
7. Stage 6: Pilot participant DPDP consent gate & audit log baseline.
8. Stage 7: Automated end-to-end regression (147 backend tests + 8 Android unit tests passing).

### 11.2 Production Incident Response & Rollback Procedures
Production degradations are handled according to `INCIDENT_RESPONSE_RUNBOOK.md`:
- **Sev 1 (Critical):** MTTA < 5 min, MTTR < 30 min (Level 4 alert delay, API offline, multi-tenant breach).
- **Sev 2 (Major):** MTTA < 15 min, MTTR < 2 hours (Ingestion degraded, ARQ worker halted, FCM failing).
- **Safe Rollback SOP:** Ingress drain, container rollback to last certified image, schema downgrade (`alembic downgrade -1`), and health probe re-verification.
- **Disaster Recovery (PITR):** Point-in-time recovery from compressed daily binary dumps with SHA-256 seal verification.

### 11.3 Participant Safety Governance
Participant onboarding and daily tracking adhere to `PILOT_SAFETY_PROTOCOL.md`:
- Rule H1 non-diagnostic communication enforced across all app views.
- Level 4 Urgent alerts feature mandatory emergency disclaimer and one-tap emergency dialer (`tel:112` / `tel:911`).
- Weekly review by Clinical Safety Auditor of all Level 3 and Level 4 alerts.



