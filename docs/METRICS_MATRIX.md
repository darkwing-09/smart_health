# METRICS_MATRIX.md — Supported Biometric Metrics Matrix & Telemetry Specifications

This document defines the 12 currently supported physiological and activity metrics within Personal Health OS, auditing their complete lifecycle from sensor acquisition through Health Connect, Android Room queuing, backend validation bounds, baseline eligibility, and clinical anomaly detection.

---

## 1. Metric Coverage Matrix

| # | Metric Type | Source | Unit | Timestamp Semantics | Sampling Frequency | Android Availability | Health Connect Availability | Wearable Dependency | Backend Bounds (Min, Max) | Baseline Eligible? | Anomaly Eligible? |
| :- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | `heart_rate` | Optical PPG / ECG | `bpm` | Instant (point in time) | 1 Hz to 1/min (device-dependent) | Android 8+ via HC | `HeartRateRecord` | Wear OS, Galaxy Watch, Fitbit, Garmin | [20.0, 260.0] | ✅ Yes (circadian hourly profile) | ✅ Yes (z-score, CUSUM, hard bounds) |
| **2** | `resting_heart_rate` | PPG (inferred during quiet wake/sleep) | `bpm` | Instant | 1/day or 1/sleep cycle | Android 8+ via HC | `RestingHeartRateRecord` | Supported on Wear OS 3+, Garmin, Apple Watch | [30.0, 150.0] | ✅ Yes (daily rolling mean/std) | ✅ Yes (cardiac risk marker) |
| **3** | `steps` | 3-axis Accelerometer | `count` | Interval (`start_time`, `end_time`) | 1/min rollup or batch | Android 8+ via HC | `StepsRecord` | Phone internal sensor or wearable | [0.0, 35000.0] | ✅ Yes (daily volume & hourly activity) | ⚠️ Indirect (used to gate exertion) |
| **4** | `distance` | GPS / Pedometry stride | `m` (meters) | Interval (`start_time`, `end_time`) | Periodic batch | Android 8+ via HC | `DistanceRecord` | Phone GPS or Wearable GPS/Pedometer | [0.0, 100000.0] | ✅ Yes (daily exercise volume) | ❌ No (context only) |
| **5** | `calories` | BMR + Exertion estimation | `kcal` | Interval (`start_time`, `end_time`) | 1/hour or 1/day | Android 8+ via HC | `TotalCaloriesBurnedRecord` | Proprietary OEM algorithms | [0.0, 20000.0] | ✅ Yes (daily energy balance) | ❌ No |
| **6** | `active_calories` | Exertion estimation above BMR | `kcal` | Interval (`start_time`, `end_time`) | Periodic during activity | Android 8+ via HC | `ActiveCaloriesBurnedRecord` | Proprietary OEM algorithms | [0.0, 20000.0] | ✅ Yes (workout intensity) | ⚠️ Indirect (activity context) |
| **7** | `sleep_stage` | Accelerometer + PPG HR/HRV sleep classifier | `stage` | Interval (`start_time`, `end_time`) | Epoched (e.g. 30s epochs) | Android 8+ via HC | `SleepSessionRecord` | Wearable required for sleep stages | [0.0, 5.0] (0=awake, 1=light, 2=deep, 3=rem) | ✅ Yes (sleep architecture, efficiency) | ✅ Yes (sleep fragmentation, insomnia) |
| **8** | `exercise_session` | User-initiated or auto-detected workout | `session` | Interval (`start_time`, `end_time`) | Event-based | Android 8+ via HC | `ExerciseSessionRecord` | Wearable or Phone workout tracker | [0.0, 86400.0] (duration in seconds) | ❌ No (discrete events) | ⚠️ Contextual (suppresses tachycardia) |
| **9** | `spo2` | Reflectance Pulse Oximeter (Red + IR PPG) | `%` | Instant | Spot-check or sleep periodic (1–5 min) | Android 8+ via HC | `OxygenSaturationRecord` | Dedicated SpO2 optical sensor required | [50.0, 100.0] | ✅ Yes (sleep baseline saturation) | ✅ Yes (hypoxemia, desaturation index) |
| **10** | `respiratory_rate` | PPG pulse wave variation / motion | `rpm` | Instant | Overnight average or spot-check | Android 8+ via HC | `RespiratoryRateRecord` | Supported on modern Wear OS, Garmin, Pixel Watch | [4.0, 60.0] | ✅ Yes (sleep respiratory profile) | ✅ Yes (tachypnea / respiratory depression) |
| **11** | `hrv` | PPG or ECG inter-beat intervals (RMSSD) | `ms` | Instant | Sleep window or scheduled 3-min spot-check | Android 8+ via HC | `HeartRateVariabilityRmssdRecord` | High-precision optical sensor required | [5.0, 300.0] | ✅ Yes (autonomic balance, 30-day baseline) | ✅ Yes (autonomic stress, systemic fatigue) |
| **12** | `body_temperature` | Skin temperature sensor / thermistor | `celsius` | Instant | Overnight continuous relative drift or spot | Android 8+ via HC | `BodyTemperatureRecord` | Specific wearables (e.g. Galaxy Watch 5+, Pixel Watch 2+) | [30.0, 45.0] | ✅ Yes (nocturnal baseline deviation) | ✅ Yes (febrile illness, temperature spikes) |

