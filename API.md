# API.md — Complete API Contract Specification

This document defines the complete RESTful HTTP API contract for Personal Health OS. All endpoints operate over HTTPS with TLS 1.3.

---

## 1. Global Standards & Error Protocol

### Authentication & Headers
- **Scheme:** HTTP Bearer Authentication with JWT (`Authorization: Bearer <token>`).
- **Idempotency:** State-altering batch endpoints require `Idempotency-Key: <UUIDv4>`.
- **Content Type:** `application/json; charset=utf-8` unless requesting PDF binary (`application/pdf`).

### Standard RFC 7807 Error Response
```json
{
  "type": "https://api.healthos.local/v1/errors/validation-failed",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "Field 'recorded_at' must be a valid UTC ISO-8601 timestamp in the past.",
  "instance": "/v1/sync/batch"
}
```

---

## 2. API Classification Overview

| Domain | Visibility | Purpose |
| :--- | :--- | :--- |
| `/v1/auth/*` | Public / Client | User authentication, device registration, token refresh |
| `/v1/sync/*` | Mobile Client | Batch biometric ingestion, sync status checking |
| `/v1/measurements/*` | Mobile Client | Querying normalized health timeline |
| `/v1/findings/*` | Mobile Client | Querying anomalies, acknowledging alerts |
| `/v1/reports/*` | Mobile Client | Querying daily digests, downloading vector PDFs |
| `/v1/care/*` | Mobile Client | Care navigation, hospital research, visit summary generation |
| `/internal/*` | Internal Services | Worker cadence triggers, agent orchestration, audit logs |

---

## 3. Public & Mobile Client API Endpoints

### 3.1 Authentication & Devices

#### `POST /v1/auth/login`
Authenticates user and returns JWT token pair.
- **Request Body:**
  ```json
  {
    "email": "user@example.com",
    "password": "SecurePassword123!"
  }
  ```
- **Response (`200 OK`):**
  ```json
  {
    "access_token": "eyJhbGciOi...",
    "refresh_token": "eyJhbGciOi...",
    "token_type": "bearer",
    "expires_in": 3600,
    "user_id": "usr_94a82b"
  }
  ```

#### `POST /v1/devices/register`
Registers the mobile phone and paired smartwatch.
- **Request Body:**
  ```json
  {
    "device_type": "watch",
    "brand": "Google",
    "model": "Pixel Watch 2",
    "os_version": "Wear OS 4.0",
    "fcm_token": "dK8f_...fcm"
  }
  ```
- **Response (`201 Created`):**
  ```json
  {
    "device_id": "dev_7c3a91",
    "user_id": "usr_94a82b",
    "paired_at": "2026-09-04T00:00:00Z",
    "status": "ACTIVE"
  }
  ```

---

### 3.2 Synchronization & Batch Ingestion

#### `POST /v1/sync/batch`
Ingests a batch of raw/normalized measurements from Health Connect.
- **Headers:** `Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000`
- **Request Body:**
  ```json
  {
    "source_id": "src_health_connect",
    "device_id": "dev_7c3a91",
    "client_sync_timestamp": "2026-09-04T02:30:00Z",
    "measurements": [
      {
        "source_record_id": "hc_hr_9921",
        "metric_type": "heart_rate",
        "value": 72.0,
        "unit": "bpm",
        "recorded_at": "2026-09-04T02:00:00Z",
        "confidence": 0.95,
        "data_quality_flag": "nominal"
      },
      {
        "source_record_id": "hc_step_102",
        "metric_type": "steps",
        "value": 45.0,
        "unit": "count",
        "recorded_at": "2026-09-04T02:01:00Z",
        "confidence": 1.0,
        "data_quality_flag": "nominal"
      }
    ]
  }
  ```
- **Response (`200 OK`):**
  ```json
  {
    "status": "SUCCESS",
    "batch_id": "550e8400-e29b-41d4-a716-446655440000",
    "accepted_count": 2,
    "duplicate_count": 0,
    "ingested_at": "2026-09-04T02:30:01Z"
  }
  ```

---

### 3.3 Timeline & Measurements

#### `GET /v1/measurements/timeline`
Queries normalized timeline metrics over a time range.
- **Query Params:**
  - `metric_type=heart_rate`
  - `start_time=2026-09-03T00:00:00Z`
  - `end_time=2026-09-04T00:00:00Z`
  - `limit=500`
