"""Notification Fatigue and Delivery Observability Metrics.

Tracks delivery latency, deduplication rates, quiet hours suppression counts,
escalations, retries, and failures to prevent alert fatigue (Principle #16).
"""

from collections import defaultdict
from dataclasses import dataclass, field
import threading
import time
from typing import Any, Dict


@dataclass
class NotificationMetricsCollector:
    """Thread-safe in-memory and operational metrics collector."""

    _lock: threading.Lock = field(default_factory=threading.Lock)
    total_dispatched: int = 0
    total_deduplicated: int = 0
    total_quiet_hours_held: int = 0
    total_escalated: int = 0
    total_retried: int = 0
    total_failed: int = 0
    total_delivered: int = 0
    tier_counts: Dict[str, int] = field(default_factory=lambda: defaultdict(int))
    user_daily_counts: Dict[str, int] = field(default_factory=lambda: defaultdict(int))
    latencies_ms: list[float] = field(default_factory=list)

    def record_dispatch(
        self,
        user_id: str,
        tier: int,
        severity: str,
        held_quiet_hours: bool = False,
        escalated: bool = False,
        latency_ms: float = 0.0
    ) -> None:
        with self._lock:
            self.total_dispatched += 1
            self.tier_counts[f"level_{tier}_{severity}"] += 1
            self.user_daily_counts[user_id] += 1
            if held_quiet_hours:
                self.total_quiet_hours_held += 1
            if escalated:
                self.total_escalated += 1
            if latency_ms > 0:
                self.latencies_ms.append(latency_ms)
                if len(self.latencies_ms) > 1000:
                    self.latencies_ms = self.latencies_ms[-1000:]

    def record_dedup(self, user_id: str, finding_id: str) -> None:
        with self._lock:
            self.total_deduplicated += 1

    def record_retry(self) -> None:
        with self._lock:
            self.total_retried += 1

    def record_failure(self) -> None:
        with self._lock:
            self.total_failed += 1

    def get_summary(self) -> dict[str, Any]:
        with self._lock:
            avg_latency = (
                sum(self.latencies_ms) / len(self.latencies_ms) if self.latencies_ms else 0.0
            )
            return {
                "total_dispatched": self.total_dispatched,
                "total_deduplicated": self.total_deduplicated,
                "total_quiet_hours_held": self.total_quiet_hours_held,
                "total_escalated": self.total_escalated,
                "total_retried": self.total_retried,
                "total_failed": self.total_failed,
                "tier_breakdown": dict(self.tier_counts),
                "unique_active_users_notified": len(self.user_daily_counts),
                "avg_delivery_latency_ms": round(avg_latency, 2),
            }


metrics = NotificationMetricsCollector()
