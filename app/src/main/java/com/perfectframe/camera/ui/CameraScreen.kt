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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.State
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
import com.perfectframe.camera.composition.PerfectFrame
import com.perfectframe.camera.editor.FilmLook
import com.perfectframe.camera.editor.processCapturedPhoto
import com.perfectframe.camera.film.DevelopingOverlay
import com.perfectframe.camera.film.FilmCameraBody
import com.perfectframe.camera.film.FilmRollController
import com.perfectframe.camera.film.FilmShelf
import com.perfectframe.camera.film.FilmViewfinderFrame
import com.perfectframe.camera.film.RollPhase
import com.perfectframe.camera.gallery.GalleryScreen
import com.perfectframe.camera.sensors.LevelDetector
import com.perfectframe.camera.sensors.LevelState
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
import com.perfectframe.camera.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The single camera screen, now with two entirely distinct shooting experiences:
 *
 * - **Frameica**: the smart autoframer, in the app's own modern glass chrome. Tap inside the
 *   suggested frame and the final saved photo is cropped to exactly that composition, straightened
 *   level, auto-toned, and finished with a clean look.
 * - **Film**: not an app skin over the camera — a physical body ([FilmCameraBody]). Metal top
 *   plate, leatherette shell, an eyepiece-style viewfinder window instead of a full-bleed preview,
 *   an analog frame counter, an exposure needle, and a winding lever that sweeps between shots.
 *   Load a stock, shoot, wind, and wait through a real ~30s develop. No autoframing.
 *
 * Both share the same camera pipeline, exposure engine, and zoom/level/grid.
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
    var isAutoFramingActive by remember { mutableStateOf(false) }
    var priorZoomRatio by remember { mutableStateOf(1f) }

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
    LaunchedEffect(mode) {
        if (mode != CaptureMode.FRAMEICA && isAutoFramingActive) {
            isAutoFramingActive = false
            controller.setZoomRatio(priorZoomRatio)
        }
    }

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
            isAutoFramingActive = false
            controller.setZoomRatio(priorZoomRatio)

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

    fun activateAutoFraming() {
        if (!frame.isIdeal) return
        val currentZoom = zoom
        scope.launch {
            priorZoomRatio = currentZoom.ratio
            isAutoFramingActive = true

            // Calculate zoom to make the frame larger: inverse of frame scale.
            // Frame.box width is a fraction of the full scene; we want to zoom by 1/width
            // to make it fill most of the screen. Clamp to device's max zoom.
            val frameWidth = frame.box.width.coerceAtLeast(0.15f)
            val targetZoom = (1f / frameWidth).coerceAtMost(currentZoom.maxRatio)
            controller.setZoomRatio(targetZoom)

            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    fun captureFromAutoFrame() {
        if (!isAutoFramingActive) return
        captureFrameica(frame.box)
    }

    fun handleFrameicaTap() {
        if (isAutoFramingActive) {
            captureFromAutoFrame()
        } else if (frame.isIdeal) {
            activateAutoFraming()
        } else {
            captureFrameica(frame.box)
        }
    }

    fun exitAutoFraming() {
        if (isAutoFramingActive) {
            isAutoFramingActive = false
            controller.setZoomRatio(priorZoomRatio)
        }
    }

    if (mode == CaptureMode.FRAMEICA) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .aspectRatio(aspect.previewAspect),
            ) {
                ViewfinderContent(
                    mode = mode,
                    previewView = previewView,
                    controller = controller,
                    frame = frame,
                    showFrame = showFrame,
                    frameState = frameState,
                    showFrameState = showFrameState,
                    primarySubject = primarySubject,
                    settings = settings,
                    level = level,
                    roll = roll,
                    focusPoint = focusPoint,
                    onFocusPoint = { focusPoint = it },
                    onCaptureFrameica = ::captureFrameica,
                    isAutoFraming = isAutoFramingActive,
                    onFrameicaTap = ::handleFrameicaTap,
                    onExitAutoFrame = ::exitAutoFraming,
                )
            }

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
                    AspectRatioSelector(current = aspect, onSelect = { aspect = it })
                }
                MetadataHud(exposure = exposure, capabilities = capabilities)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GuidanceBar(
                    frame = frame,
                    visible = settings.showGuidance && frame.hasSubject,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                if (isAutoFramingActive) {
                    Text(
                        text = "Adjust & tap to capture",
                        color = Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ZoomBar(zoom = zoom, onJump = { controller.jumpToZoom(it) })
                ModeSwitcher(mode = mode, onSelect = { mode = it })
                ShutterBar(
                    isIdeal = frame.isIdeal,
                    lastCapture = lastCapture,
                    onCapture = {
                        if (mode == CaptureMode.FRAMEICA) {
                            if (isAutoFramingActive) {
                                captureFromAutoFrame()
                            } else if (frame.isIdeal) {
                                activateAutoFraming()
                            } else {
                                captureManual()
                            }
                        } else {
                            captureManual()
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
    } else {
        FilmCameraBody(
            roll = roll,
            exposure = exposure,
            lastCapture = lastCapture,
            onShutter = {
                if (roll.stock == null || roll.phase == RollPhase.FINISHED) {
                    showFilmShelf = true
                } else {
                    scope.launch {
                        filmRoll.shoot(context, controller) { uri -> controller.noteExternalCapture(uri) }
                    }
                }
            },
            onChangeRoll = { showFilmShelf = true },
            onOpenGallery = { showGallery = true },
            onSwitchCamera = { scope.launch { controller.switchCamera() } },
            onOpenSettings = { showSettings = true },
            onSwitchToFrameica = { mode = CaptureMode.FRAMEICA },
        ) {
            ViewfinderContent(
                mode = mode,
                previewView = previewView,
                controller = controller,
                frame = frame,
                showFrame = showFrame,
                frameState = frameState,
                showFrameState = showFrameState,
                primarySubject = primarySubject,
                settings = settings,
                level = level,
                roll = roll,
                focusPoint = focusPoint,
                onFocusPoint = { focusPoint = it },
                onCaptureFrameica = ::captureFrameica,
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

/**
 * The live preview + its gesture layer + mode-specific framing overlays. Shared verbatim between
 * Frameica's letterboxed glass chrome and Film mode's eyepiece window so the capture/focus/zoom
 * behaviour is identical regardless of which physical or digital housing it's viewed through.
 */
@Composable
private fun BoxScope.ViewfinderContent(
    mode: CaptureMode,
    previewView: PreviewView,
    controller: CameraController,
    frame: PerfectFrame,
    showFrame: Boolean,
    frameState: State<PerfectFrame>,
    showFrameState: State<Boolean>,
    primarySubject: NormRect?,
    settings: ViewfinderSettings,
    level: LevelState,
    roll: com.perfectframe.camera.film.FilmRollState,
    focusPoint: Offset?,
    onFocusPoint: (Offset?) -> Unit,
    onCaptureFrameica: (NormRect) -> Unit,
    isAutoFraming: Boolean = false,
    onFrameicaTap: () -> Unit = {},
    onExitAutoFrame: () -> Unit = {},
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
                    val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                    controller.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    onFocusPoint(offset)

                    if (mode == CaptureMode.FRAMEICA) {
                        val box = frameState.value.box
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val inside = showFrameState.value &&
                            offset.x in (box.left * w)..(box.right * w) &&
                            offset.y in (box.top * h)..(box.bottom * h)
                        if (inside) {
                            if (isAutoFraming) {
                                onCaptureFrameica(frameState.value.box)
                            } else {
                                onFrameicaTap()
                            }
                        } else if (isAutoFraming) {
                            // Tap outside frame cancels auto-framing
                            onExitAutoFrame()
                        }
                    }
                }
            },
    )

    if (mode == CaptureMode.FRAMEICA) {
        FramingOverlay(
            frame = frame,
            subjectBox = if (settings.showGuidance) primarySubject else null,
            show = showFrame || isAutoFraming,
            isAutoFraming = isAutoFraming,
            modifier = Modifier.fillMaxSize(),
        )
        if (settings.showGrid && !showFrame && !isAutoFraming) {
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

    FocusRing(focusPoint = focusPoint, onFinished = { onFocusPoint(null) })

    AnimatedVisibility(
        visible = mode == CaptureMode.FILM && roll.phase == RollPhase.DEVELOPING,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        DevelopingOverlay(progress = roll.developProgress, totalSeconds = 30)
    }
}

/** Small segmented switcher between the two shooting experiences (Frameica mode's chrome only). */
@Composable
private fun ModeSwitcher(mode: CaptureMode, onSelect: (CaptureMode) -> Unit) {
    GlassSurface(shape = RoundedCornerShape(50)) {
        Row(modifier = Modifier.padding(4.dp)) {
            CaptureMode.entries.forEach { option ->
                val active = option == mode
                Text(
                    text = option.label,
                    color = if (active) com.perfectframe.camera.ui.theme.Surface0 else com.perfectframe.camera.ui.theme.TextSecondary,
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
