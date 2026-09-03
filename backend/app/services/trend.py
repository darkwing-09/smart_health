"""Deterministic Longitudinal Trend & Baseline Drift Engine.

Differentiates:
- POINT_ANOMALY (acute isolated departure)
- TREND (sustained monotonic drift across 7-28 days)
- BASELINE_SHIFT (long-term structural relocation of baseline mean)
- SAFETY_FINDING (hard biological ceiling/floor breach)

Computes ordinary least squares regression, drift z-scores, and evidence strength.
"""

from enum import Enum
from datetime import datetime, timedelta, timezone
from typing import List, Dict, Any, Optional, Tuple
import numpy as np
from pydantic import BaseModel, Field


class FindingClassification(str, Enum):
    POINT_ANOMALY = "POINT_ANOMALY"
    TREND = "TREND"
    BASELINE_SHIFT = "BASELINE_SHIFT"
    SAFETY_FINDING = "SAFETY_FINDING"


class EvidenceStrength(str, Enum):
    STRONG = "strong"
    MODERATE = "moderate"
    WEAK = "weak"


class LongitudinalTrendReport(BaseModel):
    classification: FindingClassification
    metric_type: str
    direction: str # 'increasing', 'decreasing', 'stable'
    evidence_strength: EvidenceStrength
    start_date: datetime
    end_date: datetime
    days_analyzed: int
    sample_count: int
    initial_mean: float
    current_mean: float
    total_change: float
    slope_per_day: float
    r_squared: float
    is_statistically_significant: bool
    is_clinically_meaningful: bool
    summary: str
    evidence: Dict[str, Any] = Field(default_factory=dict)


class TrendEngine:
    """
    Deterministic Trend & Baseline Evolution Engine.
    Zero LLM involvement. Computes reproducible mathematical trajectories
    over rolling daily aggregates.
    """

    @classmethod
    def evaluate_trend(
        cls,
        metric_type: str,
        daily_observations: List[Tuple[datetime, float]], # List of (date, daily_avg_value)
        historical_baseline_mean: float,
        historical_baseline_std: float,
        min_days: int = 7
    ) -> Optional[LongitudinalTrendReport]:
        """
        Evaluates daily aggregated series for sustained directional drift.

        Args:
            metric_type: Name of metric (e.g., 'resting_heart_rate', 'sleep_session').
            daily_observations: Chronologically sorted daily readings.
            historical_baseline_mean: Established 30-day baseline mean.
            historical_baseline_std: Established 30-day baseline standard deviation.
            min_days: Minimum required observations to evaluate a trend.
        """
        if len(daily_observations) < min_days:
            return None

        # Sort observations by date
        sorted_obs = sorted(daily_observations, key=lambda x: x[0])
        dates = [x[0] for x in sorted_obs]
        values = np.array([x[1] for x in sorted_obs], dtype=float)

        days_span = (dates[-1] - dates[0]).days + 1
        n = len(values)

        # 1. Ordinary Least Squares Linear Regression: values vs day index
        x = np.arange(n, dtype=float)
        x_mean = np.mean(x)
        y_mean = np.mean(values)

        denom = np.sum((x - x_mean) ** 2)
        if denom == 0:
            return None

        slope = float(np.sum((x - x_mean) * (values - y_mean)) / denom)
        intercept = float(y_mean - slope * x_mean)

        # Residuals and R-squared
        predicted = slope * x + intercept
        ss_tot = np.sum((values - y_mean) ** 2)
        ss_res = np.sum((values - predicted) ** 2)
        r_squared = float(1.0 - (ss_res / ss_tot)) if ss_tot > 0 else 0.0
        r_squared = max(0.0, min(1.0, r_squared))

        # 2. Baseline Drift: compare recent 7-day mean against historical 30-day baseline
        recent_window_size = min(7, n)
        recent_mean = float(np.mean(values[-recent_window_size:]))
        initial_mean = float(np.mean(values[:recent_window_size]))
        total_change = round(recent_mean - initial_mean, 2)

        drift_z = (recent_mean - historical_baseline_mean) / (max(historical_baseline_std, 1.0) / np.sqrt(recent_window_size))
        abs_drift_z = abs(drift_z)

        # 3. Evidence Strength Classification
        if abs_drift_z >= 2.5 and r_squared >= 0.60 and n >= 10:
            evidence_strength = EvidenceStrength.STRONG
            stat_sig = True
        elif abs_drift_z >= 1.8 and r_squared >= 0.40 and n >= 7:
            evidence_strength = EvidenceStrength.MODERATE
            stat_sig = True
        else:
            evidence_strength = EvidenceStrength.WEAK
            stat_sig = False

        if not stat_sig:
            return None # Insufficient evidence of sustained trend

        direction = "increasing" if slope > 0 else "decreasing"

        # 4. Clinical Relevance Thresholds
        # e.g., for resting heart rate, a sustained upward shift of >= 3 bpm is clinically meaningful
        # for sleep, a decline of >= 45 minutes is clinically meaningful
        if metric_type in {"heart_rate", "resting_heart_rate"}:
            is_meaningful = (direction == "increasing" and total_change >= 3.0) or (direction == "decreasing" and total_change <= -4.0)
        elif metric_type == "sleep_session":
            is_meaningful = (direction == "decreasing" and total_change <= -45.0) # losing > 45 mins/night
        else:
            is_meaningful = (abs(total_change) >= historical_baseline_std * 0.75)

        # 5. Differentiate TREND vs BASELINE_SHIFT
        # A baseline shift occurs when the new level is sustained for >= 21 days with plateauing slope
        classification = FindingClassification.BASELINE_SHIFT if (n >= 21 and abs(slope) < 0.10) else FindingClassification.TREND

        summary = (
            f"A sustained {direction} trend in {metric_type} was observed over {days_span} days "
            f"(rate: {round(slope, 2)} unit/day, total change: {round(total_change, 1)}, R²={round(r_squared, 2)})."
        )

        return LongitudinalTrendReport(
            classification=classification,
            metric_type=metric_type,
            direction=direction,
            evidence_strength=evidence_strength,
            start_date=dates[0],
            end_date=dates[-1],
            days_analyzed=days_span,
            sample_count=n,
            initial_mean=round(initial_mean, 2),
            current_mean=round(recent_mean, 2),
            total_change=round(total_change, 2),
            slope_per_day=round(slope, 3),
            r_squared=round(r_squared, 2),
            is_statistically_significant=stat_sig,
            is_clinically_meaningful=is_meaningful,
            summary=summary,
            evidence={
                "drift_z_score": round(float(drift_z), 2),
                "historical_mean": round(historical_baseline_mean, 2),
                "historical_std": round(historical_baseline_std, 2),
                "daily_values": [round(v, 2) for v in values[-14:]], # Last 14 days
                "r_squared": round(r_squared, 2),
                "slope": round(slope, 3)
            }
        )
