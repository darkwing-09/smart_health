# Personal Health OS — Hardware Test Protocol & Pilot Runbook

**Document Identifier:** PROTOCOL-PHASE8-HW-001  
**Version:** 1.0.0-PROD-PILOT  
**Effective Date:** 2026-09-04  
**Classification:** Restricted / Medical Device Engineering Runbook  
**Authority:** Personal Health OS Architecture & Clinical Safety Board  
**Governing Standard:** Rule H1 (Zero Diagnostic Assertion), Rule H2 (Personal Baseline Primacy), Rule H8 (Urgent Quiet Hours Bypass), Rule H9 (12-Hour Alert Deduplication)

---

## 1. Executive Protocol Overview

This document specifies the authoritative, reproducible 19-step verification runbook for deploying and validating Personal Health OS across physical Android devices, wearable sensors, the Health Connect framework, and the high-throughput backend ingestion cluster.

Every step in this protocol enforces deterministic evaluation boundaries:
- **Zero Fabrication:** Never record simulated tests as physical hardware validations.
- **Hardware Readiness Isolation:** When physical hardware or emulators are unavailable, tests must execute via the deterministic 14-hop simulation engine (`scripts/simulate_health_connect_pipeline.py`) while explicitly retaining the `BLOCKED` status on hardware gates.
- **Fail-Safe Operation:** Data synchronization, anomaly evaluation, quiet hours enforcement, and statutory non-diagnostic disclosures must function deterministically regardless of peripheral device availability.

---

## 2. Hardware & Infrastructure Readiness Matrix

As verified by the automated system audit script (`scripts/hardware_readiness_check.py`) on 2026-09-04:

| Component | Target Specification | Detected System State | Status | Justification / Blocker Evidence |
| :--- | :--- | :--- | :--- | :--- |
| **Workstation SDK** | Android SDK 34 (UpsideDownCake) | Platform SDK 34 installed (`/home/darkwing/Android/Sdk`) | **VERIFIED** | SDK platforms, build-tools 34.0.0 present |
| **Android Debug Bridge**| ADB 1.0.41+ | ADB Version 1.0.41 (`/usr/lib/android-sdk/platform-tools/adb`) | **VERIFIED** | Daemon running, operational |
| **PostgreSQL Database** | TimescaleDB / Hypertable (Port 5435) | PostgreSQL 16.4 reachable on port 5435 | **VERIFIED** | Migration `20260904_0005` applied, hypertable active |
| **Redis Cache / Queue** | Redis 7+ (Port 6380) | Redis reachable on port 6380 (`PONG`) | **VERIFIED** | Ingestion & dedup queues active |
| **FCM Push Gateway** | Firebase HTTP v1 Credentials | `firebase-adminsdk-credentials.json` mock present | **PARTIAL** | Dry-run simulated; production service account required |
| **Physical Android Phone**| Android 14+ device over USB | `adb devices -l` returned 0 devices | **BLOCKED** | No physical Android smartphone connected via USB |
| **Android Virtual Device**| API 34 Google Play Image | `avdmanager list avd` returned 0 AVDs | **BLOCKED** | System image `system-images;android-34;google_apis;x86_64` not downloaded |
| **Health Connect Runtime**| Framework provider package | `com.google.android.apps.healthdata` unavailable | **BLOCKED** | Requires physical device or Google Play system image |
| **Physical Wearable** | BLE Smartwatch (Wear OS, Garmin) | No Bluetooth peripheral paired | **BLOCKED** | Dependent on physical Android smartphone host |

---

## 3. The 19-Step Hardware Verification Runbook

### Step 1: Host Workstation Environment Verification
- **Objective:** Verify build toolchain, JDK version, and Android SDK command-line utilities.
- **Command:**
  ```bash
  java -version
  adb --version
  $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --list_installed
  ```
