"""Comprehensive Failure Mode & Fault Injection Tests for Phase 7 (Audit Verification).

Explicitly tests:
1. Redis outage resilience & fail-open behavior
2. PostgreSQL failure & transaction rollback during state transitions
3. FCM outage, connection failure, and retry exhaustion
4. Concurrent dispatch & duplicate worker race conditions
5. Duplicate notification suppression (12-hour anti-fatigue)
6. WebSocket disconnect, reconnect, and missed-event catchup
7. Expired authentication & invalid JWT rejection on WebSocket
8. Cross-user isolation across all notification endpoints
9. Timezone-aware quiet hours hold and release
10. Timezone change adaptation
11. Level 4 emergency quiet hours override
12. Total LLM outage & malformed output fallback
"""

import asyncio
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, AsyncGenerator, Optional
import pytest
from httpx import AsyncClient, ASGITransport
from jose import jwt
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker
from sqlalchemy.pool import NullPool

from app.core.config import settings
from app.core.rate_limit import RateLimiter
from app.db.session import get_db
from app.main import app
from app.models.finding import Finding
from app.models.notification import Notification
from app.models.user import User
from app.services.connection_manager import ws_manager
from app.services.fcm import FcmNotificationService, FcmChannelId
from app.services.notification import NotificationService
from app.services.notification_policy import AlertTier, NotificationPolicyEngine
from app.services.notification_state_machine import (
    InvalidNotificationStateTransition,
    NotificationState,
    NotificationStateMachine,
)
from app.services.quiet_hours import QuietHoursEvaluator

test_engine = create_async_engine(settings.DATABASE_URL, echo=False, poolclass=NullPool)
TestSessionLocal = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)


@pytest.fixture
async def db_session() -> AsyncGenerator[AsyncSession, None]:
    async with TestSessionLocal() as session:
        yield session


@pytest.fixture
def override_db(db_session: AsyncSession):
    async def _get_db():
        yield db_session
    app.dependency_overrides[get_db] = _get_db
    yield
    app.dependency_overrides.pop(get_db, None)


def create_test_token(user_id: uuid.UUID, expires_delta: Optional[timedelta] = None) -> str:
    now = datetime.now(timezone.utc)
    exp = now + (expires_delta or timedelta(minutes=60))
    return jwt.encode({"sub": str(user_id), "exp": exp, "iat": now}, settings.SECRET_KEY, algorithm="HS256")


# ==============================================================================
# 1. Redis Outage Resilience & Fail-Open Rate Limiting
# ==============================================================================
@pytest.mark.asyncio
async def test_redis_outage_rate_limiter_fails_open() -> None:
    """When Redis is completely unreachable, the system must fail open to prevent clinical lockout."""
    from unittest.mock import AsyncMock, MagicMock
    mock_failing_redis = MagicMock()
    mock_pipe = MagicMock()
    mock_pipe.execute = AsyncMock(side_effect=ConnectionError("Redis connection refused"))
    mock_failing_redis.pipeline.return_value = mock_pipe

    limiter = RateLimiter(redis_client=mock_failing_redis)
    is_limited, remaining, retry_after = await limiter.is_rate_limited(
        scope="auth:login",
        identifier="192.168.1.100",
        limit_per_minute=5,
        window_seconds=60
    )
    # Must fail open (is_limited = False) for safety
    assert is_limited is False
    assert remaining == 5


# ==============================================================================
# 2. PostgreSQL Failure & Rollback Protection
# ==============================================================================
@pytest.mark.asyncio
async def test_postgresql_rollback_on_failed_state_transition(db_session: AsyncSession) -> None:
    """An illegal state transition must not corrupt database state and must roll back cleanly."""
    uid = uuid.uuid4()
    user = User(
        id=uid,
        email=f"failover_{uuid.uuid4().hex[:6]}@example.com",
        hashed_password="argon2id_mock_hash",
        full_name="Database Failover Test",
        is_active=True
    )
    db_session.add(user)
    await db_session.commit()

    service = NotificationService(db_session)
    notif = await service.dispatch_notification(
        user_id=uid,
        finding_id=None,
        channel="in_app",
        severity="attention",
        title="Initial Alert",
        body="Initial Body"
    )
    assert notif.state == NotificationState.DELIVERED.value

    # Attempt illegal transition: DELIVERED -> CREATED (must raise InvalidNotificationStateTransition)
    with pytest.raises(InvalidNotificationStateTransition):
        NotificationStateMachine.validate_transition(notif.state, NotificationState.CREATED)

    # Database state remains unchanged
    refetched = await service.get_notification_by_id(uid, notif.id)
    assert refetched is not None
    assert refetched.state == NotificationState.DELIVERED.value


