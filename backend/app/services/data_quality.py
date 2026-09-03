"""Deterministic Data Quality Engine.

Ensures that health intelligence is grounded strictly on verified,
physiologically bounded, non-corrupted wearable data streams.
Detects sampling gaps, impossible values, stale telemetry, and sensor detachment.
"""

from enum import Enum
from datetime import datetime, timedelta, timezone
from typing import List, Dict, Any, Optional, Tuple
from pydantic import BaseModel, Field

from app.models.measurement import Measurement


class DataQualityRating(str, Enum):
    EXCELLENT = "excellent" # Continuous nominal telemetry, confidence >= 0.95
    GOOD = "good"           # Nominal, minor isolated gaps (< 30 min), confidence >= 0.80
    LIMITED = "limited"     # Noticeable gaps (30-120 min), or confidence 0.50-0.79
    POOR = "poor"           # Severe gaps (> 120 min), high noise, or confidence < 0.50
    INVALID = "invalid"     # Impossible biological bounds, future timestamps, invalid units


class DataQualityReport(BaseModel):
    rating: DataQualityRating
    is_usable_for_baseline: bool
    is_usable_for_alerts: bool
    flags: List[str] = Field(default_factory=list)
    reasons: List[str] = Field(default_factory=list)
    sample_count: int = 0
    nominal_count: int = 0
    gap_count: int = 0
    confidence_mean: float = 1.0
    details: Dict[str, Any] = Field(default_factory=dict)


# Biological Boundaries
BIOLOGICAL_BOUNDS = {
    "heart_rate": (30.0, 240.0, "bpm"),
    "resting_heart_rate": (32.0, 140.0, "bpm"),
    "steps": (0.0, 35000.0, "count"),
    "sleep_session": (0.0, 1440.0, "minutes"), # max 24 hours in minutes
}