- **Expected Outcome:** OpenJDK 17+, ADB 1.0.41+, Android SDK 34 installed.
- **Verification Status:** **VERIFIED**
- **Evidence:** JDK 17.0.18, ADB 1.0.41, platform-tools 34.0.5 confirmed.

---

### Step 2: Physical Android Device Connection via USB
- **Objective:** Establish ADB debugging session with target Android 14+ smartphone.
- **Command:**
  ```bash
  adb devices -l
  adb wait-for-device shell getprop ro.build.version.release
  ```
- **Expected Outcome:** Single authorized device listed with OS version $\ge 14$.
- **Verification Status:** **BLOCKED**
- **Blocker Reason:** No physical USB device connected to host.

---

### Step 3: APK Compilation & Incremental Installation
- **Objective:** Compile signed debug APK and push to connected target device.
- **Command:**
  ```bash
  cd android && ./gradlew assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **Expected Outcome:** `Success` output from package manager installer.
- **Verification Status:** **PARTIAL**
- **Evidence:** `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` compile cleanly; ADB install blocked by Step 2.

---

### Step 4: Health Connect Provider Discovery
- **Objective:** Verify standalone Health Connect runtime package on Android 13 or lower devices.
- **Command:**
  ```bash
  adb shell pm list packages | grep com.google.android.apps.healthdata
  ```
- **Expected Outcome:** Package found on pre-Android 14 devices.
- **Verification Status:** **BLOCKED**
- **Blocker Reason:** Requires connected device or emulator.

---

### Step 5: Android 14+ System Framework Health Connect Verification
- **Objective:** Confirm system-integrated Health Connect framework service is running on Android 14+.
- **Command:**
  ```bash
  adb shell cmd healthconnect status
  ```
- **Expected Outcome:** Service active, provider initialized.
- **Verification Status:** **BLOCKED**
- **Blocker Reason:** Requires connected device or emulator.

---

### Step 6: Application Launch & Authentication Flow
- **Objective:** Launch app via intent, authenticate user with backend, acquire JWT token.
- **Command:**
  ```bash
  adb shell am start -n com.healthos/.ui.MainActivity
  adb logcat -d -s HealthOSAuth:V
  ```
- **Expected Outcome:** HTTP 200 on `/v1/auth/login`, token stored in Android Keystore / EncryptedSharedPreferences.
- **Verification Status:** **BLOCKED**
- **Blocker Reason:** Requires UI execution environment.

---

### Step 7: Granular Health Connect Permission Delegation
- **Objective:** Request and grant runtime biometric read permissions (Heart Rate, Steps, Sleep, SpO2, Respiratory Rate).
- **Command:**
  ```bash
  adb shell am start -a androidx.health.ACTION_HEALTH_CONNECT_SETTINGS
  ```
- **Expected Outcome:** User approves permissions; `HealthConnectClient.permissionController.getGrantedPermissions()` returns all requested types.
- **Verification Status:** **BLOCKED**
- **Blocker Reason:** Requires user interactive UI on physical phone.

---

### Step 8: Room Encrypted Local Database Initialization
- **Objective:** Validate SQLCipher / Room encrypted storage for offline biometric buffering.
- **Command:**
  ```bash
  adb shell "run-as com.healthos ls -la databases/"
  ```
- **Expected Outcome:** `healthos_measurements.db` created with schema matching `MeasurementDao`.
- **Verification Status:** **PARTIAL**
- **Evidence:** Verified via `HealthSyncWorkerTest.kt` unit test suite; blocked on physical device.

---

### Step 9: WorkManager Periodic Sync Schedule Registration
- **Objective:** Register 15-minute battery-conscious background sync task.
- **Command:**
  ```bash
  adb shell dumpsys jobscheduler | grep com.healthos
  ```
- **Expected Outcome:** WorkManager periodic task scheduled with network type `CONNECTED`.
- **Verification Status:** **PARTIAL**
- **Evidence:** `HealthSyncWorker` configuration verified in Android unit tests; blocked on physical scheduler.

---

### Step 10: Wearable Pairing & BLE Telemetry Streaming
- **Objective:** Pair smartwatch (Samsung Galaxy Watch, Garmin, or Pixel Watch) via companion app.
- **Verification Status:** **BLOCKED**
- **Blocker Reason:** Requires physical smartwatch hardware and Bluetooth transceiver.

---

### Step 11: Wearable $\to$ Health Connect Ingestion Verification
- **Objective:** Confirm wearable writes raw biometric telemetry to local Health Connect content provider.
- **Command:**
  ```bash
  adb shell content query --uri content://androidx.health.connect/records
  ```
- **Verification Status:** **BLOCKED**
- **Blocker Reason:** Dependent on wearable hardware in Step 10.

---

### Step 12: Manual Android Sync Worker Trigger
- **Objective:** Trigger immediate execution of `HealthSyncWorker` via ADB.
- **Command:**
  ```bash
  adb shell cmd jobscheduler run -f com.healthos <JOB_ID>
  ```
- **Expected Outcome:** Worker reads new Health Connect records, formats sync batch, and posts to backend.
- **Verification Status:** **BLOCKED**
- **Blocker Reason:** Dependent on physical device execution in Step 2.

---

### Step 13: HTTPS Batch Transmission with Idempotency Key
- **Objective:** Dispatch formatted batch payload to `/v1/sync/batch` over TLS with `Idempotency-Key` header.
- **Verification Status:** **VERIFIED** (Software Protocol) / **BLOCKED** (USB Dispatch)
- **Evidence:** Verified end-to-end via Python integration test suite `test_data_quality_real_conditions.py`.

---

### Step 14: TimescaleDB Ingestion & Hypertable Partitioning
- **Objective:** Verify append-only storage into `measurements` hypertable with `ingested_at` provenance.
- **SQL Verification:**
  ```sql
  SELECT id, user_id, metric_type, value, unit, recorded_at, ingested_at, data_quality_flag
  FROM measurements
  ORDER BY ingested_at DESC LIMIT 5;
  ```
- **Expected Outcome:** Records persisted with accurate historical `recorded_at` timestamps.
- **Verification Status:** **VERIFIED**
- **Evidence:** Verified by `test_drill_01_offline_24h_sync` and `simulate_health_connect_pipeline.py`.

---

### Step 15: Deterministic Anomaly Evaluation & Baseline Profiling
- **Objective:** Detect deviations against rolling 30-day baseline; suppress exertion-correlated heart rate spikes.
- **Verification Status:** **VERIFIED**
- **Evidence:** Verified across 10 unit and integration tests in `test_phase3_baseline_anomaly_e2e.py` and `test_anomaly_math.py`.

---

### Step 16: FCM HTTP v1 Push Notification Dispatch
- **Objective:** Dispatch secure alert payload to device FCM token with zero raw PHI in payload body.
- **Verification Status:** **PARTIAL**
- **Evidence:** FCM payload builder and dry-run token dispatch verified in `test_fcm_service.py`; live device push blocked by Step 2.

---

### Step 17: In-App System Notification Display & Privacy Flag
- **Objective:** Present alert on Android notification shade with `NotificationCompat.VISIBILITY_PRIVATE`.
- **Command:**
  ```bash
  adb shell dumpsys notification --noredact | grep com.healthos
  ```
- **Expected Outcome:** Notification posted to `healthos_alerts_urgent` channel; lock screen preview redacted.
- **Verification Status:** **PARTIAL**
- **Evidence:** Verified by `NotificationPrivacyTest.kt` (`VISIBILITY_PRIVATE`, channels `healthos_alerts_attention`, `healthos_alerts_urgent`); blocked on physical screen.

---

### Step 18: Real-Time WebSocket Streaming & Catch-Up Protocol
- **Objective:** Stream real-time findings to active client session; deliver missed alerts upon reconnect.
- **Verification Status:** **VERIFIED**
- **Evidence:** Verified by `test_drill_08_websocket_disconnect_and_feed` and `test_websocket_e2e.py`.

---

### Step 19: Human-in-the-Loop Clinical Summary Approval & PDF Export
- **Objective:** Patient reviews draft clinical summary, approves with cryptographically signed HMAC token, and generates vector PDF.
- **Verification Status:** **VERIFIED**
- **Evidence:** Verified by `test_daily_report_e2e.py`, `test_phase5_clinical_readiness.py`, and `test_approval_token_tampering_detected`.

---

## 4. Deterministic Simulation Fallback Protocol

To validate the entire software architecture while physical hardware remains blocked, the engineering team executed the authoritative 14-hop deterministic simulation (`scripts/simulate_health_connect_pipeline.py`):

```
[Wearable Sensor]
      │ Hop 1 (BLE Telemetry)
      ▼
