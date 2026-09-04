"""FastAPI Dependency Injection Providers."""

from typing import AsyncGenerator
import uuid
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jose import jwt, JWTError
from sqlalchemy.ext.asyncio import AsyncSession
import redis.asyncio as aioredis
from app.core.config import settings
from app.db.session import get_db
from app.models.user import User

security_bearer = HTTPBearer(auto_error=True)


_redis_client: aioredis.Redis | None = None


def get_redis_pool() -> aioredis.Redis:
    """Returns a singleton Redis client using an async connection pool."""
    global _redis_client
    if _redis_client is None:
        _redis_client = aioredis.from_url(
            settings.REDIS_URL,
            decode_responses=True,
            max_connections=50
        )
    return _redis_client


async def get_redis() -> AsyncGenerator[aioredis.Redis, None]:
    """Yields an async Redis connection client from the connection pool."""
    client = get_redis_pool()
    yield client



import hashlib


async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security_bearer),
    db: AsyncSession = Depends(get_db)
) -> User:
    """Decodes JWT Bearer token, verifies against Redis revocation blacklist, and returns authenticated User."""
    token = credentials.credentials
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate authentication credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=["HS256"])
        user_id_str: str = payload.get("sub")
        if user_id_str is None:
            raise credentials_exception
        user_uuid = uuid.UUID(user_id_str)
    except (JWTError, ValueError):
        raise credentials_exception

    # Enforce immediate token revocation check via Redis blacklist
    try:
        redis_client = get_redis_pool()
        jti = payload.get("jti")
        token_key = f"revoked_token:{jti}" if jti else f"revoked_token:{hashlib.sha256(token.encode()).hexdigest()}"
        is_revoked = await redis_client.get(token_key)
        if is_revoked:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Token has been revoked",
                headers={"WWW-Authenticate": "Bearer"},
            )
    except HTTPException:
        raise
    except Exception:
        # Fail-safe: if Redis connection encounters an issue, proceed with DB validation
        pass

    user = await db.get(User, user_uuid)
    if user is None or not user.is_active:
        raise credentials_exception
    return user

