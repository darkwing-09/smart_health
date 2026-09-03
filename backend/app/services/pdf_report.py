"""ReportLab Vector PDF Daily Health Digest Compiler."""

import io
import os
from typing import Dict, Any
from reportlab.lib.pagesizes import letter
from reportlab.lib import colors
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from app.core.config import settings


class DailyReportPdfService:
    @staticmethod
    def compile_pdf(report_data: Dict[str, Any], output_path: str) -> str:
        """
        Compiles vector PDF and writes to persistent storage path.
        Returns file path.
        """
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        doc = SimpleDocTemplate(
            output_path,
            pagesize=letter,
            rightMargin=36,
            leftMargin=36,
            topMargin=36,
            bottomMargin=36
        )
        story = []
        styles = getSampleStyleSheet()

        title_style = ParagraphStyle(
            'ReportTitle',
            parent=styles['Heading1'],
            fontSize=18,
            leading=22,
            textColor=colors.HexColor("#1A365D")
        )
        story.append(Paragraph(f"Personal Health OS — Daily Health Digest ({report_data['date']})", title_style))
        story.append(Spacer(1, 12))

        # Metrics Table
        story.append(Paragraph("<b>24-Hour Metric Rollup & Baseline Comparison</b>", styles['Heading2']))
        story.append(Spacer(1, 6))

        table_data = [["Metric", "Recorded Value", "Personal Baseline", "Status"]]
        for row in report_data.get("metrics", []):
            table_data.append([row["name"], str(row["value"]), str(row["baseline"]), row["status"]])

        t = Table(table_data, colWidths=[140, 110, 110, 140])
        t.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor("#EDF2F7")),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.HexColor("#2D3748")),
            ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
            ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
            ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor("#CBD5E0"))
        ]))
        story.append(t)
        story.append(Spacer(1, 16))

        # Synthesized Executive Summary
        story.append(Paragraph("<b>Observations & Longitudinal Context</b>", styles['Heading2']))
        story.append(Spacer(1, 6))
        story.append(Paragraph(report_data.get("narrative", "Nominal physiological baseline maintained."), styles['Normal']))
        story.append(Spacer(1, 16))

        # Closing Dynamic Reflection Quote
        quote_style = ParagraphStyle(
            'QuoteStyle',
            parent=styles['Italic'],
            fontSize=10,
            leading=14,
            textColor=colors.HexColor("#4A5568")
        )
        quote_info = report_data.get("closing_quote", {})
        quote_text = quote_info.get("quote", "Restoration is the completion of effort.")
        author = quote_info.get("author_or_tradition", "Reflective Synthesis")
        story.append(Paragraph(f"“{quote_text}” — <i>{author}</i>", quote_style))

        doc.build(story)
        return output_path
