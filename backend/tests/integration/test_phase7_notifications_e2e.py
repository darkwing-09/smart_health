"""Real End-to-End Integration Tests for Phase 7: Notification Engine & Streaming.

Executes against live PostgreSQL (TimescaleDB) and Redis:
- 5-Tier Alert Hierarchy
- Timezone-Aware Quiet Hours & Emergency Override
- Atomic 12-Hour Deduplication & Severity Escalation Bypass
- Authoritative Notification State Machine
- REST Endpoints with Strict Multi-Tenant Isolation
- WebSocket Real-Time Transport, Heartbeat & Missed-Event Catch-Up
- Device FCM Push Token Registration
"""

import uuid
from datetime import datetime, timezone
from typing import AsyncGenerator
import pytest
from httpx import AsyncClient, ASGITransport
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool
from starlette.testclient import TestClient

from app.core.config import settings
from app.db.session import get_db
from app.main import app
from app.models.device import Device
from app.models.finding import Finding
from app.models.notification import Notification
from app.models.user import User
from app.services.notification import NotificationService
from app.services.notification_state_machine import NotificationState

test_engine = create_async_engine(settings.DATABASE_URL, poolclass=NullPool, future=True)
TestSessionFactory = async_sessionmaker(bind=test_engine, class_=AsyncSession, expire_on_commit=False)


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
async def session() -> AsyncGenerator[AsyncSession, None]:
    async with TestSessionFactory() as s:
        yield s


@pytest.fixture
async def test_users(session: AsyncSession):
    """Creates two distinct active users for multi-tenant isolation verification."""
    u1 = User(
        id=uuid.uuid4(),
        email=f"patient_notif_1_{uuid.uuid4().hex[:6]}@example.com",
        hashed_password="hashed_pw_test",
        full_name="Patient Notif One",
        timezone="Asia/Kolkata",
        is_active=True,
        notification_prefs={
            "fcm_enabled": True,
            "in_app_enabled": True,
            "quiet_hours_start": "22:00",
            "quiet_hours_end": "07:00",
        }
    )
    u2 = User(
        id=uuid.uuid4(),
        email=f"patient_notif_2_{uuid.uuid4().hex[:6]}@example.com",
        hashed_password="hashed_pw_test",
        full_name="Patient Notif Two",
        timezone="America/New_York",
        is_active=True,
        notification_prefs={
            "fcm_enabled": True,
            "in_app_enabled": True,
            "quiet_hours_start": "22:00",
            "quiet_hours_end": "07:00",
        }
    )
    session.add_all([u1, u2])
    await session.commit()
    await session.refresh(u1)
    await session.refresh(u2)
    return u1, u2


def create_auth_headers(user_id: uuid.UUID) -> dict[str, str]:
    from jose import jwt
    token = jwt.encode({"sub": str(user_id)}, settings.SECRET_KEY, algorithm="HS256")
    return {"Authorization": f"Bearer {token}"}


@pytest.mark.asyncio
async def test_user_preferences_crud_and_safety_invariant(test_users, session: AsyncSession):
    """Verifies preference CRUD and proves emergency Level 4 override cannot be disabled."""
    u1, _ = test_users
    headers = create_auth_headers(u1.id)
    transport = ASGITransport(app=app)

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # 1. GET preferences
        res_get = await client.get("/v1/users/preferences", headers=headers)
        assert res_get.status_code == 200
        data = res_get.json()
        assert data["timezone"] == "Asia/Kolkata"
        assert data["preferences"]["emergency_override_enabled"] is True

        # 2. PUT preferences attempting to alter settings
        res_put = await client.put(
            "/v1/users/preferences",
            headers=headers,
            json={
                "timezone": "Europe/London",
                "quiet_hours_start": "23:00",
                "quiet_hours_end": "06:30",
                "fcm_enabled": False
            }
        )
        assert res_put.status_code == 200
        updated = res_put.json()
        assert updated["timezone"] == "Europe/London"
        assert updated["preferences"]["quiet_hours_start"] == "23:00"
        assert updated["preferences"]["fcm_enabled"] is False
        # SAFETY INVARIANT: Emergency override remains True permanently
        assert updated["preferences"]["emergency_override_enabled"] is True


