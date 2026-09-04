"""Notifications REST API Endpoints."""

import uuid
from typing import List, Optional
from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user, get_db
from app.models.notification import Notification
from app.models.user import User
from app.schemas.notification import (
    NotificationAcknowledgeResponse,
    NotificationDismissResponse,
    NotificationListResponse,
    NotificationResponse,
)
from app.services.notification import NotificationService

router = APIRouter()


@router.get("", response_model=NotificationListResponse)
async def list_notifications(
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    unread_only: bool = Query(default=False),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> NotificationListResponse:
    """
    Returns paginated notifications for the authenticated user.
    Enforces strict multi-tenant isolation.
    """
    service = NotificationService(db=db)
    items = await service.get_user_notifications(
        user_id=current_user.id,
        limit=limit,
        offset=offset,
        unread_only=unread_only
    )

    # Count total and unread
    stmt_total = select(func.count(Notification.id)).where(Notification.user_id == current_user.id)
    total = (await db.scalars(stmt_total)).first() or 0

    stmt_unread = select(func.count(Notification.id)).where(
        Notification.user_id == current_user.id,
        Notification.acknowledged_at.is_(None)
    )
    unread_count = (await db.scalars(stmt_unread)).first() or 0

    return NotificationListResponse(
        items=[NotificationResponse.model_validate(n) for n in items],
        total=total,
        unread_count=unread_count
    )


@router.get("/{notification_id}", response_model=NotificationResponse)
async def get_notification(
    notification_id: uuid.UUID,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> NotificationResponse:
    """
    Retrieves a single notification.
    Enforces tenant isolation: returns 404 if not found or owned by another user.
    """
    service = NotificationService(db=db)
    notif = await service.get_notification_by_id(current_user.id, notification_id)
    if not notif:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Notification not found"
        )
    return NotificationResponse.model_validate(notif)


@router.post("/{notification_id}/acknowledge", response_model=NotificationAcknowledgeResponse)
async def acknowledge_notification(
    notification_id: uuid.UUID,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> NotificationAcknowledgeResponse:
    """
    Acknowledges a notification and marks the underlying Finding as acknowledged.
    """
    service = NotificationService(db=db)
    notif = await service.acknowledge_notification(current_user.id, notification_id)
    if not notif:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Notification not found"
        )
    return NotificationAcknowledgeResponse(
        id=notif.id,
        state=notif.state,
        acknowledged_at=notif.acknowledged_at  # type: ignore
    )


@router.post("/{notification_id}/dismiss", response_model=NotificationDismissResponse)
async def dismiss_notification(
    notification_id: uuid.UUID,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> NotificationDismissResponse:
    """Dismisses a notification."""
    service = NotificationService(db=db)
    notif = await service.dismiss_notification(current_user.id, notification_id)
    if not notif:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Notification not found"
        )
    return NotificationDismissResponse(
        id=notif.id,
        state=notif.state,
        dismissed_at=notif.dismissed_at  # type: ignore
    )
