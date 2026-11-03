package com.fitagent.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/*
 * Camera rep counter (G6). Pose landmarks come from on-device ML Kit; this file is pure logic so it can be tested
 * with synthetic poses. A rep is a full down → up cycle of one joint angle, with hysteresis and a minimum duration
 * so jitter can't count twice. Frames are never stored.
 */

enum class Joint { SHOULDER_L, SHOULDER_R, ELBOW_L, ELBOW_R, WRIST_L, WRIST_R, HIP_L, HIP_R, KNEE_L, KNEE_R, ANKLE_L, ANKLE_R }

/** Image-space point (any units) with detection confidence 0..1. */
data class Landmark(val x: Float, val y: Float, val confidence: Float)

data class PoseFrame(val timeMs: Long, val joints: Map<Joint, Landmark>)

/** Angle at [b] formed by a–b–c, in degrees (0..180). */
fun jointAngle(a: Landmark, b: Landmark, c: Landmark): Double {
    val v1x = a.x - b.x; val v1y = a.y - b.y
    val v2x = c.x - b.x; val v2y = c.y - b.y
    val n = sqrt((v1x * v1x + v1y * v1y).toDouble()) * sqrt((v2x * v2x + v2y * v2y).toDouble())
    if (n == 0.0) return 180.0
    return Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / n).coerceIn(-1.0, 1.0)))
}

enum class RepExercise(val label: String, val down: Double, val up: Double, val easyFlag: String) {
    /** Knee angle hip–knee–ankle. */
    SQUAT("Squats", 105.0, 160.0, "Go a little deeper — thighs toward parallel"),
    /** Elbow angle shoulder–elbow–wrist. */
    PUSHUP("Push-ups", 95.0, 155.0, "Lower your chest further"),
    /** Front knee angle. */
    LUNGE("Lunges", 105.0, 160.0, "Drop the back knee lower"),
    /** Arm angle hip–shoulder–wrist (raised = large). Inverted: "down" phase is arms up. */
    JUMPING_JACK("Jacks", 140.0, 45.0, "Raise your arms all the way up");

    val inverted: Boolean get() = down > up
}

data class RepUpdate(val count: Int, val angle: Double?, val formTip: String?, val repCompleted: Boolean)

/** [smoothingMs]: time constant of the exponential smoothing, so behaviour is the same at 30 fps or 5 fps. */
class RepCounter(private val exercise: RepExercise, private val minRepMs: Long = 400, private val smoothingMs: Double = 100.0) {
    private var smoothed: Double? = null
    private var lastMs = 0L
    private var inDown = false
    private var downAt = 0L
    private var deepest = Double.NaN
    var count = 0
        private set
    private var tip: String? = null

    fun feed(frame: PoseFrame): RepUpdate {
        val raw = measure(frame) ?: return RepUpdate(count, null, tip, false)
        val alpha = 1 - kotlin.math.exp(-(frame.timeMs - lastMs).coerceAtLeast(1) / smoothingMs)
        val a = smoothed?.let { it + alpha * (raw - it) } ?: raw
        smoothed = a
        lastMs = frame.timeMs
        var completed = false
        val reachedDown = if (exercise.inverted) a >= exercise.down else a <= exercise.down
        val reachedUp = if (exercise.inverted) a <= exercise.up else a >= exercise.up
        if (!inDown) {
            if (reachedDown) { inDown = true; downAt = frame.timeMs; deepest = a }
            else if (nearlyDown(a)) deepest = if (deepest.isNaN()) a else if (exercise.inverted) maxOf(deepest, a) else minOf(deepest, a)
        } else {
            deepest = if (exercise.inverted) maxOf(deepest, a) else minOf(deepest, a)
            if (reachedUp) {
                inDown = false
                if (frame.timeMs - downAt >= minRepMs) {
                    count++
                    completed = true
                    tip = formTip(frame)
                }
                deepest = Double.NaN
            }
        }
        // A movement that turned back before reaching depth: say so instead of silently not counting.
        if (!inDown && reachedUp && !deepest.isNaN() && !completed) {
            tip = exercise.easyFlag
            deepest = Double.NaN
        }
        return RepUpdate(count, a, tip, completed)
    }

    private fun nearlyDown(a: Double): Boolean =
        if (exercise.inverted) a > (exercise.up + exercise.down) / 2 else a < (exercise.up + exercise.down) / 2

    /** Uses whichever side of the body the camera sees better. */
    private fun measure(f: PoseFrame): Double? {
        val sides = listOf(true, false).mapNotNull { left ->
            val (a, b, c) = when (exercise) {
                RepExercise.SQUAT, RepExercise.LUNGE -> Triple(j(left, Joint.HIP_L, Joint.HIP_R), j(left, Joint.KNEE_L, Joint.KNEE_R), j(left, Joint.ANKLE_L, Joint.ANKLE_R))
                RepExercise.PUSHUP -> Triple(j(left, Joint.SHOULDER_L, Joint.SHOULDER_R), j(left, Joint.ELBOW_L, Joint.ELBOW_R), j(left, Joint.WRIST_L, Joint.WRIST_R))
                RepExercise.JUMPING_JACK -> Triple(j(left, Joint.HIP_L, Joint.HIP_R), j(left, Joint.SHOULDER_L, Joint.SHOULDER_R), j(left, Joint.WRIST_L, Joint.WRIST_R))
            }
            val pa = f.joints[a]; val pb = f.joints[b]; val pc = f.joints[c]
            if (pa == null || pb == null || pc == null) return@mapNotNull null
            val conf = minOf(pa.confidence, pb.confidence, pc.confidence)
            if (conf < MIN_CONFIDENCE) null else conf to jointAngle(pa, pb, pc)
        }
        return sides.maxByOrNull { it.first }?.second
    }

    private fun formTip(f: PoseFrame): String? = when (exercise) {
        RepExercise.PUSHUP -> {
            // Body line shoulder–hip–ankle should be close to straight.
            val line = listOf(true, false).mapNotNull { left ->
                val s = f.joints[j(left, Joint.SHOULDER_L, Joint.SHOULDER_R)]; val h = f.joints[j(left, Joint.HIP_L, Joint.HIP_R)]
                val a = f.joints[j(left, Joint.ANKLE_L, Joint.ANKLE_R)]
                if (s == null || h == null || a == null) null else jointAngle(s, h, a)
            }.maxOrNull()
            if (line != null && line < 155) "Keep your hips in line with shoulders and ankles" else null
        }
        RepExercise.SQUAT -> {
            val kneeL = f.joints[Joint.KNEE_L]; val kneeR = f.joints[Joint.KNEE_R]
            val ankleL = f.joints[Joint.ANKLE_L]; val ankleR = f.joints[Joint.ANKLE_R]
            // Facing the camera: knees much closer together than ankles = knees caving in.
            if (kneeL != null && kneeR != null && ankleL != null && ankleR != null &&
                abs(kneeL.x - kneeR.x) < 0.7f * abs(ankleL.x - ankleR.x)) "Push your knees out over your toes" else null
        }
        else -> null
    }

    private fun j(left: Boolean, l: Joint, r: Joint) = if (left) l else r

    companion object {
        const val MIN_CONFIDENCE = 0.5f
    }
}
