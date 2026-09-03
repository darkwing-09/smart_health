"""Deterministic Personalized Baseline Modeling Service (NumPy / SciPy).

Computes rolling longitudinal statistics and circadian seasonality curves
strictly conditioned on user timezone, data quality, and resting context.
"""

import uuid
from datetime import datetime, timedelta, timezone
from typing import Dict, Any, List, Optional
from zoneinfo import ZoneInfo
import numpy as np
from sqlalchemy import select, func, and_
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.user import User
from app.models.measurement import Measurement
from app.models.baseline import Baseline


class BaselineService:
    """
    Deterministic Baseline Engine.
    Zero LLM involvement. Computes personal physiological distributions,
    hourly circadian profiles (00:00-23:00 local time), and baseline establishment.
    """

    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def compute_baseline(
        self,
        user_id: uuid.UUID,
        metric_type: str = "heart_rate",
        current_date: Optional[datetime] = None,
        user_timezone: Optional[str] = None
    ) -> Baseline:
        """
        Computes 30-day rolling baseline for a user, partitioned by circadian local hour.
        """
        if current_date is None:
            current_date = datetime.now(timezone.utc)
        elif current_date.tzinfo is None:
            current_date = current_date.replace(tzinfo=timezone.utc)

        # 1. Resolve user timezone
        if user_timezone is None:
            user = await self.db.get(User, user_id)
            user_tz_str = user.timezone if (user and user.timezone) else "UTC"
        else:
            user_tz_str = user_timezone

        try:
            tz = ZoneInfo(user_tz_str)
        except Exception:
            tz = ZoneInfo("UTC")
            user_tz_str = "UTC"

        window_start = current_date - timedelta(days=settings.BASELINE_ROLLING_WINDOW_DAYS)

        # 2. Query nominal measurements in the rolling window
        # Using database projection for performance (only value and recorded_at)
        stmt = (
            select(Measurement.value, Measurement.recorded_at)
            .where(
                Measurement.user_id == user_id,
                Measurement.metric_type == metric_type,
                Measurement.recorded_at >= window_start,
                Measurement.recorded_at <= current_date,
                Measurement.data_quality_flag == "nominal"
            )
            .order_by(Measurement.recorded_at.asc())
        )
        result = await self.db.execute(stmt)
        records = result.all()

        # 3. Evaluate Baseline Establishment Criteria
        # Must have:
        # (a) At least 14 days spanning between earliest and latest record
        # (b) At least (14 * 10) = 140 valid samples
        min_required_samples = settings.BASELINE_MIN_DAYS_ESTABLISHED * 10
        is_established = False

        if records:
            earliest_ts = records[0][1]
            latest_ts = records[-1][1]
            span_days = (latest_ts - earliest_ts).total_seconds() / 86400.0
            
            if (
                span_days >= (settings.BASELINE_MIN_DAYS_ESTABLISHED - 1)
                and len(records) >= min_required_samples
            ):
                is_established = True

            values = np.array([r[0] for r in records], dtype=float)
            overall_mean = float(np.mean(values))
            overall_std = float(np.std(values)) if len(values) > 1 else 1.0
            # Guard against unrealistically low stddev in uniform synthetic data
            if overall_std < 0.5:
                overall_std = 1.0
        else:
            overall_mean = 0.0
            overall_std = 1.0

        # 4. Hourly Circadian Seasonality Profile (in User's Local Time)
        seasonality: Dict[str, Any] = {}
        for hour in range(24):
            # Convert UTC timestamp to user's local timezone to extract hour
            hour_vals = [
                r[0] for r in records
                if r[1].astimezone(tz).hour == hour
            ]
            if hour_vals:
                h_mean = float(np.mean(hour_vals))
                h_std = float(np.std(hour_vals)) if len(hour_vals) > 1 else 1.0
                seasonality[str(hour)] = {
                    "mean": round(h_mean, 2),
                    "std": round(max(h_std, 1.0), 2),
                    "count": len(hour_vals)
                }

        # 5. Persist and return Baseline snapshot
        baseline = Baseline(
            id=uuid.uuid4(),
            user_id=user_id,
            metric_type=metric_type,
            window_start=window_start,
            window_end=current_date,
            mean=round(overall_mean, 2),
            stddev=round(overall_std, 2),
            seasonality_profile=seasonality,
            established=is_established,
            rule_version="1.1.0",
            computed_at=datetime.now(timezone.utc)
        )
        self.db.add(baseline)
        await self.db.commit()
        await self.db.refresh(baseline)
        return baseline
