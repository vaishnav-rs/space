package com.perfectframe.camera.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perfectframe.camera.camera.CameraController
import com.perfectframe.camera.composition.CompositionEngine
import com.perfectframe.camera.sensors.LevelDetector
import com.perfectframe.camera.ui.hud.GuidanceBar
import com.perfectframe.camera.ui.hud.MetadataHud
import com.perfectframe.camera.ui.hud.ShutterBar
import com.perfectframe.camera.ui.overlay.FramingOverlay
import com.perfectframe.camera.ui.overlay.HorizonIndicator
import com.perfectframe.camera.ui.overlay.ThirdsGrid
import com.perfectframe.camera.ui.settings.SettingsSheet
import com.perfectframe.camera.ui.settings.ViewfinderSettings
import kotlinx.coroutines.launch

/**
 * The single screen (spec §3): a full-bleed viewfinder with everything drawn on top — top
 * metadata HUD, the framing overlay + horizon level in the centre, coaching just above the
 * shutter bar. No navigation, no tabs; the one settings sheet slides up over this.
 *
 * The [CompositionEngine] runs here off the live subject + level state and produces the
 * [com.perfectframe.camera.composition.PerfectFrame] that every overlay reads. Crossing "ideal"
 * fires a single haptic tick; capturing flashes the screen — the two moments of tactile feedback
 * that make the experience feel like a real camera.
 */
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val controller = remember { CameraController(context.applicationContext) }
    val engine = remember { CompositionEngine() }
    val levelDetector = remember { LevelDetector(context.applicationContext) }

    val capabilities by controller.capabilities.collectAsStateWithLifecycle()
    val exposure by controller.exposure.state.collectAsStateWithLifecycle()
    val subjects by controller.subjectDetector.subjects.collectAsStateWithLifecycle()
    val level by levelDetector.state.collectAsStateWithLifecycle()

    var settings by remember { mutableStateOf(ViewfinderSettings()) }
    var showSettings by remember { mutableStateOf(false) }

    val levelTolerance = if (settings.strictLevel) 0.7f else 1.5f
    LaunchedEffect(levelTolerance) { levelDetector.setTolerance(levelTolerance) }

    // The live composition verdict, recomputed whenever subjects or the horizon change.
    val frame by remember(levelTolerance) {
        derivedStateOf { engine.compute(subjects, level.rollDegrees, levelTolerance) }
    }
    val showFrame = settings.showGuidance && engine.shouldShow(frame)
    val primarySubject = remember(subjects) { subjects.maxByOrNull { it.prominence }?.box }

    // Fire one haptic tick on the rising edge of "ideal".
    var wasIdeal by remember { mutableStateOf(false) }
    LaunchedEffect(frame.isIdeal) {
        if (frame.isIdeal && !wasIdeal) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        wasIdeal = frame.isIdeal
    }

    // Shutter flash.
    val flash = remember { Animatable(0f) }

    // Push manual-exposure overrides down to the camera when they change.
    LaunchedEffect(settings.exposureLocked) { controller.setExposureLocked(settings.exposureLocked) }
    LaunchedEffect(settings.evIndex) { controller.setExposureCompensationIndex(settings.evIndex) }

    DisposableEffect(controller) { onDispose { controller.release() } }

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
    LaunchedEffect(lifecycleOwner, previewView) { controller.bind(lifecycleOwner, previewView) }

    fun capture() {
        scope.launch {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            launch { flash.snapTo(0.85f); flash.animateTo(0f, androidx.compose.animation.core.tween(360)) }
            controller.capture()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })

        // Framing overlay + horizon occupy the whole viewfinder.
        FramingOverlay(
            frame = frame,
            subjectBox = if (settings.showGuidance) primarySubject else null,
            show = showFrame,
            modifier = Modifier.fillMaxSize(),
        )
        if (settings.showHorizon) {
            HorizonIndicator(level = level, modifier = Modifier.fillMaxSize())
        }
        // Reuse the framing grid gate for a full-frame grid when guidance is off but grid is on.
        if (settings.showGrid && !showFrame) {
            ThirdsGrid(modifier = Modifier.fillMaxSize())
        }

        // Top HUD.
        MetadataHud(
            exposure = exposure,
            capabilities = capabilities,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp),
        )

        // Coaching just above the shutter bar.
        GuidanceBar(
            frame = frame,
            visible = settings.showGuidance && frame.hasSubject,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 132.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )

        // Bottom control bar.
        ShutterBar(
            isIdeal = frame.isIdeal,
            onCapture = { capture() },
            onOpenSettings = { showSettings = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
        )

        // Capture flash on top of everything.
        if (flash.value > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(Color.White.copy(alpha = flash.value)) }
            )
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            exposureState = controller.cameraExposureState(),
            onChange = { settings = it },
            onDismiss = { showSettings = false },
        )
    }
}
