"""Unit tests for deterministic baseline and anomaly detector math."""

import uuid
from datetime import datetime, timezone
import pytest
from app.models.baseline import Baseline
from app.services.anomaly import AnomalyDetector


@pytest.fixture
def sample_baseline() -> Baseline:
    now = datetime.now(timezone.utc)
    return Baseline(
        id=uuid.uuid4(),
        user_id=uuid.uuid4(),
        metric_type="heart_rate",
        window_start=now,
        window_end=now,
        mean=60.0,
        stddev=5.0,
        seasonality_profile={
            "2": {"mean": 54.0, "std": 3.0, "count": 30},
            "14": {"mean": 72.0, "std": 6.0, "count": 30}
        },
        established=True,
        rule_version="1.0.0"
    )


def test_normal_variation_returns_none(sample_baseline: Baseline) -> None:
    # 55 bpm at 02:00 (circadian mean is 54, std 3; z = 0.33)
    result = AnomalyDetector.evaluate(current_value=55.0, reading_hour=2, baseline=sample_baseline)
    assert result is None


def test_nocturnal_tachycardia_important_severity(sample_baseline: Baseline) -> None:
    # 70 bpm at 02:00 (circadian mean is 54, std 3; z = (70-54)/3 = 5.33 -> urgent / concerning)
    result = AnomalyDetector.evaluate(current_value=67.0, reading_hour=2, baseline=sample_baseline)
    assert result is not None
    assert result["severity"] in {"potentially_concerning", "urgent"}
    assert result["z_score"] >= 3.8


def test_hard_biological_ceiling_triggers_urgent(sample_baseline: Baseline) -> None:
    # 155 bpm resting (exceeds HARD_PHYSIO_HR_MAX = 150)
    result = AnomalyDetector.evaluate(current_value=155.0, reading_hour=14, baseline=sample_baseline)
    assert result is not None
    assert result["severity"] == "urgent"
    assert "Hard biological ceiling breached" in result["reason"]


def test_hard_biological_floor_triggers_urgent(sample_baseline: Baseline) -> None:
    # 35 bpm resting (below HARD_PHYSIO_HR_MIN = 38)
    result = AnomalyDetector.evaluate(current_value=35.0, reading_hour=2, baseline=sample_baseline)
    assert result is not None
    assert result["severity"] == "urgent"
    assert "Hard biological floor breached" in result["reason"]


def test_unestablished_baseline_suppresses_statistical_alert() -> None:
    now = datetime.now(timezone.utc)
    unestablished = Baseline(
        id=uuid.uuid4(),
        user_id=uuid.uuid4(),
        metric_type="heart_rate",
        window_start=now,
        window_end=now,
        mean=60.0,
        stddev=5.0,
        seasonality_profile={},
        established=False
    )
    # Mild elevation should be suppressed while learning baseline
    result = AnomalyDetector.evaluate(current_value=85.0, reading_hour=14, baseline=unestablished)
    assert result is None
