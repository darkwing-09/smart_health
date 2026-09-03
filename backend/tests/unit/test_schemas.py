"""Unit tests for Pydantic request/response schemas."""

import uuid
from datetime import datetime, timezone
import pytest
from pydantic import ValidationError
from app.schemas.sync import MeasurementItemSchema, BatchIngestRequest


def test_measurement_schema_valid() -> None:
    now = datetime.now(timezone.utc)
    item = MeasurementItemSchema(
        source_record_id="rec_001",
        metric_type="heart_rate",
        value=72.0,
        unit="bpm",
        recorded_at=now,
        confidence=0.98,
        data_quality_flag="nominal"
    )
    assert item.value == 72.0
    assert item.metric_type == "heart_rate"


def test_measurement_schema_invalid_metric() -> None:
    now = datetime.now(timezone.utc)
    with pytest.raises(ValidationError):
        MeasurementItemSchema(
            source_record_id="rec_002",
            metric_type="unsupported_quantum_vital",
            value=100.0,
            unit="quanta",
            recorded_at=now
        )


def test_batch_ingest_request_schema() -> None:
    now = datetime.now(timezone.utc)
    batch = BatchIngestRequest(
        source_id=uuid.uuid4(),
        client_sync_timestamp=now,
        measurements=[
            MeasurementItemSchema(
                source_record_id="rec_001",
                metric_type="steps",
                value=120.0,
                unit="count",
                recorded_at=now
            )
        ]
    )
    assert len(batch.measurements) == 1
