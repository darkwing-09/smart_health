"""Daily Health Report Endpoints."""

import uuid
import os
from typing import List
from fastapi import APIRouter, Depends, Query, HTTPException, status
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.api.deps import get_current_user, get_db
from app.models.user import User
from app.models.report import Report
from app.schemas.report import ReportListResponse, ReportItemSchema

router = APIRouter(prefix="/reports", tags=["reports"])


@router.get(
    "/daily",
    response_model=ReportListResponse,
    status_code=status.HTTP_200_OK,
    summary="List available daily reports"
)
async def list_reports(
    limit: int = Query(default=14, ge=1, le=60),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> ReportListResponse:
    stmt = select(Report).where(Report.user_id == current_user.id).order_by(Report.report_date.desc()).limit(limit)
    result = await db.execute(stmt)
    reports = result.scalars().all()

    items = [
        ReportItemSchema(
            report_id=r.id,
            date=r.report_date,
            generation_status=r.generation_status,
            closing_quote=r.closing_quote.get("quote", ""),
            pdf_download_url=f"/v1/reports/daily/{r.id}/download"
        )
        for r in reports
    ]
    return ReportListResponse(reports=items)


@router.get(
    "/daily/{report_id}/download",
    status_code=status.HTTP_200_OK,
    summary="Download daily report as vector PDF"
)
async def download_report_pdf(
    report_id: uuid.UUID,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
) -> FileResponse:
    report = await db.get(Report, report_id)
    if not report or report.user_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Report not found")

    if not os.path.exists(report.pdf_storage_path):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="PDF artifact not found on storage")

    return FileResponse(
        path=report.pdf_storage_path,
        media_type="application/pdf",
        filename=f"PersonalHealthReport_{report.report_date}.pdf"
    )
