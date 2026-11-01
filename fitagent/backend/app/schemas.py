from __future__ import annotations

from datetime import date, datetime
from typing import Literal
from zoneinfo import available_timezones

from fitagent_agents.schemas import WeeklyPlan
from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator, model_validator

WorkoutTypeName = Literal["WALK", "RUN", "CYCLE", "STRENGTH", "HIIT", "YOGA", "SWIM", "SPORTS", "DANCE", "OTHER"]
Metric = Literal["steps", "workout_minutes", "active_days"]

_TIMEZONES = available_timezones()


def _check_tz(tz: str | None) -> str | None:
    if tz is not None and tz not in _TIMEZONES:
        raise ValueError("unknown IANA timezone")
    return tz


# ── Auth ────────────────────────────────────────────────────────────────────

class RegisterIn(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)
    display_name: str = Field(min_length=1, max_length=60)
    timezone: str = "UTC"

    _tz = field_validator("timezone")(_check_tz)


class LoginIn(BaseModel):
    email: EmailStr
    password: str = Field(max_length=128)


class RefreshIn(BaseModel):
    refresh_token: str


class TokenOut(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int


# ── Profile ─────────────────────────────────────────────────────────────────

class GoalsModel(BaseModel):
    daily_steps: int = Field(8000, ge=1000, le=50000)
    daily_active_kcal: int = Field(400, ge=50, le=3000)
    daily_workout_minutes: int = Field(30, ge=5, le=300)
    weekly_workouts: int = Field(4, ge=1, le=14)


class ProfileOut(BaseModel):
    id: str
    email: str
    display_name: str
    timezone: str
    age: int | None
    height_cm: int | None
    weight_kg: float | None
    goals: GoalsModel
    college: str | None = None
    hostel: str | None = None
    batch: str | None = None
    show_on_campus: bool = True


HHMM = r"^([01]\d|2[0-3]):[0-5]\d$"


class FreeSlotModel(BaseModel):
    day: Literal["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
    start: str = Field(pattern=HHMM)
    end: str = Field(pattern=HHMM)

    @model_validator(mode="after")
    def _order(self) -> FreeSlotModel:
        if self.end <= self.start:
            raise ValueError("end must be after start")
        return self


class ScheduleModel(BaseModel):
    """Exam mode timetable. Deliberately has no field for course or class names."""

    free_slots: list[FreeSlotModel] = Field(default_factory=list, max_length=70)
    exam_dates: list[date] = Field(default_factory=list, max_length=40)
    bedtime: str | None = Field(None, pattern=HHMM)


class ProfileUpdate(BaseModel):
    display_name: str | None = Field(None, min_length=1, max_length=60)
    college: str | None = Field(None, max_length=80)
    hostel: str | None = Field(None, max_length=80)
    batch: str | None = Field(None, max_length=40)
    show_on_campus: bool | None = None
    timezone: str | None = None
    age: int | None = Field(None, ge=10, le=100)
    height_cm: int | None = Field(None, ge=100, le=250)
    weight_kg: float | None = Field(None, ge=25, le=300)
    goals: GoalsModel | None = None

    _tz = field_validator("timezone")(_check_tz)


# ── Sync ────────────────────────────────────────────────────────────────────

class DayIn(BaseModel):
    date: date
    steps: int = Field(0, ge=0, le=200_000)
    distance_m: float = Field(0, ge=0, le=300_000)
    active_kcal: int = Field(0, ge=0, le=15_000)
    workout_minutes: int = Field(0, ge=0, le=1440)
    resting_hr: int | None = Field(None, ge=25, le=220)
    avg_hr: int | None = Field(None, ge=25, le=230)
    sleep_minutes: int | None = Field(None, ge=0, le=1440)
    # Watch extras (Phase 2). Intraday HR, sleep segments and routes stay on the phone; only nightly/daily
    # summaries are accepted here.
    hr_min: int | None = Field(None, ge=25, le=230)
    hr_max: int | None = Field(None, ge=25, le=250)
    floors: int | None = Field(None, ge=0, le=1000)
    vo2max: float | None = Field(None, ge=10, le=95)
    sleep_deep_min: int | None = Field(None, ge=0, le=1440)
    sleep_rem_min: int | None = Field(None, ge=0, le=1440)
    sleep_light_min: int | None = Field(None, ge=0, le=1440)
    sleep_awake_min: int | None = Field(None, ge=0, le=1440)
    bed_time: datetime | None = None
    wake_time: datetime | None = None
    hrv_ms: float | None = Field(None, ge=1, le=300)
    spo2_avg: float | None = Field(None, ge=50, le=100)
    spo2_min: float | None = Field(None, ge=50, le=100)
    resp_rate: float | None = Field(None, ge=4, le=60)
    skin_temp_delta: float | None = Field(None, ge=-5, le=5)
    steps_source: str | None = Field(None, max_length=120)

    @field_validator("bed_time", "wake_time")
    @classmethod
    def _aware(cls, v: datetime | None) -> datetime | None:
        if v is not None and v.tzinfo is None:
            raise ValueError("bed_time/wake_time must include a timezone offset")
        return v


class WorkoutIn(BaseModel):
    client_id: str = Field(min_length=1, max_length=80)
    type: WorkoutTypeName
    title: str | None = Field(None, max_length=120)
    start: datetime
    end: datetime
    active_seconds: int = Field(ge=0, le=86_400)
    kcal: float = Field(0, ge=0, le=10_000)
    source: Literal["APP", "HEALTH_CONNECT"] = "APP"

    @model_validator(mode="after")
    def _order(self) -> WorkoutIn:
        if self.start.tzinfo is None or self.end.tzinfo is None:
            raise ValueError("start/end must include a timezone offset")
        if self.end < self.start:
            raise ValueError("end must be after start")
        return self


class SyncIn(BaseModel):
    timezone: str | None = None
    days: list[DayIn] = Field(default_factory=list, max_length=400)
    workouts: list[WorkoutIn] = Field(default_factory=list, max_length=500)
    deleted_workout_ids: list[str] = Field(default_factory=list, max_length=500)

    _tz = field_validator("timezone")(_check_tz)


class SyncOut(BaseModel):
    days_upserted: int
    workouts_upserted: int
    workouts_deleted: int
    server_time: datetime


class SyncStatusOut(BaseModel):
    """What the server holds for this user — lets the app show "cloud backup up to date"."""

    latest_day: date | None
    days_stored: int
    workouts_stored: int
    last_synced_at: datetime | None


class DayOut(DayIn):
    model_config = ConfigDict(from_attributes=True)


class WorkoutOut(WorkoutIn):
    model_config = ConfigDict(from_attributes=True)


# ── Challenges ──────────────────────────────────────────────────────────────

class ChallengeCreate(BaseModel):
    name: str = Field(min_length=3, max_length=60)
    metric: Metric = "steps"
    start_date: date | None = None
    days: int = Field(7, ge=1, le=90)


class JoinIn(BaseModel):
    code: str = Field(min_length=4, max_length=8)


Emoji = Literal["👏", "🔥", "💪"]


class LeaderboardRow(BaseModel):
    rank: int
    user_id: str
    display_name: str
    value: float
    is_me: bool
    kudos: dict[str, int] = Field(default_factory=dict, description="Today's reactions received, by emoji")
    my_kudos: list[str] = Field(default_factory=list, description="Emoji I sent this member today")


class KudosIn(BaseModel):
    to_user_id: str = Field(min_length=1, max_length=36)
    emoji: Emoji


class CampusRow(BaseModel):
    rank: int
    name: str
    members: int
    value: float = Field(description="Goal days per member over the period")
    is_mine: bool


class CampusOut(BaseModel):
    scope: Literal["college", "hostel", "batch"]
    days: int
    my_group: str | None
    rows: list[CampusRow]
    min_group_size: int


class ChallengeOut(BaseModel):
    id: str
    code: str
    name: str
    metric: Metric
    start_date: date
    end_date: date
    is_owner: bool
    status: Literal["upcoming", "active", "finished"]
    participants: int
    my_rank: int | None
    leaderboard: list[LeaderboardRow]


# ── Coach ───────────────────────────────────────────────────────────────────

class ChatIn(BaseModel):
    message: str = Field(min_length=1, max_length=1000)


class ChatOut(BaseModel):
    reply: str
    safety: str | None
    tools_used: list[str]
    ai_powered: bool


class MessageOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    role: Literal["user", "assistant"]
    content: str
    safety: str | None
    created_at: datetime


class ReadinessOut(BaseModel):
    score: int
    band: Literal["PUSH", "STEADY", "RECOVER"]
    components: dict[str, int]
    reasons: list[str]


class PlanOut(BaseModel):
    id: str
    starts_on: date
    created_at: datetime
    source: Literal["llm", "rules"]
    readiness: ReadinessOut
    plan: WeeklyPlan


# ── Food ────────────────────────────────────────────────────────────────────

class FoodPhotoIn(BaseModel):
    """A meal photo as base64 (JPEG/PNG/WebP, ≤ 4 MB). Used for one estimate and never stored."""

    image_base64: str = Field(min_length=100, max_length=5_600_000)
    mime_type: Literal["image/jpeg", "image/png", "image/webp"] = "image/jpeg"
    note: str = Field("", max_length=200)


class FoodItemOut(BaseModel):
    name: str
    portion: str
    kcal: int
    protein_g: float
    carbs_g: float
    fat_g: float
    confidence: Literal["low", "medium", "high"]


class FoodEstimateOut(BaseModel):
    items: list[FoodItemOut]
    note: str
    total_kcal: int
    ai_powered: bool
    safety: str | None = None
    safety_message: str | None = None


# ── Rewards & admin ─────────────────────────────────────────────────────────

class WeekPoints(BaseModel):
    week_start: date
    points: int


class PerkOut(BaseModel):
    id: str
    title: str
    description: str
    partner: str
    cost: int
    stock: int
    active: bool
    institution: str | None


class RedemptionOut(BaseModel):
    code: str
    perk_title: str
    points: int
    created_at: datetime


class RewardsOut(BaseModel):
    balance: int
    earned_total: int
    weeks: list[WeekPoints]
    recent: list[dict]
    perks: list[PerkOut]
    redemptions: list[RedemptionOut]


class RedeemIn(BaseModel):
    perk_id: str = Field(min_length=1, max_length=36)


class PerkIn(BaseModel):
    title: str = Field(min_length=3, max_length=80)
    description: str = Field("", max_length=300)
    partner: str = Field(min_length=2, max_length=80)
    cost: int = Field(ge=10, le=100_000)
    stock: int = Field(ge=0, le=100_000)


class PerkPatch(BaseModel):
    active: bool | None = None
    stock: int | None = Field(None, ge=0, le=100_000)


class OfficialChallengeOut(BaseModel):
    id: str
    code: str
    name: str
    metric: Metric
    start_date: date
    end_date: date
    participants: int
    joined: bool


class AdminOverview(BaseModel):
    institution: str
    students: int
    min_group_size: int
    insufficient: bool
    days: list[date]
    active_students: list[int] = Field(description="Students with any synced data, per day")
    goal_rate: list[float] = Field(description="Share of active students who met a goal, per day")
    hostels: list[str]
    hostel_rates: list[list[float | None]] = Field(description="[hostel][day] goal-day rate; groups < min size omitted")
    steps_histogram: list[dict]
    funnel: dict[str, int]
