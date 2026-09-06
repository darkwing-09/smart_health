"""FastAPI Application Entrypoint & Lifespan."""

import os
from contextlib import asynccontextmanager
from typing import Any, AsyncGenerator
from fastapi import FastAPI, Request, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.exceptions import ProblemDetailException, problem_detail_handler
from app.db.session import get_db
from app.observability.langsmith import configure_langsmith
from app.observability.logging import configure_logging
from app.observability.correlation import CorrelationIdMiddleware
from app.api.v1.router import api_v1_router


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Lifespan context managing startup and shutdown tasks."""
    configure_logging()
    configure_langsmith()
    yield


app = FastAPI(
    title="HealthAgent API",
    description="Privacy-first longitudinal health intelligence and agentic platform",
    version="0.1.0",
    docs_url="/docs",
    redoc_url="/redoc",
    lifespan=lifespan
)

# Correlation ID Tracing Middleware (must be outer-most to trace all requests)
app.add_middleware(CorrelationIdMiddleware)

# Cross-Origin Resource Sharing (CORS)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)



@app.middleware("http")
async def security_headers_middleware(request: Request, call_next):
    """Enforces strict security headers on all HTTP responses."""
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["X-XSS-Protection"] = "1; mode=block"
    response.headers["Strict-Transport-Security"] = "max-age=63072000; includeSubDomains; preload"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://fonts.gstatic.com; "
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
        "font-src 'self' https://fonts.gstatic.com data:; "
        "connect-src 'self' ws: wss: http: https:; "
        "img-src 'self' data: https:; "
        "frame-ancestors 'none';"
    )
    return response


# Register RFC 7807 Exception Handlers
app.add_exception_handler(ProblemDetailException, problem_detail_handler)


# Mount static directory for interactive Web Control Center
_static_dir = os.path.join(os.path.dirname(__file__), "static")
if os.path.exists(_static_dir):
    app.mount("/static", StaticFiles(directory=_static_dir), name="static")


@app.get("/", include_in_schema=False)
@app.get("/dashboard", include_in_schema=False)
async def serve_dashboard() -> FileResponse:
    """Serves the interactive HealthAgent Web Control Center."""
    index_path = os.path.join(_static_dir, "index.html")
    if os.path.exists(index_path):
        return FileResponse(index_path)
    return FileResponse(os.path.join(_static_dir, "index.html"))


# Include v1 API routes
app.include_router(api_v1_router, prefix=settings.API_V1_STR)


@app.get("/health", tags=["health"])
async def health_check() -> dict[str, str]:
    """Liveness probe endpoint."""
    return {"status": "healthy", "service": "healthagent-api"}


@app.get("/ready", tags=["health"])
async def readiness_check(db: AsyncSession = Depends(get_db)) -> dict[str, Any]:
    """
    Readiness probe: validates PostgreSQL and Redis connectivity.
    Returns HTTP 200 when all backends are reachable, HTTP 503 when degraded.
    """
    from fastapi.responses import JSONResponse
    from sqlalchemy import text
    import redis.asyncio as aioredis

    checks: dict[str, Any] = {}
    all_healthy = True

    # PostgreSQL / TimescaleDB check
    try:
        result = await db.execute(text("SELECT 1"))
        result.scalar()
        checks["postgresql"] = {"status": "ok"}
    except Exception as e:
        checks["postgresql"] = {"status": "error", "detail": str(e)}
        all_healthy = False

    # Redis check
    try:
        r = aioredis.from_url(settings.REDIS_URL, decode_responses=True)
        pong = await r.ping()
        await r.aclose()
        checks["redis"] = {"status": "ok" if pong else "error"}
        if not pong:
            all_healthy = False
    except Exception as e:
        checks["redis"] = {"status": "error", "detail": str(e)}
        all_healthy = False

    response_data = {
        "status": "ready" if all_healthy else "degraded",
        "service": "healthagent-api",
        "checks": checks,
    }

    status_code = 200 if all_healthy else 503
    return JSONResponse(content=response_data, status_code=status_code)
