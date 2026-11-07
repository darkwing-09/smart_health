package com.fitagent.report

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.fitagent.domain.DayActivity
import com.fitagent.domain.Goals
import com.fitagent.domain.Profile
import com.fitagent.domain.Readiness
import com.fitagent.domain.Workout
import com.fitagent.domain.formatCount
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** What goes on the report. [readiness] is null when there isn't a real night of watch data yet. */
data class DailyReportData(
    val day: LocalDate,
    val profile: Profile,
    val watchLabel: String?,
    val today: DayActivity?,
    val week: List<DayActivity>,
    val readiness: Readiness?,
    val workouts: List<Workout>,
    val streak: Int,
) {
    val hasData: Boolean get() = watchLabel != null && today?.hasData == true
}

/** One-line caption for WhatsApp; same wording as the server's. */
fun reportSummary(d: DailyReportData): String {
    val date = d.day.format(DateTimeFormatter.ofPattern("d MMM"))
    val t = d.today
    if (!d.hasData || t == null) return "No watch data for $date — sync your watch."
    return buildString {
        append("$date: ")
        d.readiness?.let { append("readiness ${it.score} (${it.band.label}), ") }
        append("${formatCount(t.steps)} steps")
        t.sleepMinutes?.let { append(", slept ${it / 60} h ${"%02d".format(it % 60)} m") }
        append(".")
    }
}

/**
 * Builds the daily report as an A4 PDF on the phone, no server needed. Same layout as the server's
 * (backend/app/services/report.py). Never prints sample values: missing metrics show "—".
 */
object DailyReportPdf {
    private const val W = 595 // A4 in points
    private const val H = 842
    private const val M = 44f
    private val INK = 0xFF0A0D12.toInt()
    private val MUTED = 0xFF5B6472.toInt()
    private val PANEL = 0xFFF5F7FA.toInt()
    private val TRACK = 0xFFE6E9EE.toInt()
    private val LIME = 0xFF8DB800.toInt()
    private val CORAL = 0xFFFF6B4A.toInt()
    private val CYAN = 0xFF14A8BA.toInt()
    private val VIOLET = 0xFF7B61FF.toInt()

