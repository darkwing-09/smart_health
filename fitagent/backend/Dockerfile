# Build from the fitagent/ directory:  docker build -f backend/Dockerfile .
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/srv/.venv

COPY --from=ghcr.io/astral-sh/uv:0.11.7 /uv /bin/uv
WORKDIR /srv

# Dependencies first for layer caching
COPY pyproject.toml uv.lock ./
COPY agents/pyproject.toml agents/pyproject.toml
COPY backend/pyproject.toml backend/pyproject.toml
RUN uv sync --frozen --no-dev --package fitagent-backend --no-install-workspace

COPY agents agents
COPY backend backend
RUN uv sync --frozen --no-dev --package fitagent-backend \
    && useradd --system --uid 10001 fitagent

USER fitagent
WORKDIR /srv/backend
ENV PATH=/srv/.venv/bin:$PATH \
    DB_AUTO_CREATE=false \
    FITAGENT_ENV=production

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health').status==200 else 1)"

# One worker: the rate limiter is in-process. Scale with more containers behind a gateway limiter.
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers --forwarded-allow-ips='*'"]
