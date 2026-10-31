package com.fitagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitagent.domain.Band
import com.fitagent.domain.DayActivity
import com.fitagent.domain.HrBucket
import com.fitagent.domain.SleepSegment
import com.fitagent.domain.WHO_WEEKLY_MINUTES
import com.fitagent.domain.ZONE_NAMES
import com.fitagent.domain.bandFor
import com.fitagent.domain.baselineHeadline
import com.fitagent.domain.bedtimeMinutes
import com.fitagent.domain.formatHours
import com.fitagent.domain.hrMax
import com.fitagent.domain.normalRange
import com.fitagent.domain.readinessHeadline
import com.fitagent.domain.sleepHeadline
import com.fitagent.domain.sleepQuality
import com.fitagent.domain.sleepReadinessHeadline
import com.fitagent.domain.sleepScore
import com.fitagent.domain.whoHeadline
import com.fitagent.domain.whoMinutes
import com.fitagent.domain.whoMinutesFromWorkouts
import com.fitagent.domain.zoneFloors
import com.fitagent.domain.zoneMinutes
import com.fitagent.ui.HomeState
import com.fitagent.ui.charts.ArcGauge
import com.fitagent.ui.charts.Bars
import com.fitagent.ui.charts.BulletBar
import com.fitagent.ui.charts.ChartPeriod
import com.fitagent.ui.charts.DivergingBars
import com.fitagent.ui.charts.Donut
import com.fitagent.ui.charts.HBand
import com.fitagent.ui.charts.HorizontalStackBar
import com.fitagent.ui.charts.Hypnogram
import com.fitagent.ui.charts.Legend
import com.fitagent.ui.charts.LineChart
import com.fitagent.ui.charts.LineSeries
import com.fitagent.ui.charts.PeriodChips
import com.fitagent.ui.charts.RangeBars
import com.fitagent.ui.charts.Scatter
import com.fitagent.ui.charts.StackedBars
import com.fitagent.ui.charts.sparseLabels
import com.fitagent.ui.components.LinearMeter
import com.fitagent.ui.theme.Fit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

