"""
Correlation ID Middleware & Tracing Context.

Ensures every inbound request, database transaction, background worker job,
and LangGraph workflow carries a unified X-Correlation-ID for end-to-end traceability.
"""

import uuid
from typing import Callable, Awaitable
from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware
import structlog


CORRELATION_HEADER = "X-Correlation-ID"


class CorrelationIdMiddleware(BaseHTTPMiddleware):
    """
    FastAPI middleware injecting or extracting correlation IDs across requests and logs.
    """

    async def dispatch(self, request: Request, call_next: Callable[[Request], Awaitable[Response]]) -> Response:
        correlation_id = request.headers.get(CORRELATION_HEADER)
        if not correlation_id:
            correlation_id = uuid.uuid4().hex

        # Bind to structlog contextvars for automatic attachment to all logs in this task
        structlog.contextvars.clear_contextvars()
        structlog.contextvars.bind_contextvars(correlation_id=correlation_id)
        request.state.correlation_id = correlation_id

        response = await call_next(request)
        response.headers[CORRELATION_HEADER] = correlation_id
        return response
