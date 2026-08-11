package com.perfectframe.camera.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perfectframe.camera.camera.CameraController
import com.perfectframe.camera.sensors.LevelDetector
import kotlin.math.tan
import kotlinx.coroutines.launch

/**
 * The single screen. A full-bleed viewfinder with overlays drawn on top (spec §3).
 *
 * Commit 2: live preview + a temporary debug capability readout and shutter button so the
 * max-resolution capture path is exercisable on device. The premium HUD/overlays replace
 * these placeholders in the UI pass.
 */
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val controller = remember { CameraController(context.applicationContext) }
    val capabilities by controller.capabilities.collectAsStateWithLifecycle()
    val exposure by controller.exposure.state.collectAsStateWithLifecycle()

    val subjects by controller.subjectDetector.subjects.collectAsStateWithLifecycle()

    val levelDetector = remember { LevelDetector(context.applicationContext) }
    val level by levelDetector.state.collectAsStateWithLifecycle()

    // Release camera + ML Kit resources when this screen leaves composition for good.
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    // Register/unregister sensor listeners with the lifecycle to avoid leaking them.
    DisposableEffect(lifecycleOwner, levelDetector) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> levelDetector.start()
                Lifecycle.Event.ON_PAUSE -> levelDetector.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            levelDetector.stop()
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(lifecycleOwner, previewView) {
        controller.bind(lifecycleOwner, previewView)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView }
        )

        // TEMP raw subject boxes (Phase-5 confirmation that detection works, before framing UI).
        Canvas(modifier = Modifier.fillMaxSize()) {
            subjects.forEach { s ->
                val l = s.box.left * size.width
                val t = s.box.top * size.height
                drawRect(
                    color = if (s.kind.name == "FACE") Color(0xFF7DF9C6) else Color(0xFFFFC24B),
                    topLeft = Offset(l, t),
                    size = androidx.compose.ui.geometry.Size(s.box.width * size.width, s.box.height * size.height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                )
            }
        }

        // TEMP basic level line (replaced by the integrated edge indicator in the UI pass):
        // a center horizon line that rotates with device roll and turns accent when level.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val half = size.width * 0.32f
            // Screen-space slope from roll angle; clamp so extreme tilt stays on-screen.
            val slope = tan((-level.rollDegrees).coerceIn(-45f, 45f) * (Math.PI / 180.0)).toFloat()
            val dy = half * slope
            val color = if (level.isLevel) Color(0xFF7DF9C6) else Color(0x99FFFFFF)
            drawLine(
                color = color,
                start = Offset(cx - half, cy + dy),
                end = Offset(cx + half, cy - dy),
                strokeWidth = 4f,
            )
            // Fixed reference dot at true center.
            drawCircle(color = color, radius = 6f, center = Offset(cx, cy))
        }

        // TEMP debug readout (replaced by the glass HUD in the UI pass): honest capability line
        // plus live auto-exposure telemetry and the reasoning for why it settled where it did.
        Text(
            text = buildString {
                append("${exposure.isoLabel}   ${exposure.shutterLabel}   ")
                append("${exposure.apertureLabel}   ${exposure.whiteBalanceLabel}\n")
                append(exposure.reasonLabel).append('\n')
                append(capabilities?.summary() ?: "Reading sensor capabilities…")
            },
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                .background(Color(0x66000000))
                .padding(8.dp)
        )

        // TEMP shutter (replaced by ShutterBar in the UI pass).
        Button(
            onClick = { scope.launch { controller.capture() } },
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
        ) {
            Text("Capture")
        }
    }
}
