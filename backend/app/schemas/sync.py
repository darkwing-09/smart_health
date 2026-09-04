"""Synchronization and Ingestion Pydantic Schemas."""

import uuid
from datetime import datetime
from typing import List, Optional
from pydantic import BaseModel, Field, field_validator


class MeasurementItemSchema(BaseModel):
    source_record_id: str = Field(..., description="Unique client/Health Connect record ID")
    metric_type: str = Field(..., description="Metric type e.g. heart_rate, steps, sleep_stage")
    value: float = Field(..., description="Numeric measurement value")
    unit: str = Field(..., description="Unit of measurement e.g. bpm, count, m")
    recorded_at: datetime = Field(..., description="UTC device recording timestamp")
    confidence: float = Field(default=1.0, ge=0.0, le=1.0, description="Sensor confidence score")
    data_quality_flag: str = Field(default="nominal", description="Quality flag: nominal, estimated, gap_filled, missing")

    @field_validator("metric_type")
    @classmethod
    def validate_metric_type(cls, v: str) -> str:
        allowed = {
            "heart_rate", "resting_heart_rate", "steps", "distance",
            "sleep_stage", "calories", "active_calories", "spo2",
            "respiratory_rate", "hrv", "body_temperature", "exercise_session"
        }
        if v not in allowed:
            raise ValueError(f"Unsupported metric_type '{v}'. Must be one of {sorted(allowed)}")
        return v


class BatchIngestRequest(BaseModel):
    source_id: uuid.UUID = Field(..., description="Foreign key of WearableSource")
    device_id: Optional[uuid.UUID] = Field(None, description="Optional device ID")
    client_sync_timestamp: datetime = Field(..., description="Timestamp client initiated sync")
    measurements: List[MeasurementItemSchema] = Field(..., max_length=500, description="Batch of measurements (max 500)")


class BatchIngestResponse(BaseModel):
    status: str = Field(..., description="Status string: SUCCESS or ALREADY_PROCESSED")
    batch_id: str = Field(..., description="Idempotency batch identifier")
    accepted_count: int = Field(..., description="Number of new records inserted")
    duplicate_count: int = Field(..., description="Number of duplicate records skipped")
    invalid_count: int = Field(default=0, description="Number of records failing biological bounds validation")
    ingested_at: datetime = Field(..., description="Server completion timestamp")