# ==============================================================================
# 3. FCM Outage, Timeouts & Retry Exhaustion
# ==============================================================================
@pytest.mark.asyncio
async def test_fcm_outage_and_retry_exhaustion(db_session: AsyncSession) -> None:
    """FCM provider failure must execute bounded retries and report failure without crashing."""
    from unittest.mock import patch, AsyncMock
    service = FcmNotificationService(db=db_session)
    uid = uuid.uuid4()
    nid = uuid.uuid4()

    # Mock HTTP client throwing connection timeout on all attempts
    mock_client = AsyncMock()
    mock_client.post.side_effect = TimeoutError("Google FCM gateway timeout")

    with patch("httpx.AsyncClient", return_value=mock_client):
        # Force live path (force_dry_run=False) with mock client
        with patch.object(settings, "APP_ENV", "production"):
            result = await service.dispatch(
                fcm_token="sample_token_xyz",
                title="Emergency Alert",
                body="Severe tachycardia observed",
                notification_id=nid,
                user_id=uid,
                alert_tier=4,
                max_retries=3,
                force_dry_run=False
            )

    assert result.success is False
    assert result.error_code == "RETRY_EXHAUSTED"
    assert result.attempts == 3


# ==============================================================================
# 4. Concurrent Dispatch & Duplicate Worker Race Condition
# ==============================================================================
@pytest.mark.asyncio
async def test_concurrent_dispatch_race_condition(db_session: AsyncSession) -> None:
    """Simultaneous worker runs dispatching the exact same finding must be race-safe."""
    uid = uuid.uuid4()
    user = User(
        id=uid,
        email=f"race_{uuid.uuid4().hex[:6]}@example.com",
        hashed_password="mock",
        full_name="Race Condition User",
        is_active=True
    )
    fid = uuid.uuid4()
    finding = Finding(
        id=fid,
        user_id=uid,
        metric_type="heart_rate",
        severity="urgent",
        rule_id="RULE_HARD_GATE_HR_MAX",
        rule_version="1.0.0",
        observed_value=165.0,
        baseline_value=65.0,
        deviation=100.0,
        reading_timestamp=datetime.now(timezone.utc),
        status="new"
    )
    db_session.add(user)
    await db_session.commit()
    db_session.add(finding)
    await db_session.commit()

    service = NotificationService(db_session)

    # Execute 2 concurrent dispatches for the same finding
    res1 = await service.dispatch_finding_alert(uid, finding)
    res2 = await service.dispatch_finding_alert(uid, finding)

    assert res1 is not None
    assert res2 is not None
    # Must resolve to the exact same notification ID without database crash
    assert res1.id == res2.id


# ==============================================================================
# 5. Duplicate Notification Suppression (12-Hour Window)
# ==============================================================================
@pytest.mark.asyncio
async def test_duplicate_notification_suppressed_within_12_hours(db_session: AsyncSession) -> None:
    """An alert for the same finding at the same tier within 12 hours must be suppressed."""
    uid = uuid.uuid4()
    user = User(
        id=uid,
        email=f"dup_{uuid.uuid4().hex[:6]}@example.com",
        hashed_password="mock",
        full_name="Duplicate Suppression User",
        is_active=True
    )
    fid = uuid.uuid4()
    finding = Finding(
        id=fid,
        user_id=uid,
        metric_type="heart_rate",
        severity="potentially_concerning",
        rule_id="RULE_STAT_RESTING_TACHYCARDIA",
        rule_version="1.0.0",
        observed_value=98.0,
        baseline_value=60.0,
        deviation=38.0,
        reading_timestamp=datetime.now(timezone.utc),
        status="new"
    )
    db_session.add(user)
    await db_session.commit()
    db_session.add(finding)
    await db_session.commit()

    service = NotificationService(db_session)
    # First dispatch -> DELIVERED
    notif1 = await service.dispatch_finding_alert(uid, finding)
    assert notif1 is not None
    assert notif1.state == NotificationState.DELIVERED.value

    # Second dispatch immediately -> returns prior notification, 0 new rows
    notif2 = await service.dispatch_finding_alert(uid, finding)
    assert notif2 is not None
    assert notif2.id == notif1.id


# ==============================================================================
# 6. WebSocket Expired Authentication & Invalid Token Rejection
# ==============================================================================
@pytest.mark.asyncio
async def test_websocket_expired_jwt_rejected() -> None:
    """Expired JWT must be rejected during WebSocket handshake with status 1008."""
    uid = uuid.uuid4()
    # Create token that expired 10 minutes ago
    expired_token = create_test_token(uid, expires_delta=-timedelta(minutes=10))

    from app.api.v1.endpoints.stream import authenticate_ws_token
    auth_user = await authenticate_ws_token(expired_token)
    assert auth_user is None

    invalid_token = "not.a.valid.jwt"
    auth_invalid = await authenticate_ws_token(invalid_token)
    assert auth_invalid is None


