package com.perfectframe.camera.ui

import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perfectframe.camera.camera.CameraController
import com.perfectframe.camera.camera.CaptureMode
import com.perfectframe.camera.composition.CompositionEngine
import com.perfectframe.camera.composition.NormRect
import com.perfectframe.camera.editor.FilmLook
import com.perfectframe.camera.editor.processCapturedPhoto
import com.perfectframe.camera.film.DevelopingOverlay
import com.perfectframe.camera.film.FilmShelf
import com.perfectframe.camera.film.FilmRollController
import com.perfectframe.camera.film.FilmStockBadge
import com.perfectframe.camera.film.FilmViewfinderFrame
import com.perfectframe.camera.film.FrameCounter
import com.perfectframe.camera.film.RollPhase
import com.perfectframe.camera.gallery.GalleryScreen
import com.perfectframe.camera.sensors.LevelDetector
import com.perfectframe.camera.ui.components.GlassSurface
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
import com.perfectframe.camera.ui.theme.Surface0
import com.perfectframe.camera.ui.theme.TextPrimary
import com.perfectframe.camera.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The single camera screen, now with two shooting experiences (spec extension):
 *
 * - **Frameica**: the smart autoframer. Tap inside the suggested frame and the final saved photo
 *   is cropped to exactly that composition, straightened level, auto-toned, and finished with a
 *   clean look — showing *only* the perfect frame, not the wider scene it was taken from.
 * - **Film**: a skeuomorphic ritual. Load a stock from the shelf, shoot, wind, and wait through a
 *   real ~30s develop before the photo (in that stock's look) appears. No autoframing — a real
 *   camera doesn't reframe for you.
 *
 * Both share the same camera pipeline, exposure HUD, zoom/aspect controls, and level/grid.
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
    val filmRoll = remember { FilmRollController() }

    val capabilities by controller.capabilities.collectAsStateWithLifecycle()
    val exposure by controller.exposure.state.collectAsStateWithLifecycle()
    val subjects by controller.subjectDetector.subjects.collectAsStateWithLifecycle()
    val level by levelDetector.state.collectAsStateWithLifecycle()
    val zoom by controller.zoom.collectAsStateWithLifecycle()
    val lastCapture by controller.lastCapture.collectAsStateWithLifecycle()
    val roll by filmRoll.state.collectAsStateWithLifecycle()

    var settings by remember { mutableStateOf(ViewfinderSettings()) }
    var showSettings by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var showFilmShelf by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(controller.currentAspect()) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var mode by remember { mutableStateOf(CaptureMode.FRAMEICA) }

    val levelTolerance = if (settings.strictLevel) 1.5f else 3.0f
    LaunchedEffect(levelTolerance) { levelDetector.setTolerance(levelTolerance) }

    val frame by remember(levelTolerance) {
        derivedStateOf { engine.compute(subjects, level.rollDegrees, levelTolerance) }
    }
    val showFrame = mode == CaptureMode.FRAMEICA && settings.showGuidance && engine.shouldShow(frame)
    val primarySubject = remember(subjects) { subjects.maxByOrNull { it.prominence }?.box }

    val frameState = rememberUpdatedState(frame)
    val showFrameState = rememberUpdatedState(showFrame)
    val levelState = rememberUpdatedState(level)

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

    fun flashScreen() {
        scope.launch { flash.snapTo(0.85f); flash.animateTo(0f, tween(360)) }
    }

    fun captureManual() {
        scope.launch {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            flashScreen()
            controller.capture()
        }
    }

    /** The Frameica exact-crop flow: capture privately, then crop+straighten+auto-tone+look. */
    fun captureFrameica(box: NormRect) {
        scope.launch {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            flashScreen()
            val file = controller.captureToTempFile() ?: return@launch
            val rollDegrees = levelState.value.rollDegrees
            val uri = processCapturedPhoto(
                context = context,
                sourceFile = file,
                cropBox = box,
                straightenDegrees = rollDegrees,
                autoTone = true,
                look = FilmLook.CLEAR,
            )
            if (uri != null) controller.noteExternalCapture(uri)
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

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(mode) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            if (zoomChange != 1f) controller.scaleZoom(zoomChange)
                        }
                    }
                    .pointerInput(mode) {
                        detectTapGestures { offset ->
                            val point = previewView.meteringPointFactory
                                .createPoint(offset.x, offset.y)
                            controller.startFocusAndMetering(
                                FocusMeteringAction.Builder(point).build(),
                            )
                            focusPoint = offset

                            if (mode == CaptureMode.FRAMEICA) {
                                val f = frameState.value
                                val box = f.box
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val inside = showFrameState.value &&
                                    offset.x in (box.left * w)..(box.right * w) &&
                                    offset.y in (box.top * h)..(box.bottom * h)
                                if (inside) captureFrameica(box)
                            }
                        }
                    },
            )

            if (mode == CaptureMode.FRAMEICA) {
                FramingOverlay(
                    frame = frame,
                    subjectBox = if (settings.showGuidance) primarySubject else null,
                    show = showFrame,
                    modifier = Modifier.fillMaxSize(),
                )
                if (settings.showGrid && !showFrame) {
                    ThirdsGrid(modifier = Modifier.fillMaxSize())
                }
            } else {
                FilmViewfinderFrame(modifier = Modifier.fillMaxSize())
                if (settings.showGrid) {
                    ThirdsGrid(modifier = Modifier.fillMaxSize())
                }
            }
            if (settings.showHorizon) {
                HorizonIndicator(level = level, modifier = Modifier.fillMaxSize())
            }

            FocusRing(focusPoint = focusPoint, onFinished = { focusPoint = null })

            AnimatedVisibility(
                visible = mode == CaptureMode.FILM && roll.phase == RollPhase.DEVELOPING,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                DevelopingOverlay(progress = roll.developProgress, totalSeconds = 30)
            }
        }

        // ---- Top chrome ------------------------------------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 10.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassSurface(shape = CircleShape) {
                    Box(
                        modifier = Modifier.size(44.dp).clickable { showSettings = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = TextPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                if (mode == CaptureMode.FRAMEICA) {
                    AspectRatioSelector(current = aspect, onSelect = { aspect = it })
                } else {
                    FilmStockBadge(roll = roll, onChangeRoll = { showFilmShelf = true })
                }
            }
            MetadataHud(exposure = exposure, capabilities = capabilities)
        }

        // ---- Bottom chrome ----------------------------------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (mode == CaptureMode.FRAMEICA) {
                GuidanceBar(
                    frame = frame,
                    visible = settings.showGuidance && frame.hasSubject,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
            } else {
                FrameCounter(roll = roll)
            }
            ZoomBar(zoom = zoom, onJump = { controller.jumpToZoom(it) })
            ModeSwitcher(mode = mode, onSelect = { mode = it })
            ShutterBar(
                isIdeal = mode == CaptureMode.FRAMEICA && frame.isIdeal,
                lastCapture = lastCapture,
                shutterEnabled = mode == CaptureMode.FRAMEICA || !roll.isBusy,
                onCapture = {
                    when (mode) {
                        CaptureMode.FRAMEICA -> captureManual()
                        CaptureMode.FILM -> {
                            if (roll.stock == null || roll.phase == RollPhase.FINISHED) {
                                showFilmShelf = true
                            } else {
                                scope.launch {
                                    filmRoll.shoot(context, controller) { uri ->
                                        controller.noteExternalCapture(uri)
                                    }
                                }
                            }
                        }
                    }
                },
                onOpenGallery = { showGallery = true },
                onSwitchCamera = { scope.launch { controller.switchCamera() } },
                modifier = Modifier.fillMaxWidth(),
            )
        }

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

    if (showFilmShelf) {
        FilmShelf(
            onLoad = { stock -> filmRoll.loadRoll(stock); showFilmShelf = false },
            onDismiss = { showFilmShelf = false },
        )
    }
}

/** Small segmented switcher between the two shooting experiences. */
@Composable
private fun ModeSwitcher(mode: CaptureMode, onSelect: (CaptureMode) -> Unit) {
    GlassSurface(shape = RoundedCornerShape(50)) {
        Row(modifier = Modifier.padding(4.dp)) {
            CaptureMode.entries.forEach { option ->
                val active = option == mode
                Text(
                    text = option.label,
                    color = if (active) Surface0 else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) Accent else Color.Transparent)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
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
