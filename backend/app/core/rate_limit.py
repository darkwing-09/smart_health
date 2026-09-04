"""
Redis-backed Sliding-Window Rate Limiter.

Protects authentication, ingestion, and clinical document generation endpoints
from credential stuffing, DoS, and abusive expensive operations.
"""

import time
import uuid
import logging
from typing import Optional, Tuple
from fastapi import Request, HTTPException, status
import redis.asyncio as aioredis

from app.core.config import settings
from app.api.deps import get_redis_pool

logger = logging.getLogger("healthos.ratelimit")


class RateLimiter:
    """Sliding-window rate limiter using Redis sorted sets (ZSET)."""

    def __init__(self, redis_client: Optional[aioredis.Redis] = None) -> None:
        self.redis = redis_client

    async def is_rate_limited(
        self,
        scope: str,
        identifier: str,
        limit_per_minute: int,
        window_seconds: int = 60
    ) -> Tuple[bool, int, int]:
        """
        Evaluates whether an identifier has exceeded its rate limit.
        Returns (is_limited, remaining_quota, retry_after_seconds).
        """
        if not settings.RATE_LIMIT_ENABLED:
            return False, limit_per_minute, 0

        key = f"rl:{scope}:{identifier}"
        now = time.time()
        window_start = now - window_seconds

        client = None
        should_close = False
        try:
            if self.redis is not None:
                client = self.redis
            else:
                client = aioredis.from_url(settings.REDIS_URL, decode_responses=True)
                should_close = True

            pipe = client.pipeline()
            # Remove timestamps older than the sliding window
            pipe.zremrangebyscore(key, 0, window_start)
            # Count remaining requests in current window
            pipe.zcard(key)
            # Add current request timestamp
            req_id = str(uuid.uuid4())
            pipe.zadd(key, {req_id: now})
            # Set key expiry slightly longer than the window
            pipe.expire(key, window_seconds + 5)

            results = await pipe.execute()
            current_count = results[1]  # zcard result before adding current

            if current_count >= limit_per_minute:
                retry_after = int(window_seconds - (now - window_start))
                return True, 0, max(1, retry_after)

            remaining = max(0, limit_per_minute - (current_count + 1))
            return False, remaining, 0

        except Exception as e:
            # Fail-open with warning on Redis failure to avoid denying clinical access
            logger.warning(
                "Redis rate-limiter unavailable, failing open",
                extra={"scope": scope, "identifier": identifier, "error": str(e)}
            )
            return False, limit_per_minute, 0
        finally:
            if should_close and client is not None:
                await client.aclose()



rate_limiter = RateLimiter()


async def check_rate_limit(
    request: Request,
    scope: str,
    limit: int,
    identifier: Optional[str] = None
) -> None:
    """
    FastAPI dependency helper enforcing rate limits.
    Raises HTTP 429 Too Many Requests if limit exceeded.
    """
    client_id = identifier
    if not client_id:
        # Fallback to client IP
        forwarded = request.headers.get("X-Forwarded-For")
        client_id = forwarded.split(",")[0].strip() if forwarded else (request.client.host if request.client else "unknown")

    is_limited, remaining, retry_after = await rate_limiter.is_rate_limited(
        scope=scope,
        identifier=client_id,
        limit_per_minute=limit
    )

    if is_limited:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=f"Rate limit exceeded for {scope}. Please retry after {retry_after} seconds.",
            headers={"Retry-After": str(retry_after)}
        )
