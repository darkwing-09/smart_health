"""Deterministic Baseline Modeling Service (NumPy / SciPy)."""

import uuid
from datetime import datetime, timedelta, timezone
from typing import Dict, Any, List
import numpy as np
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.core.config import settings
from app.models.measurement import Measurement
from app.models.baseline import Baseline


class BaselineService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def compute_baseline(
        self,
        user_id: uuid.UUID,
        metric_type: str,
        current_date: datetime
    ) -> Baseline:
        window_start = current_date - timedelta(days=settings.BASELINE_ROLLING_WINDOW_DAYS)

        stmt = select(Measurement.value, Measurement.recorded_at).where(
            Measurement.user_id == user_id,
            Measurement.metric_type == metric_type,
            Measurement.recorded_at >= window_start,
            Measurement.recorded_at <= current_date,
            Measurement.data_quality_flag == "nominal"
        )
        result = await self.db.execute(stmt)
        records = result.all()

        min_samples = settings.BASELINE_MIN_DAYS_ESTABLISHED * 12 # Minimum expected nominal data points
        is_established = len(records) >= min_samples

        if records:
            values = np.array([r[0] for r in records], dtype=float)
            mean_val = float(np.mean(values))
            std_val = float(np.std(values)) if len(values) > 1 else 1.0
        else:
            mean_val = 0.0
            std_val = 1.0

        # Hourly Circadian Seasonality Profile
        seasonality: Dict[str, Any] = {}
        for hour in range(24):
            hour_vals = [r[0] for r in records if r[1].hour == hour]
            if hour_vals:
                seasonality[str(hour)] = {
                    "mean": float(np.mean(hour_vals)),
                    "std": float(np.std(hour_vals)) if len(hour_vals) > 1 else 1.0,
                    "count": len(hour_vals)
                }

        baseline = Baseline(
            user_id=user_id,
            metric_type=metric_type,
            window_start=window_start,
            window_end=current_date,
            mean=mean_val,
            stddev=std_val,
            seasonality_profile=seasonality,
            established=is_established,
            rule_version="1.0.0",
            computed_at=datetime.now(timezone.utc)
        )
        self.db.add(baseline)
        await self.db.commit()
        return baseline