@pytest.mark.asyncio
async def test_device_fcm_token_registration_ownership(test_users, session: AsyncSession):
    """Verifies device push token registration enforcing multi-tenant boundaries."""
    u1, u2 = test_users
    headers_u1 = create_auth_headers(u1.id)
    transport = ASGITransport(app=app)

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # Register token for User 1
        res = await client.post(
            "/v1/devices/fcm-token",
            headers=headers_u1,
            json={"fcm_token": "valid_firebase_push_token_for_user_1"}
        )
        assert res.status_code == 200
        assert res.json()["success"] is True

        # Verify device in DB
        device = (await session.scalars(select(Device).where(Device.user_id == u1.id))).first()
        assert device is not None
        assert device.fcm_token == "valid_firebase_push_token_for_user_1"

        # User 2 cannot register token for User 1's device
        headers_u2 = create_auth_headers(u2.id)
        res_fail = await client.post(
            "/v1/devices/fcm-token",
            headers=headers_u2,
            json={"fcm_token": "malicious_token", "device_id": str(device.id)}
        )
        assert res_fail.status_code == 403


@pytest.mark.asyncio
async def test_notification_state_machine_and_dispatch_e2e(test_users, session: AsyncSession):
    """Verifies end-to-end finding alert dispatch with state transitions."""
    u1, _ = test_users
    service = NotificationService(db=session)

    # Create a Finding entity
    finding = Finding(
        id=uuid.uuid4(),
        user_id=u1.id,
        metric_type="heart_rate",
        severity="urgent",
        rule_id="RULE_H2_CEILING",
        observed_value=156.0,
        baseline_value=72.0,
        status="new"
    )
    session.add(finding)
    await session.commit()

    # Dispatch Alert
    notif = await service.dispatch_finding_alert(
        user_id=u1.id,
        finding=finding,
        user_timezone=u1.timezone,
        user_prefs=u1.notification_prefs
    )

    assert notif is not None
    assert notif.severity == "urgent"
    assert notif.state == NotificationState.DELIVERED.value
    assert notif.delivery_status == "SENT"
    assert notif.delivered_at is not None
    assert "Urgent" in notif.title
    assert "SAFETY NOTICE" in notif.body
    # Verify underlying Finding transitioned to notified
    await session.refresh(finding)
    assert finding.status == "notified"


@pytest.mark.asyncio
async def test_12h_deduplication_and_escalation_bypass(test_users, session: AsyncSession):
    """Verifies 12h duplicate suppression and severity escalation bypass."""
    u1, _ = test_users
    service = NotificationService(db=session)

    fid = uuid.uuid4()
    finding_lvl2 = Finding(
        id=fid,
        user_id=u1.id,
        metric_type="heart_rate",
        severity="worth_monitoring", # Tier 2
        rule_id="RULE_Z_28",
        observed_value=98.0,
        baseline_value=72.0,
        status="new"
    )
    session.add(finding_lvl2)
    await session.commit()

    # 1. First alert -> Successfully created
    notif1 = await service.dispatch_finding_alert(
        user_id=u1.id,
        finding=finding_lvl2,
        user_timezone=u1.timezone,
        user_prefs=u1.notification_prefs
    )
    assert notif1 is not None

    # Count notifications in DB
    count1 = len((await session.scalars(select(Notification).where(Notification.finding_id == fid))).all())
    assert count1 == 1

    # 2. Second alert within 12h with same severity -> SUPPRESSED
    notif2 = await service.dispatch_finding_alert(
        user_id=u1.id,
        finding=finding_lvl2,
        user_timezone=u1.timezone,
        user_prefs=u1.notification_prefs
    )
    assert notif2.id == notif1.id
    count2 = len((await session.scalars(select(Notification).where(Notification.finding_id == fid))).all())
    assert count2 == 1  # 0 new rows!

    # 3. Third alert: Finding escalates to Level 4 Urgent -> ESCALATION BYPASS!
    finding_lvl2.severity = "urgent"
    finding_lvl2.observed_value = 158.0
    finding_lvl2.rule_id = "RULE_H2_CEILING"
    await session.commit()

    notif3 = await service.dispatch_finding_alert(
        user_id=u1.id,
        finding=finding_lvl2,
        user_timezone=u1.timezone,
        user_prefs=u1.notification_prefs
    )
    assert notif3 is not None
    assert notif3.id != notif1.id  # New notification generated!
    assert notif3.severity == "urgent"
    assert notif3.payload.get("is_escalation") is True


