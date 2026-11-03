"""Food photo estimates (S4). The image is used for one model call and never written to disk or the database."""

from __future__ import annotations

import base64
import binascii

from fastapi import APIRouter, HTTPException, status

from ..deps import CurrentUser, limiter
from ..schemas import FoodEstimateOut, FoodItemOut, FoodPhotoIn
from ..services.agents import estimate_food_photo, get_runtime

router = APIRouter(prefix="/v1/food", tags=["food"])

FOOD_PER_HOUR = 20
MAX_BYTES = 4 * 1024 * 1024
_MAGIC = {"image/jpeg": (b"\xff\xd8\xff",), "image/png": (b"\x89PNG",), "image/webp": (b"RIFF",)}


@router.post("/estimate", response_model=FoodEstimateOut)
async def estimate(body: FoodPhotoIn, user: CurrentUser) -> FoodEstimateOut:
    """Estimates dishes, portions, calories and macros. Without an AI key, returns no items so the app offers manual entry."""
    limiter.check(f"food:{user.id}", FOOD_PER_HOUR, 3600)
    try:
        image = base64.b64decode(body.image_base64, validate=True)
    except (binascii.Error, ValueError) as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "image_base64 is not valid base64") from e
    if len(image) > MAX_BYTES:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "Image must be 4 MB or smaller")
    if not image.startswith(_MAGIC[body.mime_type]):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Image content doesn't match mime_type")

    result = await estimate_food_photo(get_runtime(), image, body.mime_type, body.note)
    est = result.estimate
    return FoodEstimateOut(
        items=[FoodItemOut(**i.model_dump()) for i in est.items] if est else [],
        note=est.note if est else "",
        total_kcal=est.total_kcal if est else 0,
        ai_powered=result.ai_powered,
        safety=result.safety.category if result.safety else None,
        safety_message=result.safety.response if result.safety else None,
    )
