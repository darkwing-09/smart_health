"""End-to-End Deterministic Anomaly Pipeline Service.

Coordinates:
Measurements -> Temporal Context (Steps) -> Baseline -> AnomalyDetector
-> Idempotent Finding Persistence -> HealthIntelligenceGraph invocation.
"""

import uuid
import logging
from datetime import datetime, timedelta, timezone
from typing import Dict, Any, List, Optional
from zoneinfo import ZoneInfo
from sqlalchemy import select, func, and_
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.user import User
from app.models.measurement import Measurement
from app.models.baseline import Baseline
from app.models.finding import Finding, FindingExplanation
from app.services.baseline import BaselineService
from app.services.anomaly import AnomalyDetector
from app.graphs.health_intel import build_health_intel_graph

logger = logging.getLogger("healthos.pipeline.anomaly")


class AnomalyPipelineService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db
        self.baseline_service = BaselineService(db)
        self.intel_graph = build_health_intel_graph()

    async def run_pipeline_for_user(
        self,
        user_id: uuid.UUID,
        eval_window_start: datetime,
        eval_window_end: datetime,
        auto_explain: bool = True
    ) -> List[Finding]:
        """
        Executes the deterministic anomaly pipeline for a user across a time window.
        Idempotent: rerunning against the same window does not duplicate findings.
        """
        user = await self.db.get(User, user_id)
        if not user:
            logger.warning("Pipeline skipped: User not found", extra={"user_id": str(user_id)})
            return []

        user_tz_str = user.timezone or "UTC"
        try:
            tz = ZoneInfo(user_tz_str)
        except Exception:
            tz = ZoneInfo("UTC")
            user_tz_str = "UTC"

        # 1. Fetch or compute active baseline for heart rate
        # Find most recent baseline computed within last 24h, else recompute
        stmt_baseline = (
            select(Baseline)
            .where(
                Baseline.user_id == user_id,
                Baseline.metric_type == "heart_rate"
            )
            .order_by(Baseline.computed_at.desc())
            .limit(1)
        )
        res_baseline = await self.db.execute(stmt_baseline)
        active_baseline = res_baseline.scalar_one_or_none()

        if not active_baseline or (eval_window_end - active_baseline.computed_at) > timedelta(hours=24):
            logger.info("Computing fresh rolling baseline", extra={"user_id": str(user_id)})
            active_baseline = await self.baseline_service.compute_baseline(
                user_id=user_id,
                metric_type="heart_rate",
                current_date=eval_window_end,
                user_timezone=user_tz_str
            )

        # 2. Query target heart rate measurements in evaluation window
        stmt_hr = (
            select(Measurement)
            .where(
                Measurement.user_id == user_id,
                Measurement.metric_type == "heart_rate",
                Measurement.recorded_at >= eval_window_start,
                Measurement.recorded_at <= eval_window_end
            )
            .order_by(Measurement.recorded_at.asc())
        )
        res_hr = await self.db.execute(stmt_hr)
        hr_measurements = res_hr.scalars().all()

        created_findings: List[Finding] = []

        for m in hr_measurements:
            # 3. Retrieve concurrent step count in +/- 15 minute window (activity context)
            step_window_start = m.recorded_at - timedelta(minutes=15)
            step_window_end = m.recorded_at + timedelta(minutes=15)

            stmt_steps = select(func.coalesce(func.sum(Measurement.value), 0.0)).where(
                Measurement.user_id == user_id,
                Measurement.metric_type == "steps",
                Measurement.recorded_at >= step_window_start,
                Measurement.recorded_at <= step_window_end
            )
            concurrent_steps = int(await self.db.scalar(stmt_steps) or 0)

            # Convert reading time to user's local hour (0..23)
            local_reading_time = m.recorded_at.astimezone(tz)
            reading_hour_local = local_reading_time.hour

            # 4. Evaluate deterministic anomaly
            eval_result = AnomalyDetector.evaluate(
                current_value=m.value,
                reading_hour=reading_hour_local,
                baseline=active_baseline,
                steps_recent=concurrent_steps,
                is_active_workout=False,
                data_quality_flag=m.data_quality_flag
            )

            if not eval_result:
                continue

            # 5. Persist Finding idempotently using ON CONFLICT DO NOTHING
            finding_id = uuid.uuid4()
            stmt_insert = (
                insert(Finding)
                .values(
                    id=finding_id,
                    user_id=user_id,
                    metric_type="heart_rate",
                    severity=eval_result["severity"],
                    rule_id=eval_result["rule_id"],
                    rule_version="1.1.0",
                    baseline_id=active_baseline.id,
                    status="new",
                    first_detected_at=datetime.now(timezone.utc),
                    last_updated_at=datetime.now(timezone.utc),
                    observed_value=eval_result["observed_value"],
                    baseline_value=eval_result["expected_mean"],
                    deviation=eval_result["deviation"],
                    standard_deviation=eval_result["expected_std"],
                    reading_timestamp=m.recorded_at,
                    timezone=user_tz_str,
                    activity_context=eval_result["activity_context"],
                    data_quality=m.data_quality_flag,
                    confidence=m.confidence,
                    source_measurement_ids=[str(m.id)],
                    evidence=eval_result["evidence"]
                )
                .on_conflict_do_nothing(
                    index_elements=["user_id", "metric_type", "rule_id", "reading_timestamp"]
                )
            )
            result = await self.db.execute(stmt_insert)
            await self.db.commit()

            if result.rowcount > 0:
                # Newly created finding
                persisted_finding = await self.db.get(Finding, finding_id)
                if persisted_finding:
                    created_findings.append(persisted_finding)
                    logger.info(
                        "Durable finding generated",
                        extra={
                            "finding_id": str(finding_id),
                            "rule_id": eval_result["rule_id"],
                            "severity": eval_result["severity"],
                            "user_id": str(user_id)
                        }
                    )

                    # 6. Optional automated explanation via HealthIntelligenceGraph
                    if auto_explain and eval_result["severity"] in {"worth_monitoring", "potentially_concerning", "urgent"}:
                        await self.generate_and_attach_explanation(persisted_finding, active_baseline)

        return created_findings

    async def generate_and_attach_explanation(
        self,
        finding: Finding,
        baseline: Baseline
    ) -> Optional[FindingExplanation]:
        """
        Executes HealthIntelligenceGraph to generate and attach a grounded,
        calm, 7-part explanation to the Finding, gated by Rule H1 guardrail.
        """
        initial_state = {
            "finding_id": str(finding.id),
            "metric_type": finding.metric_type,
            "observed_value": finding.observed_value,
            "unit": "bpm",
            "recorded_at": finding.reading_timestamp.isoformat() if finding.reading_timestamp else "",
            "baseline": {
                "circadian_mean": finding.baseline_value,
                "circadian_std": finding.standard_deviation
            },
            "explanation": None,
            "safety_approved": False,
            "safety_violations": []
        }

        graph_result = await self.intel_graph.ainvoke(initial_state)
        expl_data = graph_result.get("explanation")
        if not expl_data:
            return None

        explanation_record = FindingExplanation(
            id=uuid.uuid4(),
            finding_id=finding.id,
            agent_id="agent:health_intel",
            what_changed=expl_data.get("what_changed", ""),
            measurements_caused=expl_data.get("measurements_caused", []),
            baseline_difference=expl_data.get("baseline_difference", ""),
            historical_context=expl_data.get("historical_context", ""),
            confidence_and_data_quality=expl_data.get("confidence_and_data_quality", ""),
            why_it_matters=expl_data.get("why_it_matters", ""),
            next_steps=expl_data.get("next_steps", []),
            grounding_trace={
                "z_score": finding.evidence.get("z_score") if finding.evidence else None,
                "rule_id": finding.rule_id,
                "safety_approved": graph_result.get("safety_approved", True)
            },
            model_version="gpt-4o",
            prompt_version="1.0.0",
            generated_at=datetime.now(timezone.utc)
        )
        self.db.add(explanation_record)
        await self.db.commit()
        await self.db.refresh(explanation_record)
        return explanation_record
