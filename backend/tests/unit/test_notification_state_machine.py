"""Unit tests for Notification State Machine transitions."""

import pytest
from app.services.notification_state_machine import (
    InvalidNotificationStateTransition,
    NotificationState,
    NotificationStateMachine,
)


def test_valid_notification_lifecycle_path():
    """Verifies standard forward lifecycle: CREATED -> POLICY_EVALUATED -> DEDUP_CHECKED -> QUEUED -> DISPATCHING -> DELIVERED."""
    state = NotificationState.CREATED
    state = NotificationStateMachine.validate_transition(state, NotificationState.POLICY_EVALUATED)
    assert state == NotificationState.POLICY_EVALUATED

    state = NotificationStateMachine.validate_transition(state, NotificationState.DEDUP_CHECKED)
    assert state == NotificationState.DEDUP_CHECKED

    state = NotificationStateMachine.validate_transition(state, NotificationState.QUEUED)
    assert state == NotificationState.QUEUED

    state = NotificationStateMachine.validate_transition(state, NotificationState.DISPATCHING)
    assert state == NotificationState.DISPATCHING

    state = NotificationStateMachine.validate_transition(state, NotificationState.DELIVERED)
    assert state == NotificationState.DELIVERED

    state = NotificationStateMachine.validate_transition(state, NotificationState.ACKNOWLEDGED)
    assert state == NotificationState.ACKNOWLEDGED

    state = NotificationStateMachine.validate_transition(state, NotificationState.DISMISSED)
    assert state == NotificationState.DISMISSED


def test_invalid_state_transition_raises_exception():
    """Verifies that skipping or reverting states raises InvalidNotificationStateTransition."""
    with pytest.raises(InvalidNotificationStateTransition):
        # Cannot jump from CREATED directly to DELIVERED
        NotificationStateMachine.validate_transition(
            NotificationState.CREATED, NotificationState.DELIVERED
        )

    with pytest.raises(InvalidNotificationStateTransition):
        # Cannot transition back from DELIVERED to CREATED
        NotificationStateMachine.validate_transition(
            NotificationState.DELIVERED, NotificationState.CREATED
        )

    with pytest.raises(InvalidNotificationStateTransition):
        # Terminal state DISMISSED cannot transition to DISPATCHING
        NotificationStateMachine.validate_transition(
            NotificationState.DISMISSED, NotificationState.DISPATCHING
        )


def test_retry_and_dead_letter_transitions():
    """Verifies failure path: DISPATCHING -> FAILED -> RETRYING -> DEAD_LETTER."""
    state = NotificationState.DISPATCHING
    state = NotificationStateMachine.validate_transition(state, NotificationState.FAILED)
    assert state == NotificationState.FAILED

    state = NotificationStateMachine.validate_transition(state, NotificationState.RETRYING)
    assert state == NotificationState.RETRYING

    state = NotificationStateMachine.validate_transition(state, NotificationState.DEAD_LETTER)
    assert state == NotificationState.DEAD_LETTER
