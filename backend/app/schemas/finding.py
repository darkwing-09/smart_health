"""Finding and Anomaly Pydantic Schemas."""

import uuid
from datetime import datetime
from typing import List, Optional, Dict, Any
from pydantic import BaseModel, Field


class FindingExplanationSchema(BaseModel):
    """Mandatory 7-Part Explainability Schema."""
    what_changed: str = Field(description="Clear 1-sentence summary of the physiological shift")
    measurements_caused: List[str] = Field(description="Exact metric values and timestamps that triggered this flag")
    baseline_difference: str = Field(description="Statistical comparison against personal baseline")
    historical_context: str = Field(description="Historical occurrence of similar patterns for this user")
    confidence_and_data_quality: str = Field(description="Assessment of sensor quality, completeness, and confidence")
    why_it_matters: str = Field(description="Physiological context without clinical diagnosis")
    next_steps: List[str] = Field(description="Actionable, safe steps to consider (non-diagnostic)")


class FindingResponse(BaseModel):
    id: uuid.UUID
    metric_type: str
    severity: str
    status: str
    first_detected_at: datetime
    last_updated_at: datetime
    explanation: Optional[FindingExplanationSchema] = None


class AcknowledgeResponse(BaseModel):
    id: uuid.UUID
    status: str
    acknowledged_at: datetime
