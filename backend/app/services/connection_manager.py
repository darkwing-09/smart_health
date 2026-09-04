"""WebSocket Connection Manager and Real-Time Event Dispatcher.

WebSocket is strictly a TRANSPORT mechanism.
PostgreSQL remains the authoritative source of truth.

Capabilities:
- Per-user connection registry and strict tenant isolation (User A never receives User B data).
- Heartbeat ping/pong handling.
- Real-time event broadcasting (notifications, findings, sync completion).
- Missed-event catch-up protocol on reconnect.
"""

import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Optional
from fastapi import WebSocket
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.notification import Notification

logger = logging.getLogger("healthos.websocket")


class WebSocketConnectionManager:
    """Manages active user WebSocket sessions and broadcasts domain events."""

    def __init__(self) -> None:
        # Maps user_id -> Set of active WebSockets (allows multiple tabs/devices per user)
        self._active_connections: dict[uuid.UUID, set[WebSocket]] = {}

    async def connect(self, user_id: uuid.UUID, websocket: WebSocket) -> None:
        """Accepts WebSocket and registers to user pool."""
        await websocket.accept()
        if user_id not in self._active_connections:
            self._active_connections[user_id] = set()
        self._active_connections[user_id].add(websocket)
        logger.info(f"WebSocket client connected for user {user_id}. Total connections: {len(self._active_connections[user_id])}")

    def disconnect(self, user_id: uuid.UUID, websocket: WebSocket) -> None:
        """Unregisters disconnected WebSocket."""
        if user_id in self._active_connections:
            self._active_connections[user_id].discard(websocket)
            if not self._active_connections[user_id]:
                del self._active_connections[user_id]
        logger.info(f"WebSocket client disconnected for user {user_id}.")

    async def send_personal_message(self, user_id: uuid.UUID, message: dict[str, Any]) -> int:
        """
        Sends JSON message to all active sockets belonging strictly to this user.
        Returns the number of sockets successfully sent to.
        """
        sockets = self._active_connections.get(user_id, set())
        if not sockets:
            return 0

        dead_sockets = set()
        success_count = 0
        for ws in list(sockets):
            try:
                await ws.send_json(message)
                success_count += 1
            except Exception as e:
                logger.warning(f"Failed sending WS event to socket for user {user_id}: {e}")
                dead_sockets.add(ws)

        for dead in dead_sockets:
            self.disconnect(user_id, dead)

        return success_count

    async def broadcast_event(
        self,
        user_id: uuid.UUID,
        event_type: str,
        data: dict[str, Any]
    ) -> int:
        """Constructs canonical event payload and broadcasts to user."""
        payload = {
            "type": event_type,
            "event_id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "data": data
        }
        return await self.send_personal_message(user_id, payload)

    async def send_catchup_notifications(
        self,
        user_id: uuid.UUID,
        websocket: WebSocket,
        db: AsyncSession,
        since_timestamp: datetime
    ) -> int:
        """
        Replay protocol: Queries PostgreSQL for notifications created or modified since
        `since_timestamp` and streams them to the newly reconnected socket.
        """
        stmt = (
            select(Notification)
            .where(
                Notification.user_id == user_id,
                Notification.created_at >= since_timestamp
            )
            .order_by(Notification.created_at.asc())
        )
        notifications = (await db.scalars(stmt)).all()

        catchup_payload = {
            "type": "sync.catchup",
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "count": len(notifications),
            "items": [
                {
                    "id": str(n.id),
                    "finding_id": str(n.finding_id) if n.finding_id else None,
                    "title": n.title,
                    "body": n.body,
                    "severity": n.severity,
                    "state": n.state,
                    "acknowledged_at": n.acknowledged_at.isoformat() if n.acknowledged_at else None,
                    "created_at": n.created_at.isoformat(),
                }
                for n in notifications
            ]
        }
        try:
            await websocket.send_json(catchup_payload)
            return len(notifications)
        except Exception as err:
            logger.error(f"Failed sending catch-up payload to user {user_id}: {err}")
            return 0


# Global singleton instance
ws_manager = WebSocketConnectionManager()
