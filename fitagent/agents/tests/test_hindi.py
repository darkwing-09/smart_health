"""Hindi: safety routing, support responses and the offline coach reply."""

from dataclasses import replace

import pytest
from fitagent_agents.data import InMemoryProvider
from fitagent_agents.graphs.coach import build_coach_graph, run_coach
from fitagent_agents.safety import check_message, is_devanagari

from .conftest import make_ctx

pytestmark = pytest.mark.asyncio


def test_devanagari_detection():
    assert is_devanagari("आज मुझे क्या करना चाहिए?")
    assert not is_devanagari("What should I do today?")
    assert is_devanagari("मेरे steps कितने हैं?")


@pytest.mark.parametrize("text,category", [
    ("मुझे सीने में दर्द हो रहा है", "emergency"),
    ("मैं आत्महत्या के बारे में सोच रहा हूँ", "self_harm"),
    ("घुटने में सूजन है", "injury"),
    ("मैं भूखा रहकर वज़न कम करना चाहता हूँ", "eating_disorder"),
])
def test_hindi_red_flags(text, category):
    flag = check_message(text)
    assert flag and flag.category == category and is_devanagari(flag.for_language(True))


async def test_hindi_message_gets_hindi_offline_reply_and_safety():
    graph = build_coach_graph(InMemoryProvider({"u": make_ctx()}), llm=None)
    reply = await run_coach(graph, "u", [], "आज मुझे क्या करना चाहिए?")
    assert is_devanagari(reply.text) and "रेडीनेस" in reply.text
    safety = await run_coach(graph, "u", [], "मुझे सीने में दर्द है")
    assert safety.safety == "emergency" and "112" in safety.text and is_devanagari(safety.text)


async def test_language_preference_without_devanagari_message():
    ctx = make_ctx()
    ctx = replace(ctx, profile=replace(ctx.profile, language="hi"))
    graph = build_coach_graph(InMemoryProvider({"u": ctx}), llm=None)
    assert is_devanagari((await run_coach(graph, "u", [], "plan?")).text)