- **Response (`200 OK`):**
  ```json
  {
    "metric_type": "heart_rate",
    "count": 1,
    "measurements": [
      {
        "id": "meas_381029",
        "value": 72.0,
        "unit": "bpm",
        "recorded_at": "2026-09-04T02:00:00Z",
        "confidence": 0.95,
        "quality": "nominal",
        "provenance": {
          "source": "health_connect",
          "device_model": "Pixel Watch 2"
        }
      }
    ]
  }
  ```

---

### 3.4 Findings & Anomalies

#### `GET /v1/findings`
Retrieves findings filtered by status and severity.
- **Query Params:** `status=notified&min_severity=worth_monitoring`
- **Response (`200 OK`):**
  ```json
  {
    "findings": [
      {
        "id": "fnd_88310a",
        "metric_type": "heart_rate",
        "severity": "potentially_concerning",
        "status": "notified",
        "first_detected_at": "2026-09-04T02:15:00Z",
        "explanation": {
          "what_changed": "A sustained elevation in resting heart rate was recorded during deep sleep hours.",
          "measurements_caused": ["Resting heart rate measured at 104 bpm at 02:15 UTC"],
          "baseline_difference": "11.2 standard deviations above 30-day circadian mean (58.2 bpm).",
          "historical_context": "Zero similar occurrences in past 30 days.",
          "confidence_and_data_quality": "High confidence (98%), unbroken optical sensor trace.",
          "why_it_matters": "Indicates acute physiological stress or elevated sympathetic activity during sleep.",
          "next_steps": [
            "Rest and hydrate.",
            "Seek emergency care if experiencing chest pain or lightheadedness."
          ]
        }
      }
    ]
  }
  ```

#### `POST /v1/findings/{finding_id}/acknowledge`
User acknowledges receipt of an alert.
- **Response (`200 OK`):**
  ```json
  {
    "id": "fnd_88310a",
    "status": "acknowledged",
    "acknowledged_at": "2026-09-04T06:30:00Z"
  }
  ```

---

### 3.5 Daily Health Reports

#### `GET /v1/reports/daily`
Lists daily reports for the authenticated user.
- **Query Params:** `limit=7`
- **Response (`200 OK`):**
  ```json
  {
    "reports": [
      {
        "report_id": "rep_20260903",
        "date": "2026-09-03",
        "generation_status": "complete",
        "closing_quote": "To cultivate calm in the body is to prepare the mind for clarity.",
        "pdf_download_url": "/v1/reports/daily/rep_20260903/download"
      }
    ]
  }
  ```

#### `GET /v1/reports/daily/{report_id}/download`
Downloads the vector PDF document directly.
- **Headers:** `Accept: application/pdf`
- **Response (`200 OK`):** Binary stream with `Content-Type: application/pdf`, `Content-Disposition: inline; filename="HealthReport_2026-09-03.pdf"`.

---

### 3.6 Care Navigation, Clinical Consent & Doctor Visit Summaries (Phase 5)

#### `POST /v1/care/consent`
Grants explicit, granular patient consent for clinical data sharing (DPDP Act 2023 compliant).
- **Request Body:**
  ```json
  {
    "purpose": "doctor_consultation",
    "scope_date_start": "2026-08-28T00:00:00Z",
    "scope_date_end": "2026-09-04T00:00:00Z",
    "permitted_metrics": ["heart_rate", "steps", "sleep_session"],
    "recipient_name": "Dr. Mehta",
    "recipient_facility": "Apollo Hospitals",
    "duration_days": 7
  }
  ```
- **Response (`201 Created`):**
  ```json
  {
    "consent_id": "cns_9281a0",
    "user_id": "usr_1029",
    "consent_version": "1.0.0",
    "purpose": "doctor_consultation",
    "permitted_metrics": ["heart_rate", "steps", "sleep_session"],
    "scope_date_start": "2026-08-28T00:00:00Z",
    "scope_date_end": "2026-09-04T00:00:00Z",
    "granted_at": "2026-09-04T02:00:00Z",
    "expires_at": "2026-09-11T02:00:00Z",
    "status": "active",
    "recipient_name": "Dr. Mehta"
  }
  ```

#### `GET /v1/care/consent/{consent_id}`
Inspects active consent parameters, remaining validity, and permitted data scopes.

