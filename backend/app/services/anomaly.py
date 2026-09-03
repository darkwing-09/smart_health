"""Deterministic Anomaly Detection Service.

Implements statistical deviation scoring (z-scores, circadian hourly lookup)
and hard biological safety gates (Rule H2).

PHILOSOPHY & BOUNDARIES:
- A statistical anomaly (e.g. z-score >= 3.8) means the reading departs significantly
  from the patient's personal 30-day historical distribution (p < 0.0001 under normality).
- It does NOT declare a medical diagnosis (e.g., it is NOT a diagnosis of arrhythmia or heart disease).
- It represents an unusual physiological observation (e.g. nocturnal stress, fever, dehydration,
  poor recovery, or sensor artifact) that warrants contextual review.
- High step counts indicate physical exertion, which legitimately increases heart rate and
  statistically suppresses resting anomaly alerts.
"""

from typing import Optional, Dict, Any
from app.core.config import settings
from app.models.baseline import Baseline


class AnomalyDetector:
    """
    Deterministic Anomaly Detection Engine.
    Zero LLM involvement. Final authority over severity classification.
    """

    @staticmethod
    def evaluate(
        current_value: float,
        reading_hour: int,
        baseline: Baseline,
        steps_recent: int = 0,
        is_active_workout: bool = False,
        data_quality_flag: str = "nominal"
    ) -> Optional[Dict[str, Any]]:
        """
        Evaluates a measurement against personal baseline, activity context, and hard biological gates.

        Args:
            current_value: Observed heart rate value (bpm).
            reading_hour: Local time hour (0..23) corresponding to user's timezone.
            baseline: Established or unestablished personal baseline entity.
            steps_recent: Step count in the concurrent 15-30 minute window.
            is_active_workout: Whether the user was in an active workout session.
            data_quality_flag: Measurement quality flag ('nominal', 'estimated', 'gap_filled', 'missing').

        Returns:
            Structured anomaly evidence dict, or None if reading represents normal physiology or is suppressed.
        """
        # 1. Reject non-nominal or corrupted telemetry from statistical alerting
        if data_quality_flag != "nominal":
            return None

        # 2. Hard Biological Safety Gates (Rule H2)
        # Safety findings supersede baseline establishment and activity context
        if baseline.metric_type == "heart_rate" and not is_active_workout and steps_recent < 50:
            if current_value >= settings.HARD_PHYSIO_HR_MAX:
                return {
                    "finding_type": "SAFETY_FINDING",
                    "rule_id": "RULE_H2_CEILING",
                    "severity": "urgent",
                    "z_score": 99.0,
                    "expected_mean": baseline.mean,
                    "expected_std": baseline.stddev,
                    "observed_value": current_value,
                    "deviation": round(current_value - baseline.mean, 2),
                    "reason": f"Hard biological ceiling breached: {current_value} bpm resting (limit: {settings.HARD_PHYSIO_HR_MAX} bpm)",
                    "activity_context": {"steps_recent": steps_recent, "is_active_workout": False},
                    "evidence": {
                        "gate_type": "hard_biological_ceiling",
                        "threshold": settings.HARD_PHYSIO_HR_MAX,
                        "observed": current_value
                    }
                }
            if current_value <= settings.HARD_PHYSIO_HR_MIN:
                return {
                    "finding_type": "SAFETY_FINDING",
                    "rule_id": "RULE_H2_FLOOR",
                    "severity": "urgent",
                    "z_score": -99.0,
                    "expected_mean": baseline.mean,
                    "expected_std": baseline.stddev,
                    "observed_value": current_value,
                    "deviation": round(current_value - baseline.mean, 2),
                    "reason": f"Hard biological floor breached: {current_value} bpm resting (limit: {settings.HARD_PHYSIO_HR_MIN} bpm)",
                    "activity_context": {"steps_recent": steps_recent, "is_active_workout": False},
                    "evidence": {
                        "gate_type": "hard_biological_floor",
                        "threshold": settings.HARD_PHYSIO_HR_MIN,
                        "observed": current_value
                    }
                }

        # 3. Baseline Establishment Rule
        # If personal baseline is not established (< 14 days or sparse data), suppress statistical alert
        if not baseline.established:
            return None

        # 4. Exertion Cross-Examination (Suppression of resting alerts during exercise)
        # High heart rate during walking/running is physiological, not an anomaly
        if is_active_workout or steps_recent >= 100:
            return None

        # 5. Circadian Local Hour Lookup
        hour_key = str(reading_hour)
        if baseline.seasonality_profile and hour_key in baseline.seasonality_profile:
            exp_mean = baseline.seasonality_profile[hour_key]["mean"]
            exp_std = max(baseline.seasonality_profile[hour_key]["std"], 1.0)
        else:
            exp_mean = baseline.mean
            exp_std = max(baseline.stddev, 1.0)

        deviation = current_value - exp_mean
        z_score = deviation / exp_std
        abs_z = abs(z_score)

        # 6. 5-Level Notification Hierarchy Classification
        # Thresholds configured via settings
        if abs_z < settings.ANOMALY_ZSCORE_UNUSUAL:
            return None # Level 0: Information (Normal variance within 2.0 SD)
        elif abs_z < settings.ANOMALY_ZSCORE_MONITORING:
            severity = "unusual" # Level 1: Insight (2.0 to 2.8 SD)
        elif abs_z < settings.ANOMALY_ZSCORE_CONCERNING:
            severity = "worth_monitoring" # Level 2: Attention (2.8 to 3.8 SD)
        elif abs_z < settings.ANOMALY_ZSCORE_URGENT:
            severity = "potentially_concerning" # Level 3: Important (3.8 to 5.0 SD)
        else:
            severity = "urgent" # Level 4: Urgent (>= 5.0 SD)

        # Classify specific clinical scenarios (e.g. nocturnal resting tachycardia)
        is_nighttime = (reading_hour >= 23 or reading_hour <= 6)
        if is_nighttime and steps_recent == 0 and z_score >= settings.ANOMALY_ZSCORE_CONCERNING:
            rule_id = "RULE_STAT_NOCTURNAL_TACHYCARDIA"
            reason = (
                f"Nocturnal resting heart rate of {current_value} bpm exceeds personal "
                f"circadian baseline ({exp_mean} ± {exp_std} bpm) by +{round(z_score, 1)} standard deviations."
            )
        else:
            rule_id = "RULE_STAT_CIRCADIAN_DEVIATION"
            direction = "elevation" if z_score > 0 else "reduction"
            reason = (
                f"Statistically significant {direction} in {baseline.metric_type} ({current_value}) "
                f"departing from circadian expectation ({exp_mean} bpm, z={round(z_score, 1)})."
            )

        return {
            "finding_type": "STATISTICAL_FINDING",
            "rule_id": rule_id,
            "severity": severity,
            "z_score": round(float(z_score), 2),
            "expected_mean": round(float(exp_mean), 2),
            "expected_std": round(float(exp_std), 2),
            "observed_value": current_value,
            "deviation": round(float(deviation), 2),
            "reason": reason,
            "activity_context": {
                "steps_recent": steps_recent,
                "is_active_workout": is_active_workout,
                "reading_hour_local": reading_hour,
                "is_nighttime": is_nighttime
            },
            "evidence": {
                "z_score": round(float(z_score), 2),
                "circadian_mean": round(float(exp_mean), 2),
                "circadian_std": round(float(exp_std), 2),
                "baseline_mean": baseline.mean,
                "baseline_std": baseline.stddev,
                "threshold_concerning": settings.ANOMALY_ZSCORE_CONCERNING,
                "threshold_urgent": settings.ANOMALY_ZSCORE_URGENT
            }
        }