[Health Connect Provider]
      │ Hop 2 (Permission & Change Log)
      ▼
[Android Room SQLite]
      │ Hop 3 (Encrypted Local Queue)
      ▼
[WorkManager Background Task]
      │ Hop 4 (Exponential Backoff Batching)
      ▼
[HTTPS Gateway (TLS + JWT)]
      │ Hop 5 (FastAPI Endpoint /v1/sync/batch)
      ▼
[TimescaleDB Hypertable]
      │ Hop 6 (Immutable Partitioned Write)
      ▼
[DataQualityEngine]
      │ Hop 7 (Biological Bounds & Sensor Detachment Flagging)
      ▼
[ContextEngine]
      │ Hop 8 (Sedentary vs Exertion Disambiguation)
      ▼
[BaselineService]
      │ Hop 9 (Rolling 30-Day Circadian Profiling)
      ▼
[AnomalyDetector]
      │ Hop 10 (Deterministic Z-Score & CUSUM Scoring)
      ▼
[Finding Entity]
      │ Hop 11 (Rule H1 Non-Diagnostic Record Persistence)
      ▼
[NotificationService]
      │ Hop 12 (Rule H8 Quiet Hours & Rule H9 12-Hour Anti-Fatigue)
      ▼
[FCM HTTP v1 & WebSocket Transport]
      │ Hop 13 (Private Lock Screen Masking & Real-Time Broadcast)
      ▼
