"""Real-Time WebSocket Streaming Endpoint.

WebSocket is ONLY a transport. PostgreSQL remains the source of truth.
Enforces per-user authentication, tenant isolation, heartbeat, and missed-event catchup.
"""

import logging
import uuid
from datetime import datetime, timezone
from typing import Optional
from fastapi import APIRouter, Depends, Query, WebSocket, WebSocketDisconnect, status
from jose import jwt, JWTError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.db.session import get_db
from app.models.user import User
from app.services.connection_manager import ws_manager

router = APIRouter()
logger = logging.getLogger("healthos.ws_endpoint")


async def authenticate_ws_token(token: Optional[str]) -> Optional[uuid.UUID]:
    """Validates JWT token from WebSocket query parameters."""
    if not token:
        return None
    try:
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=["HS256"])
        user_id_str: Optional[str] = payload.get("sub")
        if not user_id_str:
            return None
        return uuid.UUID(user_id_str)
    except (JWTError, ValueError):
        return None


@router.websocket("/stream")
async def websocket_stream_endpoint(
    websocket: WebSocket,
    token: Optional[str] = Query(default=None),
    since: Optional[str] = Query(default=None),
    db: AsyncSession = Depends(get_db)
) -> None:
    """
    Authenticated real-time WebSocket connection for biometrics, findings, and alerts.
    Usage:
      ws://<host>/v1/ws/stream?token=<jwt_access_token>&since=2026-09-04T10:00:00Z
    """
    user_id = await authenticate_ws_token(token)
    if not user_id:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Authentication failed")
        return

    # Verify user is active in database
    user = await db.get(User, user_id)
    if not user or not user.is_active:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="User inactive")
        return

    await ws_manager.connect(user_id, websocket)

    # Missed-event catch-up protocol on reconnect if 'since' parameter is provided
    if since:
        try:
            since_dt = datetime.fromisoformat(since.replace("Z", "+00:00"))
            await ws_manager.send_catchup_notifications(user_id, websocket, db, since_dt)
        except Exception as e:
            logger.warning(f"Failed processing initial catch-up for user {user_id}: {e}")

    try:
        while True:
            data = await websocket.receive_json()
            msg_type = data.get("type")

            # Heartbeat ping/pong
            if msg_type == "ping":
                await websocket.send_json({
                    "type": "pong",
                    "timestamp": datetime.now(timezone.utc).isoformat()
                })
            # Client-initiated catch-up request
            elif msg_type == "catch_up":
                since_str = data.get("since")
                if since_str:
                    try:
                        since_dt = datetime.fromisoformat(since_str.replace("Z", "+00:00"))
                        await ws_manager.send_catchup_notifications(user_id, websocket, db, since_dt)
                    except Exception as err:
                        await websocket.send_json({"type": "error", "message": f"Invalid catch-up timestamp: {err}"})

    except WebSocketDisconnect:
        ws_manager.disconnect(user_id, websocket)
    except Exception as exc:
        logger.warning(f"WebSocket session terminated with error: {exc}")
        ws_manager.disconnect(user_id, websocket)
