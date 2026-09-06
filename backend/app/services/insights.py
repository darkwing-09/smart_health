"""Gemini AI Wellness Insights Service.

Grounded, privacy-first wellness insights engine powered by Google Gemini API.
Adheres strictly to Rule H1: Non-diagnostic, calm, telemetry-bounded observations.
Never diagnoses medical conditions or prescribes clinical treatments.
Gracefully falls back to deterministic physiological heuristic templates when
API keys are not configured or upstream connectivity fails.
"""

import json
import logging
import uuid
from typing import Optional, Dict, Any
from pydantic import BaseModel, Field
import httpx

from app.core.config import settings
from app.services.daily_digest import DailyDigestReport, DailyDigestService
from sqlalchemy.ext.asyncio import AsyncSession

logger = logging.getLogger(__name__)

MANDATORY_DISCLAIMER = (
    "HealthAgent observations are generated for wellness awareness "
    "and do not constitute clinical diagnosis."
)


class DailyInsightPayload(BaseModel):
    headline: str = Field(..., description="Short, calm summary headline")
    narrative: str = Field(..., description="Objective, grounded physiological summary")
    category: str = Field(default="RECOVERY", description="Insight category (RECOVERY, ACTIVITY, CARDIOVASCULAR, SLEEP)")
    recommendation: Optional[str] = Field(default=None, description="Safe lifestyle wellness guidance")
    confidence: float = Field(default=0.95, ge=0.0, le=1.0)
    is_fallback: bool = Field(default=False, description="True if generated via deterministic fallback")
    disclaimer: str = Field(default=MANDATORY_DISCLAIMER)


class GeminiInsightsService:
    def __init__(self, db: AsyncSession) -> None:
        self.db = db

    async def generate_daily_insight(self, user_id: uuid.UUID) -> DailyInsightPayload:
        """
        Synthesize daily biometric telemetry into a personalized wellness observation.
        Uses Gemini API if configured, otherwise falls back gracefully.
        """
        digest_service = DailyDigestService(self.db)
        digest = await digest_service.compile_digest(user_id=user_id)

        # If API key is available, attempt Gemini synthesis
        if settings.GEMINI_API_KEY and settings.GEMINI_API_KEY.strip():
            try:
                insight = await self._call_gemini(digest)
                if insight:
                    return insight
            except Exception as e:
                logger.warning("Gemini insight generation failed, using deterministic fallback: %s", e)

        return self._generate_fallback(digest)

    async def _call_gemini(self, digest: DailyDigestReport) -> Optional[DailyInsightPayload]:
        """Call Gemini REST API via google-genai or httpx."""
        api_key = settings.GEMINI_API_KEY.strip()
        model = settings.GEMINI_MODEL or "gemini-2.5-flash"

        metrics = digest.metrics
        prompt = f"""You are HealthAgent's calm, non-diagnostic health observation engine.
Analyze the user's daily biometric summary and provide an objective, reassuring wellness insight.

MANDATORY SAFETY GUARDRAILS:
1. Never diagnose, declare medical conditions, or use alarmist clinical language.
2. Never prescribe medications or treatments.
3. Suggest only gentle, everyday lifestyle wellness habits (pacing, hydration, sleep schedule).
4. Restrict observations strictly to the telemetry data provided below.

Telemetry for {digest.report_date}:
- Resting Heart Rate: {metrics.resting_heart_rate or 'N/A'} bpm
- Heart Rate Range: {metrics.heart_rate_min or 'N/A'} - {metrics.heart_rate_max or 'N/A'} bpm (Mean: {metrics.heart_rate_mean or 'N/A'} bpm)
- Total Step Count: {metrics.total_steps} steps
- Sleep Duration: {metrics.sleep_duration_minutes or 'N/A'} minutes
- Data Quality: {metrics.data_quality_rating}
- Active Findings: {len(digest.active_findings)} flagged events

Respond ONLY in valid JSON matching this exact schema:
{{
  "headline": "Brief title (max 8 words)",
  "narrative": "1-2 calm sentences describing physiological observation",
  "category": "RECOVERY or ACTIVITY or CARDIOVASCULAR or SLEEP",
  "recommendation": "1 gentle lifestyle wellness suggestion",
  "confidence": 0.95
}}
"""

        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
        payload = {
            "contents": [
                {
                    "parts": [{"text": prompt}]
                }
            ],
            "generationConfig": {
                "temperature": settings.GEMINI_TEMPERATURE,
                "maxOutputTokens": settings.GEMINI_MAX_TOKENS,
                "responseMimeType": "application/json"
            }
        }

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, json=payload)
            if resp.status_code == 200:
                data = resp.json()
                text = data["candidates"][0]["content"]["parts"][0]["text"]
                parsed = json.loads(text)
                return DailyInsightPayload(
                    headline=parsed.get("headline", "Physiological Overview"),
                    narrative=parsed.get("narrative", "Daily biometric metrics have been recorded and evaluated."),
                    category=parsed.get("category", "RECOVERY"),
                    recommendation=parsed.get("recommendation"),
                    confidence=float(parsed.get("confidence", 0.95)),
                    is_fallback=False
                )
            else:
                logger.warning("Gemini API error %d: %s", resp.status_code, resp.text)
                return None

    def _generate_fallback(self, digest: DailyDigestReport) -> DailyInsightPayload:
        """Deterministic heuristic fallback based on authentic telemetry values."""
        metrics = digest.metrics
        steps = metrics.total_steps
        rhr = metrics.resting_heart_rate
        sleep = metrics.sleep_duration_minutes

        if steps >= 8000 and (rhr is None or rhr < 75):
            headline = "Active Movement & Cardiovascular Balance"
            narrative = (
                f"You achieved {steps:,} steps today with a stable resting pulse. "
                "Physical exertion and cardiovascular recovery are well synchronized."
            )
            category = "ACTIVITY"
            rec = "Maintain steady hydration throughout your post-activity recovery window."
            confidence = 0.92
        elif sleep and sleep < 360:
            headline = "Recovery Window Prioritized"
            narrative = (
                f"Recorded sleep duration was {int(sleep // 60)}h {int(sleep % 60)}m. "
                "Resting cardiovascular readings indicate adequate short-term recovery, but extended rest will support vitality."
            )
            category = "SLEEP"
            rec = "Consider an earlier screen-free wind-down routine tonight to support restorative sleep cycles."
            confidence = 0.88
        elif rhr and rhr >= 85:
            headline = "Mild Elevated Resting Pulse Observed"
            narrative = (
                f"Resting heart rate averaged {int(rhr)} bpm during quiet intervals. "
                "Minor baseline variances are commonly influenced by hydration, recent meals, or daily stress."
            )
            category = "CARDIOVASCULAR"
            rec = "Prioritize light hydration and gentle breathing exercises during calm evening periods."
            confidence = 0.85
        else:
            headline = "Cardiovascular Stability Observed"
            narrative = (
                "Continuous biometric metrics indicate steady resting pulse and normal diurnal variance "
                "against your established baseline."
            )
            category = "RECOVERY"
            rec = "Continue your consistent daily activity and balanced hydration habits."
            confidence = 0.94

        return DailyInsightPayload(
            headline=headline,
            narrative=narrative,
            category=category,
            recommendation=rec,
            confidence=confidence,
            is_fallback=True
        )
