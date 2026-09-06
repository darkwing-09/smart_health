"""ReportLab Vector PDF Compiler for Doctor Visit Summaries.

Compiles an evidence-grounded, redactable, publication-grade clinical brief
for patient-physician consultations.
"""

import os
import hashlib
from typing import Dict, Any, Optional
from reportlab.lib.pagesizes import letter
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, KeepTogether, HRFlowable
)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle


class DoctorVisitSummaryPdfService:
    @staticmethod
    def compile_pdf(summary_payload: Dict[str, Any], output_path: str) -> str:
        """
        Compiles the Doctor Visit Summary vector PDF and saves to output_path.
        Returns the output_path.
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

        # Custom Styles
        title_style = ParagraphStyle(
            'DocTitle',
            parent=styles['Heading1'],
            fontSize=16,
            leading=20,
            textColor=colors.HexColor("#1A365D")
        )
        meta_style = ParagraphStyle(
            'MetaStyle',
            parent=styles['Normal'],
            fontSize=9,
            leading=12,
            textColor=colors.HexColor("#4A5568")
        )
        disclaimer_style = ParagraphStyle(
            'DisclaimerStyle',
            parent=styles['Normal'],
            fontSize=8,
            leading=11,
            textColor=colors.HexColor("#742A2A")
        )
        h2_style = ParagraphStyle(
            'SectionHeader',
            parent=styles['Heading2'],
            fontSize=11,
            leading=14,
            textColor=colors.HexColor("#2B6CB0"),
            spaceBefore=8,
            spaceAfter=4
        )
        body_style = ParagraphStyle(
            'BodyTextCustom',
            parent=styles['Normal'],
            fontSize=9,
            leading=13,
            textColor=colors.HexColor("#2D3748")
        )

        # 1. Header & Title
        story.append(Paragraph("<b>HealthAgent — Clinical Consultation Brief</b>", title_style))
        story.append(Paragraph("<i>Longitudinal Wearable Telemetry & Baseline Analytics Summary</i>", meta_style))
        story.append(Spacer(1, 6))

        # 2. Prominent Non-Diagnostic Advisory Box
        disclaimer_text = (
            "<b>CLINICAL ADVISORY & STATUTORY DISCLAIMER:</b> "
            "HealthAgent is a personal health data infrastructure and telemetry interpreter, "
            "NOT a medical diagnostic device or emergency dispatch service. This document is compiled "
            "from consumer wearable sensors solely to assist patient-physician consultations. "
            "It does NOT constitute a clinical diagnosis, treatment recommendation, or ECG evaluation. "
            "All medical evaluations must be conducted by a licensed physician."
        )
        disclaimer_table = Table([[Paragraph(disclaimer_text, disclaimer_style)]], colWidths=[540])
        disclaimer_table.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor("#FFF5F5")),
            ('BOX', (0, 0), (-1, -1), 1, colors.HexColor("#FEB2B2")),
            ('PADDING', (0, 0), (-1, -1), 6),
        ]))
        story.append(disclaimer_table)
        story.append(Spacer(1, 10))

        # 3. Patient & Scope Metadata Table
        rep_period = summary_payload.get("reporting_period", {})
        cov = summary_payload.get("data_coverage", {})
        patient_info = [
            [
                Paragraph(f"<b>Patient ID:</b> {summary_payload.get('user_id', 'N/A')}", meta_style),
                Paragraph(f"<b>Reporting Window:</b> {rep_period.get('start_date', '')} to {rep_period.get('end_date', '')}", meta_style),
            ],
            [
                Paragraph(f"<b>Consent Ref:</b> {summary_payload.get('consent_id', 'N/A')}", meta_style),
                Paragraph(f"<b>Adherence / Data Coverage:</b> {cov.get('wear_adherence_percent', 0.0)}% ({cov.get('total_measurements', 0)} samples)", meta_style),
            ]
        ]
        t_meta = Table(patient_info, colWidths=[270, 270])
        t_meta.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor("#F7FAFC")),
            ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor("#E2E8F0")),
            ('PADDING', (0, 0), (-1, -1), 4),
        ]))
        story.append(t_meta)
        story.append(Spacer(1, 10))

        # 4. Relevant Measurements & Baseline Comparison
        story.append(Paragraph("<b>1. Physiological Vitals & Baseline Comparisons</b>", h2_style))
        metrics_table_data = [["Metric", "Observed Value", "Personal Baseline", "Circadian Envelope", "Deviation"]]
        for m in summary_payload.get("measurements_summary", []):
            metrics_table_data.append([
                str(m.get("metric_name", "")),
                str(m.get("observed_display", "")),
                str(m.get("baseline_display", "")),
                str(m.get("circadian_envelope", "N/A")),
                str(m.get("deviation_display", ""))
            ])

        t_metrics = Table(metrics_table_data, colWidths=[120, 105, 105, 110, 100])
        t_metrics.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor("#EDF2F7")),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.HexColor("#2B6CB0")),
            ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
            ('FONTSIZE', (0, 0), (-1, -1), 8),
            ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
            ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor("#CBD5E0")),
            ('PADDING', (0, 0), (-1, -1), 4),
        ]))
        story.append(t_metrics)
        story.append(Spacer(1, 10))

        # 5. Longitudinal Trends
        trends = summary_payload.get("longitudinal_trends", [])
        if trends:
            story.append(Paragraph("<b>2. Multi-Day Longitudinal Trends</b>", h2_style))
            trends_data = [["Metric", "Direction", "Rate of Drift", "Fit (R²)", "Evidence Strength"]]
            for tr in trends:
                trends_data.append([
                    str(tr.get("metric_type", "")),
                    str(tr.get("direction", "")).capitalize(),
                    f"{tr.get('slope_per_day', 0.0):+.2f} unit/day",
                    f"{tr.get('r_squared', 0.0):.2f}",
                    str(tr.get("evidence_strength", "")).upper()
                ])
            t_trends = Table(trends_data, colWidths=[120, 90, 110, 110, 110])
            t_trends.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor("#EDF2F7")),
                ('FONTSIZE', (0, 0), (-1, -1), 8),
                ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor("#CBD5E0")),
                ('PADDING', (0, 0), (-1, -1), 4),
            ]))
            story.append(t_trends)
            story.append(Spacer(1, 10))

        # 6. Flagged Findings History (with redaction handling)
        findings = summary_payload.get("findings", [])
        story.append(Paragraph(f"<b>3. Evaluated Telemetry Findings ({len(findings)} events)</b>", h2_style))
        if findings:
            find_table = [["Timestamp", "Metric", "Severity", "Observed", "Baseline Diff", "Rule / Notes"]]
            for f in findings:
                if f.get("is_redacted"):
                    find_table.append([
                        str(f.get("timestamp", "")),
                        "[REDACTED]",
                        "[REDACTED]",
                        "[REDACTED]",
                        "[REDACTED]",
                        "Excluded from disclosure by patient"
                    ])
                else:
                    find_table.append([
                        str(f.get("timestamp", "")),
                        str(f.get("metric_type", "")),
                        str(f.get("severity", "")),
                        f"{f.get('observed_value', 0.0)}",
                        f"{f.get('deviation', 0.0):+.1f}",
                        str(f.get("rule_id", ""))
                    ])
            t_find = Table(find_table, colWidths=[90, 80, 85, 75, 75, 135])
            t_find.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor("#EDF2F7")),
                ('FONTSIZE', (0, 0), (-1, -1), 7.5),
                ('GRID', (0, 0), (-1, -1), 0.5, colors.HexColor("#CBD5E0")),
                ('PADDING', (0, 0), (-1, -1), 3),
            ]))
            story.append(t_find)
        else:
            story.append(Paragraph("<i>No significant statistical anomalies detected within the consented reporting window.</i>", meta_style))
        story.append(Spacer(1, 10))

        # 7. Clinician Synthesis & Deterministic Routing
        story.append(Paragraph("<b>4. Clinical Discussion Considerations & Specialty Routing</b>", h2_style))
        ai_synthesis = summary_payload.get("ai_synthesis", {})
        routing = summary_payload.get("specialty_routing", {})

        synthesis_text = ai_synthesis.get("clinician_summary", "Longitudinal telemetry compiled for physician review.")
        story.append(Paragraph(f"<b>Telemetry Summary:</b> {synthesis_text}", body_style))
        story.append(Spacer(1, 4))

        primary_spec = routing.get("primary_specialty", "Primary Care / Routine Health Maintenance")
        spec_rationale = routing.get("clinical_rationale", "Standard health maintenance review.")
        story.append(Paragraph(f"<b>Recommended Clinical Specialty:</b> {primary_spec}", body_style))
        story.append(Paragraph(f"<b>Routing Rationale:</b> {spec_rationale}", meta_style))
        story.append(Spacer(1, 10))

        # 8. Data Quality, Limitations, and Integrity Checksum Footer
        story.append(HRFlowable(width="100%", thickness=0.5, color=colors.HexColor("#CBD5E0"), spaceBefore=10, spaceAfter=8))
        checksum = summary_payload.get("checksum_sha256", "UNVERIFIED")
        footer_text = (
            f"<b>Integrity Checksum (SHA-256):</b> {checksum}<br/>"
            f"<b>Status:</b> {summary_payload.get('status', 'draft').upper()} | "
            f"<b>Approval Token:</b> {summary_payload.get('approval_token', 'PENDING_APPROVAL')} | "
            f"<b>Generated At:</b> {summary_payload.get('created_at', '')}<br/>"
            "This document is sealed with immutable cryptographic verification. "
            "Any alteration voids clinical verification."
        )
        story.append(Paragraph(footer_text, ParagraphStyle('FooterText', parent=styles['Normal'], fontSize=7.5, leading=10, textColor=colors.HexColor("#718096"))))

        doc.build(story)
        return output_path
