"""FitAgent coaching agents (LangGraph + LangChain, traced with LangSmith)."""

from .data import (
    ActivityDay,
    ChallengeStanding,
    DataProvider,
    Goals,
    InMemoryProvider,
    Profile,
    UserContext,
    WorkoutRecord,
)
from .env import load_env
from .graphs.coach import CoachReply, build_coach_graph, run_coach
from .graphs.planner import PlanResult, build_planner_graph, run_planner
from .llm import get_chat_model
from .metrics import Band, Readiness, compute_readiness, current_streak
from .schemas import PlannedSession, WeeklyPlan
from .tracing import configure_tracing

__all__ = [
    "ActivityDay",
    "Band",
    "ChallengeStanding",
    "CoachReply",
    "DataProvider",
    "Goals",
    "InMemoryProvider",
    "PlanResult",
    "PlannedSession",
    "Profile",
    "Readiness",
    "UserContext",
    "WeeklyPlan",
    "WorkoutRecord",
    "build_coach_graph",
    "build_planner_graph",
    "compute_readiness",
    "configure_tracing",
    "current_streak",
    "get_chat_model",
    "load_env",
    "run_coach",
    "run_planner",
]
