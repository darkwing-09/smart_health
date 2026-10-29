import pytest
from fitagent_agents import InMemoryProvider, build_coach_graph, build_planner_graph, run_coach, run_planner
from fitagent_agents.schemas import PlannedSession, WeeklyPlan
from langchain_core.messages import AIMessage, HumanMessage

from .conftest import StructuredFake, ToolCallingFake, make_ctx

pytestmark = pytest.mark.asyncio


@pytest.fixture
def provider():
    return InMemoryProvider({"u1": make_ctx(), "tired": make_ctx(sleep_today=240, rhr_today=70)})


async def test_coach_fallback_without_llm(provider):
    graph = build_coach_graph(provider, llm=None)
    reply = await run_coach(graph, "u1", [], "What should I do today?")
    assert not reply.used_llm and reply.safety is None
    assert "Readiness today" in reply.text and "4,200" in reply.text


async def test_coach_safety_short_circuits_before_llm(provider):
    llm = ToolCallingFake(messages=iter([]))  # would raise if called
    graph = build_coach_graph(provider, llm)
    reply = await run_coach(graph, "u1", [], "I had chest pain during my run")
    assert reply.safety == "emergency"
    assert "doctor" in reply.text and not reply.used_llm


async def test_coach_uses_tools_then_answers(provider):
    llm = ToolCallingFake(messages=iter([
        AIMessage(content="", tool_calls=[{"name": "get_workouts", "args": {"days": 7}, "id": "c1"},
                                          {"name": "get_readiness_and_suggestion", "args": {}, "id": "c2"}]),
        AIMessage(content="You trained 3 times this week. Try 20 min of HIIT today."),
    ]))
    graph = build_coach_graph(provider, llm)
    history = [HumanMessage("hi"), AIMessage("Hey! How can I help?")]
    reply = await run_coach(graph, "u1", history, "How am I doing?")
    assert reply.used_llm
    assert reply.tools_used == ["get_readiness_and_suggestion", "get_workouts"]
    assert reply.text.startswith("You trained 3 times")


async def test_coach_postcheck_blocks_diagnosis(provider):
    llm = ToolCallingFake(messages=iter([AIMessage(content="Based on that, you probably have tachycardia.")]))
    reply = await run_coach(build_coach_graph(provider, llm), "u1", [], "my heart rate is 75 at rest")
    assert "can't diagnose" in reply.text


def _valid_plan() -> WeeklyPlan:
    spec = [("WED", "STRENGTH", 30, "moderate"), ("THU", "REST", 0, "rest"), ("FRI", "HIIT", 20, "hard"),
            ("SAT", "WALK", 40, "easy"), ("SUN", "RUN", 30, "hard"), ("MON", "YOGA", 20, "easy"),
            ("TUE", "REST", 0, "rest")]
    return WeeklyPlan(summary="Balanced week.", sessions=[
        PlannedSession(day=d, type=t, minutes=m, intensity=i, focus="do it") for d, t, m, i in spec])


def _invalid_plan() -> WeeklyPlan:
    p = _valid_plan()
    sessions = [s.model_copy(update={"type": "RUN", "minutes": 30, "intensity": "hard"}) if s.type == "REST" else s
                for s in p.sessions]
    return WeeklyPlan(summary="Too much.", sessions=sessions)


async def test_planner_without_llm_uses_rules(provider):
    result = await run_planner(build_planner_graph(provider, None), "tired")
    assert result.source == "rules"
    assert result.plan.sessions[0].intensity in ("rest", "easy")


async def test_planner_repairs_invalid_llm_draft(provider):
    llm = StructuredFake(messages=iter([]), outputs=[_invalid_plan(), _valid_plan()])
    result = await run_planner(build_planner_graph(provider, llm), "u1")
    assert result.source == "llm" and result.attempts == 2
    assert result.plan.summary == "Balanced week."


async def test_planner_falls_back_after_repeated_failures(provider):
    llm = StructuredFake(messages=iter([]), outputs=[_invalid_plan(), ValueError("bad json"), _invalid_plan()])
    result = await run_planner(build_planner_graph(provider, llm), "u1")
    assert result.source == "rules" and result.attempts == 3
