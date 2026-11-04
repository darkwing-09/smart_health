"""Daily PDF report: one A4 page built from the watch data the app synced. Pure function of a UserContext.

No sample numbers: a metric the watch didn't share is printed as "—", and a day without any watch data says so.
"""

from __future__ import annotations

import dataclasses
import io
from dataclasses import dataclass
from datetime import date, timedelta

from fitagent_agents import ActivityDay, UserContext
from fitagent_agents.metrics import Band, compute_readiness, current_streak, goal_met
from reportlab.graphics.charts.barcharts import VerticalBarChart
from reportlab.graphics.shapes import Drawing, Line, String
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import (
    KeepTogether,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

INK = colors.HexColor("#0A0D12")
MUTED = colors.HexColor("#5B6472")
TRACK = colors.HexColor("#E6E9EE")
LIME = colors.HexColor("#8DB800")  # print-safe version of the app's #C6F432
CORAL = colors.HexColor("#FF6B4A")
CYAN = colors.HexColor("#14A8BA")
VIOLET = colors.HexColor("#7B61FF")
BAND_COLOR = {Band.PUSH: colors.HexColor("#14B87A"), Band.STEADY: colors.HexColor("#E0A800"),
              Band.RECOVER: colors.HexColor("#E04848")}

H1 = ParagraphStyle("h1", fontName="Helvetica-Bold", fontSize=20, leading=24, textColor=INK)
H2 = ParagraphStyle("h2", fontName="Helvetica-Bold", fontSize=12, leading=15, textColor=INK, spaceBefore=6, spaceAfter=4)
BODY = ParagraphStyle("body", fontName="Helvetica", fontSize=9.5, leading=13, textColor=INK)
SMALL = ParagraphStyle("small", fontName="Helvetica", fontSize=8, leading=10, textColor=MUTED)
BIG = ParagraphStyle("big", fontName="Helvetica-Bold", fontSize=17, leading=20, textColor=INK)


@dataclass(frozen=True)
class ReportPerson:
    name: str
    age: int | None = None
    city: str | None = None
    watch: str | None = None  # e.g. "NoiseFit" (the app that delivered the data)


@dataclass(frozen=True)
class DailyReport:
    day: date
    pdf: bytes
    has_data: bool
    summary: str  # one line for the WhatsApp caption


def _hm(minutes: int | None) -> str:
    return "—" if minutes is None else f"{minutes // 60} h {minutes % 60:02d} m"


def _num(v: float | None, unit: str = "", fmt: str = "{:,.0f}") -> str:
    return "—" if v is None else (fmt.format(v) + (f" {unit}" if unit else ""))


def _has_watch_data(d: ActivityDay | None) -> bool:
    if d is None:
        return False
    return bool(d.steps or d.sleep_minutes or d.resting_hr or d.avg_hr or d.active_kcal)


def _tile(label: str, value: str, sub: str, color: colors.Color) -> Table:
    t = Table([[Paragraph(label.upper(), SMALL)], [Paragraph(value, BIG)], [Paragraph(sub or "&nbsp;", SMALL)]],
              colWidths=[42 * mm], rowHeights=[5 * mm, 8 * mm, 5 * mm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#F5F7FA")),
        ("LINEBEFORE", (0, 0), (0, -1), 3, color),
        ("LEFTPADDING", (0, 0), (-1, -1), 8), ("TOPPADDING", (0, 0), (-1, -1), 1),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 1), ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
    ]))
    return t


def _bars(title: str, labels: list[str], values: list[float | None], color: colors.Color, goal: float | None,
          unit_div: float = 1.0) -> Drawing:
    w, h = 85 * mm, 48 * mm
    d = Drawing(w, h)
    d.add(String(0, h - 10, title, fontName="Helvetica-Bold", fontSize=9, fillColor=INK))
    vals = [(v or 0) / unit_div for v in values]
    c = VerticalBarChart()
    c.x, c.y, c.width, c.height = 22, 14, w - 28, h - 34
    c.data = [vals]
    c.bars[0].fillColor = color
    c.bars[0].strokeColor = None
    c.barSpacing = 3
    top = max(vals + [(goal or 0) / unit_div, 1])
    c.valueAxis.valueMin = 0
    c.valueAxis.valueMax = top * 1.15
    c.valueAxis.labels.fontSize = 6.5
    c.valueAxis.labels.fillColor = MUTED
    c.valueAxis.strokeColor = TRACK
    c.valueAxis.gridStrokeColor = TRACK
    c.valueAxis.visibleGrid = True
    c.categoryAxis.categoryNames = labels
    c.categoryAxis.labels.fontSize = 7
    c.categoryAxis.labels.fillColor = MUTED
    c.categoryAxis.strokeColor = TRACK
    d.add(c)
    if goal:
        gy = c.y + c.height * ((goal / unit_div) / c.valueAxis.valueMax)
        d.add(Line(c.x, gy, c.x + c.width, gy, strokeColor=MUTED, strokeDashArray=[2, 2], strokeWidth=0.6))
        d.add(String(c.x + c.width - 22, gy + 2, "goal", fontSize=6.5, fillColor=MUTED))
    return d


