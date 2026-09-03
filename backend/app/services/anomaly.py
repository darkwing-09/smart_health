"""Deterministic Anomaly Detection Service."""

from typing import Optional, Dict, Any
from app.core.config import settings
from app.models.baseline import Baseline


class AnomalyDetector:
    @staticmethod
    def evaluate(
        current_value: float,
        reading_hour: int,
        baseline: Baseline,
        is_active_workout: bool = False
    ) -> Optional[Dict[str, Any]]:
        """
        Evaluates a measurement against personal baseline and biological hard gates.
        Returns anomaly metadata or None if within normal variance.
        """
        # Hard Biological Boundary Gates (Rule H2)
        if baseline.metric_type == "heart_rate" and not is_active_workout:
            if current_value >= settings.HARD_PHYSIO_HR_MAX:
                return {
                    "severity": "urgent",
                    "z_score": 99.0,
                    "expected_mean": baseline.mean,
                    "expected_std": baseline.stddev,
                    "observed_value": current_value,
                    "reason": "Hard biological ceiling breached (severe resting tachycardia)"
                }
            if current_value <= settings.HARD_PHYSIO_HR_MIN:
                return {
                    "severity": "urgent",
                    "z_score": -99.0,
                    "expected_mean": baseline.mean,
                    "expected_std": baseline.stddev,
                    "observed_value": current_value,
                    "reason": "Hard biological floor breached (severe resting bradycardia)"
                }

        # If baseline is not established, suppress statistical alerting
        if not baseline.established:
            return None

        # Circadian hourly variance lookup
        hour_key = str(reading_hour)
        if baseline.seasonality_profile and hour_key in baseline.seasonality_profile:
            exp_mean = baseline.seasonality_profile[hour_key]["mean"]
            exp_std = max(baseline.seasonality_profile[hour_key]["std"], 1.0)
        else:
            exp_mean = baseline.mean
            exp_std = max(baseline.stddev, 1.0)

        z_score = (current_value - exp_mean) / exp_std

        # Classify into 5-Level Notification Hierarchy
        abs_z = abs(z_score)
        if abs_z < settings.ANOMALY_ZSCORE_UNUSUAL:
            return None # Level 0: Information (normal variance)
        elif abs_z < settings.ANOMALY_ZSCORE_MONITORING:
            severity = "unusual" # Level 1: Insight
        elif abs_z < settings.ANOMALY_ZSCORE_CONCERNING:
            severity = "worth_monitoring" # Level 2: Attention
        elif abs_z < settings.ANOMALY_ZSCORE_URGENT:
            severity = "potentially_concerning" # Level 3: Important
        else:
            severity = "urgent" # Level 4: Urgent

        return {
            "severity": severity,
            "z_score": round(float(z_score), 2),
            "expected_mean": round(float(exp_mean), 2),
            "expected_std": round(float(exp_std), 2),
            "observed_value": current_value,
            "reason": f"Statistical deviation (z-score: {round(float(z_score), 2)}) from personal baseline"
        }
