"""Timezone-Aware Quiet Hours Evaluation Service (Agent 11).

Never hard-codes UTC assumptions.
Computes whether the current moment falls within the user's localized quiet hours window,
and calculates the exact UTC timestamp for when quiet hours conclude.
"""

from datetime import datetime, time, timedelta, timezone
from typing import Optional, Tuple
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
import logging

logger = logging.getLogger("healthos.quiet_hours")

DEFAULT_QUIET_START = "22:00"
DEFAULT_QUIET_END = "07:00"
DEFAULT_TIMEZONE = "Asia/Kolkata"


def parse_time_str(time_str: str, default: time) -> time:
    """Safely parses 'HH:MM' string into time object."""
    try:
        parts = [int(p) for p in time_str.strip().split(":")]
        return time(hour=parts[0], minute=parts[1])
    except Exception:
        return default


def get_safe_zoneinfo(tz_name: Optional[str]) -> ZoneInfo:
    """Resolves ZoneInfo safely with fallback."""
    if not tz_name:
        return ZoneInfo(DEFAULT_TIMEZONE)
    try:
        return ZoneInfo(tz_name.strip())
    except (ZoneInfoNotFoundError, ValueError, Exception):
        logger.warning(f"Unrecognized timezone '{tz_name}', falling back to {DEFAULT_TIMEZONE}")
        return ZoneInfo(DEFAULT_TIMEZONE)


class QuietHoursEvaluator:
    """
    Evaluates whether a notification falls within user's quiet hours in their local timezone.
    Calculates next release timestamp for deferred dispatch.
    """

    @classmethod
    def evaluate(
        cls,
        user_timezone: Optional[str],
        quiet_start_str: Optional[str] = None,
        quiet_end_str: Optional[str] = None,
        current_time_utc: Optional[datetime] = None
    ) -> Tuple[bool, datetime]:
        """
        Determines if local time is within quiet hours and calculates next release UTC time.

        Returns:
            Tuple of:
            - is_in_quiet_hours: bool
            - release_time_utc: datetime (when quiet hours end)
        """
        ref_utc = current_time_utc or datetime.now(timezone.utc)
        if ref_utc.tzinfo is None:
            ref_utc = ref_utc.replace(tzinfo=timezone.utc)

        user_tz = get_safe_zoneinfo(user_timezone)
        local_now = ref_utc.astimezone(user_tz)

        start_t = parse_time_str(quiet_start_str or DEFAULT_QUIET_START, time(22, 0))
        end_t = parse_time_str(quiet_end_str or DEFAULT_QUIET_END, time(7, 0))
        current_t = local_now.time()

        # Check if start and end span overnight (e.g. 22:00 -> 07:00)
        is_overnight = start_t > end_t

        if is_overnight:
            # Quiet if >= 22:00 OR < 07:00
            is_quiet = (current_t >= start_t) or (current_t < end_t)
            if is_quiet:
                # Release time is today at end_t if before midnight, or today at end_t if after midnight
                if current_t >= start_t:
                    # After 22:00, ends tomorrow at 07:00
                    target_date = local_now.date() + timedelta(days=1)
                else:
                    # Before 07:00, ends today at 07:00
                    target_date = local_now.date()
                release_local = datetime.combine(target_date, end_t, tzinfo=user_tz)
                release_utc = release_local.astimezone(timezone.utc)
            else:
                # Outside quiet hours; release time is immediate or start of next quiet hours
                release_utc = ref_utc
        else:
            # Daytime quiet window (e.g. 13:00 -> 15:00)
            is_quiet = start_t <= current_t < end_t
            if is_quiet:
                target_date = local_now.date()
                release_local = datetime.combine(target_date, end_t, tzinfo=user_tz)
                release_utc = release_local.astimezone(timezone.utc)
            else:
                release_utc = ref_utc

        return is_quiet, release_utc
