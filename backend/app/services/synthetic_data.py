"""Deterministic 30-Day Longitudinal Telemetry Generator.

Generates reproducible physiological time-series data for testing
circadian baseline modeling, activity suppression, and nocturnal anomaly detection.
Uses a fixed random seed (zero real PHI).
"""

import uuid
from datetime import datetime, timedelta, timezone
from typing import List, Dict, Any, Tuple
from zoneinfo import ZoneInfo
import numpy as np

from app.models.measurement import Measurement


class SyntheticDataGenerator:
    """
    Deterministic Synthetic Telemetry Factory.
    Uses numpy Generator with a fixed seed for 100% reproducible results.
    """

    def __init__(self, seed: int = 42) -> None:
        self.rng = np.random.default_rng(seed)

    def generate_30_day_timeline(
        self,
        user_id: uuid.UUID,
        source_id: uuid.UUID,
        reference_time: datetime,
        user_timezone: str = "Asia/Kolkata"
    ) -> Tuple[List[Measurement], Dict[str, Any]]:
        """
        Generates 30 days of hourly heart rate and step measurements.
        Injects:
        1. A controlled nocturnal resting tachycardia episode on Day 30 at 03:00 AM local.
        2. Normal daytime workouts with elevated HR + high steps (for false-positive testing).
        3. Normal nocturnal resting baseline (56-62 bpm, 0 steps).
        """
        tz = ZoneInfo(user_timezone)
        start_time = reference_time - timedelta(days=30)

        measurements: List[Measurement] = []
        injected_anomalies: List[Dict[str, Any]] = []

        # Generate hourly readings from start_time to reference_time
        current = start_time
        total_hours = 30 * 24

        for hour_idx in range(total_hours):
            current += timedelta(hours=1)
            local_dt = current.astimezone(tz)
            local_hour = local_dt.hour
            is_last_day = (hour_idx >= (total_hours - 24))

            # Nighttime hours (23:00 to 06:59)
            if local_hour >= 23 or local_hour < 7:
                # Controlled Anomaly: Nocturnal Resting Tachycardia on Day 30 at 03:00 AM
                if is_last_day and local_hour == 3:
                    hr_val = 94.0 # Significantly elevated above nocturnal baseline of ~58 bpm
                    steps_val = 0.0
                    injected_anomalies.append({
                        "type": "nocturnal_resting_tachycardia",
                        "recorded_at": current,
                        "value": hr_val,
                        "steps": steps_val,
                        "expected_hour_mean": 58.0
                    })
                else:
                    # Normal nocturnal sleep: 55-62 bpm, 0 steps
                    hr_val = round(float(self.rng.normal(58.0, 2.5)), 1)
                    steps_val = 0.0 if self.rng.random() > 0.1 else float(self.rng.integers(5, 25))

            # Daytime hours (07:00 to 22:59)
            else:
                # Workout hour at 18:00: High HR + High Steps
                if local_hour == 18:
                    hr_val = round(float(self.rng.normal(132.0, 4.0)), 1)
                    steps_val = float(self.rng.integers(1400, 2200))
                else:
                    # Normal daytime active/resting mix
                    hr_val = round(float(self.rng.normal(74.0, 5.0)), 1)
                    steps_val = float(self.rng.integers(100, 500))

            # Occasional simulated sensor noise/gap (1 out of every 150 points)
            is_gap = (hour_idx % 180 == 45)
            quality_flag = "missing" if is_gap else "nominal"
            conf = 0.0 if is_gap else 1.0

            # 1. Heart Rate Measurement
            hr_record = Measurement(
                id=uuid.uuid4(),
                user_id=user_id,
                source_id=source_id,
                metric_type="heart_rate",
                value=hr_val,
                unit="bpm",
                recorded_at=current,
                ingested_at=current + timedelta(minutes=2),
                confidence=conf,
                data_quality_flag=quality_flag
            )
            measurements.append(hr_record)

            # 2. Steps Measurement
            step_record = Measurement(
                id=uuid.uuid4(),
                user_id=user_id,
                source_id=source_id,
                metric_type="steps",
                value=steps_val,
                unit="count",
                recorded_at=current,
                ingested_at=current + timedelta(minutes=2),
                confidence=conf,
                data_quality_flag=quality_flag
            )
            measurements.append(step_record)

        metadata = {
            "total_records": len(measurements),
            "user_timezone": user_timezone,
            "injected_anomalies": injected_anomalies,
            "span_days": 30
        }
        return measurements, metadata
