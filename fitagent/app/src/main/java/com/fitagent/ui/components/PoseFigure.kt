package com.fitagent.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.fitagent.domain.Exercise
import com.fitagent.domain.Pose
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Looping exercise demo: a side-view figure moving through the exercise's keyframes (eased, ping-pong free). */
@Composable
fun ExerciseAnimation(exercise: Exercise, color: Color, modifier: Modifier = Modifier, paused: Boolean = false, phase: Float? = null) {
    val frames = exercise.keyframes
    val inspection = LocalInspectionMode.current
    val t = if (phase != null) phase else if (inspection || paused || frames.size < 2) 0.5f else {
        val transition = rememberInfiniteTransition(label = exercise.id)
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(exercise.cycleMs, easing = LinearEasing), RepeatMode.Restart), label = "t").value
    }
    val pose = poseAt(frames, t)
    val floor = color.copy(alpha = 0.25f)
    Canvas(modifier.semantics { contentDescription = "Animated demo: ${exercise.name}" }) {
        // Draw in a centred square so the figure keeps its proportions in any box.
        val side = size.minDimension
        val origin = Offset((size.width - side) / 2, (size.height - side) / 2)
        drawLine(floor, origin + Offset(side * 0.04f, side * 0.92f), origin + Offset(side * 0.96f, side * 0.92f), side * 0.012f)
        drawFigure(pose, color, origin, side)
    }
}

/** Pose at phase [t] (0..1) through [frames], cycling back to the first frame, with smoothstep easing. */
fun poseAt(frames: List<Pose>, t: Float): Pose {
    if (frames.size == 1) return frames[0]
    val pos = t * frames.size
    val i = pos.toInt() % frames.size
    val f = pos - pos.toInt()
    val e = f * f * (3 - 2 * f)
    return lerp(frames[i], frames[(i + 1) % frames.size], e)
}

private fun lerp(a: Pose, b: Pose, f: Float): Pose {
    fun l(x: Float, y: Float) = x + (y - x) * f
    return Pose(l(a.hipX, b.hipX), l(a.hipY, b.hipY), l(a.torso, b.torso), l(a.head, b.head),
        l(a.nearThigh, b.nearThigh), l(a.nearShin, b.nearShin), l(a.farThigh, b.farThigh), l(a.farShin, b.farShin),
        l(a.nearArm, b.nearArm), l(a.nearForearm, b.nearForearm), l(a.farArm, b.farArm), l(a.farForearm, b.farForearm))
}

private fun DrawScope.drawFigure(p: Pose, color: Color, origin: Offset, h: Float) {
    val stroke = h * 0.045f
    val far = color.copy(alpha = 0.45f)
    fun dir(deg: Float, len: Float) = Offset(cos(deg * PI / 180).toFloat() * len, sin(deg * PI / 180).toFloat() * len)
    val hip = origin + Offset(h * p.hipX, h * p.hipY)
    val shoulder = hip + dir(p.torso, h * 0.27f)
    val neck = shoulder + dir(p.head, h * 0.04f)
    val head = neck + dir(p.head, h * 0.065f)
    fun limb(from: Offset, a: Float, b: Float, l1: Float, l2: Float, c: Color) {
        val joint = from + dir(a, h * l1)
        val end = joint + dir(b, h * l2)
        drawLine(c, from, joint, stroke, StrokeCap.Round)
        drawLine(c, joint, end, stroke, StrokeCap.Round)
    }
    // Far side first so the near side draws on top.
    limb(hip, p.farThigh, p.farShin, 0.2f, 0.2f, far)
    limb(shoulder, p.farArm, p.farForearm, 0.15f, 0.14f, far)
    drawLine(color, hip, shoulder, stroke * 1.15f, StrokeCap.Round)
    drawLine(color, shoulder, neck, stroke, StrokeCap.Round)
    drawCircle(color, h * 0.06f, head)
    limb(hip, p.nearThigh, p.nearShin, 0.2f, 0.2f, color)
    limb(shoulder, p.nearArm, p.nearForearm, 0.15f, 0.14f, color)
}