    fun write(context: Context, d: DailyReportData): File {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        draw(page.canvas, d)
        doc.finishPage(page)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "FitAgent-report-${d.day}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** Opens the share sheet with the PDF; WhatsApp is preferred when installed and [toWhatsApp]. */
    fun share(context: Context, file: File, caption: String, toWhatsApp: Boolean) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, caption)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val wa = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
        val intent = if (toWhatsApp && wa != null) Intent(send).setPackage(wa) else Intent.createChooser(send, "Share your report")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens the PDF in the phone's PDF viewer. */
    fun view(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "Open report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun paint(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    internal fun draw(c: Canvas, d: DailyReportData) {
        c.drawColor(0xFFFFFFFF.toInt())
        var y = M + 20
        c.drawText("FitAgent · Daily report", M, y, paint(INK, 20f, true))
        val right = paint(INK, 10f, true).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(d.day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")), W - M, y - 8, right)
        val who = listOfNotNull(d.profile.name.ifBlank { null }, "${d.profile.ageYears} yrs", d.profile.city.ifBlank { null })
            .joinToString(" · ")
        c.drawText(who, W - M, y + 6, paint(MUTED, 9.5f).apply { textAlign = Paint.Align.RIGHT })
        y += 16
        c.drawRect(M, y, W - M, y + 2, fill(LIME))
        y += 16
        d.watchLabel?.let { c.drawText("Data from your watch via $it.", M, y, paint(MUTED, 8.5f)); y += 14 }

        val t = d.today
        if (!d.hasData || t == null) {
            y += 20
            c.drawText("No watch data for this day", M, y, paint(INK, 13f, true))
            y += 18
            c.drawText("Your watch didn't sync anything for this date, so there is nothing to report.", M, y, paint(INK, 10f))
            c.drawText("Open your watch app (NoiseFit, boAt Crest…) so it syncs, then open FitAgent.", M, y + 14, paint(INK, 10f))
            return
        }

        // Readiness
        val r = d.readiness
        val heroH = 64f
        c.drawRect(M, y, W - M, y + heroH, fill(PANEL))
        val bandColor = when (r?.band?.name) { "PUSH" -> 0xFF14B87A.toInt(); "RECOVER" -> 0xFFE04848.toInt(); else -> 0xFFE0A800.toInt() }
        c.drawRect(M, y, M + 4, y + heroH, fill(if (r != null) bandColor else MUTED))
        if (r != null) {
            c.drawText("${r.score}", M + 14, y + 44, paint(bandColor, 34f, true))
            c.drawText("Readiness · ${r.band.label}", M + 90, y + 18, paint(INK, 10.5f, true))
            r.reasons.take(3).forEachIndexed { i, s -> c.drawText("• $s".take(95), M + 90, y + 33 + i * 12, paint(INK, 9f)) }
        } else {
            c.drawText("Readiness", M + 14, y + 26, paint(INK, 11f, true))
            c.drawText("Needs a night of watch data (sleep or resting heart rate).", M + 14, y + 42, paint(MUTED, 9.5f))
        }
        y += heroH + 10

        // Tiles 4×2
        val g = d.profile.goals
        fun pct(v: Number, goal: Int) = if (goal > 0) "${(100 * v.toDouble() / goal).toInt()}% of ${formatCount(goal.toLong())}" else ""
        val tiles = listOf(
            Triple("STEPS", formatCount(t.steps), pct(t.steps, g.dailySteps)) to LIME,
            Triple("ACTIVE ENERGY", "${t.activeKcal} kcal", pct(t.activeKcal, g.dailyActiveKcal)) to CORAL,
            Triple("WORKOUT", "${t.workoutMinutes} min", pct(t.workoutMinutes, g.dailyWorkoutMinutes)) to CYAN,
            Triple("DISTANCE", if (t.distanceMeters > 0) "%.2f km".format(t.distanceMeters / 1000) else "—", "") to LIME,
            Triple("SLEEP", t.sleepMinutes?.let { "${it / 60} h ${"%02d".format(it % 60)} m" } ?: "—",
                t.sleepStages?.takeIf { it.deep > 0 }?.let { "deep ${it.deep / 60} h ${"%02d".format(it.deep % 60)} m" } ?: "") to VIOLET,
            Triple("HEART RATE", (t.avgHr ?: t.restingHr)?.let { "$it bpm" } ?: "—",
                listOfNotNull(t.restingHr?.let { "resting $it" },
                    if (t.hrMin != null && t.hrMax != null) "range ${t.hrMin}–${t.hrMax}" else null).joinToString(" · ")) to CORAL,
            Triple("HRV", t.night.hrvMs?.let { "${it.toInt()} ms" } ?: "—", "") to VIOLET,
            Triple("BLOOD OXYGEN", t.night.spo2Avg?.let { "${it.toInt()} %" } ?: "—",
                t.night.spo2Min?.let { "min ${it.toInt()} %" } ?: "") to CYAN,
        )
        val tw = (W - 2 * M - 3 * 7) / 4
        val th = 52f
        tiles.forEachIndexed { i, (txt, color) ->
            val x = M + (i % 4) * (tw + 7)
            val ty = y + (i / 4) * (th + 7)
            c.drawRect(x, ty, x + tw, ty + th, fill(PANEL))
            c.drawRect(x, ty, x + 3, ty + th, fill(color))
            c.drawText(txt.first, x + 10, ty + 14, paint(MUTED, 7.5f))
            c.drawText(txt.second, x + 10, ty + 33, paint(INK, 15f, true))
            c.drawText(txt.third, x + 10, ty + 46, paint(MUTED, 7.5f))
        }
        y += 2 * th + 7 + 22

        // Charts
        c.drawText("Your week", M, y, paint(INK, 12f, true))
        y += 10
        val days = (6 downTo 0).map { d.day.minusDays(it.toLong()) }
        val byDay = d.week.associateBy { it.date }
        val cw = (W - 2 * M - 16) / 2
        bars(c, M, y, cw, 130f, "Steps · last 7 days", days, days.map { byDay[it]?.steps?.toFloat() }, LIME, g.dailySteps.toFloat())
        bars(c, M + cw + 16, y, cw, 130f, "Sleep (hours) · last 7 days", days,
            days.map { byDay[it]?.sleepMinutes?.let { m -> m / 60f } }, VIOLET, 8f)
        y += 150
        val met = days.count { byDay[it]?.goalMet(g) == true }
        c.drawText("Step goal met on $met of 7 days · current streak ${d.streak} day${if (d.streak == 1) "" else "s"}.",
            M, y, paint(INK, 9.5f))
        y += 26

        // Workouts
        c.drawText("Workouts", M, y, paint(INK, 12f, true))
        y += 16
        val zone = ZoneId.systemDefault()
        val todays = d.workouts.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == d.day }
        if (todays.isEmpty()) {
            c.drawText("No workouts recorded.", M, y, paint(INK, 9.5f)); y += 14
        } else {
            listOf("Time" to 0f, "Workout" to 60f, "Minutes" to 340f, "kcal" to 420f).forEach { (h, dx) -> c.drawText(h, M + dx, y, paint(MUTED, 8.5f, true)) }
            y += 4
            c.drawRect(M, y, W - M, y + 0.6f, fill(TRACK))
            y += 13
            todays.take(8).forEach { w ->
                val time = Instant.ofEpochMilli(w.startMs).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
                c.drawText(time, M, y, paint(INK, 9.5f))
                c.drawText((w.title ?: w.type.label).take(48), M + 60, y, paint(INK, 9.5f))
                c.drawText("${w.activeSeconds / 60}", M + 340, y, paint(INK, 9.5f))
                c.drawText("${w.kcal.toInt()}", M + 420, y, paint(INK, 9.5f))
                y += 15
            }
        }
        y += 12
        c.drawText("Tomorrow", M, y, paint(INK, 12f, true))
        y += 16
        c.drawText(tip(r, t, g), M, y, paint(INK, 9.5f))
        c.drawText("FitAgent is a fitness coach, not a medical device. If something feels wrong, talk to a doctor.",
            M, H - M, paint(MUTED, 8f))
    }