---

## 2. Deep-Dive: Metric Semantics & Known Hardware Limitations

### 2.1 Heart Rate (`heart_rate`)
- **Timestamp Semantics:** Instantaneous measurement timestamped in UTC microseconds.
- **Physical Wearable Constraints:**
  - Optical PPG is prone to motion artifacts during vigorous activity, loose band fit, or cold extremities (peripheral vasoconstriction).
  - Tattoos, deep skin pigmentation, and excessive wrist hair can reduce optical signal-to-noise ratio.
- **Data Quality Safeguards:**
  - Readings with confidence score $< 0.40$ are tagged `estimated` or `gap_filled`.
  - Values outside [20.0, 260.0] bpm are quarantined as `invalid`.
  - Sudden jumps $> 40$ bpm within 5 seconds without concurrent accelerometer activity are flagged for verification.

### 2.2 Blood Oxygen Saturation (`spo2`)
- **Timestamp Semantics:** Point-in-time sample.
- **Physical Wearable Constraints:**
  - Wrist-based reflectance pulse oximetry is significantly less accurate than medical-grade fingertip transmission oximeters.
  - Excessive movement during sleep can cause false desaturation dips below 90%.
- **Data Quality Safeguards:**
  - Isolated drops $< 85\%$ lasting $< 30$ seconds without supporting physiological markers are treated with caution.
  - Strict statutory disclaimer: "Wrist-worn pulse oximetry is for wellness tracking only, not for medical diagnosis of sleep apnea or pulmonary embolism."

### 2.3 Heart Rate Variability (`hrv`)
- **Timestamp Semantics:** RMSSD (Root Mean Square of Successive Differences) in milliseconds.
- **Physical Wearable Constraints:**
  - Highly sensitive to cardiac ectopic beats, movement, and sleep stage. Meaningful longitudinal HRV must be sampled during stable non-REM sleep windows at consistent times.
- **Baseline Modeling:**
  - Modeled using 30-day log-transformed normal distribution due to natural positive skewness of RMSSD data.

### 2.4 Sleep Stages (`sleep_stage`)
- **Timestamp Semantics:** Interval records defining stage transitions.
- **Physical Wearable Constraints:**
  - Commercial wearables agree with polysomnography (PSG) sleep staging with approximately 65–75% concordance. Deep vs. Light sleep differentiation is frequently misclassified by movement heuristics alone.
- **Data Quality Safeguards:**
  - Sleep sessions $< 120$ minutes are classified as naps rather than primary circadian sleep cycles.

---

## 3. Universal Wearable Support Disclaimer

> [!IMPORTANT]
> **Statutory Notice on Hardware Incompatibility**:
> Personal Health OS does **NOT** claim universal wearable support. Metric availability is strictly constrained by the user's specific hardware model, OEM companion app synchronization policies, and Android Health Connect permission grants.
> - Devices lacking optical SpO2, skin temperature, or ECG sensors will report those metrics as `UNAVAILABLE` rather than `0` or `NORMAL`.
> - Sensor absence must **never** be interpreted as healthy physiological baseline.
