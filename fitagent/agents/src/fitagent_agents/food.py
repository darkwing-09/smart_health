"""Mess / canteen food photo → estimated dishes, portions, calories and protein (feature S4).

The estimate is a starting point the student edits before saving. Guard rails:
- a note that suggests disordered eating gets the support response instead of numbers;
- per-item and per-photo sanity bounds (a misread photo can't log 9,000 kcal);
- the model is told to describe food, never to judge it or advise eating less.
Photos are sent to the model provider only; the backend never stores them.
"""

from __future__ import annotations

import base64
import logging
from typing import Literal

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage
from langsmith import traceable
from pydantic import BaseModel, Field

from .safety import SafetyFlag, check_message

log = logging.getLogger(__name__)

MAX_ITEM_KCAL = 1500
MAX_PHOTO_KCAL = 3000


class FoodItem(BaseModel):
    name: str = Field(max_length=60, description="Dish name as a student would say it, e.g. 'Dal tadka', 'Chapati'")
    portion: str = Field(max_length=40, description="Visible amount, e.g. '1 katori (150 g)', '2 pieces'")
    kcal: int = Field(ge=0, le=MAX_ITEM_KCAL)
    protein_g: float = Field(ge=0, le=150)
    carbs_g: float = Field(ge=0, le=300)
    fat_g: float = Field(ge=0, le=150)
    confidence: Literal["low", "medium", "high"]


class FoodEstimate(BaseModel):
    items: list[FoodItem] = Field(max_length=12)
    note: str = Field("", max_length=200, description="One neutral sentence, e.g. 'Good protein from the dal and curd.'")

    @property
    def total_kcal(self) -> int:
        return sum(i.kcal for i in self.items)


FOOD_SYSTEM = """You estimate food from a photo for a student's personal food log.
List each visible dish with a realistic portion and its calories and macros. Typical Indian hostel/mess foods:
chapati/roti (~100 kcal, 3 g protein each), rice (1 cup cooked ~200 kcal), dal (1 katori ~150 kcal, 9 g protein),
sambar (~130 kcal), rajma/chole (1 katori ~220 kcal, 10 g protein), paneer curry (~300 kcal, 14 g protein),
egg curry (2 eggs ~280 kcal, 13 g protein), chicken curry (~300 kcal, 25 g protein), idli (~40 kcal each),
dosa (~170 kcal), poha (1 plate ~250 kcal), upma (~250 kcal), aloo paratha (~280 kcal), curd (1 katori ~100 kcal,
6 g protein), samosa (~260 kcal), maggi (1 pack ~350 kcal), chai with sugar (~90 kcal).
Rules: estimate only what you can see; use 'low' confidence when unsure; never comment on weight, never suggest
eating less or skipping meals; the note is one neutral, encouraging sentence (e.g. about protein or vegetables).
If the photo has no food, return an empty items list."""


@traceable(name="food_sanity", run_type="chain")
def sanitise(estimate: FoodEstimate) -> FoodEstimate:
    """Drops empty items and scales an implausible total down to the photo cap (keeps relative portions)."""
    items = [i for i in estimate.items if i.kcal > 0 and i.name.strip()]
    total = sum(i.kcal for i in items)
    if total > MAX_PHOTO_KCAL:
        f = MAX_PHOTO_KCAL / total
        items = [i.model_copy(update={"kcal": round(i.kcal * f), "protein_g": round(i.protein_g * f, 1),
                                      "carbs_g": round(i.carbs_g * f, 1), "fat_g": round(i.fat_g * f, 1),
                                      "confidence": "low"}) for i in items]
    note = estimate.note if check_message(estimate.note) is None else ""
    return FoodEstimate(items=items, note=note)


class FoodResult(BaseModel):
    estimate: FoodEstimate | None
    safety: SafetyFlag | None = None
    ai_powered: bool = False

    model_config = {"arbitrary_types_allowed": True}


@traceable(name="estimate_food", run_type="chain")
async def estimate_food(llm: BaseChatModel | None, image: bytes, mime_type: str, note: str = "") -> FoodResult:
    """Returns an estimate, a safety response, or (no LLM / failure) neither — the app then offers manual entry."""
    flag = check_message(note) if note else None
    if flag is not None:
        return FoodResult(estimate=None, safety=flag)
    if llm is None:
        return FoodResult(estimate=None)
    content = [
        {"type": "text", "text": f"Estimate this meal. Student's note: {note[:200] or '(none)'}"},
        # LangChain v1 standard content block; provider integrations translate it.
        {"type": "image", "base64": base64.b64encode(image).decode(), "mime_type": mime_type},
    ]
    try:
        raw = await llm.with_structured_output(FoodEstimate).ainvoke([SystemMessage(FOOD_SYSTEM), HumanMessage(content=content)])
    except Exception:  # provider error, timeout, unparsable output
        log.exception("food estimate failed")
        return FoodResult(estimate=None)
    return FoodResult(estimate=sanitise(raw), ai_powered=True)