def build_daily_report(ctx: UserContext, day: date, person: ReportPerson) -> DailyReport:
    """Renders the report for [day] (usually yesterday or today in the user's timezone)."""
    ctx = dataclasses.replace(ctx, today=day, days=[d for d in ctx.days if d.day <= day])
    today = ctx.day(day)
    goals = ctx.profile.goals
    has_data = _has_watch_data(today)
    week = [day - timedelta(days=i) for i in range(6, -1, -1)]
    by_day = {d.day: d for d in ctx.days}
    labels = [d.strftime("%a")[:2] for d in week]

    buf = io.BytesIO()
    doc = SimpleDocTemplate(buf, pagesize=A4, leftMargin=16 * mm, rightMargin=16 * mm, topMargin=14 * mm,
                            bottomMargin=14 * mm, title=f"FitAgent daily report {day.isoformat()}",
                            author="FitAgent")
    story: list = []

    who = " · ".join(x for x in [person.name, f"{person.age} yrs" if person.age else None, person.city] if x)
    header = Table([[Paragraph("FitAgent · Daily report", H1),
                     Paragraph(f"<b>{day.strftime('%A, %d %B %Y')}</b><br/>{who}", ParagraphStyle(
                         "r", parent=BODY, alignment=2))]], colWidths=[100 * mm, 78 * mm])
    header.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                                ("LINEBELOW", (0, 0), (-1, 0), 2, LIME), ("BOTTOMPADDING", (0, 0), (-1, -1), 8)]))
    story += [header, Spacer(1, 6)]
    if person.watch:
        story.append(Paragraph(f"Data from your watch via {person.watch}.", SMALL))

    if not has_data:
        story += [Spacer(1, 18), Paragraph("No watch data for this day", H2), Paragraph(
            "Your watch didn't sync anything for this date, so there is nothing to report. Open your watch app "
            "(NoiseFit, boAt Crest…) so it syncs to Health Connect, then open FitAgent.", BODY)]
        doc.build(story)
        return DailyReport(day, buf.getvalue(), False, f"No watch data for {day.strftime('%d %b')} — sync your watch.")

    assert today is not None
    r = compute_readiness(ctx)
    band_color = BAND_COLOR.get(r.band, MUTED)
    streak = current_streak(ctx)

    # Readiness hero
    reasons = "<br/>".join(f"• {x}" for x in r.reasons[:3]) or "Based on your recent sleep and heart rate."
    hero = Table([[Paragraph(f"<b>{r.score}</b>",
                             ParagraphStyle("s", parent=BIG, fontSize=34, leading=38, textColor=band_color)),
                   Paragraph(f"<b>Readiness · {r.band.value.title()}</b><br/>{reasons}", BODY)]],
                 colWidths=[30 * mm, 148 * mm])
    hero.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                              ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#F5F7FA")),
                              ("LINEBEFORE", (0, 0), (0, -1), 4, band_color),
                              ("LEFTPADDING", (0, 0), (-1, -1), 10), ("TOPPADDING", (0, 0), (-1, -1), 8),
                              ("BOTTOMPADDING", (0, 0), (-1, -1), 8)]))
    story += [Spacer(1, 6), hero, Spacer(1, 8)]

    # KPI tiles (4 × 2)
    pct = lambda v, g: f"{min(999, round(100 * v / g))}% of {g:,}" if g else ""
    tiles = [
        _tile("Steps", _num(today.steps), pct(today.steps, goals.daily_steps) + " goal", LIME),
        _tile("Active energy", _num(today.active_kcal, "kcal"), pct(today.active_kcal, goals.daily_active_kcal), CORAL),
        _tile("Workout", _num(today.workout_minutes, "min"), pct(today.workout_minutes, goals.daily_workout_minutes), CYAN),
        _tile("Distance", _num(today.distance_m / 1000 if today.distance_m else None, "km", "{:.2f}"), "", LIME),
        _tile("Sleep", _hm(today.sleep_minutes),
              "deep " + _hm(today.sleep_deep_min) if today.sleep_deep_min is not None else "", VIOLET),
        _tile("Resting HR", _num(today.resting_hr, "bpm"),
              f"range {today.hr_min}–{today.hr_max}" if today.hr_min and today.hr_max else "", CORAL),
        _tile("HRV", _num(today.hrv_ms, "ms"), "", VIOLET),
        _tile("Blood oxygen", _num(today.spo2_avg, "%", "{:.0f}"), "", CYAN),
    ]
    grid = Table([tiles[:4], tiles[4:]], colWidths=[44.5 * mm] * 4, rowHeights=[20 * mm, 20 * mm])
    grid.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 0), ("RIGHTPADDING", (0, 0), (-1, -1), 2.5),
                              ("TOPPADDING", (0, 0), (-1, -1), 0), ("BOTTOMPADDING", (0, 0), (-1, -1), 2.5)]))
    story += [grid, Spacer(1, 6)]

    # Charts
    steps = [by_day[d].steps if d in by_day else None for d in week]
    sleep = [by_day[d].sleep_minutes if d in by_day else None for d in week]
    charts = Table([[_bars("Steps · last 7 days", labels, steps, LIME, goals.daily_steps),
                     _bars("Sleep (hours) · last 7 days", labels, sleep, VIOLET, 8 * 60, unit_div=60)]],
                   colWidths=[89 * mm, 89 * mm])
    story += [KeepTogether([Paragraph("Your week", H2), charts])]

    met = sum(1 for d in week if d in by_day and goal_met(by_day[d], goals))
    story.append(Paragraph(f"Step goal met on <b>{met} of 7</b> days · current streak <b>{streak}</b> "
                           f"day{'s' if streak != 1 else ''}.", BODY))

    # Workouts
    wk = [w for w in ctx.workouts if w.start.date() == day]
    story.append(Paragraph("Workouts", H2))
    if wk:
        rows = [["Time", "Workout", "Minutes", "kcal"]] + [
            [w.start.strftime("%H:%M"), (w.title or w.type.title())[:40], str(w.active_minutes), f"{w.kcal:.0f}"]
            for w in wk]
        t = Table(rows, colWidths=[22 * mm, 96 * mm, 30 * mm, 30 * mm])
        t.setStyle(TableStyle([("FONT", (0, 0), (-1, 0), "Helvetica-Bold", 8.5), ("FONT", (0, 1), (-1, -1), "Helvetica", 9),
                               ("TEXTCOLOR", (0, 0), (-1, 0), MUTED), ("LINEBELOW", (0, 0), (-1, 0), 0.5, TRACK),
                               ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F7F8FA")])]))
        story.append(t)
    else:
        story.append(Paragraph("No workouts recorded.", BODY))

    # Tip
    story += [Paragraph("Tomorrow", H2), Paragraph(_tip(r.band, today, goals.daily_steps), BODY), Spacer(1, 14),
              Paragraph("FitAgent is a fitness coach, not a medical device. If something feels wrong, talk to a doctor.",
                        SMALL)]
    doc.build(story)
    summary = (f"{day.strftime('%d %b')}: readiness {r.score} ({r.band.value}), {today.steps:,} steps"
               + (f", slept {_hm(today.sleep_minutes)}" if today.sleep_minutes else "") + ".")
    return DailyReport(day, buf.getvalue(), True, summary)


def _tip(band: Band, d: ActivityDay, step_goal: int) -> str:
    if band == Band.RECOVER:
        return "Take it easy: a 20-minute walk and an early night will help more than a hard session."
    if d.sleep_minutes is not None and d.sleep_minutes < 6 * 60:
        return "Short night — aim to be in bed 30 minutes earlier, and keep any workout moderate."
    if d.steps < step_goal:
        return f"You were {step_goal - d.steps:,} steps short of your goal. A walk after a class or a meal closes it."
    if band == Band.PUSH:
        return "You're well recovered — a good day for a harder workout or a run."
    return "Keep the rhythm: one 20–30 minute session and your step goal."
