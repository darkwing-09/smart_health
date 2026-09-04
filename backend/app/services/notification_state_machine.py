"""Notification Lifecycle State Machine.

Authoritative state machine governing alert persistence and dispatch stages:
CREATED
→ POLICY_EVALUATED
→ DEDUP_CHECKED
→ QUEUED
→ DISPATCHING
→ DELIVERED

Failure & Terminal Paths:
FAILED → RETRYING → DEAD_LETTER
SUPPRESSED_DUPLICATE
EXPIRED
ACKNOWLEDGED
DISMISSED
"""

from enum import Enum
from typing import Set


class NotificationState(str, Enum):
    CREATED = "CREATED"
    POLICY_EVALUATED = "POLICY_EVALUATED"
    DEDUP_CHECKED = "DEDUP_CHECKED"
    SUPPRESSED_DUPLICATE = "SUPPRESSED_DUPLICATE"
    QUEUED = "QUEUED"
    DISPATCHING = "DISPATCHING"
    DELIVERED = "DELIVERED"
    FAILED = "FAILED"
    RETRYING = "RETRYING"
    DEAD_LETTER = "DEAD_LETTER"
    EXPIRED = "EXPIRED"
    ACKNOWLEDGED = "ACKNOWLEDGED"
    DISMISSED = "DISMISSED"


class InvalidNotificationStateTransition(Exception):
    """Raised when an illegal state transition is attempted on a notification."""

    def __init__(self, current_state: str, target_state: str) -> None:
        super().__init__(
            f"Illegal notification state transition from '{current_state}' to '{target_state}'."
        )
        self.current_state = current_state
        self.target_state = target_state


class NotificationStateMachine:
    """Validates and enforces valid state transitions."""

    VALID_TRANSITIONS: dict[NotificationState, Set[NotificationState]] = {
        NotificationState.CREATED: {
            NotificationState.POLICY_EVALUATED,
            NotificationState.FAILED,
        },
        NotificationState.POLICY_EVALUATED: {
            NotificationState.DEDUP_CHECKED,
            NotificationState.SUPPRESSED_DUPLICATE,
            NotificationState.FAILED,
            NotificationState.EXPIRED,
        },
        NotificationState.DEDUP_CHECKED: {
            NotificationState.QUEUED,
            NotificationState.DISPATCHING,
            NotificationState.SUPPRESSED_DUPLICATE,
            NotificationState.FAILED,
        },
        NotificationState.QUEUED: {
            NotificationState.DISPATCHING,
            NotificationState.FAILED,
            NotificationState.EXPIRED,
        },
        NotificationState.DISPATCHING: {
            NotificationState.DELIVERED,
            NotificationState.FAILED,
        },
        NotificationState.FAILED: {
            NotificationState.RETRYING,
            NotificationState.DEAD_LETTER,
        },
        NotificationState.RETRYING: {
            NotificationState.DISPATCHING,
            NotificationState.DEAD_LETTER,
            NotificationState.FAILED,
        },
        NotificationState.DELIVERED: {
            NotificationState.ACKNOWLEDGED,
            NotificationState.DISMISSED,
            NotificationState.EXPIRED,
        },
        NotificationState.ACKNOWLEDGED: {
            NotificationState.DISMISSED,
        },
        # Terminal states:
        NotificationState.SUPPRESSED_DUPLICATE: set(),
        NotificationState.DEAD_LETTER: set(),
        NotificationState.EXPIRED: set(),
        NotificationState.DISMISSED: set(),
    }

    @classmethod
    def validate_transition(
        cls,
        current_state: str | NotificationState,
        target_state: str | NotificationState
    ) -> NotificationState:
        """
        Validates transition and returns the target NotificationState enum.
        Raises InvalidNotificationStateTransition if invalid.
        """
        curr = NotificationState(current_state) if isinstance(current_state, str) else current_state
        target = NotificationState(target_state) if isinstance(target_state, str) else target_state

        if target not in cls.VALID_TRANSITIONS.get(curr, set()):
            raise InvalidNotificationStateTransition(curr.value, target.value)

        return target
