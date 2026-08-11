package com.perfectframe.camera.ui

import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import com.perfectframe.camera.gallery.GalleryScreen
import com.perfectframe.camera.sensors.LevelDetector
import com.perfectframe.camera.ui.controls.AspectRatioSelector
import com.perfectframe.camera.ui.controls.ZoomBar
import com.perfectframe.camera.ui.hud.GuidanceBar
import com.perfectframe.camera.ui.hud.MetadataHud
import com.perfectframe.camera.ui.hud.ShutterBar
import com.perfectframe.camera.ui.overlay.FramingOverlay
import com.perfectframe.camera.ui.overlay.HorizonIndicator
import com.perfectframe.camera.ui.overlay.ThirdsGrid
import com.perfectframe.camera.ui.settings.SettingsSheet
import com.perfectframe.camera.ui.settings.ViewfinderSettings
import com.perfectframe.camera.ui.theme.Accent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The single camera screen (spec §3), now WYSIWYG: the viewfinder is letterboxed to the selected
 * capture aspect ratio so what you frame is exactly what's saved. Overlays (framing, horizon,
 * grid, focus ring) live inside that preview box; chrome (HUD, aspect + zoom controls, coaching,
 * shutter) sits in the letterbox margins. Pinch to zoom, tap to focus, and the shutter-bar
 * thumbnail opens the in-app gallery.
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
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
    val zoom by controller.zoom.collectAsStateWithLifecycle()
    val lastCapture by controller.lastCapture.collectAsStateWithLifecycle()

    var settings by remember { mutableStateOf(ViewfinderSettings()) }
    var showSettings by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(controller.currentAspect()) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    val levelTolerance = if (settings.strictLevel) 0.7f else 1.5f
    LaunchedEffect(levelTolerance) { levelDetector.setTolerance(levelTolerance) }

    val frame by remember(levelTolerance) {
        derivedStateOf { engine.compute(subjects, level.rollDegrees, levelTolerance) }
    }
    val showFrame = settings.showGuidance && engine.shouldShow(frame)
    val primarySubject = remember(subjects) { subjects.maxByOrNull { it.prominence }?.box }

    var wasIdeal by remember { mutableStateOf(false) }
    LaunchedEffect(frame.isIdeal) {
        if (frame.isIdeal && !wasIdeal) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        wasIdeal = frame.isIdeal
    }

    val flash = remember { Animatable(0f) }

    LaunchedEffect(aspect) { controller.setAspect(aspect) }
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
            launch { flash.snapTo(0.85f); flash.animateTo(0f, tween(360)) }
            controller.capture()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ---- Letterboxed WYSIWYG preview + overlays ------------------------------------------
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(aspect.previewAspect),
        ) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })

            // Gesture layer: pinch to zoom, tap to focus.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            if (zoomChange != 1f) controller.scaleZoom(zoomChange)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val point = previewView.meteringPointFactory
                                .createPoint(offset.x, offset.y)
                            controller.startFocusAndMetering(
                                FocusMeteringAction.Builder(point).build(),
                            )
                            focusPoint = offset
                        }
                    },
            )

            FramingOverlay(
                frame = frame,
                subjectBox = if (settings.showGuidance) primarySubject else null,
                show = showFrame,
                modifier = Modifier.fillMaxSize(),
            )
            if (settings.showHorizon) {
                HorizonIndicator(level = level, modifier = Modifier.fillMaxSize())
            }
            if (settings.showGrid && !showFrame) {
                ThirdsGrid(modifier = Modifier.fillMaxSize())
            }

            FocusRing(focusPoint = focusPoint, onFinished = { focusPoint = null })
        }

        // ---- Top: metadata HUD + aspect selector ---------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetadataHud(exposure = exposure, capabilities = capabilities)
            AspectRatioSelector(current = aspect, onSelect = { aspect = it })
        }

        // ---- Bottom: coaching + zoom + shutter -----------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GuidanceBar(
                frame = frame,
                visible = settings.showGuidance && frame.hasSubject,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            ZoomBar(zoom = zoom, onJump = { controller.jumpToZoom(it) })
            ShutterBar(
                isIdeal = frame.isIdeal,
                lastCapture = lastCapture,
                onCapture = { capture() },
                onOpenSettings = { showSettings = true },
                onOpenGallery = { showGallery = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- Capture flash -------------------------------------------------------------------
        if (flash.value > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(Color.White.copy(alpha = flash.value)) },
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

    if (showGallery) {
        GalleryScreen(onClose = { showGallery = false })
    }
}

/** A brief shrinking ring at the tapped focus point. */
@Composable
private fun FocusRing(focusPoint: Offset?, onFinished: () -> Unit) {
    if (focusPoint == null) return
    val scale = remember(focusPoint) { Animatable(1.4f) }
    LaunchedEffect(focusPoint) {
        scale.animateTo(1f, tween(220))
        delay(500)
        onFinished()
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = Accent,
            radius = 34.dp.toPx() * scale.value,
            center = focusPoint,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
