"""Weekly planner graph — generate, verify, repair.

    START → load_context → assess → draft → validate ─(ok)──────────────► END
                                      ▲        ├─(violations, attempts left)─┘ (back to draft)
                                      │        └─(still invalid / no LLM)──► fallback → END
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Literal, TypedDict

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.graph import END, START, StateGraph

from ..data import DataProvider, UserContext
from ..metrics import Readiness, compute_readiness, last_7_days
from ..prompts import planner_prompts
from ..rules import fallback_plan, weekday_of
from ..safety import validate_plan, week_order, weekly_minutes_cap
from ..schemas import WeeklyPlan
from ..tracing import run_config

log = logging.getLogger(__name__)
MAX_ATTEMPTS = 3


class PlannerState(TypedDict, total=False):
    user_id: str
    ctx: UserContext
    readiness: Readiness
    plan: WeeklyPlan | None
    violations: list[str]
    attempts: int
    source: Literal["llm", "rules"]


@dataclass(frozen=True)
class PlanResult:
    plan: WeeklyPlan
    source: str
    attempts: int
    readiness: Readiness


def build_planner_graph(provider: DataProvider, llm: BaseChatModel | None):
    structured = llm.with_structured_output(WeeklyPlan) if llm is not None else None

    async def load_context(state: PlannerState) -> dict[str, Any]:
        return {"ctx": await provider.get_context(state["user_id"]), "attempts": 0, "violations": []}

    def assess(state: PlannerState) -> dict[str, Any]:
        return {"readiness": compute_readiness(state["ctx"])}

    async def draft(state: PlannerState) -> dict[str, Any]:
        ctx, r = state["ctx"], state["readiness"]
        order = week_order(weekday_of(ctx))
        cap = weekly_minutes_cap(last_7_days(ctx).workout_minutes)
        system, user = planner_prompts(ctx, r, order=order, cap=cap, violations=state.get("violations", []))
        attempts = state.get("attempts", 0) + 1
        try:
            plan = await structured.ainvoke([SystemMessage(system), HumanMessage(user)])
        except Exception:  # malformed output, provider error, timeout
            log.exception("planner draft failed (attempt %s)", attempts)
            return {"plan": None, "attempts": attempts, "violations": ["Model output could not be parsed."]}
        return {"plan": plan, "attempts": attempts, "source": "llm"}

    def validate(state: PlannerState) -> dict[str, Any]:
        plan = state.get("plan")
        if plan is None:
            return {}
        ctx = state["ctx"]
        return {"violations": validate_plan(
            plan, band=state["readiness"].band, last_week_minutes=last_7_days(ctx).workout_minutes,
            start=weekday_of(ctx), schedule=ctx.schedule, start_date=ctx.today,
        )}

    def fallback(state: PlannerState) -> dict[str, Any]:
        return {"plan": fallback_plan(state["ctx"], state["readiness"]), "source": "rules", "violations": []}

    def after_assess(_: PlannerState) -> Literal["draft", "fallback"]:
        return "draft" if structured is not None else "fallback"

    def after_validate(state: PlannerState) -> Literal["draft", "fallback", "__end__"]:
        if state.get("plan") is not None and not state.get("violations"):
            return END
        return "draft" if state.get("attempts", 0) < MAX_ATTEMPTS else "fallback"

    g = StateGraph(PlannerState)
    g.add_node("load_context", load_context)
    g.add_node("assess", assess)
    g.add_node("draft", draft)
    g.add_node("validate", validate)
    g.add_node("fallback", fallback)
    g.add_edge(START, "load_context")
    g.add_edge("load_context", "assess")
    g.add_conditional_edges("assess", after_assess)
    g.add_edge("draft", "validate")
    g.add_conditional_edges("validate", after_validate)
    g.add_edge("fallback", END)
    return g.compile(name="fitagent_planner")


async def run_planner(graph, user_id: str) -> PlanResult:
    state = await graph.ainvoke({"user_id": user_id}, config=run_config("weekly_plan", user_id))
    return PlanResult(plan=state["plan"], source=state["source"], attempts=state.get("attempts", 0),
                      readiness=state["readiness"])
