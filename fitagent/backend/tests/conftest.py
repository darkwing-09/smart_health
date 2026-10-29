from __future__ import annotations

import os
import tempfile
from collections.abc import AsyncIterator

_db_dir = tempfile.mkdtemp(prefix="fitagent-test-")
os.environ["DATABASE_URL"] = f"sqlite+aiosqlite:///{_db_dir}/test.db"
os.environ["SECRET_KEY"] = "test-secret-key-that-is-long-enough-1234567890"
os.environ["DB_AUTO_CREATE"] = "false"
for key in ("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY", "LANGSMITH_API_KEY"):
    os.environ.pop(key, None)

import httpx
import pytest
from app.db import Base, engine
from app.deps import limiter
from app.main import app


@pytest.fixture(autouse=True)
async def fresh_db() -> AsyncIterator[None]:
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
        await conn.run_sync(Base.metadata.create_all)
    limiter.reset()
    yield


@pytest.fixture
async def client() -> AsyncIterator[httpx.AsyncClient]:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


async def register(client: httpx.AsyncClient, email: str = "asha@uni.edu", name: str = "Asha",
                   tz: str = "Asia/Kolkata") -> dict[str, str]:
    r = await client.post("/v1/auth/register", json={"email": email, "password": "correct-horse-9",
                                                     "display_name": name, "timezone": tz})
    assert r.status_code == 201, r.text
    return {"Authorization": f"Bearer {r.json()['access_token']}", "_refresh": r.json()["refresh_token"]}


def auth(h: dict[str, str]) -> dict[str, str]:
    return {"Authorization": h["Authorization"]}
