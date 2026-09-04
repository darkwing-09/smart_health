"""Integration Test Suite: Production Worker Daily Cadence & Report Generation.

Validates:
- cron_daily_baseline_recompute: Rolling 30-day baseline computation across active users
- Baseline establishment: 14+ day span and 140+ samples marks baseline as established
- cron_daily_report_pipeline: 24-hour vitals rollup, ReportLab PDF generation, Report model persistence
- Zero-data graceful degradation: Reports generated as 'degraded_trends_only' with valid PDF
- REST API download: Verification of /v1/reports/daily and /v1/reports/daily/{id}/download
"""

import os
import uuid
from datetime import datetime, timedelta, timezone
import pytest
from httpx import AsyncClient, ASGITransport
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool
from jose import jwt

from app.main import app
from app.core.config import settings
from app.db.session import get_db
from app.models.user import User
from app.models.device import WearableSource
from app.models.measurement import Measurement
from app.models.baseline import Baseline
from app.models.report import Report
from app.workers.worker import cron_daily_baseline_recompute, cron_daily_report_pipeline

test_engine = create_async_engine(settings.DATABASE_URL, poolclass=NullPool, future=True)
TestSessionFactory = async_sessionmaker(bind=test_engine, class_=AsyncSession, expire_on_commit=False)


async def override_get_db():
    async with TestSessionFactory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


@pytest.fixture(autouse=True)
def setup_db_override():
    app.dependency_overrides[get_db] = override_get_db
    yield
    app.dependency_overrides.pop(get_db, None)


@pytest.fixture
async def cadence_user():
    user_id = uuid.uuid4()
    source_id = uuid.uuid4()
    now_utc = datetime.now(timezone.utc)

    async with TestSessionFactory() as session:
        user = User(
            id=user_id,
            email=f"cadence_user_{user_id.hex[:6]}@example.com",
            hashed_password="test_hashed_password",
            full_name="Cadence Pilot Subject",
            timezone="Asia/Kolkata",
            is_active=True
        )
        source = WearableSource(
            id=source_id,
            user_id=user_id,
            adapter_type="health_connect",
            reliability_tier="official",
            auth_status="ACTIVE"
        )
        session.add_all([user, source])

        # Seed 20 days of data (10 samples per day = 200 samples)
        # Yesterday is the target report date
        measurements = []
        for day in range(20, 0, -1):
            day_base = now_utc - timedelta(days=day)
            for hour in range(10):
                ts = day_base.replace(hour=hour + 8, minute=0, second=0, microsecond=0)
                measurements.append(Measurement(
                    id=uuid.uuid4(),
                    user_id=user_id,
                    source_id=source_id,
                    metric_type="heart_rate",
                    value=70.0 + (day % 5) + (hour % 3),
                    unit="bpm",
                    recorded_at=ts,
                    confidence=0.95,
                    data_quality_flag="nominal"
                ))
            # Also add daily steps
            measurements.append(Measurement(
                id=uuid.uuid4(),
                user_id=user_id,
                source_id=source_id,
                metric_type="steps",
                value=8500.0,
                unit="count",
                recorded_at=day_base.replace(hour=20, minute=0),
                confidence=1.0,
                data_quality_flag="nominal"
            ))

        session.add_all(measurements)
        await session.commit()

    return user, source


@pytest.mark.asyncio
async def test_cron_daily_baseline_recompute_e2e(cadence_user):
    """Verifies that cron_daily_baseline_recompute computes established baselines in DB."""
    user, source = cadence_user

    # Execute daily baseline worker task with test session
    async with TestSessionFactory() as session:
        result = await cron_daily_baseline_recompute(ctx={}, session=session, user_ids=[user.id])
        assert result["status"] == "success"
        assert result["recomputed_count"] >= 1
        assert result["failed_count"] == 0

        # Verify baseline in DB
        stmt = (
            select(Baseline)
            .where(Baseline.user_id == user.id, Baseline.metric_type == "heart_rate")
            .order_by(Baseline.computed_at.desc())
        )
        baseline = (await session.scalars(stmt)).first()
        assert baseline is not None
        assert baseline.mean > 65.0
        assert baseline.stddev > 0.0
        assert baseline.established is True
        assert len(baseline.seasonality_profile) > 0


@pytest.mark.asyncio
async def test_cron_daily_report_pipeline_and_download_e2e(cadence_user):
    """
    Verifies that cron_daily_report_pipeline compiles 24-hour summary,
    generates vector PDF artifact, stores Report model, and serves via API.
    """
    user, source = cadence_user

    # 1. Execute report pipeline with test session
    async with TestSessionFactory() as session:
        result = await cron_daily_report_pipeline(ctx={}, session=session, user_ids=[user.id])
        assert result["status"] == "success"
        assert result["compiled_count"] >= 1
        assert result["failed_count"] == 0

        # 2. Verify Report record in DB
        stmt = (
            select(Report)
            .where(Report.user_id == user.id)
            .order_by(Report.report_date.desc())
        )
        report = (await session.scalars(stmt)).first()
        assert report is not None
        assert report.generation_status in ("complete", "degraded_trends_only")
        assert os.path.exists(report.pdf_storage_path)

        # 3. Verify PDF content
        with open(report.pdf_storage_path, "rb") as f:
            pdf_bytes = f.read()
        assert pdf_bytes.startswith(b"%PDF")
        assert len(pdf_bytes) > 1000

        report_id = report.id
        gen_status = report.generation_status

    # 4. Verify REST API endpoints
    token = jwt.encode(
        {"sub": str(user.id), "exp": datetime.now(timezone.utc) + timedelta(hours=1), "jti": str(uuid.uuid4())},
        settings.SECRET_KEY,
        algorithm="HS256"
    )

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # List reports
        list_res = await client.get(
            "/v1/reports/daily",
            headers={"Authorization": f"Bearer {token}"}
        )
        assert list_res.status_code == 200
        reports_list = list_res.json()["reports"]
        assert len(reports_list) >= 1
        rep_item = next(r for r in reports_list if str(r["report_id"]) == str(report_id))
        assert rep_item["generation_status"] == gen_status

        # Download report vector PDF
        download_res = await client.get(
            f"/v1/reports/daily/{report_id}/download",
            headers={"Authorization": f"Bearer {token}"}
        )
        assert download_res.status_code == 200
        assert download_res.headers["content-type"] == "application/pdf"
        assert download_res.content.startswith(b"%PDF")


@pytest.mark.asyncio
async def test_cron_daily_report_zero_data_degradation():
    """
    Proves that an active user with 0 measurements compiles safely as
    'degraded_trends_only' with a valid vector PDF and no exceptions.
    """
    zero_user_id = uuid.uuid4()
    async with TestSessionFactory() as session:
        zero_user = User(
            id=zero_user_id,
            email=f"zero_user_{zero_user_id.hex[:6]}@example.com",
            hashed_password="test_hashed_password",
            full_name="Zero Data Subject",
            timezone="UTC",
            is_active=True
        )
        session.add(zero_user)
        await session.commit()

        # Run report pipeline with isolated user and test session
        res = await cron_daily_report_pipeline(ctx={}, session=session, user_ids=[zero_user_id])
        assert res["status"] == "success"
        assert res["compiled_count"] == 1
        assert res["failed_count"] == 0

        stmt = select(Report).where(Report.user_id == zero_user_id)
        rep = (await session.scalars(stmt)).first()
        assert rep is not None
        assert rep.generation_status == "degraded_trends_only"
        assert os.path.exists(rep.pdf_storage_path)
        with open(rep.pdf_storage_path, "rb") as f:
            assert f.read().startswith(b"%PDF")

