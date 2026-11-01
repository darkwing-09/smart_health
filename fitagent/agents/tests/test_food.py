"""Food photo estimates: guard rails, sanity bounds and graceful fallback."""

import pytest
from fitagent_agents.food import FoodEstimate, FoodItem, estimate_food, sanitise
from fitagent_agents.safety import check_message

from .conftest import StructuredFake

pytestmark = pytest.mark.asyncio
JPEG = b"\xff\xd8\xff\xe0fake"


def item(name="Dal", kcal=150, protein=9.0, conf="high"):
    return FoodItem(name=name, portion="1 katori", kcal=kcal, protein_g=protein, carbs_g=20, fat_g=5, confidence=conf)


async def test_estimate_with_model():
    llm = StructuredFake(messages=iter([]), outputs=[FoodEstimate(items=[item(), item("Chapati", 200, 6)], note="Nice protein from the dal.")])
    r = await estimate_food(llm, JPEG, "image/jpeg", "mess lunch")
    assert r.ai_powered and r.estimate.total_kcal == 350


async def test_no_model_means_manual_entry():
    r = await estimate_food(None, JPEG, "image/jpeg")
    assert r.estimate is None and r.safety is None and not r.ai_powered


async def test_model_failure_falls_back_to_manual():
    llm = StructuredFake(messages=iter([]), outputs=[ValueError("bad json")])
    r = await estimate_food(llm, JPEG, "image/jpeg")
    assert r.estimate is None


@pytest.mark.parametrize("note", ["I want to skip meals to lose weight", "how to lose 5 kg in a week", "eating under 800 calories a day"])
async def test_disordered_eating_notes_get_support_not_numbers(note):
    llm = StructuredFake(messages=iter([]), outputs=[FoodEstimate(items=[item()])])
    r = await estimate_food(llm, JPEG, "image/jpeg", note)
    assert r.estimate is None and r.safety and r.safety.category == "eating_disorder"
    assert check_message(note) is not None


async def test_sanity_caps_implausible_totals_and_drops_empty_items():
    est = FoodEstimate(items=[item("Biryani", 1500, 30), item("Biryani", 1500, 30), item("Raita", 1200, 10), item("", 100)])
    s = sanitise(est)
    assert s.total_kcal <= 3000 and len(s.items) == 3
    assert all(i.confidence == "low" for i in s.items)
