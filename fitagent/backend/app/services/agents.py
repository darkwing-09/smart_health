"""Holds the compiled agent graphs for the lifetime of the process."""

from __future__ import annotations

import logging
from dataclasses import dataclass

from fitagent_agents import (
    CoachReply,
    PlanResult,
    build_coach_graph,
    build_planner_graph,
    configure_tracing,
    get_chat_model,
    run_coach,
    run_planner,
)
from langchain_core.messages import AIMessage, AnyMessage, HumanMessage

from ..db import SessionLocal
from .provider import DbDataProvider

log = logging.getLogger(__name__)


@dataclass
class AgentRuntime:
    coach: object
    planner: object
    coach_rules: object
    planner_rules: object
    llm_enabled: bool
    tracing_enabled: bool

    @classmethod
    def create(cls) -> AgentRuntime:
        tracing = configure_tracing()
        provider = DbDataProvider(SessionLocal)
        llm = get_chat_model()
        rules_coach, rules_planner = build_coach_graph(provider, None), build_planner_graph(provider, None)
        return cls(
            coach=build_coach_graph(provider, llm) if llm else rules_coach,
            planner=build_planner_graph(provider, llm) if llm else rules_planner,
            coach_rules=rules_coach,
            planner_rules=rules_planner,
            llm_enabled=llm is not None,
            tracing_enabled=tracing,
        )

    async def chat(self, user_id: str, history: list[tuple[str, str]], message: str) -> CoachReply:
        msgs: list[AnyMessage] = [HumanMessage(c) if r == "user" else AIMessage(c) for r, c in history]
        try:
            return await run_coach(self.coach, user_id, msgs, message)
        except Exception:
            # Provider outage, rate limit, timeout: degrade to the rule-based coach instead of failing.
            log.exception("coach LLM run failed; serving rule-based reply")
            return await run_coach(self.coach_rules, user_id, msgs, message)

    async def plan(self, user_id: str) -> PlanResult:
        try:
            return await run_planner(self.planner, user_id)
        except Exception:
            log.exception("planner LLM run failed; serving rule-based plan")
            return await run_planner(self.planner_rules, user_id)


_runtime: AgentRuntime | None = None


def get_runtime() -> AgentRuntime:
    global _runtime
    if _runtime is None:
        _runtime = AgentRuntime.create()
    return _runtime
