"""Security Test Suite: JWT Token Blacklist and Immediate Session Revocation.

Validates:
- JTI presence in newly issued JWT tokens
- Authenticated endpoint access with valid Bearer token
- Immediate session termination via POST /v1/auth/logout
- Instant HTTP 401 Unauthorized rejection on subsequent requests using revoked token
- Multi-user token isolation (revoking User A does not impact User B)
"""

import uuid
from datetime import datetime, timezone
import pytest
from httpx import AsyncClient, ASGITransport
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool
from passlib.context import CryptContext
from jose import jwt

from app.main import app
from app.core.config import settings
from app.db.session import get_db
from app.models.user import User

test_engine = create_async_engine(settings.DATABASE_URL, poolclass=NullPool, future=True)
TestSessionFactory = async_sessionmaker(bind=test_engine, class_=AsyncSession, expire_on_commit=False)
pwd_context = CryptContext(schemes=["argon2"], deprecated="auto")


async def override_get_db():
    async with TestSessionFactory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


@pytest.fixture(autouse=True)
def setup_db_override():
    app.dependency_overrides[get_db] = override_get_db
    yield
    app.dependency_overrides.pop(get_db, None)


@pytest.fixture
async def auth_users():

    u1_id = uuid.uuid4()
    u2_id = uuid.uuid4()
    password = "SecurePassword123!"
    hashed = pwd_context.hash(password)

    async with TestSessionFactory() as session:
        user1 = User(
            id=u1_id,
            email=f"token_rev_user1_{u1_id.hex[:6]}@example.com",
            hashed_password=hashed,
            full_name="Revocation User 1",
            timezone="Asia/Kolkata",
            is_active=True
        )
        user2 = User(
            id=u2_id,
            email=f"token_rev_user2_{u2_id.hex[:6]}@example.com",
            hashed_password=hashed,
            full_name="Revocation User 2",
            timezone="UTC",
            is_active=True
        )
        session.add_all([user1, user2])
        await session.commit()

    return {"u1": user1, "u2": user2, "password": password}


@pytest.mark.asyncio
async def test_jwt_contains_jti_and_authenticates(auth_users):
    """Verifies that login issues JWTs containing a unique JTI claim."""
    u1 = auth_users["u1"]
    password = auth_users["password"]

    ip1 = f"10.200.{uuid.uuid4().int % 250}.{uuid.uuid4().int % 250}"
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        login_res = await client.post(
            "/v1/auth/login",
            json={"email": u1.email, "password": password},
            headers={"X-Forwarded-For": ip1}
        )
        assert login_res.status_code == 200
        data = login_res.json()
        token = data["access_token"]

        # Decode token payload and verify jti
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=["HS256"])
        assert payload["sub"] == str(u1.id)
        assert "jti" in payload
        assert len(payload["jti"]) > 10

        # Authenticated access succeeds
        me_res = await client.get(
            "/v1/users/preferences",
            headers={"Authorization": f"Bearer {token}"}
        )
        assert me_res.status_code == 200
        assert str(me_res.json()["user_id"]) == str(u1.id)



@pytest.mark.asyncio
async def test_immediate_token_revocation_on_logout(auth_users):
    """
    Proves that calling /v1/auth/logout registers the token in Redis blacklist
    and immediately rejects subsequent requests with HTTP 401.
    """
    u1 = auth_users["u1"]
    u2 = auth_users["u2"]
    password = auth_users["password"]

    ip2 = f"10.201.{uuid.uuid4().int % 250}.{uuid.uuid4().int % 250}"
    ip3 = f"10.202.{uuid.uuid4().int % 250}.{uuid.uuid4().int % 250}"
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # 1. Login User 1 and User 2 with isolated IPs
        res1 = await client.post("/v1/auth/login", json={"email": u1.email, "password": password}, headers={"X-Forwarded-For": ip2})
        token1 = res1.json()["access_token"]

        res2 = await client.post("/v1/auth/login", json={"email": u2.email, "password": password}, headers={"X-Forwarded-For": ip3})
        token2 = res2.json()["access_token"]


        # 2. Both can access protected endpoint
        r1_before = await client.get("/v1/users/preferences", headers={"Authorization": f"Bearer {token1}"})
        assert r1_before.status_code == 200
        assert str(r1_before.json()["user_id"]) == str(u1.id)

        r2_before = await client.get("/v1/users/preferences", headers={"Authorization": f"Bearer {token2}"})
        assert r2_before.status_code == 200
        assert str(r2_before.json()["user_id"]) == str(u2.id)

        # 3. User 1 logs out
        logout_res = await client.post(
            "/v1/auth/logout",
            headers={"Authorization": f"Bearer {token1}"}
        )
        assert logout_res.status_code == 200
        assert logout_res.json()["status"] == "success"

        # 4. User 1's token is immediately rejected
        r1_after = await client.get("/v1/users/preferences", headers={"Authorization": f"Bearer {token1}"})
        assert r1_after.status_code == 401
        assert "revoked" in r1_after.json()["detail"].lower()

        # 5. User 2's token remains completely unaffected
        r2_after = await client.get("/v1/users/preferences", headers={"Authorization": f"Bearer {token2}"})
        assert r2_after.status_code == 200
        assert str(r2_after.json()["user_id"]) == str(u2.id)

