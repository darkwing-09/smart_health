# INCIDENT_RESPONSE_RUNBOOK.md — Personal Health OS Incident Response & Rollback Runbook

This runbook establishes standard operating procedures (SOP) for detecting, triaging, escalating, mitigating, and post-morteming incidents during the Personal Health OS production pilot.

---

## 1. Incident Severity Matrix

| Severity | Definition | Target Response (MTTA) | Target Mitigation (MTTR) | Communication Cadence |
| :--- | :--- | :--- | :--- | :--- |
| **SEV-1 (Critical)** | Core API offline; Level 4 Urgent alerts dropped or delayed >60s; data corruption; multi-tenant PHI breach. | < 5 minutes | < 30 minutes | Every 15 mins to executive/clinical leads. |
| **SEV-2 (Major)** | TimescaleDB ingestion degraded; background ARQ worker halted; daily report generation delayed >2 hours; FCM delivery failing. | < 15 minutes | < 2 hours | Hourly updates to pilot team. |
| **SEV-3 (Minor)** | Non-critical endpoint error rate >2%; slow query latency (>500ms); single wearable sync failures. | < 1 hour | < 8 hours | Daily summary. |
| **SEV-4 (Low)** | Cosmetic UI glitch; minor documentation discrepancy; non-blocking telemetry warning. | Next business day | Next sprint release | Ticket tracker. |

---

## 2. On-Call Roles & Escalation Chain

1. **Incident Commander (IC)**: Leads triage, coordinates remediation, and owns executive/clinical communication.
2. **Operations / SRE Lead**: Investigates container logs, database metrics, Redis queues, and network ingress.
3. **Clinical Safety Auditor**: Assesses patient safety impact if anomaly detection or Level 4 alerts are impaired; determines if pilot halt is required.
4. **Security Officer**: Activated immediately upon suspected data breach, authentication bypass, or ActionGate compromise.

---

## 3. Immediate Diagnostic Protocol

Execute diagnostic checks across system components:

```bash
# 1. Check API Liveness and Readiness
curl -s http://localhost:8000/health | jq .
curl -s http://localhost:8000/ready | jq .

# 2. Check Database Connectivity and Active Connections
psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c "
SELECT count(*), state FROM pg_stat_activity GROUP BY state;
"

# 3. Check TimescaleDB Hypertable Chunk Status
psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c "
SELECT chunk_name, range_start, range_end, is_compressed 
FROM timescaledb_information.chunks 
WHERE hypertable_name = 'measurements' 
ORDER BY range_end DESC LIMIT 5;
"

# 4. Check Redis Queue Depth and ARQ Workers
redis-cli -h $REDIS_HOST -p $REDIS_PORT info cpu
redis-cli -h $REDIS_HOST -p $REDIS_PORT info memory
redis-cli -h $REDIS_HOST -p $REDIS_PORT llen "arq:queue"

# 5. Check API Application Logs for Unhandled Exceptions (RFC 7807)
docker logs personal-healthos-api --tail 100 | grep -E "ERROR|CRITICAL"
```

---

## 4. Failure Scenario Remediation Runbooks

### Scenario A: TimescaleDB Connection Exhaustion / Lockup (SEV-1 / SEV-2)

- **Symptoms**: `/ready` returns HTTP 503 (`postgresql: unhealthy`); API requests timeout with SQLAlchemy `TimeoutError`.
- **Mitigation Steps**:
  1. Inspect blocking queries:
     ```sql
     SELECT pid, age(clock_timestamp(), query_start), usename, query 
     FROM pg_stat_activity 
     WHERE state != 'idle' AND age(clock_timestamp(), query_start) > interval '10 seconds';
     ```
  2. Terminate hung worker queries:
     ```sql
     SELECT pg_terminate_backend(pid) 
     FROM pg_stat_activity 
     WHERE state != 'idle' AND query LIKE '%measurements%' AND pid != pg_backend_pid();
     ```
  3. Scale connection pool: Increase `DATABASE_POOL_SIZE` in environment configuration and restart API container gracefully.

### Scenario B: Redis / ARQ Worker Queue Backlog (SEV-2)

- **Symptoms**: `evaluate_acute_measurements` or `compile_daily_report` delayed; `llen "arq:queue"` > 500.
- **Mitigation Steps**:
  1. Verify ARQ worker process is running:
     ```bash
     ps aux | grep arq
     ```
  2. Scale worker instances:
     ```bash
     docker compose up -d --scale worker=3
     ```
  3. Ingestion continues uninterrupted because `IngestionService` enqueues fail-open and commits directly to PostgreSQL.

### Scenario C: Downstream LLM Outage (Anthropic / OpenAI / Gemini) (SEV-2)

