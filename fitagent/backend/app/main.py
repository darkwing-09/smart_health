from __future__ import annotations

import logging
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import text

from . import __version__
from .config import get_settings
from .db import Base, engine
from .routers import auth, challenges, coach, me
from .services.agents import get_runtime

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("fitagent")


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings = get_settings()
    if settings.db_auto_create:
        # Dev/test convenience. Production runs `alembic upgrade head` instead (see Dockerfile).
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
    runtime = get_runtime()
    log.info("FitAgent API %s ready (env=%s, llm=%s, langsmith=%s)", __version__, settings.env,
             runtime.llm_enabled, runtime.tracing_enabled)
    yield
    await engine.dispose()


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title="FitAgent API",
        version=__version__,
        description="Accounts, sync, challenges and the AI coach for the FitAgent fitness app.",
        lifespan=lifespan,
        docs_url=None if settings.is_production else "/docs",
        redoc_url=None,
    )
    if settings.cors_origins:
        app.add_middleware(CORSMiddleware, allow_origins=settings.cors_origins, allow_credentials=False,
                           allow_methods=["*"], allow_headers=["Authorization", "Content-Type"])

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request_id = request.headers.get("X-Request-ID") or uuid.uuid4().hex[:16]
        started = time.perf_counter()
        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id
        log.info("%s %s %s %.0fms rid=%s", request.method, request.url.path, response.status_code,
                 (time.perf_counter() - started) * 1000, request_id)
        return response

    @app.exception_handler(Exception)
    async def unhandled(request: Request, exc: Exception):
        log.exception("unhandled error on %s %s", request.method, request.url.path)
        return JSONResponse(status_code=500, content={"detail": "Internal server error"})

    @app.get("/health", tags=["ops"])
    async def health():
        async with engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
        runtime = get_runtime()
        return {"status": "ok", "version": __version__, "ai_coach": "llm" if runtime.llm_enabled else "rules",
                "tracing": runtime.tracing_enabled}

    for r in (auth.router, me.router, challenges.router, coach.router):
        app.include_router(r)
    return app


app = create_app()
