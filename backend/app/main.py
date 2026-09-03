"""FastAPI Application Entrypoint & Lifespan."""

from contextlib import asynccontextmanager
from typing import AsyncGenerator
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.core.config import settings
from app.core.exceptions import ProblemDetailException, problem_detail_handler
from app.observability.langsmith import configure_langsmith
from app.observability.logging import configure_logging
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

# Cross-Origin Resource Sharing (CORS)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Register RFC 7807 Exception Handlers
app.add_exception_handler(ProblemDetailException, problem_detail_handler)

# Include v1 API routes
app.include_router(api_v1_router, prefix=settings.API_V1_STR)


@app.get("/health", tags=["health"])
async def health_check() -> dict[str, str]:
    """Liveness probe endpoint."""
    return {"status": "healthy", "service": "personal-health-os-api"}