    private fun bars(c: Canvas, x: Float, y: Float, w: Float, h: Float, title: String, days: List<LocalDate>,
                     values: List<Float?>, color: Int, goal: Float) {
        c.drawText(title, x, y + 8, paint(INK, 9f, true))
        val top = y + 20
        val bottom = y + h - 12
        val max = (values.filterNotNull() + goal).maxOrNull()!!.coerceAtLeast(1f) * 1.15f
        val slot = w / values.size
        c.drawRect(x, bottom, x + w, bottom + 0.6f, fill(TRACK))
        values.forEachIndexed { i, v ->
            val bx = x + i * slot + slot * 0.18f
            if (v != null && v > 0) c.drawRoundRect(RectF(bx, bottom - (bottom - top) * v / max, bx + slot * 0.64f, bottom), 2f, 2f, fill(color))
            c.drawText(days[i].dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2), bx, bottom + 10, paint(MUTED, 7f))
        }
        val gy = bottom - (bottom - top) * goal / max
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = MUTED; strokeWidth = 0.6f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 2f), 0f)
        }
        c.drawLine(x, gy, x + w, gy, dash)
        c.drawText("goal", x + w - 18, gy - 2, paint(MUTED, 6.5f))
    }

    internal fun tip(r: Readiness?, d: DayActivity, g: Goals): String = when {
        r?.band?.name == "RECOVER" -> "Take it easy: a 20-minute walk and an early night will help more than a hard session."
        d.sleepMinutes != null && d.sleepMinutes < 6 * 60 -> "Short night — aim to be in bed 30 minutes earlier, and keep any workout moderate."
        d.steps < g.dailySteps -> "You were ${formatCount(g.dailySteps - d.steps)} steps short of your goal. A walk after a class or a meal closes it."
        r?.band?.name == "PUSH" -> "You're well recovered — a good day for a harder workout or a run."
        else -> "Keep the rhythm: one 20–30 minute session and your step goal."
    }
}
