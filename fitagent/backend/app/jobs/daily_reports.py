"""Daily report job.  python -m app.jobs.daily_reports [--loop]

Once: sends every due report and exits (use from cron).  --loop: repeats every REPORT_JOB_INTERVAL_S seconds
(the `reports` service in docker-compose). Idempotent: one delivery per user per local day.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
from datetime import UTC, datetime

from ..config import get_settings
from ..db import SessionLocal
from ..services.daily_report import deliver, due_users

log = logging.getLogger("fitagent.reports")


async def run_once(now: datetime | None = None) -> dict[str, int]:
    counts: dict[str, int] = {}
    for user, day in await due_users(SessionLocal, now or datetime.now(UTC)):
        try:
            result = await deliver(SessionLocal, user, day)
        except Exception:  # one bad user must not stop the rest
            log.exception("report for %s failed", user.id)
            counts["error"] = counts.get("error", 0) + 1
            continue
        counts[result.status] = counts.get(result.status, 0) + 1
        log.info("report %s for user %s: %s", day, user.id, result.status)
    return counts


async def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--loop", action="store_true")
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    s = get_settings()
    log.info("daily reports: WhatsApp %s", "configured" if s.whatsapp_configured else "NOT configured (dry run)")
    while True:
        log.info("run: %s", await run_once())
        if not args.loop:
            return
        await asyncio.sleep(s.report_job_interval_s)


if __name__ == "__main__":
    asyncio.run(main())