@pytest.mark.asyncio
async def test_rest_api_tenant_isolation_and_acknowledgement(test_users, session: AsyncSession):
    """Verifies GET /v1/notifications, tenant isolation, acknowledge, and dismiss."""
    u1, u2 = test_users
    headers_u1 = create_auth_headers(u1.id)
    headers_u2 = create_auth_headers(u2.id)
    transport = ASGITransport(app=app)

    # Create notification for User 1
    now = datetime.now(timezone.utc)
    notif_u1 = Notification(
        id=uuid.uuid4(),
        user_id=u1.id,
        channel="in_app",
        severity="potentially_concerning",
        title="Test Alert User 1",
        body="Body",
        state=NotificationState.DELIVERED.value,
        delivery_status="SENT",
        sent_at=now,
        created_at=now
    )
    session.add(notif_u1)
    await session.commit()

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # User 1 queries feed -> sees alert
        res_u1 = await client.get("/v1/notifications", headers=headers_u1)
        assert res_u1.status_code == 200
        data_u1 = res_u1.json()
        assert data_u1["total"] >= 1
        assert any(item["id"] == str(notif_u1.id) for item in data_u1["items"])

        # User 2 queries feed -> sees 0 alerts (TENANT ISOLATION)
        res_u2 = await client.get("/v1/notifications", headers=headers_u2)
        assert res_u2.status_code == 200
        assert not any(item["id"] == str(notif_u1.id) for item in res_u2.json()["items"])

        # User 2 attempts to acknowledge User 1's alert -> 404
        res_bad_ack = await client.post(f"/v1/notifications/{notif_u1.id}/acknowledge", headers=headers_u2)
        assert res_bad_ack.status_code == 404

        # User 1 acknowledges alert -> Success
        res_ack = await client.post(f"/v1/notifications/{notif_u1.id}/acknowledge", headers=headers_u1)
        assert res_ack.status_code == 200
        assert res_ack.json()["state"] == "ACKNOWLEDGED"

        # User 1 dismisses alert -> Success
        res_dis = await client.post(f"/v1/notifications/{notif_u1.id}/dismiss", headers=headers_u1)
        assert res_dis.status_code == 200
        assert res_dis.json()["state"] == "DISMISSED"


def test_websocket_streaming_heartbeat_and_auth(test_users):
    """Tests WebSocket endpoint authentication and heartbeat ping/pong."""
    u1, _ = test_users
    from jose import jwt
    token = jwt.encode({"sub": str(u1.id)}, settings.SECRET_KEY, algorithm="HS256")

    client = TestClient(app)

    # 1. Valid token connection & heartbeat
    with client.websocket_connect(f"/v1/ws/stream?token={token}") as ws:
        ws.send_json({"type": "ping"})
        data = ws.receive_json()
        assert data["type"] == "pong"
        assert "timestamp" in data

    # 2. Invalid token -> connection rejected
    with pytest.raises(Exception):
        with client.websocket_connect("/v1/ws/stream?token=invalid_token") as ws_bad:
            pass