- **Symptoms**: Graph synthesis nodes fail with HTTP 502/503 or `APITimeoutError`.
- **System Invariant**: Zero clinical diagnostic interruption.
- **Mitigation Steps**:
  1. The deterministic engine operates autonomously without LLM availability.
  2. Health Intelligence Agent falls back immediately to deterministic explanation template:
     `"A significant deviation in heart rate (+38 bpm vs 30-day baseline) was detected at [timestamp]. Observed: 110 bpm; Baseline: 72 bpm."`
  3. Safety & Policy Agent ensures Level 4 emergency disclaimers remain attached.
  4. Daily Report Agent falls back to metric summary tables and stoic quotes.

### Scenario D: Push Notification Delivery Failure (FCM) (SEV-1 for Level 4)

- **Symptoms**: `dispatch_notification` logs `FCMDeliveryError`; notifications transition to `FAILED` or `DEAD_LETTER`.
- **Mitigation Steps**:
  1. Verify FCM credentials JSON validity:
     ```bash
     python -c "from app.services.fcm import get_fcm_client; print(get_fcm_client().is_dry_run)"
     ```
  2. Check WebSocket broadcast fallback: Ensure patients with active mobile sessions receive findings via authenticated WebSocket stream (`/v1/notifications/ws`).
  3. Mobile App Local Polling Fallback: Android companion app polls `/v1/notifications?since=[ts]` every 60s when FCM token is invalid or push fails.

### Scenario E: Mobile Clock Skew or Massive Replay Floods (SEV-3)

- **Symptoms**: Ingestion endpoint reports elevated `invalid_count` or HTTP 422 with `FUTURE_TIMESTAMP`.
- **Mitigation Steps**:
  1. System automatically quarantines points where `recorded_at > now + 5 minutes` with `data_quality_flag = 'invalid'`.
  2. Quarantined records are excluded from baseline calculations and anomaly alerting.
  3. Replay batches are deduplicated atomically via `SyncBatch` idempotency table and hypertable composite primary key.

### Scenario F: Multi-Tenant Unauthorized Resource Probe (SEV-1 Security Incident)

- **Symptoms**: Audit logs record 404 responses on non-owned resource IDs; suspicious JWT tokens.
- **Mitigation Steps**:
  1. Invalidate compromised user session / JWT:
     ```bash
     redis-cli set "revoked_token:[jti]" "1" EX 86400
     ```
  2. Extract offending IP and block via ingress firewall:
     ```bash
     iptables -A INPUT -s [ATTACKER_IP] -j DROP
     ```
  3. Verify zero information disclosure: System returns 404 on non-owned resources to conceal existence.
  4. Initiate DPDP breach assessment protocol.

---

## 5. Backup & Point-in-Time Restore (PITR)

### Regular Automated Backups
Daily physical backup via `pg_dump` with custom format:
```bash
pg_dump -h $DB_HOST -U $DB_USER -Fc -d $DB_NAME -f /backups/healthos_db_$(date +%Y%m%d_%H%M%S).dump
```

### Verification Restore Procedure
To verify backup integrity on a non-production instance:
```bash
createdb -h $DB_HOST -U $DB_USER healthos_db_verify
pg_restore -h $DB_HOST -U $DB_USER -d healthos_db_verify /backups/healthos_db_latest.dump
psql -h $DB_HOST -U $DB_USER -d healthos_db_verify -c "SELECT count(*) FROM measurements;"
dropdb -h $DB_HOST -U $DB_USER healthos_db_verify
```

---

## 6. Safe Rollback Procedure

When an unresolvable defect or data corruption occurs during a deployment:

1. **Traffic Drain**: Switch ingress load balancer to maintenance mode (`HTTP 503 Maintenance`).
2. **Container Rollback**: Deploy previous verified container image digest:
   ```bash
   docker service update --image personal-healthos-api:phase8-certified personal-healthos-api
   docker service update --image personal-healthos-worker:phase8-certified personal-healthos-worker
   ```
3. **Database Schema Rollback (if migrations were executed)**:
   ```bash
   alembic downgrade -1
   ```
4. **Cache Flush**: Clear volatile queue state while preserving idempotency records:
   ```bash
   redis-cli del "arq:queue"
   ```
5. **Health Probe Verification**: Confirm `/health` and `/ready` return 200 before restoring ingress traffic.

---

## 7. Data Retention & DPDP 2023 Erasure Protocol

Under India Digital Personal Data Protection (DPDP) Act 2023:
1. **Consent Revocation**: Upon user withdrawal (`POST /v1/care/consent/{id}/revoke`), external access to medical summaries is immediately revoked (HTTP 403 / 404).
2. **Right to Erasure**:
   ```sql
   -- Purge user measurements and findings
   DELETE FROM measurements WHERE user_id = :user_id;
   DELETE FROM findings WHERE user_id = :user_id;
   DELETE FROM clinical_summaries WHERE user_id = :user_id;
   -- Anonymize audit log entry
   UPDATE audit_logs SET user_id = NULL, details = '{"status": "erased_under_dpdp"}' WHERE user_id = :user_id;
   ```
3. Cryptographic shredding ensures that any cached vector PDF reports or exported summaries cannot be decrypted or verified without valid ActionGate tokens.
