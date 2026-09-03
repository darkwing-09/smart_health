"""Deterministic Context Engine.

Resolves physiological and behavioral activity states (RESTING, WALKING,
RUNNING, EXERCISE, SLEEPING, POST_EXERCISE, UNKNOWN) using deterministic rules.
Zero LLM inference.
"""

from enum import Enum
from datetime import datetime, timedelta, timezone
from typing import Optional, Dict, Any, List
from zoneinfo import ZoneInfo
from pydantic import BaseModel, Field


class UserActivityContext(str, Enum):
    RESTING = "RESTING"
    WALKING = "WALKING"
    RUNNING = "RUNNING"
    EXERCISE = "EXERCISE"
    SLEEPING = "SLEEPING"
    POST_EXERCISE = "POST_EXERCISE"
    UNKNOWN = "UNKNOWN"


class ContextSnapshot(BaseModel):
    primary_context: UserActivityContext
    confidence: float
    local_hour: int
    steps_concurrent: int
    heart_rate_concurrent: Optional[float] = None
    is_nighttime: bool
    reasons: List[str] = Field(default_factory=list)
    evidence: Dict[str, Any] = Field(default_factory=dict)


class ContextEngine:
    """
    Deterministic Activity & State Classification Engine.
    Examines multi-modal telemetry (steps, heart rate, circadian time, recent history)
    to classify behavioral state without machine learning ambiguity.
    """

    @classmethod
    def classify_context(
        cls,
        timestamp: datetime,
        user_timezone: str = "UTC",
        steps_recent: int = 0,
        heart_rate_recent: Optional[float] = None,
        is_active_workout: bool = False,
        steps_prior_30m: int = 0,
        has_active_sleep_session: bool = False
    ) -> ContextSnapshot:
        """
        Classifies user context around a target timestamp.
        """
        try:
            tz = ZoneInfo(user_timezone)
        except Exception:
            tz = ZoneInfo("UTC")

        if timestamp.tzinfo is None:
            timestamp = timestamp.replace(tzinfo=timezone.utc)

        local_dt = timestamp.astimezone(tz)
        local_hour = local_dt.hour
        is_nighttime = (local_hour >= 23 or local_hour < 7)

        reasons = []

        # 1. Hard workout session flag
        if is_active_workout:
            reasons.append("Active workout session flag recorded by wearable.")
            return ContextSnapshot(
                primary_context=UserActivityContext.EXERCISE,
                confidence=0.99,
                local_hour=local_hour,
                steps_concurrent=steps_recent,
                heart_rate_concurrent=heart_rate_recent,
                is_nighttime=is_nighttime,
                reasons=reasons,
                evidence={"is_active_workout": True}
            )

        # 2. Sleep Session or Nocturnal Inactivity
        if has_active_sleep_session or (is_nighttime and steps_recent == 0 and (heart_rate_recent is None or heart_rate_recent < 78.0)):
            reasons.append("Nocturnal timeframe with zero steps and resting heart rate indicates sleep.")
            return ContextSnapshot(
                primary_context=UserActivityContext.SLEEPING,
                confidence=0.95 if has_active_sleep_session else 0.88,
                local_hour=local_hour,
                steps_concurrent=steps_recent,
                heart_rate_concurrent=heart_rate_recent,
                is_nighttime=is_nighttime,
                reasons=reasons,
                evidence={"has_active_sleep_session": has_active_sleep_session, "is_nighttime": is_nighttime}
            )

        # 3. High Exertion: Running / Intensive Exercise
        if steps_recent >= 750 or (steps_recent >= 200 and heart_rate_recent and heart_rate_recent >= 125.0):
            context = UserActivityContext.RUNNING if steps_recent >= 800 else UserActivityContext.EXERCISE
            reasons.append(f"High step cadence ({steps_recent} steps) or elevated exertion HR indicates active exercise.")
            return ContextSnapshot(
                primary_context=context,
                confidence=0.92,
                local_hour=local_hour,
                steps_concurrent=steps_recent,
                heart_rate_concurrent=heart_rate_recent,
                is_nighttime=is_nighttime,
                reasons=reasons,
                evidence={"steps_recent": steps_recent, "hr_recent": heart_rate_recent}
            )

        # 4. Moderate Exertion: Walking
        if 50 <= steps_recent < 750:
            reasons.append(f"Moderate step cadence ({steps_recent} steps) indicates walking activity.")
            return ContextSnapshot(
                primary_context=UserActivityContext.WALKING,
                confidence=0.90,
                local_hour=local_hour,
                steps_concurrent=steps_recent,
                heart_rate_concurrent=heart_rate_recent,
                is_nighttime=is_nighttime,
                reasons=reasons,
                evidence={"steps_recent": steps_recent}
            )

        # 5. Post-Exercise Recovery
        # Steps are now low (< 50), but preceding 30m had substantial exercise (>= 500 steps)
        if steps_recent < 50 and steps_prior_30m >= 500 and heart_rate_recent and heart_rate_recent > 80.0:
            reasons.append("Low immediate steps following high prior exertion with lingering heart rate elevation represents post-exercise recovery.")
            return ContextSnapshot(
                primary_context=UserActivityContext.POST_EXERCISE,
                confidence=0.85,
                local_hour=local_hour,
                steps_concurrent=steps_recent,
                heart_rate_concurrent=heart_rate_recent,
                is_nighttime=is_nighttime,
                reasons=reasons,
                evidence={"steps_prior_30m": steps_prior_30m, "steps_recent": steps_recent}
            )

        # 6. Stationary Daytime: Resting
        if steps_recent < 50:
            reasons.append(f"Low step count ({steps_recent} steps) during waking hours indicates resting state.")
            return ContextSnapshot(
                primary_context=UserActivityContext.RESTING,
                confidence=0.90,
                local_hour=local_hour,
                steps_concurrent=steps_recent,
                heart_rate_concurrent=heart_rate_recent,
                is_nighttime=is_nighttime,
                reasons=reasons,
                evidence={"steps_recent": steps_recent}
            )

        return ContextSnapshot(
            primary_context=UserActivityContext.UNKNOWN,
            confidence=0.50,
            local_hour=local_hour,
            steps_concurrent=steps_recent,
            heart_rate_concurrent=heart_rate_recent,
            is_nighttime=is_nighttime,
            reasons=["Telemetry parameters are ambiguous."],
            evidence={}
        )
