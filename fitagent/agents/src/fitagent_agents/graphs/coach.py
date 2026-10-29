"""Coach chat graph.

    START → guard ─(red flag)──────────────────────────────► END
              └──► load_context ─(no LLM)─► fallback ──────► END
                         └──► agent ⇄ tools
                                └──► postcheck ────────────► END
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Annotated, Any, Literal, TypedDict

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, AnyMessage, HumanMessage, SystemMessage, ToolMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.prebuilt import ToolNode

from ..data import DataProvider, UserContext
from ..metrics import compute_readiness
from ..prompts import coach_system_prompt
from ..rules import fallback_reply
from ..safety import DISCLAIMER, check_message
from ..tools import COACH_TOOLS
from ..tracing import run_config

_DIAGNOSIS = re.compile(
    r"\byou (likely |probably |may |might |could )?(have|are suffering from) (a |an )?"
    r"(\w+ )?(disease|disorder|syndrome|condition|infection|arrhythmia|tachycardia|diabetes|asthma)\b",
    re.IGNORECASE,
)


class CoachState(TypedDict, total=False):
    messages: Annotated[list[AnyMessage], add_messages]
    user_id: str
    ctx: UserContext
    safety: str | None


@dataclass(frozen=True)
class CoachReply:
    text: str
    safety: str | None
    tools_used: list[str]
    used_llm: bool


def build_coach_graph(provider: DataProvider, llm: BaseChatModel | None):
    model = llm.bind_tools(COACH_TOOLS) if llm is not None else None

    def guard(state: CoachState) -> dict[str, Any]:
        last = next((m for m in reversed(state["messages"]) if isinstance(m, HumanMessage)), None)
        flag = check_message(str(last.content)) if last else None
        if flag:
            return {"safety": flag.category, "messages": [AIMessage(flag.response, name="safety")]}
        return {"safety": None}

    async def load_context(state: CoachState) -> dict[str, Any]:
        return {"ctx": await provider.get_context(state["user_id"])}

    async def agent(state: CoachState) -> dict[str, Any]:
        ctx = state["ctx"]
        system = SystemMessage(coach_system_prompt(ctx, compute_readiness(ctx)))
        response = await model.ainvoke([system, *state["messages"]])
        return {"messages": [response]}

    def fallback(state: CoachState) -> dict[str, Any]:
        ctx = state["ctx"]
        last = next(m for m in reversed(state["messages"]) if isinstance(m, HumanMessage))
        reply = fallback_reply(ctx, compute_readiness(ctx), str(last.content))
        return {"messages": [AIMessage(reply, name="fallback")]}

    def postcheck(state: CoachState) -> dict[str, Any]:
        last = state["messages"][-1]
        text = _text(last)
        if _DIAGNOSIS.search(text):
            return {"messages": [AIMessage(
                "I can't diagnose health conditions. If something feels off, please check in with a doctor. "
                f"{DISCLAIMER}", id=last.id)]}
        return {}

    def after_guard(state: CoachState) -> Literal["load_context", "__end__"]:
        return END if state.get("safety") else "load_context"

    def after_context(_: CoachState) -> Literal["agent", "fallback"]:
        return "agent" if model is not None else "fallback"

    def after_agent(state: CoachState) -> Literal["tools", "postcheck"]:
        last = state["messages"][-1]
        return "tools" if isinstance(last, AIMessage) and last.tool_calls else "postcheck"

    g = StateGraph(CoachState)
    g.add_node("guard", guard)
    g.add_node("load_context", load_context)
    g.add_node("agent", agent)
    g.add_node("tools", ToolNode(COACH_TOOLS))
    g.add_node("fallback", fallback)
    g.add_node("postcheck", postcheck)
    g.add_edge(START, "guard")
    g.add_conditional_edges("guard", after_guard)
    g.add_conditional_edges("load_context", after_context)
    g.add_conditional_edges("agent", after_agent)
    g.add_edge("tools", "agent")
    g.add_edge("fallback", END)
    g.add_edge("postcheck", END)
    return g.compile(name="fitagent_coach")


def _text(message: AnyMessage) -> str:
    content = message.content
    if isinstance(content, str):
        return content
    return "".join(part.get("text", "") for part in content if isinstance(part, dict))


async def run_coach(graph, user_id: str, history: list[AnyMessage], message: str) -> CoachReply:
    """Runs one chat turn. `history` is prior user/assistant messages (oldest first)."""
    result = await graph.ainvoke(
        {"messages": [*history, HumanMessage(message)], "user_id": user_id},
        config=run_config("coach_chat", user_id),
    )
    new = result["messages"][len(history) + 1:]
    tools_used = sorted({m.name for m in new if isinstance(m, ToolMessage) and m.name})
    used_llm = result["messages"][-1].name not in ("fallback", "safety")
    return CoachReply(text=_text(result["messages"][-1]).strip(), safety=result.get("safety"),
                      tools_used=tools_used, used_llm=used_llm)
