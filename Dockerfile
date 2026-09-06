# Multi-stage production container for HealthAgent backend
FROM python:3.11-slim AS builder

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential libpq-dev curl && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml .
# Install wheel and all explicit production runtime dependencies
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir --user \
    "fastapi>=0.111.0" \
    "uvicorn[standard]>=0.30.0" \
    "pydantic>=2.7.0" \
    "pydantic-settings>=2.3.0" \
    "sqlalchemy[asyncio]>=2.0.30" \
    "asyncpg>=0.29.0" \
    "alembic>=1.13.0" \
    "redis[hiredis]>=5.0.0" \
    "arq>=0.26.0" \
    "langgraph>=0.2.0" \
    "langchain-core>=0.2.0" \
    "langchain-openai>=0.1.0" \
    "langchain-anthropic>=0.1.0" \
    "numpy>=1.26.0" \
    "scipy>=1.13.0" \
    "reportlab>=4.2.0" \
    "structlog>=24.1.0" \
    "httpx>=0.27.0" \
    "python-jose[cryptography]>=3.3.0" \
    "passlib[argon2]>=1.7.4" \
    "argon2-cffi>=23.1.0" \
    "cryptography>=42.0.0"

# Final lightweight runner stage
FROM python:3.11-slim AS runner

# Create non-root system group and user
RUN groupadd -g 10001 appgroup && \
    useradd -u 10001 -g appgroup -s /sbin/nologin -m appuser

WORKDIR /app/backend
RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 curl && rm -rf /var/lib/apt/lists/*

COPY --from=builder --chown=appuser:appgroup /root/.local /home/appuser/.local
ENV PATH=/home/appuser/.local/bin:$PATH
ENV PYTHONUNBUFFERED=1
ENV PYTHONPATH=/app/backend
ENV TMPDIR=/tmp

COPY --chown=appuser:appgroup backend/ /app/backend/
COPY --chown=appuser:appgroup alembic.ini /app/backend/alembic.ini

# Switch to non-root execution
USER 10001:10001

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:8000/health || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

