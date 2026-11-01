package com.fitagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitagent.data.remote.dto.PlanRes
import com.fitagent.domain.BusyBlock
import com.fitagent.domain.Timetable
import com.fitagent.domain.code
import com.fitagent.domain.freeSlots
import com.fitagent.domain.hhmm
import com.fitagent.domain.parseHhmm
import com.fitagent.ui.charts.Legend
import com.fitagent.ui.charts.TimelineBlock
import com.fitagent.ui.charts.WeekTimeline
import com.fitagent.ui.components.AppCard
import com.fitagent.ui.components.Pill
import com.fitagent.ui.theme.Fit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Exam mode (S1/S2): classes, exams and bedtime → a plan that fits your free time and goes easy around exams. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimetableScreen(
    timetable: Timetable,
    today: LocalDate,
    plan: PlanRes?,
    onUpdate: ((Timetable) -> Timetable) -> Unit,
    onImportIcs: () -> Unit,
    onBack: () -> Unit,
) {
    val fc = Fit.colors
    var addClass by remember { mutableStateOf(false) }
    var addExam by remember { mutableStateOf(false) }
    val upcoming = timetable.upcomingExams(today)
    DetailScaffold("Exam mode", "Fit training around classes and exams", onBack) {
        item {
            val next = upcoming.firstOrNull()
            AppCard(color = if (timetable.examWeek(today)) fc.recover.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.School, null, tint = if (next != null) fc.recover else fc.muted)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                next == null -> "No exams coming up"
                                next == today -> "Exam today — good luck!"
                                else -> "Next exam in ${ChronoUnit.DAYS.between(today, next)} day${if (ChronoUnit.DAYS.between(today, next) == 1L) "" else "s"}"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (timetable.examWeek(today)) "Exam week: your plan uses short sessions in free slots, keeps exam days easy and protects sleep."
                            else "Add your classes and exams — your coach plans around them. Class names stay on this phone.",
                            style = MaterialTheme.typography.bodySmall, color = fc.muted,
                        )
                    }
                }
            }
        }
        item {
            val days = DayOfWeek.entries
            val blocks = buildList {
                // Exam days first, as a red wash behind the whole column.
                upcoming.filter { it.isBefore(today.plusDays(7)) }.forEach { add(TimelineBlock(it.dayOfWeek.ordinal, 7 * 60, 22 * 60, fc.recover.copy(alpha = 0.22f))) }
                freeSlots(timetable).forEach { add(TimelineBlock(it.day.ordinal, it.startMin, it.endMin, fc.push.copy(alpha = 0.18f))) }
                timetable.busy.forEach { add(TimelineBlock(it.day.ordinal, it.startMin, it.endMin, fc.muted.copy(alpha = 0.55f), it.label)) }
                plan?.plan?.sessions?.forEach { s ->
                    val start = s.startTime?.let(::parseHhmm) ?: return@forEach
                    val day = days.indexOfFirst { it.code == s.day }.takeIf { it >= 0 } ?: return@forEach
                    if (s.type != "REST") add(TimelineBlock(day, start, start + s.minutes, fc.steps))
                }
            }
            val free = freeSlots(timetable).sumOf { it.minutes } / 60
            ChartCard("Your week", if (timetable.busy.isEmpty()) "Add classes to see your free time." else "About $free free hours a week between 07:00 and 22:00") {
                WeekTimeline(blocks, days.map { it.code.take(2) }, description = "Classes, free time, exams and planned sessions this week")
                Legend(listOf("Class" to fc.muted, "Free" to fc.push, "Planned" to fc.steps, "Exam" to fc.recover))
            }
        }
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Classes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onImportIcs) { Icon(Icons.Rounded.EventNote, null); Spacer(Modifier.width(4.dp)); Text("Import .ics") }
                    TextButton(onClick = { addClass = true }) { Icon(Icons.Rounded.Add, null); Text("Add") }
                }
                if (timetable.busy.isEmpty()) Text("No classes yet.", style = MaterialTheme.typography.bodySmall, color = fc.muted)
                timetable.busy.sortedWith(compareBy({ it.day }, { it.startMin })).forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.day.code, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                        Text("${hhmm(b.startMin)}–${hhmm(b.endMin)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(104.dp))
                        Text(b.label.ifBlank { "Class" }, style = MaterialTheme.typography.bodySmall, color = fc.muted, modifier = Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { onUpdate { t -> t.copy(busy = t.busy - b) } }) { Icon(Icons.Rounded.Close, "Remove") }
                    }
                }
            }
        }
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Exams", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { addExam = true }) { Icon(Icons.Rounded.Add, null); Text("Add") }
                }
                if (upcoming.isEmpty()) Text("No upcoming exams.", style = MaterialTheme.typography.bodySmall, color = fc.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    upcoming.forEach { d ->
                        FilterChip(selected = true, onClick = { onUpdate { t -> t.copy(examDates = t.examDates - d) } },
                            label = { Text(d.format(DateTimeFormatter.ofPattern("EEE d MMM"))) },
                            trailingIcon = { Icon(Icons.Rounded.Close, "Remove") })
                    }
                }
            }
        }
        item {
            AppCard {
                Text("Bedtime", style = MaterialTheme.typography.titleMedium)
                Text("Sessions end at least 2 hours before this.", style = MaterialTheme.typography.bodySmall, color = fc.muted)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(22 * 60, 22 * 60 + 30, 23 * 60, 23 * 60 + 30, 0, 30).forEach { m ->
                        FilterChip(selected = timetable.bedtimeMin == m, onClick = {
                            onUpdate { t -> t.copy(bedtimeMin = if (t.bedtimeMin == m) null else m) }
                        }, label = { Text(hhmm(m)) })
                    }
                }
            }
        }
        if (!timetable.isEmpty) {
            item {
                OutlinedButton(onClick = { onUpdate { Timetable() } }, modifier = Modifier.fillMaxWidth()) { Text("Clear timetable") }
            }
        }
    }
    if (addClass) AddClassDialog(onDismiss = { addClass = false }) { block -> onUpdate { t -> t.copy(busy = t.busy + block) }; addClass = false }
    if (addExam) AddExamDialog(today, onDismiss = { addExam = false }) { d -> onUpdate { t -> t.copy(examDates = (t.examDates + d).distinct()) }; addExam = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddClassDialog(onDismiss: () -> Unit, onAdd: (BusyBlock) -> Unit) {
    var days by remember { mutableStateOf(setOf(DayOfWeek.MONDAY)) }
    var start by remember { mutableStateOf("09:00") }
    var end by remember { mutableStateOf("10:00") }
    var label by remember { mutableStateOf("") }
    val s = parseHhmm(start); val e = parseHhmm(end)
    val valid = s != null && e != null && e > s && days.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add class") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(d in days, { days = if (d in days) days - d else days + d }, label = { Text(d.code.take(2)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("Start") }, singleLine = true, isError = s == null)
                    OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("End") }, singleLine = true, isError = e == null || (s != null && e <= s))
                }
                OutlinedTextField(label, { label = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Name (stays on this phone)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = { days.forEach { d -> onAdd(BusyBlock(d, s!!, e!!, label.trim())) } }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExamDialog(today: LocalDate, onDismiss: () -> Unit, onAdd: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = today.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onAdd(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("Add exam") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

/** Train-tab entry card. */
@Composable
fun ExamModeCard(timetable: Timetable, today: LocalDate, onOpen: () -> Unit) {
    val next = timetable.upcomingExams(today).firstOrNull()
    val fc = Fit.colors
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.School, null, tint = if (timetable.examWeek(today)) fc.recover else fc.workout)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Exam mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        timetable.isEmpty -> "Add your timetable — plans fit your free time"
                        next != null -> "Next exam ${next.format(DateTimeFormatter.ofPattern("EEE d MMM"))} · ${timetable.busy.size} classes"
                        else -> "${timetable.busy.size} classes · no exams coming up"
                    },
                    style = MaterialTheme.typography.bodySmall, color = fc.muted,
                )
            }
            if (timetable.examWeek(today)) Pill("Exam week", fc.recover)
            OutlinedButton(onClick = onOpen, modifier = Modifier.padding(start = 8.dp)) { Text(if (timetable.isEmpty) "Set up" else "Open") }
        }
    }
}
