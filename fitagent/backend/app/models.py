from __future__ import annotations

import uuid
from datetime import UTC, date, datetime

from sqlalchemy import (
    JSON,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base


def _uuid() -> str:
    return str(uuid.uuid4())


def utcnow() -> datetime:
    return datetime.now(UTC)


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    email: Mapped[str] = mapped_column(String(320), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    display_name: Mapped[str] = mapped_column(String(60))
    # Bumped on logout-all / password change to invalidate every issued token.
    token_version: Mapped[int] = mapped_column(Integer, default=0)
    timezone: Mapped[str] = mapped_column(String(64), default="UTC")
    age: Mapped[int | None] = mapped_column(Integer)
    height_cm: Mapped[int | None] = mapped_column(Integer)
    weight_kg: Mapped[float | None] = mapped_column(Float)
    goal_steps: Mapped[int] = mapped_column(Integer, default=8000)
    goal_active_kcal: Mapped[int] = mapped_column(Integer, default=400)
    goal_workout_minutes: Mapped[int] = mapped_column(Integer, default=30)
    goal_weekly_workouts: Mapped[int] = mapped_column(Integer, default=4)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    days: Mapped[list[DailyActivity]] = relationship(back_populates="user", cascade="all, delete-orphan",
                                                     passive_deletes=True)


class DailyActivity(Base):
    __tablename__ = "daily_activity"

    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    day: Mapped[date] = mapped_column(Date, primary_key=True)
    steps: Mapped[int] = mapped_column(Integer, default=0)
    distance_m: Mapped[float] = mapped_column(Float, default=0)
    active_kcal: Mapped[int] = mapped_column(Integer, default=0)
    workout_minutes: Mapped[int] = mapped_column(Integer, default=0)
    resting_hr: Mapped[int | None] = mapped_column(Integer)
    avg_hr: Mapped[int | None] = mapped_column(Integer)
    sleep_minutes: Mapped[int | None] = mapped_column(Integer)
    hr_min: Mapped[int | None] = mapped_column(Integer)
    hr_max: Mapped[int | None] = mapped_column(Integer)
    floors: Mapped[int | None] = mapped_column(Integer)
    vo2max: Mapped[float | None] = mapped_column(Float)
    sleep_deep_min: Mapped[int | None] = mapped_column(Integer)
    sleep_rem_min: Mapped[int | None] = mapped_column(Integer)
    sleep_light_min: Mapped[int | None] = mapped_column(Integer)
    sleep_awake_min: Mapped[int | None] = mapped_column(Integer)
    bed_time: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    wake_time: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    hrv_ms: Mapped[float | None] = mapped_column(Float)
    spo2_avg: Mapped[float | None] = mapped_column(Float)
    spo2_min: Mapped[float | None] = mapped_column(Float)
    resp_rate: Mapped[float | None] = mapped_column(Float)
    skin_temp_delta: Mapped[float | None] = mapped_column(Float)
    steps_source: Mapped[str | None] = mapped_column(String(120))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)

    user: Mapped[User] = relationship(back_populates="days")


class Workout(Base):
    __tablename__ = "workouts"
    __table_args__ = (
        UniqueConstraint("user_id", "client_id", name="uq_workout_client"),
        Index("ix_workouts_user_start", "user_id", "start"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    client_id: Mapped[str] = mapped_column(String(80))
    type: Mapped[str] = mapped_column(String(20))
    title: Mapped[str | None] = mapped_column(String(120))
    start: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    end: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    active_seconds: Mapped[int] = mapped_column(Integer)
    kcal: Mapped[float] = mapped_column(Float, default=0)
    source: Mapped[str] = mapped_column(String(20), default="APP")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Challenge(Base):
    __tablename__ = "challenges"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    code: Mapped[str] = mapped_column(String(8), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(60))
    metric: Mapped[str] = mapped_column(String(20))  # steps | workout_minutes | active_days
    start_date: Mapped[date] = mapped_column(Date)
    end_date: Mapped[date] = mapped_column(Date)
    owner_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    members: Mapped[list[ChallengeMember]] = relationship(back_populates="challenge", cascade="all, delete-orphan",
                                                          passive_deletes=True)


class ChallengeMember(Base):
    __tablename__ = "challenge_members"

    challenge_id: Mapped[str] = mapped_column(ForeignKey("challenges.id", ondelete="CASCADE"), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    joined_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    challenge: Mapped[Challenge] = relationship(back_populates="members")
    user: Mapped[User] = relationship()


class CoachMessage(Base):
    __tablename__ = "coach_messages"
    __table_args__ = (Index("ix_coach_messages_user_created", "user_id", "created_at"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    role: Mapped[str] = mapped_column(String(10))  # user | assistant
    content: Mapped[str] = mapped_column(Text)
    safety: Mapped[str | None] = mapped_column(String(30))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class WeeklyPlanRecord(Base):
    __tablename__ = "weekly_plans"
    __table_args__ = (Index("ix_weekly_plans_user_created", "user_id", "created_at"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    starts_on: Mapped[date] = mapped_column(Date)
    plan: Mapped[dict] = mapped_column(JSON)
    source: Mapped[str] = mapped_column(String(10))
    readiness_score: Mapped[int] = mapped_column(Integer)
    readiness_band: Mapped[str] = mapped_column(String(10))
    readiness_detail: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