private fun dayLabels(days: List<DayActivity>): List<String> =
    if (days.size <= 7) days.map { it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
    else sparseLabels(days.map { "${it.date.dayOfMonth}" }, if (days.size <= 31) 7 else 30)

// ── Readiness ───────────────────────────────────────────────────────────────────────────────────

@Composable
fun ReadinessScreen(state: HomeState, onBack: () -> Unit) {
    val r = state.readiness
    val fc = Fit.colors
    val history = state.readinessHistory
    DetailScaffold("Readiness", "How ready your body is today", onBack) {
        item {
            ChartCard("Today", readinessHeadline(r)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArcGauge(r.score / 100f, fc.band(r.band), Modifier.size(110.dp), description = "Readiness ${r.score}") {
                        Text("${r.score}", style = MaterialTheme.typography.displayMedium, color = fc.band(r.band))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.components.forEach { (k, v) ->
                            Column {
                                Row {
                                    Text(COMPONENT_NAMES[k] ?: k, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                    Text("$v", style = MaterialTheme.typography.labelMedium)
                                }
                                LinearMeter(v / 100f, fc.band(bandFor(v)), height = 6.dp)
                            }
                        }
                    }
                }
                if (r.reasons.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    r.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = fc.muted) }
                }
                if (r.components.size == 1) {
                    Text("Wear your watch to bed to add sleep, resting heart rate and HRV to your score.",
                        style = MaterialTheme.typography.bodySmall, color = fc.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            val scores = history.map { it.second.score.toFloat() }
            val avg = scores.average().roundToInt()
            ChartCard("Last 30 days", "Average $avg · ${history.count { it.second.band == Band.PUSH }} push days") {
                LineChart(
                    listOf(LineSeries(scores, fc.push, fill = true)),
                    bands = listOf(HBand(67f, 100f, fc.push.copy(alpha = 0.08f)), HBand(34f, 67f, fc.steady.copy(alpha = 0.06f)),
                        HBand(0f, 34f, fc.recover.copy(alpha = 0.08f))),
                    yMin = 0f, yMax = 100f,
                    xLabels = sparseLabels(history.map { "${it.first.dayOfMonth}" }, 7),
                    scrubLabel = { "${history[it].first.format(DAY)} · ${history[it].second.score}" },
                    description = "Readiness over the last 30 days, average $avg",
                )
                Legend(listOf("Push ≥ 67" to fc.push, "Steady" to fc.steady, "Recover < 34" to fc.recover))
            }
        }
        item {
            ChartCard("What moved your score", "Each day's contributors, 0–100") {
                val keys = listOf("sleep", "hrv", "resting_hr")
                val colors = listOf(fc.sleep, fc.hrv, fc.heart)
                LineChart(
                    keys.mapIndexed { i, k -> LineSeries(history.map { it.second.components[k]?.toFloat() }, colors[i], width = 2.dp) },
                    yMin = 0f, yMax = 100f, height = 120.dp,
                    xLabels = sparseLabels(history.map { "${it.first.dayOfMonth}" }, 7),
                )
                Legend(keys.mapIndexed { i, k -> (COMPONENT_SHORT_NAMES[k] ?: k) to colors[i] })
            }
        }
        item {
            val sleepByDay = state.history.associate { it.date to it.sleepMinutes }
            val points = history.mapNotNull { (d, rd) -> sleepByDay[d]?.let { it / 60f to rd.score.toFloat() } }
            ChartCard("Sleep vs readiness", sleepReadinessHeadline(points) ?: "A week of sleep data shows how sleep drives your score.") {
                Scatter(points, fc.sleep, xLabel = "hours slept →", yLabel = "readiness", description = "Readiness against hours slept")
            }
        }
        item { LoadCard(state) }
    }
}

@Composable
private fun LoadCard(state: HomeState) {
    val fc = Fit.colors
    val zone = ZoneId.systemDefault()
    val days = state.history.takeLast(30)
    val minutes = days.map { d ->
        state.workouts.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == d.date }.sumOf { it.minutes }.toFloat()
    }
    // Rolling 7-day (acute) vs 28-day (chronic) average minutes per day.
    fun avg(i: Int, window: Int) = minutes.subList((i - window + 1).coerceAtLeast(0), i + 1).average().toFloat()
    val acute = minutes.indices.map { avg(it, 7) }
    val chronic = minutes.indices.map { avg(it, 28) }
    val ratio = state.readiness.acwr
    ChartCard(
        "Training load",
        ratio?.let { "Acute:chronic ratio $it — ${if (it in 0.8..1.3) "in the sweet spot" else if (it > 1.3) "rising fast" else "lighter than usual"}" }
            ?: "Two weeks of workouts unlock your load ratio.",
    ) {
        LineChart(
            listOf(LineSeries(acute, fc.move, fill = true), LineSeries(chronic, fc.muted, dashed = true)),
            yMin = 0f, height = 120.dp,
            xLabels = sparseLabels(days.map { "${it.date.dayOfMonth}" }, 7),
            scrubLabel = { "${days[it].date.format(DAY)} · ${acute[it].roundToInt()} vs ${chronic[it].roundToInt()} min/day" },
        )
        Legend(listOf("Last 7 days" to fc.move, "Last 4 weeks" to fc.muted))
    }
}

// ── Sleep ───────────────────────────────────────────────────────────────────────────────────────

@Composable
fun SleepScreen(state: HomeState, lastNight: List<SleepSegment>, onBack: () -> Unit) {
    val fc = Fit.colors
    val zone = ZoneId.systemDefault()
    val t = state.todayActivity
    var period by rememberSaveable { mutableStateOf(ChartPeriod.WEEK) }
    val window = state.history.takeLast(period.days)
    val previous = state.history.dropLast(1).takeLast(14).mapNotNull { it.sleepMinutes }
    val bedtimes = state.history.takeLast(7).mapNotNull { d -> d.bedMs?.let { bedtimeMinutes(it, zone) } }
    DetailScaffold("Sleep", "From your watch, via Health Connect", onBack) {
        item {
            val asleep = t.sleepMinutes
            ChartCard("Last night", sleepHeadline(asleep, previous)) {
                if (asleep == null) {
                    Text("No sleep recorded. Wear your watch to bed and make sure its app shares Sleep with Health Connect.",
                        style = MaterialTheme.typography.bodyMedium, color = fc.muted)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val score = sleepScore(asleep, t.sleepStages, bedtimes)
                        ArcGauge(score / 100f, fc.sleep, Modifier.size(96.dp), description = "Sleep score $score") {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$score", style = MaterialTheme.typography.headlineMedium, color = fc.sleep)
                                Text("SCORE", style = MaterialTheme.typography.labelSmall, color = fc.muted)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(formatHours(asleep), style = MaterialTheme.typography.headlineMedium)
                            Text("asleep", style = MaterialTheme.typography.bodySmall, color = fc.muted)
                            t.bedMs?.let { bed ->
                                val f = DateTimeFormatter.ofPattern("HH:mm")
                                Text("${f.format(Instant.ofEpochMilli(bed).atZone(zone))} → ${t.wakeMs?.let { f.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "—"}",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (lastNight.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Hypnogram(lastNight, zone)
                    }
                }
            }
        }
        t.sleepStages?.let { s ->
            item {
                ChartCard("Stages", "Quality ${sleepQuality(s)}/100 — deep ${pct(s.deep, s.total)}%, REM ${pct(s.rem, s.total)}%") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Donut(listOf(s.deep.toFloat() to fc.stageDeep, s.light.toFloat() to fc.stageLight,
                            s.rem.toFloat() to fc.stageRem, s.awake.toFloat() to fc.stageAwake), Modifier.size(120.dp),
                            description = "Deep ${s.deep}, light ${s.light}, REM ${s.rem}, awake ${s.awake} minutes") {
                            Text(formatHours(s.asleep), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StageRow("Deep", s.deep, s.total, fc.stageDeep)
                            StageRow("Light", s.light, s.total, fc.stageLight)
                            StageRow("REM", s.rem, s.total, fc.stageRem)
                            StageRow("Awake", s.awake, s.total, fc.stageAwake)
                        }
                    }
                    Text("Stages are estimated from wrist movement and heart rate — great for trends, not a medical test.",
                        style = MaterialTheme.typography.bodySmall, color = fc.muted, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        item {
            val avg = window.mapNotNull { it.sleepMinutes }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
            ChartCard("Sleep per night", avg?.let { "Average ${formatHours(it)} over ${period.days} days" },
                trailing = { PeriodChips(period, { period = it }) }) {
                val staged = window.any { it.sleepStages != null }
                if (staged) {
                    StackedBars(
                        window.map { d ->
                            d.sleepStages?.let { listOf(it.deep, it.light, it.rem, it.awake).map(Int::toFloat) }
                                ?: listOf(0f, (d.sleepMinutes ?: 0).toFloat(), 0f, 0f)
                        },
                        listOf(fc.stageDeep, fc.stageLight, fc.stageRem, fc.stageAwake),
                        goal = 480f, labels = dayLabels(window),
                        scrubLabel = { i -> "${window[i].date.format(DAY)} · ${window[i].sleepMinutes?.let { formatHours(it) } ?: "—"}" },
                    )
                } else {
                    Bars(window.map { (it.sleepMinutes ?: 0).toFloat() }, fc.sleep, goal = 480f, labels = dayLabels(window),
                        scrubLabel = { i -> "${window[i].date.format(DAY)} · ${window[i].sleepMinutes?.let { formatHours(it) } ?: "—"}" })
                }
            }
        }
        item {
            val week = state.history.takeLast(7)
            val ranges = week.map { d ->
                val b = d.bedMs; val w = d.wakeMs
                if (b == null || w == null) null else bedtimeMinutes(b, zone).toFloat()..(bedtimeMinutes(b, zone) + (w - b) / 60_000f)
            }
            val sd = bedtimes.takeIf { it.size >= 3 }?.let { list ->
                val m = list.average(); kotlin.math.sqrt(list.sumOf { (it - m) * (it - m) } / list.size).roundToInt()
            }
            ChartCard("Consistency", sd?.let { "Your bedtime varied by ±$it min this week${if (it > 45) " — a steadier bedtime helps recovery" else ""}." }
                ?: "Bed and wake times appear here once your watch reports them.") {
                RangeBars(ranges, fc.sleep, yMin = 180f, yMax = 900f, target = 300f..780f, downwards = true,
                    labels = dayLabels(week),
                    scrubLabel = { i -> week[i].bedMs?.let { b -> val f = DateTimeFormatter.ofPattern("HH:mm")
                        "${f.format(Instant.ofEpochMilli(b).atZone(zone))} → ${week[i].wakeMs?.let { f.format(Instant.ofEpochMilli(it).atZone(zone)) }}" } ?: "—" },
                    description = "Bedtime to wake time per night")
                Text("Shaded: 23:00 – 07:00", style = MaterialTheme.typography.labelSmall, color = fc.muted)
            }
        }
        val spo2 = window.map { d -> d.night.spo2Avg?.let { avg -> (d.night.spo2Min ?: avg).toFloat()..avg.toFloat() } }
        if (spo2.any { it != null }) {
            item {
                ChartCard("Blood oxygen overnight", "Lowest to average SpO₂ each night") {
                    RangeBars(spo2, fc.spo2, yMin = 85f, yMax = 100f, labels = dayLabels(window),
                        scrubLabel = { i -> spo2[i]?.let { "${it.start.roundToInt()}–${it.endInclusive.roundToInt()}%" } ?: "—" })
                }
            }
        }
        val temp = window.map { it.night.skinTempDelta?.toFloat() }
        if (temp.any { it != null }) {
            item {
                ChartCard("Skin temperature", "Difference from your baseline each night") {
                    DivergingBars(temp, fc.move, fc.workout, labels = dayLabels(window),
                        scrubLabel = { i -> temp[i]?.let { "%+.1f °C".format(it) } ?: "—" })
                }
            }
        }
    }
}

private fun pct(part: Int, total: Int) = if (total <= 0) 0 else part * 100 / total

@Composable
private fun StageRow(label: String, minutes: Int, total: Int, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.size(10.dp).padding(1.dp)) {
            androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp))
        Text("${formatHours(minutes)} · ${pct(minutes, total)}%", style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
    }
}

// ── Heart ───────────────────────────────────────────────────────────────────────────────────────

@Composable
fun HeartScreen(state: HomeState, today: List<HrBucket>, week: List<HrBucket>, onBack: () -> Unit) {
    val fc = Fit.colors
    val zone = ZoneId.systemDefault()
    var period by rememberSaveable { mutableStateOf(ChartPeriod.MONTH) }
    val window = state.history.takeLast(period.days)
    val max = hrMax(state.profile.ageYears)
    val floors = zoneFloors(max)
    DetailScaffold("Heart", "Resting HR, HRV and zones from your watch", onBack) {
        item {
            val start = state.today.atStartOfDay(zone).toInstant().toEpochMilli()
            val slots = 24 * 6
            val series = MutableList<Float?>(slots) { null }
            today.forEach { b -> val i = ((b.startMs - start) / 600_000).toInt(); if (i in 0 until slots) series[i] = b.avg.toFloat() }
            val peak = today.maxOfOrNull { it.max }
            ChartCard("Today", if (today.isEmpty()) "No heart-rate data yet today." else "Range ${today.minOf { it.min }}–$peak bpm · your max is about $max bpm (age ${state.profile.ageYears})") {
                LineChart(
                    listOf(LineSeries(series, fc.heart, width = 2.dp)),
                    bands = floors.mapIndexed { i, f -> HBand(f.toFloat(), (floors.getOrNull(i + 1) ?: max).toFloat(), fc.zones[i].copy(alpha = 0.12f)) },
                    yMin = 40f, yMax = max.toFloat(), height = 160.dp,
                    xLabels = listOf("00", "", "06", "", "12", "", "18", ""),
                    scrubLabel = { i -> "%02d:%02d · %s".format(i / 6, i % 6 * 10, series[i]?.let { "${it.roundToInt()} bpm" } ?: "—") },
                    description = "Heart rate today over zone bands",
                )
                Legend(ZONE_NAMES.mapIndexed { i, n -> n to fc.zones[i] })
            }
        }
        item {
            val zones = zoneMinutes(week, max)
            val hrWho = whoMinutes(zones)
            val weekStart = state.today.minusDays(6)
            val workoutWho = whoMinutesFromWorkouts(state.workouts.filter {
                !Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate().isBefore(weekStart)
            })
            val who = maxOf(hrWho, workoutWho)
            ChartCard("Active minutes this week", whoHeadline(who)) {
                BulletBar(who.toFloat(), WHO_WEEKLY_MINUTES.toFloat(), fc.push)
                Text("$who of $WHO_WEEKLY_MINUTES WHO minutes (vigorous minutes count double)", style = MaterialTheme.typography.labelSmall,
                    color = fc.muted, modifier = Modifier.padding(top = 4.dp))
                if (zones.any { it > 0 }) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalStackBar(zones.mapIndexed { i, m -> m.toFloat() to fc.zones[i] })
                    Legend(zones.mapIndexed { i, m -> "Z${i + 1} ${m}m" to fc.zones[i] })
                    Text("Estimated from 10-minute heart-rate averages.", style = MaterialTheme.typography.labelSmall, color = fc.muted)
                }
            }
        }
        item {
            val rhr = window.map { it.restingHr?.toFloat() }
            val base = state.history.dropLast(1).takeLast(14).mapNotNull { it.restingHr?.toDouble() }
            val range = normalRange(base)
            ChartCard("Resting heart rate", baselineHeadline("Resting HR", state.todayActivity.restingHr?.toDouble(), base, higherIsBetter = false, unit = "bpm"),
                trailing = { PeriodChips(period, { period = it }) }) {
                LineChart(
                    listOf(LineSeries(rhr, fc.heart, fill = true)),
                    bands = listOfNotNull(range?.let { HBand(it.start.toFloat(), it.endInclusive.toFloat(), fc.baselineBand) }),
                    highlightOutside = range?.let { it.start.toFloat()..it.endInclusive.toFloat() },
                    xLabels = dayLabels(window),
                    scrubLabel = { i -> "${window[i].date.format(DAY)} · ${rhr[i]?.roundToInt()?.let { "$it bpm" } ?: "—"}" },
                    description = "Resting heart rate with your normal range shaded",
                )
                Text("Shaded: your normal range (last 14 days)", style = MaterialTheme.typography.labelSmall, color = fc.muted)
            }
        }
        item {
            val hrv = window.map { it.night.hrvMs?.toFloat() }
            val base = state.history.dropLast(1).takeLast(14).mapNotNull { it.night.hrvMs }
            val range = normalRange(base)
            ChartCard("Heart rate variability", if (hrv.all { it == null }) "Your watch app doesn't share HRV with Health Connect yet."
                else baselineHeadline("HRV", state.todayActivity.night.hrvMs, base, higherIsBetter = true, unit = "ms")) {
                LineChart(
                    listOf(LineSeries(hrv, fc.hrv, fill = true)),
                    bands = listOfNotNull(range?.let { HBand(it.start.toFloat(), it.endInclusive.toFloat(), fc.baselineBand) }),
                    highlightOutside = range?.let { it.start.toFloat()..it.endInclusive.toFloat() },
                    xLabels = dayLabels(window),
                    scrubLabel = { i -> "${window[i].date.format(DAY)} · ${hrv[i]?.roundToInt()?.let { "$it ms" } ?: "—"}" },
                    description = "Overnight HRV with your normal range shaded",
                )
            }
        }
        item {
            val ranges = window.map { d -> if (d.hrMin != null && d.hrMax != null) d.hrMin.toFloat()..d.hrMax.toFloat() else null }
            ChartCard("Daily heart-rate range", "Lowest to highest heart rate each day") {
                RangeBars(ranges, fc.heart, labels = dayLabels(window),
                    scrubLabel = { i -> ranges[i]?.let { "${it.start.roundToInt()}–${it.endInclusive.roundToInt()} bpm" } ?: "—" })
            }
        }
        val vo2 = state.history.map { it.vo2max?.toFloat() }
        if (vo2.any { it != null }) {
            item {
                val latest = vo2.lastOrNull { it != null }
                ChartCard("Cardio fitness (VO₂ max)", "Latest ${latest?.roundToInt()} ml/kg/min — rises with regular cardio") {
                    LineChart(listOf(LineSeries(vo2, fc.workout, dots = true)), xLabels = dayLabels(state.history),
                        description = "VO2 max estimates from your watch")
                }
            }
        }
        item {
            Text("Heart data comes from your watch and is for fitness guidance only — not a medical device.",
                style = MaterialTheme.typography.bodySmall, color = fc.muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        }
    }
}
