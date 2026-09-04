"""Unit tests for FCM Push Notification Service."""

import uuid
import pytest
from app.services.fcm import FcmChannelId, FcmNotificationService, FcmPriority


def test_fcm_payload_structure():
    """Verifies Firebase HTTP v1 compliant JSON payload format."""
    service = FcmNotificationService()
    token = "fake_device_token_xyz_1234567890"
    notif_id = uuid.uuid4()
    finding_id = uuid.uuid4()

    payload = service.build_payload(
        fcm_token=token,
        title="Urgent Physiological Observation",
        body="Resting heart rate reached 155 bpm.",
        notification_id=notif_id,
        finding_id=finding_id,
        severity="urgent",
        alert_tier=4,
        fcm_channel=FcmChannelId.URGENT,
        priority=FcmPriority.HIGH
    )

    assert "message" in payload
    msg = payload["message"]
    assert msg["token"] == token
    assert msg["notification"]["title"] == "Urgent Physiological Observation"
    assert msg["data"]["notification_id"] == str(notif_id)
    assert msg["data"]["finding_id"] == str(finding_id)
    assert msg["data"]["alert_tier"] == "4"
    assert msg["android"]["priority"] == "HIGH"
    assert msg["android"]["notification"]["channel_id"] == "healthos_urgent"


@pytest.mark.asyncio
async def test_fcm_dry_run_dispatch():
    """Verifies dry-run dispatch succeeds cleanly in test/dev environment."""
    service = FcmNotificationService()
    user_id = uuid.uuid4()
    notif_id = uuid.uuid4()

    result = await service.dispatch(
        fcm_token="sample_token_1234567890",
        title="Test Notification",
        body="Test Body",
        notification_id=notif_id,
        user_id=user_id,
        severity="potentially_concerning",
        alert_tier=3,
        force_dry_run=True
    )

    assert result.success is True
    assert result.message_id is not None
    assert "dry_run" in result.message_id
    assert result.attempts == 1