[Vector PDF Clinical Engine]
      │ Hop 14 (Statutory Non-Diagnostic Report Generation)
```

**Simulation Result:** 14 of 14 hops completed with 100% data fidelity, mathematical consistency, and complete compliance with statutory medical safety rules.

---

## 5. Physical Hardware Unblocking Procedure

When physical Android hardware or an active development board is connected to the workstation, follow this exact sequence to promote blocked items to `VERIFIED`:

1. **Connect Smartphone:** Plug Android 14+ smartphone into USB 3.0 port with USB Debugging enabled in Developer Options.
2. **Authorize Host:** Accept workstation RSA key fingerprint on device screen (`adb devices` shows `device`, not `unauthorized`).
3. **Install Application:** Execute `cd android && ./gradlew installDebug`.
4. **Grant Permissions:** Launch application and navigate to Settings $\to$ Health Connect $\to$ App Permissions $\to$ Personal Health OS $\to$ Allow All.
5. **Pair Wearable:** Pair Bluetooth LE smartwatch using official OEM companion app (e.g. Galaxy Wearable, Garmin Connect).
6. **Trigger Live Sync:** Run `adb shell cmd jobscheduler run -f com.healthos <JOB_ID>`.
7. **Inspect Ingestion:** Check backend logs via `tail -f backend/app.log` for successful batch ingestion.
8. **Run Hardware Test Suite:** Execute `./scripts/hardware_readiness_check.py` to confirm all 9 gates transition from `BLOCKED` to `VERIFIED`.