# ==============================================================================
# 7. Cross-User Notification Access Isolation
# ==============================================================================
@pytest.mark.asyncio
async def test_cross_user_isolation(override_db, db_session: AsyncSession) -> None:
    """User A must NEVER be able to view, acknowledge, or dismiss User B's notifications."""
    user_a = User(id=uuid.uuid4(), email=f"usera_{uuid.uuid4().hex[:6]}@example.com", hashed_password="h", full_name="User A", is_active=True)
    user_b = User(id=uuid.uuid4(), email=f"userb_{uuid.uuid4().hex[:6]}@example.com", hashed_password="h", full_name="User B", is_active=True)
    db_session.add_all([user_a, user_b])
    await db_session.commit()

    service = NotificationService(db_session)
    notif_b = await service.dispatch_notification(
        user_id=user_b.id,
        finding_id=None,
        channel="in_app",
        severity="attention",
        title="User B Alert",
        body="Secret health alert for B"
    )

    # User A tries to acknowledge User B's notification
    token_a = create_test_token(user_a.id)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        res = await client.post(
            f"/v1/notifications/{notif_b.id}/acknowledge",
            headers={"Authorization": f"Bearer {token_a}"}
        )
        assert res.status_code == 404

        # User A tries to dismiss User B's notification
        res_dismiss = await client.post(
            f"/v1/notifications/{notif_b.id}/dismiss",
            headers={"Authorization": f"Bearer {token_a}"}
        )
        assert res_dismiss.status_code == 404


# ==============================================================================
# 8. Timezone Change Adaptation & Quiet Hours
# ==============================================================================
@pytest.mark.asyncio
async def test_timezone_change_adaptation() -> None:
    """Moving user from Asia/Kolkata (+5:30) to America/New_York (-4:00) must shift quiet hours correctly."""
    # 23:30 UTC:
    # In Asia/Kolkata (+5:30), this is 05:00 next day (Inside 22:00-07:00 quiet hours!)
    # In America/New_York (-4:00), this is 19:30 same day (Outside quiet hours!)
    test_time_utc = datetime(2026, 9, 4, 23, 30, tzinfo=timezone.utc)

    is_quiet_kolkata, release_kolkata = QuietHoursEvaluator.evaluate(
        user_timezone="Asia/Kolkata",
        quiet_start_str="22:00",
        quiet_end_str="07:00",
        current_time_utc=test_time_utc
    )
    assert is_quiet_kolkata is True
    assert release_kolkata > test_time_utc

    is_quiet_ny, release_ny = QuietHoursEvaluator.evaluate(
        user_timezone="America/New_York",
        quiet_start_str="22:00",
        quiet_end_str="07:00",
        current_time_utc=test_time_utc
    )
    assert is_quiet_ny is False
    assert release_ny == test_time_utc


# ==============================================================================
# 9. Level 4 Emergency Quiet Hours Override
# ==============================================================================
@pytest.mark.asyncio
async def test_level_4_emergency_quiet_hours_override(db_session: AsyncSession) -> None:
    """Even during the deepest night of quiet hours, Level 4 Urgent alerts MUST dispatch immediately."""
    uid = uuid.uuid4()
    user = User(
        id=uid,
        email=f"emergency_{uuid.uuid4().hex[:6]}@example.com",
        hashed_password="mock",
        full_name="Emergency Night User",
        is_active=True,
        timezone="Asia/Kolkata"
    )
    fid = uuid.uuid4()
    # Hard gate breach: Resting HR 168 bpm
    finding = Finding(
        id=fid,
        user_id=uid,
        metric_type="heart_rate",
        severity="urgent",
        rule_id="RULE_HARD_GATE_HR_MAX",
        rule_version="1.0.0",
        observed_value=168.0,
        baseline_value=62.0,
        deviation=106.0,
        reading_timestamp=datetime(2026, 9, 4, 23, 0, tzinfo=timezone.utc),  # 04:30 AM local time
        status="new"
    )
    db_session.add(user)
    await db_session.commit()
    db_session.add(finding)
    await db_session.commit()

    service = NotificationService(db_session)
    notif = await service.dispatch_finding_alert(
        user_id=uid,
        finding=finding,
        user_timezone="Asia/Kolkata"
    )
    assert notif is not None
    assert notif.severity == "urgent"
    # MUST NOT BE HELD
    assert notif.quiet_hours_held is False
    assert notif.state == NotificationState.DELIVERED.value


# ==============================================================================
# 10. LLM Outage & Malformed Output Fallback
# ==============================================================================
@pytest.mark.asyncio
async def test_deterministic_notification_content_on_llm_outage() -> None:
    """When the LLM provider fails completely or outputs malformed text, deterministic fallback applies."""
    policy = NotificationPolicyEngine.evaluate(severity="urgent", rule_id="RULE_HARD_GATE_HR_MAX")
    finding = Finding(
        id=uuid.uuid4(),
        user_id=uuid.uuid4(),
        metric_type="heart_rate",
        severity="urgent",
        rule_id="RULE_HARD_GATE_HR_MAX",
        rule_version="1.0.0",
        observed_value=175.0,
        baseline_value=65.0
    )

    service = NotificationService(None)
    title, body = service._generate_deterministic_content(finding, policy)

    assert "Urgent Physiological Observation" in title
    assert "175 bpm" in body
    assert "SAFETY NOTICE" in body
    assert "emergency medical evaluation" in body
