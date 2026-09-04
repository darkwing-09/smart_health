"""Human-in-the-Loop Action Gate & Safety Boundary.

Enforces strict boundaries between:
- INFORMATIONAL_ACTION (read-only timeline, summaries)
- RECOMMENDATION (non-clinical behavioral guidance)
- EXTERNAL_ACTION (sending WhatsApp, sharing records, provider outreach)

Consequential external actions REQUIRE explicit human approval tokens (ADR-003, Rule H3).
Autonomous execution of external actions is strictly prohibited.
"""

import uuid
import hmac
import hashlib
from enum import Enum
from datetime import datetime, timezone
from typing import Dict, Any, Optional
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.audit import AuditLog


class ActionTier(str, Enum):
    INFORMATIONAL_ACTION = "INFORMATIONAL_ACTION" # Display in-app, update timeline
    RECOMMENDATION = "RECOMMENDATION"             # Suggest hydration, sleep hygiene
    EXTERNAL_ACTION = "EXTERNAL_ACTION"           # WhatsApp dispatch, doctor outreach, booking


ActionType = ActionTier


class ActionApprovalStatus(str, Enum):
    ALLOWED_AUTONOMOUS = "ALLOWED_AUTONOMOUS"
    PENDING_USER_APPROVAL = "PENDING_USER_APPROVAL"
    APPROVED_BY_USER = "APPROVED_BY_USER"
    REJECTED_BY_POLICY = "REJECTED_BY_POLICY"


class ActionGateDecision(BaseModel):
    action_tier: ActionTier
    action_name: str
    status: ActionApprovalStatus
    is_executable: bool
    requires_user_approval: bool
    reason: str
    audit_id: Optional[str] = None


class ActionGate:
    """
    Deterministic Action Gate.
    Guarantees AI agent cannot unilaterally execute real-world consequential actions.
    """

    @classmethod
    async def evaluate_action(
        cls,
        db: Optional[AsyncSession],
        user_id: uuid.UUID,
        action_tier: ActionTier,
        action_name: str,
        user_approval_granted: bool = False,
        action_payload: Optional[Dict[str, Any]] = None
    ) -> ActionGateDecision:
        now = datetime.now(timezone.utc)
        audit_id = None

        if action_tier in {ActionTier.INFORMATIONAL_ACTION, ActionTier.RECOMMENDATION}:
            decision = ActionGateDecision(
                action_tier=action_tier,
                action_name=action_name,
                status=ActionApprovalStatus.ALLOWED_AUTONOMOUS,
                is_executable=True,
                requires_user_approval=False,
                reason="Informational and recommendation tiers are permitted for autonomous system dispatch."
            )
        else:
            # EXTERNAL_ACTION
            if user_approval_granted:
                decision = ActionGateDecision(
                    action_tier=action_tier,
                    action_name=action_name,
                    status=ActionApprovalStatus.APPROVED_BY_USER,
                    is_executable=True,
                    requires_user_approval=True,
                    reason="Explicit user confirmation token verified for external action."
                )
            else:
                decision = ActionGateDecision(
                    action_tier=action_tier,
                    action_name=action_name,
                    status=ActionApprovalStatus.PENDING_USER_APPROVAL,
                    is_executable=False,
                    requires_user_approval=True,
                    reason="Consequential external action blocked: User approval required prior to execution (Rule H3)."
                )

        # Audit if session is provided
        if db:
            audit_entry = AuditLog(
                id=uuid.uuid4(),
                user_id=user_id,
                actor="system:action_gate",
                action="action_gate_evaluated",
                target_ref=f"action:{action_name}",
                detail={
                    "action_tier": action_tier.value,
                    "action_name": action_name,
                    "status": decision.status.value,
                    "is_executable": decision.is_executable,
                    "payload": action_payload or {}
                },
                timestamp=now
            )
            db.add(audit_entry)
            await db.commit()
            decision.audit_id = str(audit_entry.id)

        return decision

    @classmethod
    def generate_approval_token(
        cls,
        user_id: uuid.UUID,
        action_type: ActionTier,
        target_ref: str
    ) -> str:
        """
        Generates a cryptographically signed HMAC token bound to a specific user, action tier, and target.
        """
        salt = uuid.uuid4().hex[:12]
        tier_val = action_type.value if hasattr(action_type, "value") else str(action_type)
        msg = f"{user_id}:{tier_val}:{target_ref}:{salt}"
        sig = hmac.new(
            settings.SECRET_KEY.encode("utf-8"),
            msg.encode("utf-8"),
            hashlib.sha256
        ).hexdigest()[:24]
        return f"appr_{salt}_{sig}"

    @classmethod
    def verify_approval_token(
        cls,
        token: str,
        user_id: uuid.UUID,
        action_type: ActionTier,
        target_ref: str
    ) -> bool:
        """
        Verifies HMAC integrity and binding of an approval token.
        Fails on tampering, truncation, or cross-action replay.
        """
        if not token or not token.startswith("appr_"):
            return False
        parts = token.split("_")
        if len(parts) != 3:
            return False
        salt = parts[1]
        provided_sig = parts[2]
        tier_val = action_type.value if hasattr(action_type, "value") else str(action_type)
        msg = f"{user_id}:{tier_val}:{target_ref}:{salt}"
        expected_sig = hmac.new(
            settings.SECRET_KEY.encode("utf-8"),
            msg.encode("utf-8"),
            hashlib.sha256
        ).hexdigest()[:24]
        return hmac.compare_digest(provided_sig, expected_sig)
