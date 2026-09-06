#!/usr/bin/env bash
# Personal Health OS - Database Backup Utility
# Performs consistent, compressed pg_dump of TimescaleDB and emits SHA-256 checksum.

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-backups}"
mkdir -p "${BACKUP_DIR}"

TIMESTAMP=$(date -u +"%Y%m%d_%H%M%SZ")
BACKUP_FILE="${BACKUP_DIR}/healthos_db_backup_${TIMESTAMP}.dump"
CONTAINER_NAME="${CONTAINER_NAME:-healthos_postgres}"
DB_USER="${DB_USER:-healthos_user}"
DB_NAME="${DB_NAME:-healthos_db}"

echo "=================================================="
echo " Starting Personal Health OS Database Backup"
echo " Container: ${CONTAINER_NAME}"
echo " Database:  ${DB_NAME}"
echo " Target:    ${BACKUP_FILE}"
echo "=================================================="

# Execute pg_dump inside container using custom binary format (-Fc) with compression
docker exec -e PGPASSWORD=healthos_dev_password "${CONTAINER_NAME}" \
  pg_dump -U "${DB_USER}" -d "${DB_NAME}" -Fc --verbose > "${BACKUP_FILE}"

FILE_SIZE=$(du -h "${BACKUP_FILE}" | cut -f1)
SHA256_HASH=$(sha256sum "${BACKUP_FILE}" | cut -d' ' -f1)

echo "=================================================="
echo " Backup Completed Successfully"
echo " File:     ${BACKUP_FILE}"
echo " Size:     ${FILE_SIZE}"
echo " SHA-256:  ${SHA256_HASH}"
echo "=================================================="

# Save checksum file for automated integrity verification during restore drills
echo "${SHA256_HASH}  ${BACKUP_FILE}" > "${BACKUP_FILE}.sha256"