#### `DELETE /v1/care/consent/{consent_id}`
Immediately revokes active consent. Downstream PDF export or data sharing is blocked immediately.
- **Response (`200 OK`):**
  ```json
  {
    "consent_id": "cns_9281a0",
    "status": "revoked",
    "revoked_at": "2026-09-04T02:15:00Z"
  }
  ```

#### `POST /v1/care/summary/draft`
Compiles longitudinal evidence into an initial Doctor Visit Summary draft.
- **Request Body:**
  ```json
  {
    "consent_id": "cns_9281a0"
  }
  ```
- **Response (`201 Created`):**
  ```json
  {
    "summary_id": "sum_88301",
    "user_id": "usr_1029",
    "consent_id": "cns_9281a0",
    "status": "draft",
    "approval_token": null,
    "checksum_sha256": "4b2e81fa9...",
    "recommended_specialties": ["Cardiology / Electrophysiology", "Internal Medicine"],
    "routing_rationale": "Sustained nocturnal resting vital elevation outside baseline.",
    "summary_payload": { ... },
    "created_at": "2026-09-04T02:05:00Z"
  }
  ```

#### `GET /v1/care/summary/{summary_id}`
Retrieves and previews the structured summary, redactions, and current approval state.

#### `POST /v1/care/summary/{summary_id}/redact`
Applies patient redactions to specific findings or metric categories.
- **Request Body:**
  ```json
  {
    "redact_finding_ids": ["fnd_88310a"],
    "redact_metrics": ["steps"]
  }
  ```
- **Response (`200 OK`):** Updated summary with `status: "redacted"` and updated SHA-256 checksum.

#### `POST /v1/care/summary/{summary_id}/approve`
Patient signs off on the finalized document. Issues cryptographically secure `approval_token`.
- **Request Body:**
  ```json
  {
    "confirm_approval": true
  }
  ```
- **Response (`200 OK`):** Summary with `status: "approved"`, `approval_token: "appr_7f39b1a..."`.

#### `GET /v1/care/summary/{summary_id}/export/pdf`
Downloads the sealed vector PDF. Requires `status == "approved"` and active consent.
- **Headers:** `Accept: application/pdf`
- **Response (`200 OK`):** Binary vector PDF with SHA-256 seal and non-diagnostic disclaimers.

#### `GET /v1/care/routing`
Evaluates deterministic specialty routing based strictly on objective mathematical deviations.
- **Response (`200 OK`):**
  ```json
  {
    "primary_specialty": "Cardiology / Electrophysiology",
    "secondary_specialties": ["Internal Medicine", "General Practice"],
    "rule_id": "RULE_SPEC_NOCTURNAL_CARDIO",
    "clinical_rationale": "Observed sustained resting heart rate deviation of 98 bpm (+40 bpm above baseline).",
    "urgency_tier": "prompt",
    "disclaimer": "CLINICAL ADVISORY: Recommended specialties are deterministic routing suggestions..."
  }
  ```

#### `POST /v1/care/research`
Initiates research into verified nearby facilities (Requires explicit user authorization).


---

## 4. Internal Worker & Cadence Trigger APIs

#### `POST /internal/cadence/hourly`
Triggered by worker cron every hour.
- **Headers:** `X-Internal-Secret: <SECRET>`
- **Response (`200 OK`):**
  ```json
  {
    "status": "COMPLETED",
    "users_evaluated": 1,
    "findings_created": 0,
    "findings_escalated": 0
  }
  ```

#### `POST /internal/cadence/daily`
Triggered every midnight UTC. Recomputes baselines and triggers report compilation.
- **Response (`200 OK`):**
  ```json
  {
    "status": "COMPLETED",
    "baselines_recalculated": 3,
    "reports_generated": 1
  }
  ```

---

## 5. Third-Party Integration API Maps

| Integration | Endpoint / Interface Pattern | Notes |
| :--- | :--- | :--- |
| **Android Health Connect** | `androidx.health.connect.client.HealthConnectClient#readRecords` | Native Android on-device IPC. |
| **Firebase Cloud Messaging** | `POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send` | Google FCM HTTP v1 API. |
| **WhatsApp Business Platform** | `POST https://graph.facebook.com/v19.0/{phone_number_id}/messages` | **DEFERRED (V1)** — Requires approved HSM template. |
| **Fitbit Web API** | `GET https://api.fitbit.com/1/user/-/activities/heart/date/{date}/1d.json` | **DEFERRED (V1)** — OAuth 2.0 PKCE flow. |
