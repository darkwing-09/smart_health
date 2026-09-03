"""API v1 Router Aggregator."""

from fastapi import APIRouter
from app.api.v1.endpoints import auth, sync, measurements, findings, reports, care

api_v1_router = APIRouter()
api_v1_router.include_router(auth.router)
api_v1_router.include_router(sync.router)
api_v1_router.include_router(measurements.router)
api_v1_router.include_router(findings.router)
api_v1_router.include_router(reports.router)
api_v1_router.include_router(care.router)
