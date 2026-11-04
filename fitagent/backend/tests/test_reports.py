from __future__ import annotations

from datetime import UTC, datetime, timedelta

import httpx
import pytest
from app.config import get_settings
from app.jobs.daily_reports import run_once
from app.services.whatsapp import send_report

from .conftest import auth, register
from .test_api import days_payload


@pytest.fixture
def report_dir(tmp_path, monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "report_dir", str(tmp_path))
    monkeypatch.setattr(s, "whatsapp_token", "")
    monkeypatch.setattr(s, "whatsapp_phone_number_id", "")
    return tmp_path


async def test_profile_whatsapp_fields(client):
    h = await register(client)
    r = await client.patch("/v1/me", headers=auth(h), json={
        "whatsapp_number": "+91 98765-43210", "whatsapp_opt_in": True, "city": " Pune ", "gender": "female",
        "report_hour": 20})
    assert r.status_code == 200, r.text
    p = r.json()
    assert p["whatsapp_number"] == "+919876543210" and p["whatsapp_opt_in"] and p["city"] == "Pune"
    assert p["report_hour"] == 20
    assert (await client.patch("/v1/me", headers=auth(h), json={"whatsapp_number": "98765"})).status_code == 422
    # clearing the number also turns the report off
    p = (await client.patch("/v1/me", headers=auth(h), json={"whatsapp_number": ""})).json()
    assert p["whatsapp_number"] is None and p["whatsapp_opt_in"] is False


async def test_report_pdf_has_no_dummy_data(client):
    h = await register(client)
    r = await client.get("/v1/me/report.pdf", headers=auth(h))
    assert r.status_code == 200 and r.headers["content-type"] == "application/pdf"
    assert r.content.startswith(b"%PDF")
    # with synced watch data the report is longer (charts, tiles)
    await client.post("/v1/me/sync", headers=auth(h), json={"timezone": "Asia/Kolkata", "days": days_payload(7, 8000)})
    full = await client.get("/v1/me/report.pdf", headers=auth(h))
    assert full.status_code == 200 and len(full.content) > len(r.content)
    future = (datetime.now(UTC) + timedelta(days=3)).date().isoformat()
    assert (await client.get(f"/v1/me/report.pdf?day={future}", headers=auth(h))).status_code == 422


async def test_send_now_dry_run(client, report_dir):
    h = await register(client)
    assert (await client.post("/v1/me/report/send", headers=auth(h))).status_code == 409  # no number yet
    await client.patch("/v1/me", headers=auth(h), json={"whatsapp_number": "+919876543210", "whatsapp_opt_in": True})
    r = await client.post("/v1/me/report/send", headers=auth(h))
    assert r.status_code == 200 and r.json()["status"] == "dry_run"
    assert list(report_dir.glob("919876543210-FitAgent-report-*.pdf"))
    st = (await client.get("/v1/me/report/status", headers=auth(h))).json()
    assert st["whatsapp_configured"] is False and st["last_status"] == "dry_run"


async def test_daily_job_is_idempotent(client, report_dir):
    h = await register(client, tz="UTC")
    await client.patch("/v1/me", headers=auth(h), json={"whatsapp_number": "+919876543210", "whatsapp_opt_in": True,
                                                        "report_hour": 0})
    assert (await run_once()) == {"dry_run": 1}
    assert (await run_once()) == {}  # already delivered today
    other = await register(client, "b@uni.edu", "B", tz="UTC")
    await client.patch("/v1/me", headers=auth(other), json={"whatsapp_number": "+919800000000"})  # not opted in
    assert (await run_once()) == {}


async def test_whatsapp_cloud_api_calls():
    s = get_settings().model_copy(update={"whatsapp_token": "T", "whatsapp_phone_number_id": "123",
                                          "whatsapp_template": "daily_report"})
    seen = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        if req.url.path.endswith("/media"):
            return httpx.Response(200, json={"id": "MEDIA1"})
        return httpx.Response(200, json={"messages": [{"id": "wamid.X"}]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as c:
        r = await send_report(s, "+919876543210", "r.pdf", b"%PDF-1.4", "caption", client=c)
    assert r.status == "sent" and r.detail == "wamid.X"
    assert seen[0].url.path == "/v21.0/123/media" and seen[0].headers["authorization"] == "Bearer T"
    import json
    body = json.loads(seen[1].content)
    assert body["to"] == "919876543210" and body["template"]["name"] == "daily_report"
    assert body["template"]["components"][0]["parameters"][0]["document"]["id"] == "MEDIA1"


async def test_whatsapp_error_is_reported():
    s = get_settings().model_copy(update={"whatsapp_token": "T", "whatsapp_phone_number_id": "123"})
    transport = httpx.MockTransport(lambda req: httpx.Response(400, json={"error": {"code": 131030,
                                    "message": "Recipient phone number not in allowed list"}}))
    async with httpx.AsyncClient(transport=transport) as c:
        r = await send_report(s, "+919876543210", "r.pdf", b"%PDF", "c", client=c)
    assert r.status == "failed" and "131030" in r.detail
