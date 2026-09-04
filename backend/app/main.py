"""FastAPI Application Entrypoint & Lifespan."""

from contextlib import asynccontextmanager
from typing import Any, AsyncGenerator
from fastapi import FastAPI, Request, Depends
from fastapi.middleware.cors import CORSMiddleware
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
    title="Personal Health OS API",
    description="Privacy-first longitudinal personal health operating system platform",
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
    response.headers["Content-Security-Policy"] = "default-src 'self'; frame-ancestors 'none';"
    return response


# Register RFC 7807 Exception Handlers
app.add_exception_handler(ProblemDetailException, problem_detail_handler)


# Include v1 API routes
app.include_router(api_v1_router, prefix=settings.API_V1_STR)


@app.get("/health", tags=["health"])
async def health_check() -> dict[str, str]:
    """Liveness probe endpoint."""
    return {"status": "healthy", "service": "personal-health-os-api"}


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
        "service": "personal-health-os-api",
        "checks": checks,
    }

    status_code = 200 if all_healthy else 503
    return JSONResponse(content=response_data, status_code=status_code)
