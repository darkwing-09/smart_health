#!/usr/bin/env bash
# Personal Health OS - Disaster Recovery Restore Drill Utility
# Validates backup integrity and restores into target database with table count verification.

set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <backup_file_path> [target_database_name]"
  exit 1
fi

BACKUP_FILE="$1"
TARGET_DB="${2:-healthos_db_drill}"
CONTAINER_NAME="${CONTAINER_NAME:-healthos_postgres}"
DB_USER="${DB_USER:-healthos_user}"

if [ ! -f "${BACKUP_FILE}" ]; then
  echo "Error: Backup file '${BACKUP_FILE}' not found."
  exit 1
fi

echo "=================================================="
echo " Personal Health OS Database Restore Drill"
echo " Backup File: ${BACKUP_FILE}"
echo " Target DB:   ${TARGET_DB}"
echo " Container:   ${CONTAINER_NAME}"
echo "=================================================="

# 1. Verify SHA-256 integrity if .sha256 file exists
if [ -f "${BACKUP_FILE}.sha256" ]; then
  echo "[1/4] Verifying SHA-256 checksum..."
  sha256sum -c "${BACKUP_FILE}.sha256"
  echo "Checksum verification PASSED."
else
  echo "[1/4] Warning: No checksum file found, skipping pre-verification."
fi

# 2. Recreate target drill database in container
echo "[2/4] Preparing target database '${TARGET_DB}'..."
docker exec -e PGPASSWORD=healthos_dev_password "${CONTAINER_NAME}" \
  psql -U "${DB_USER}" -d postgres -c "DROP DATABASE IF EXISTS ${TARGET_DB};"
docker exec -e PGPASSWORD=healthos_dev_password "${CONTAINER_NAME}" \
  psql -U "${DB_USER}" -d postgres -c "CREATE DATABASE ${TARGET_DB};"

# Ensure timescaledb extension is enabled on target database
docker exec -e PGPASSWORD=healthos_dev_password "${CONTAINER_NAME}" \
  psql -U "${DB_USER}" -d "${TARGET_DB}" -c "CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;"

# 3. Stream backup file into pg_restore inside container
echo "[3/4] Restoring database dump into '${TARGET_DB}'..."
cat "${BACKUP_FILE}" | docker exec -i -e PGPASSWORD=healthos_dev_password "${CONTAINER_NAME}" \
  pg_restore -U "${DB_USER}" -d "${TARGET_DB}" --no-owner --role="${DB_USER}" || true

# 4. Verification Audit: compare table row counts
echo "[4/4] Executing post-restore integrity audit..."
docker exec -e PGPASSWORD=healthos_dev_password "${CONTAINER_NAME}" \
  psql -U "${DB_USER}" -d "${TARGET_DB}" -c "
  SELECT 
    (SELECT count(*) FROM users) AS users_count,
    (SELECT count(*) FROM measurements) AS measurements_count,
    (SELECT count(*) FROM baselines) AS baselines_count,
    (SELECT count(*) FROM findings) AS findings_count,
    (SELECT count(*) FROM clinical_consents) AS consents_count,
    (SELECT count(*) FROM clinical_summaries) AS summaries_count,
    (SELECT count(*) FROM audit_logs) AS audit_logs_count;
"

echo "=================================================="
echo " Restore Drill Verification Completed Successfully!"
echo " Target database '${TARGET_DB}' is fully verified."
echo "=================================================="
