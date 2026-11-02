package com.fitagent.ui.reps

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.fitagent.domain.Joint
import com.fitagent.domain.Landmark
import com.fitagent.domain.PoseFrame
import com.fitagent.domain.RepCounter
import com.fitagent.domain.RepExercise
import com.fitagent.ui.VoiceCoach
import com.fitagent.ui.theme.Fit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors

private val ML_JOINTS = mapOf(
    PoseLandmark.LEFT_SHOULDER to Joint.SHOULDER_L, PoseLandmark.RIGHT_SHOULDER to Joint.SHOULDER_R,
    PoseLandmark.LEFT_ELBOW to Joint.ELBOW_L, PoseLandmark.RIGHT_ELBOW to Joint.ELBOW_R,
    PoseLandmark.LEFT_WRIST to Joint.WRIST_L, PoseLandmark.RIGHT_WRIST to Joint.WRIST_R,
    PoseLandmark.LEFT_HIP to Joint.HIP_L, PoseLandmark.RIGHT_HIP to Joint.HIP_R,
    PoseLandmark.LEFT_KNEE to Joint.KNEE_L, PoseLandmark.RIGHT_KNEE to Joint.KNEE_R,
    PoseLandmark.LEFT_ANKLE to Joint.ANKLE_L, PoseLandmark.RIGHT_ANKLE to Joint.ANKLE_R,
)

private val BONES = listOf(
    Joint.SHOULDER_L to Joint.ELBOW_L, Joint.ELBOW_L to Joint.WRIST_L, Joint.SHOULDER_R to Joint.ELBOW_R, Joint.ELBOW_R to Joint.WRIST_R,
    Joint.SHOULDER_L to Joint.SHOULDER_R, Joint.SHOULDER_L to Joint.HIP_L, Joint.SHOULDER_R to Joint.HIP_R, Joint.HIP_L to Joint.HIP_R,
    Joint.HIP_L to Joint.KNEE_L, Joint.KNEE_L to Joint.ANKLE_L, Joint.HIP_R to Joint.KNEE_R, Joint.KNEE_R to Joint.ANKLE_R,
)

/**
 * Camera rep counter (G6): counts squats, push-ups, lunges and jumping jacks from the camera with on-device pose
 * detection, and gives one form tip at a time. Nothing is recorded or uploaded.
 */
@Composable
fun RepCounterScreen(voiceEnabled: Boolean, onSave: (RepExercise, Int, Long) -> Unit, onClose: () -> Unit) {
    val fc = Fit.colors
    var exercise by remember { mutableStateOf(RepExercise.SQUAT) }
    var front by remember { mutableStateOf(true) }
    var count by remember { mutableIntStateOf(0) }
    var tip by remember { mutableStateOf<String?>(null) }
    var skeleton by remember { mutableStateOf<Pair<Map<Joint, Landmark>, Pair<Int, Int>>?>(null) }
    var startedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    val inspection = LocalInspectionMode.current
    val context = LocalContext.current
    val coach = remember { if (inspection) null else VoiceCoach(context) }
    DisposableEffect(Unit) { onDispose { coach?.shutdown() } }
    val counter = remember(exercise) { RepCounter(exercise).also { count = 0; tip = null; startedAt = System.currentTimeMillis() } }
    LaunchedEffect(count) { if (voiceEnabled && count > 0) coach?.say("$count") }
    androidx.activity.compose.BackHandler(onBack = onClose)

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            if (!inspection) {
                CameraPose(front) { frame, w, h ->
                    val u = counter.feed(frame)
                    count = u.count
                    if (u.formTip != null) tip = u.formTip
                    skeleton = frame.joints to (w to h)
                }
            }
            skeleton?.let { (joints, size) ->
                Canvas(Modifier.fillMaxSize()) {
                    val (w, h) = size
                    val scale = maxOf(this.size.width / w, this.size.height / h)
                    val ox = (this.size.width - w * scale) / 2
                    val oy = (this.size.height - h * scale) / 2
                    fun at(l: Landmark) = Offset(ox + (if (front) w - l.x else l.x) * scale, oy + l.y * scale)
                    BONES.forEach { (a, b) ->
                        val pa = joints[a]; val pb = joints[b]
                        if (pa != null && pb != null && minOf(pa.confidence, pb.confidence) > RepCounter.MIN_CONFIDENCE) {
                            drawLine(fc.steps, at(pa), at(pb), 6.dp.toPx())
                        }
                    }
                }
            }
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                    Text("Rep counter", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
                    IconButton(onClick = { front = !front }) { Icon(Icons.Rounded.Cameraswitch, "Switch camera", tint = Color.White) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RepExercise.entries.forEach { e -> FilterChip(e == exercise, { exercise = e }, label = { Text(e.label) }) }
                }
                Spacer(Modifier.weight(1f))
                tip?.let {
                    Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000)).padding(12.dp))
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("$count", fontSize = 96.sp, fontWeight = FontWeight.Black, color = fc.steps)
                        Text(exercise.label.uppercase(), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    Button(onClick = { onSave(exercise, count, (System.currentTimeMillis() - startedAt) / 1000) }, enabled = count > 0) {
                        Text("Save")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(if (exercise == RepExercise.PUSHUP || exercise == RepExercise.LUNGE) "Prop the phone so the camera sees you from the side."
                    else "Prop the phone so your whole body is in view.",
                    color = Color(0xFFB0B8C4), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Camera preview + ML Kit pose detection (stream mode) on a background thread. */
@Composable
private fun CameraPose(front: Boolean, onPose: (PoseFrame, Int, Int) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember {
        PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { provider?.unbindAll() }
            detector.close()
            executor.shutdown()
        }
    }
    LaunchedEffect(front) {
        val p = ProcessCameraProvider.awaitInstance(context)
        provider = p
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { proxy -> analyse(proxy, detector, onPose) }
        p.unbindAll()
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching { p.bindToLifecycle(lifecycle, selector, preview, analysis) }
    }
    AndroidView({ previewView }, Modifier.fillMaxSize())
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun analyse(proxy: ImageProxy, detector: com.google.mlkit.vision.pose.PoseDetector, onPose: (PoseFrame, Int, Int) -> Unit) {
    val media = proxy.image ?: run { proxy.close(); return }
    val rotation = proxy.imageInfo.rotationDegrees
    val image = InputImage.fromMediaImage(media, rotation)
    val (w, h) = if (rotation % 180 == 0) proxy.width to proxy.height else proxy.height to proxy.width
    val time = System.currentTimeMillis()
    detector.process(image)
        .addOnSuccessListener { pose ->
            val joints = ML_JOINTS.mapNotNull { (type, joint) ->
                pose.getPoseLandmark(type)?.let { joint to Landmark(it.position.x, it.position.y, it.inFrameLikelihood) }
            }.toMap()
            if (joints.isNotEmpty()) onPose(PoseFrame(time, joints), w, h)
        }
        .addOnCompleteListener { proxy.close() }
}

/** Placeholder card used where a camera isn't available (previews/tests). */
@Composable
fun RepCounterCard(onOpen: () -> Unit) {
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Camera rep counter", style = MaterialTheme.typography.titleMedium)
                Text("Counts squats, push-ups, lunges and jacks — and checks your form. Video stays on the phone.",
                    style = MaterialTheme.typography.bodySmall, color = Fit.colors.muted)
            }
            Spacer(Modifier.width(12.dp))
            Text("📷", fontSize = 28.sp)
        }
    }
}
