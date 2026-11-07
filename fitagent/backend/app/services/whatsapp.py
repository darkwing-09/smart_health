"""WhatsApp Cloud API sender (Meta Graph API): upload the PDF, then send it as a document.

Business-initiated messages (our daily report) must use an approved *template*; a plain document message is only
delivered within 24 h of the user's last message to the business number. See docs/WHATSAPP_REPORTS.md.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

import httpx

from ..config import Settings

log = logging.getLogger(__name__)
GRAPH = "https://graph.facebook.com"


@dataclass(frozen=True)
class SendResult:
    status: str  # sent | dry_run | failed
    detail: str


async def send_report(settings: Settings, to_e164: str, filename: str, pdf: bytes, caption: str,
                      client: httpx.AsyncClient | None = None) -> SendResult:
    if not settings.whatsapp_configured:
        path = Path(settings.report_dir)
        path.mkdir(parents=True, exist_ok=True)
        out = path / f"{to_e164.lstrip('+')}-{filename}"
        out.write_bytes(pdf)
        log.info("WhatsApp not configured — saved %s instead of sending", out)
        return SendResult("dry_run", f"WhatsApp not configured; saved to {out}")

    base = f"{GRAPH}/{settings.whatsapp_api_version}/{settings.whatsapp_phone_number_id}"
    headers = {"Authorization": f"Bearer {settings.whatsapp_token}"}
    own = client is None
    client = client or httpx.AsyncClient(timeout=30)
    try:
        up = await client.post(f"{base}/media", headers=headers,
                               data={"messaging_product": "whatsapp", "type": "application/pdf"},
                               files={"file": (filename, pdf, "application/pdf")})
        if up.status_code >= 300:
            return SendResult("failed", f"media upload {up.status_code}: {_err(up)}")
        media_id = up.json()["id"]
        document = {"id": media_id, "filename": filename}
        if settings.whatsapp_template:
            message = {"type": "template", "template": {
                "name": settings.whatsapp_template, "language": {"code": settings.whatsapp_template_lang},
                "components": [
                    {"type": "header", "parameters": [{"type": "document", "document": document}]},
                    {"type": "body", "parameters": [{"type": "text", "text": caption[:1000]}]},
                ]}}
        else:
            message = {"type": "document", "document": {**document, "caption": caption[:1000]}}
        r = await client.post(f"{base}/messages", headers=headers,
                              json={"messaging_product": "whatsapp", "to": to_e164.lstrip("+"), **message})
        if r.status_code >= 300:
            return SendResult("failed", f"send {r.status_code}: {_err(r)}")
        return SendResult("sent", r.json().get("messages", [{}])[0].get("id", "ok"))
    except httpx.HTTPError as e:
        return SendResult("failed", f"network: {e.__class__.__name__}")
    finally:
        if own:
            await client.aclose()


def _err(r: httpx.Response) -> str:
    try:
        e = r.json().get("error", {})
        return f"{e.get('code')} {e.get('message', '')}"[:300]
    except ValueError:
        return r.text[:300]
