"""Invite links (G2): https://<host>/j/CODE opens the app straight into "join challenge" via Android App Links;
without the app installed the same URL shows a small page with the code and a Play Store link."""

from __future__ import annotations

import html

from fastapi import APIRouter
from fastapi.responses import HTMLResponse, JSONResponse
from sqlalchemy import func, select

from ..config import get_settings
from ..deps import SessionDep
from ..models import Challenge, ChallengeMember

router = APIRouter(tags=["invites"], include_in_schema=False)

PLAY_URL = "https://play.google.com/store/apps/details?id={package}"


@router.get("/j/{code}", response_class=HTMLResponse)
async def invite_page(code: str, session: SessionDep) -> HTMLResponse:
    code = code.strip().upper()[:8]
    c = await session.scalar(select(Challenge).where(Challenge.code == code))
    package = get_settings().android_package
    if c is None:
        title, line = "Challenge not found", "This invite code doesn't exist any more. Ask your friend for a new one."
    else:
        people = await session.scalar(select(func.count()).select_from(ChallengeMember).where(ChallengeMember.challenge_id == c.id))
        title = f"Join “{c.name}” on FitAgent"
        line = f"{people} {'person' if people == 1 else 'people'} · ends {c.end_date:%d %b}"
    body = f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{html.escape(title)}</title>
<style>body{{font-family:system-ui,sans-serif;background:#0A0D12;color:#EDF1F5;margin:0;display:grid;place-items:center;min-height:100vh}}
main{{max-width:420px;padding:24px;text-align:center}}.code{{font-size:40px;font-weight:800;letter-spacing:6px;color:#C6F432;margin:16px 0}}
a{{display:block;margin:12px 0;padding:14px;border-radius:999px;background:#C6F432;color:#0A0D12;font-weight:700;text-decoration:none}}
a.alt{{background:#1B222C;color:#EDF1F5}}p{{color:#8A94A3}}</style></head><body><main>
<h1>{html.escape(title)}</h1><p>{html.escape(line)}</p><div class="code">{html.escape(code)}</div>
<a href="intent://j/{html.escape(code)}#Intent;scheme=https;package={html.escape(package)};end">Open in FitAgent</a>
<a class="alt" href="{PLAY_URL.format(package=html.escape(package))}">Get FitAgent</a>
<p>In the app: Squad → Join with code.</p></main></body></html>"""
    return HTMLResponse(body, status_code=200 if c else 404)


@router.get("/.well-known/assetlinks.json")
async def assetlinks() -> JSONResponse:
    """Digital Asset Links for App Links verification. Set ANDROID_CERT_SHA256 (comma-separated) in production."""
    s = get_settings()
    fingerprints = [f.strip() for f in s.android_cert_sha256.split(",") if f.strip()]
    if not fingerprints:
        return JSONResponse([])
    return JSONResponse([{
        "relation": ["delegate_permission/common.handle_all_urls"],
        "target": {"namespace": "android_app", "package_name": s.android_package, "sha256_cert_fingerprints": fingerprints},
    }])
