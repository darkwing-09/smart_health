"""Structured Logging Configuration (structlog)."""

import logging
import sys
from typing import Any, Dict
import structlog
from app.core.config import settings



SENSITIVE_LOG_KEYS = {
    "password", "token", "access_token", "refresh_token", "secret", "secret_key",
    "authorization", "api_key", "llm_api_key", "heart_rate", "steps",
    "observed_value", "baseline_value", "raw_payload", "clinician_note"
}


def phi_and_secret_sanitizer(logger: Any, method_name: str, event_dict: Dict[str, Any]) -> Dict[str, Any]:
    """Sanitizes sensitive security credentials and personal health telemetry from log streams."""
    for k in list(event_dict.keys()):
        low_k = k.lower()
        if any(sens in low_k for sens in SENSITIVE_LOG_KEYS):
            event_dict[k] = "[REDACTED_FOR_AUDIT]"
    return event_dict


def configure_logging() -> None:
    """Configures structured JSON logging with standard fields and zero PHI."""
    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO)
    )

    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            phi_and_secret_sanitizer,
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer()
        ],
        wrapper_class=structlog.make_filtering_bound_logger(
            getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO)
        ),
        context_class=dict,
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True
    )