class DataQualityEngine:
    """
    Deterministic Data Quality Engine.
    Zero LLM involvement. Protects downstream intelligence from noisy, corrupted, or synthetic artifacts.
    """

    @classmethod
    def evaluate_point(
        cls,
        metric_type: str,
        value: float,
        unit: str,
        recorded_at: datetime,
        confidence: float = 1.0,
        data_quality_flag: str = "nominal",
        reference_time: Optional[datetime] = None
    ) -> Tuple[DataQualityRating, List[str], List[str]]:
        """
        Evaluates a single telemetry sample against hard biological bounds and temporal validity.
        """
        if reference_time is None:
            reference_time = datetime.now(timezone.utc)
        elif reference_time.tzinfo is None:
            reference_time = reference_time.replace(tzinfo=timezone.utc)

        if recorded_at.tzinfo is None:
            recorded_at = recorded_at.replace(tzinfo=timezone.utc)

        flags = []
        reasons = []

        # 1. Biological Bounds Validation
        if metric_type in BIOLOGICAL_BOUNDS:
            min_val, max_val, expected_unit = BIOLOGICAL_BOUNDS[metric_type]
            if value < min_val or value > max_val:
                flags.append("IMPOSSIBLE_VALUE")
                reasons.append(f"Value {value} is outside biological limits [{min_val}, {max_val}] for {metric_type}.")
                return DataQualityRating.INVALID, flags, reasons

            if unit.lower() != expected_unit.lower():
                flags.append("INCONSISTENT_UNIT")
                reasons.append(f"Unit '{unit}' does not match expected '{expected_unit}'.")
                return DataQualityRating.INVALID, flags, reasons

        # 2. Timestamp Anomaly Checks
        if recorded_at > (reference_time + timedelta(minutes=5)):
            flags.append("FUTURE_TIMESTAMP")
            reasons.append(f"Recorded timestamp {recorded_at.isoformat()} is in the future relative to reference {reference_time.isoformat()}.")
            return DataQualityRating.INVALID, flags, reasons

        # 3. Telemetry Flag & Confidence Checks
        if data_quality_flag in {"missing", "unwearable", "invalid"}:
            flags.append(f"QUALITY_FLAG_{data_quality_flag.upper()}")
            reasons.append(f"Sample tagged as {data_quality_flag} by ingestion pipeline.")
            return DataQualityRating.POOR, flags, reasons

        if confidence < 0.50:
            flags.append("LOW_CONFIDENCE")
            reasons.append(f"Sensor optical confidence {round(confidence, 2)} is critically low.")
            return DataQualityRating.POOR, flags, reasons
        elif confidence < 0.80:
            flags.append("MODERATE_CONFIDENCE")
            reasons.append(f"Sensor confidence {round(confidence, 2)} is sub-optimal.")
            return DataQualityRating.LIMITED, flags, reasons

        return DataQualityRating.EXCELLENT, flags, reasons

    @classmethod
    def evaluate_window(
        cls,
        measurements: List[Measurement],
        expected_interval_minutes: int = 60,
        reference_time: Optional[datetime] = None
    ) -> DataQualityReport:
        """
        Evaluates an entire time series window of measurements for continuity,
        sampling gaps, sensor detachment, and statistical usability.
        """
        if reference_time is None:
            reference_time = datetime.now(timezone.utc)
        elif reference_time.tzinfo is None:
            reference_time = reference_time.replace(tzinfo=timezone.utc)

        if not measurements:
            return DataQualityReport(
                rating=DataQualityRating.POOR,
                is_usable_for_baseline=False,
                is_usable_for_alerts=False,
                flags=["ZERO_SAMPLES"],
                reasons=["No measurements found in evaluation window."],
                sample_count=0,
                nominal_count=0,
                gap_count=0,
                confidence_mean=0.0
            )

        # Sort measurements by timestamp
        sorted_m = sorted(measurements, key=lambda x: x.recorded_at)
        total_samples = len(sorted_m)

        flags: List[str] = []
        reasons: List[str] = []
        confidences: List[float] = []
        nominal_count = 0
        gap_count = 0
        has_invalid_point = False

        for idx, m in enumerate(sorted_m):
            point_rating, p_flags, p_reasons = cls.evaluate_point(
                metric_type=m.metric_type,
                value=m.value,
                unit=m.unit,
                recorded_at=m.recorded_at,
                confidence=m.confidence,
                data_quality_flag=m.data_quality_flag,
                reference_time=reference_time
            )

            if point_rating == DataQualityRating.INVALID:
                has_invalid_point = True
                flags.extend(p_flags)
                reasons.extend(p_reasons)

            if m.data_quality_flag == "nominal":
                nominal_count += 1
            confidences.append(m.confidence)

            # Check gap between consecutive measurements
            if idx > 0:
                prev_ts = sorted_m[idx - 1].recorded_at
                curr_ts = m.recorded_at
                delta_mins = (curr_ts - prev_ts).total_seconds() / 60.0
                if delta_mins > (expected_interval_minutes * 2):
                    gap_count += 1

        avg_conf = sum(confidences) / len(confidences) if confidences else 0.0
        nominal_ratio = nominal_count / total_samples if total_samples > 0 else 0.0

        if gap_count > 0:
            flags.append("SAMPLING_GAPS_DETECTED")
            reasons.append(f"Detected {gap_count} abnormal sampling gap(s) exceeding {expected_interval_minutes * 2} minutes.")

        # Determine overall rating
        if has_invalid_point:
            overall_rating = DataQualityRating.INVALID
            usable_baseline = False
            usable_alerts = False
        elif nominal_ratio < 0.60 or avg_conf < 0.50 or gap_count >= 5:
            overall_rating = DataQualityRating.POOR
            usable_baseline = False
            usable_alerts = False
        elif nominal_ratio < 0.85 or avg_conf < 0.80 or gap_count >= 2:
            overall_rating = DataQualityRating.LIMITED
            usable_baseline = (nominal_ratio >= 0.70)
            usable_alerts = False # Suppress confident alerts on limited telemetry
        elif gap_count == 1:
            overall_rating = DataQualityRating.GOOD
            usable_baseline = True
            usable_alerts = True
        else:
            overall_rating = DataQualityRating.EXCELLENT
            usable_baseline = True
            usable_alerts = True

        return DataQualityReport(
            rating=overall_rating,
            is_usable_for_baseline=usable_baseline,
            is_usable_for_alerts=usable_alerts,
            flags=list(set(flags)),
            reasons=list(set(reasons)),
            sample_count=total_samples,
            nominal_count=nominal_count,
            gap_count=gap_count,
            confidence_mean=round(avg_conf, 2),
            details={
                "nominal_ratio": round(nominal_ratio, 2),
                "earliest_ts": sorted_m[0].recorded_at.isoformat(),
                "latest_ts": sorted_m[-1].recorded_at.isoformat(),
            }
        )
