# PILOT_SAFETY_PROTOCOL.md — Controlled Pilot Participant Safety & Clinical Protocol

This document establishes the clinical governance, participant safety procedures, and non-diagnostic boundaries for conducting controlled real-world pilots with Personal Health OS.

---

## 1. Clinical Safety Philosophy & Core Invariants

Personal Health OS is engineered as an **investigational personal health intelligence platform**, NOT an intensive care unit (ICU) medical monitor, automated diagnostic device, or life-support system.

### Mandatory Safety Invariants:
1. **Rule H1 Non-Diagnostic Invariant**: The system must NEVER state or imply a medical diagnosis (e.g., "You have arrhythmia", "You are suffering from a heart attack", "Hypertensive crisis detected").
2. **Deterministic Threshold Authority**: Biological bounds, alert severity levels (Levels 0–4), and anomaly detection math are 100% deterministic code. LLMs are strictly prohibited from generating, altering, inferring, or suppressing alert tiers or biological thresholds.
3. **Mandatory Level 4 Emergency Disclaimer**: Every Level 4 Urgent alert must include the unalterable clinical emergency disclaimer:
   > *"SAFETY NOTICE: A significant physiological deviation was recorded. Personal Health OS does not provide medical diagnoses. If you are experiencing acute chest pain, shortness of breath, dizziness, or severe distress, please seek emergency medical evaluation immediately."*
4. **Zero Autonomous External Actions**: The system shall never contact doctors, schedule appointments, or dispatch medical summaries to third parties without explicit human sign-off via cryptographic ActionGate tokens.

---

## 2. Participant Eligibility & Enrollment Criteria

### Inclusion Criteria:
- Age 18 years or older.
- Owns and operates an Android smartphone running Android 14 (API 34) or higher.
- Possesses a compatible wearable device (Wear OS, Samsung Galaxy Watch, Garmin, or device that writes to Health Connect).
- Able to read and understand English or localized participant materials.
- Capable of providing voluntary, informed written and digital consent.

### Exclusion Criteria:
- Individuals with unstable acute cardiovascular conditions requiring hospital telemetry monitoring.
- Individuals with implantable cardiac devices (pacemakers, ICDs) that require specialized clinic monitoring.
- Vulnerable individuals incapable of consenting under DPDP Act 2023.

---

## 3. Informed Consent & Privacy Protocol (DPDP Act 2023)

1. **Digital & Physical Consent**:
   - Every participant signs a formal DPDP 2023 Informed Consent Agreement prior to app onboarding.
   - The consent explicitly details data collection purposes: biometric analysis, statistical baseline modeling, and doctor consultation note drafting.
2. **Explicit Granular Permissions**:
   - Participants explicitly choose which metrics are ingested into the platform (`heart_rate`, `steps`, `sleep_stage`, `spo2`, `respiratory_rate`).
3. **Unconditional Right of Revocation**:
   - Participants can revoke consent at any time via in-app toggle (`POST /v1/care/consent/{id}/revoke`).
   - Revocation immediately terminates external data sharing, disables doctor summary exports, and stops longitudinal processing.
4. **Data Minimization & Lock Screen Privacy**:
   - Push notifications use `NotificationCompat.VISIBILITY_PRIVATE`, ensuring sensitive biometric data is never revealed on lock screens to bystanders.

---

## 4. Wearable Device Onboarding & Hardware Setup SOP

1. **Step 1: Android Health Connect Setup**
   - Verify Health Connect is enabled in Android Settings (`Settings -> Security & Privacy -> Health Connect`).
   - Open Personal Health OS companion app and trigger Health Connect permission request.
   - Grant read permissions for all requested biometric data types.
2. **Step 2: Wearable Companion App Synchronization**
   - Verify the OEM wearable app (e.g., Galaxy Wearable, Pixel Watch, Garmin Connect) has write permissions to Health Connect.
   - Perform a manual sync in the OEM app to verify biometric flow into Health Connect.
3. **Step 3: Wearable Fit & Sensor Alignment**
   - Ensure the watch is worn snugly, one finger's width above the wrist bone.
   - Instruct participant that loose fit produces optical photoplethysmography (PPG) noise, motion artifacts, or false tachycardia flags.
4. **Step 4: Initial 14-Day Baseline Warm-up**
   - Inform participant that during the first 14 days, the system builds personalized circadian baselines (`established = false`).
   - Anomaly alerts during this period are conservative to prevent false alarms.

---

## 5. Data Quality Limitations & Troubleshooting

Participants must be educated on the inherent physical limitations of optical sensors:

| Condition | Sensor Impact | System Handling | Participant Guidance |
| :--- | :--- | :--- | :--- |
| **Wearable Off-Wrist** | Zero heart rate & zero steps concurrently for >30m. | Tagged as `missing` or `unwearable`. Never imputed with normal baseline. | Prompt: *"Wearable appears off wrist. Reattach watch to resume tracking."* |
| **Vigorous Exercise** | Elevated heart rate with high cadence/steps. | Anomaly Engine checks concurrent step count; suppresses resting tachycardia alert. | Normal exertion; no alert generated. |
| **Cold Ambient Temp** | Peripheral vasoconstriction reduces optical PPG signal. | Low confidence (<0.50) tagged `limited`. Statistical weights dampened. | Warm hands; adjust watch strap snugness. |
| **Motion Artifacts** | Irregular PPG waveform during typing or vibration. | Data Quality Engine marks noisy samples as `estimated` or `limited`. | Alert thresholds require multiple consecutive abnormal readings. |

---

## 6. Non-Diagnostic Communication Guidelines

All system communications must maintain a calm, objective, grounded tone:

- **Prohibited Phrases**:
  - ❌ *"You have tachycardia."*
  - ❌ *"Heart attack symptoms detected."*
  - ❌ *"You are suffering from sleep apnea."*
  - ❌ *"Your vitals are dangerously abnormal."*
- **Approved Grounded Phrases**:
  - ✅ *"Your resting heart rate was 108 bpm, which is 36 bpm above your 30-day baseline of 72 bpm."*
  - ✅ *"A significant physiological deviation in heart rate was recorded while you were inactive."*
  - ✅ *"Your nighttime breathing rate showed increased variance compared to your typical baseline."*
  - ✅ *"You may wish to discuss this trend with your physician during your next consultation."*

---

## 7. Emergency Escalation Pathways

1. **Level 4 Urgent Alerts**:
   - Triggered only when a physiological vital crosses hard biological safety thresholds (e.g., sustained resting heart rate > 140 bpm while inactive, or SpO2 < 85%).
   - In-app screen displays high-contrast red banner with one-tap emergency dialer (`tel:112` in India, `tel:911` in US).
   - In-app button: *"I am safe / False reading"* to log participant feedback and suppress further alerts.
2. **Pilot Support Helpline**:
   - Participants are provided with a 24/7 dedicated pilot operations phone number and email (`pilot-support@healthos.internal`).
   - Any report of acute medical distress triggers immediate guidance to contact local emergency medical services.

---

## 8. Clinical Oversight & Adverse Event Reporting

1. **Weekly Clinical Safety Review**:
   - Clinical Safety Auditor and Medical Advisor review all generated Level 3 and Level 4 alerts weekly.
   - Audit logs inspected for false-positive rates, alert fatigue indicators, and consent revocations.
2. **Adverse Event Logging**:
   - Any participant hospital admission, acute cardiac event, or false reassurance complaint is logged in `adverse_events` registry within 24 hours.
   - Clinical Safety Auditor possesses unilateral authority to suspend the pilot if safety boundaries are compromised.
