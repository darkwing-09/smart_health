"""RFC 7807 Problem Details and Domain Exception definitions."""

from typing import Any, Dict, Optional
from fastapi import Request, status
from fastapi.responses import JSONResponse


class ProblemDetailException(Exception):
    """Base RFC 7807 problem detail exception."""
    def __init__(
        self,
        title: str,
        status_code: int,
        detail: str,
        type_uri: str = "about:blank",
        instance: Optional[str] = None,
        extra: Optional[Dict[str, Any]] = None
    ) -> None:
        super().__init__(detail)
        self.title = title
        self.status_code = status_code
        self.detail = detail
        self.type_uri = type_uri
        self.instance = instance
        self.extra = extra or {}


class PhysiologicalBoundsException(ProblemDetailException):
    def __init__(self, metric: str, value: float, bounds: tuple[float, float]) -> None:
        super().__init__(
            title="Physiological Value Out of Bounds",
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Metric '{metric}' value {value} is outside biological limits {bounds}.",
            type_uri="https://api.healthos.local/errors/physiological-bounds-exceeded"
        )


class IdempotencyViolationException(ProblemDetailException):
    def __init__(self, key: str) -> None:
        super().__init__(
            title="Duplicate Idempotency Key",
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Idempotency key '{key}' has already been processed with different payload.",
            type_uri="https://api.healthos.local/errors/idempotency-conflict"
        )


async def problem_detail_handler(request: Request, exc: ProblemDetailException) -> JSONResponse:
    content: Dict[str, Any] = {
        "type": exc.type_uri,
        "title": exc.title,
        "status": exc.status_code,
        "detail": exc.detail,
        "instance": exc.instance or str(request.url.path),
    }
    if exc.extra:
        content.update(exc.extra)
    return JSONResponse(status_code=exc.status_code, content=content)
