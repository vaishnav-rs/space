package com.perfectframe.camera.film

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.perfectframe.camera.camera.ExposureState
import com.perfectframe.camera.camera.LimitingReason

// A two-tone SLR body: brushed-aluminium top plate over a dark leatherette shell. Deliberately
// no glass/translucent "app chrome" anywhere in this file — every surface reads as a material,
// every control as a mechanism.
private val MetalLight = Color(0xFFC7CBD1)
private val MetalDark = Color(0xFF52565C)
private val LeatherBase = Color(0xFF161310)
private val LeatherEdge = Color(0xFF0A0806)
private val Cream = Color(0xFFEDE4D0)
private val Ink = Color(0xFF201C16)
private val RedDot = Color(0xFFC53030)

private val leatherBrush = Brush.radialGradient(
    colors = listOf(LeatherBase, LeatherEdge),
    radius = 900f,
)
private val metalBrush = Brush.verticalGradient(colors = listOf(MetalLight, MetalDark))

/**
 * The Film-mode body: metal top plate, an eyepiece-style viewfinder window (not a full-bleed
 * preview — a real camera makes you look *into* something), and a leatherette bottom deck with a
 * mechanical shutter, an analog frame counter, and a winding lever that actually sweeps.
 *
 * [viewfinder] renders the live preview + its own overlays (framing lines, level, grid) — this
 * composable only supplies the physical housing around it.
 */
@Composable
fun FilmCameraBody(
    roll: FilmRollState,
    exposure: ExposureState,
    lastCapture: Uri?,
    onShutter: () -> Unit,
    onChangeRoll: () -> Unit,
    onOpenGallery: () -> Unit,
    onSwitchCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchToFrameica: () -> Unit,
    viewfinder: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(LeatherBase)) {
        TopPlate(
            roll = roll,
            onChangeRoll = onChangeRoll,
            onSwitchCamera = onSwitchCamera,
            onOpenSettings = onOpenSettings,
        )
        ViewfinderBezel(
            roll = roll,
            exposure = exposure,
            lastCapture = lastCapture,
            onOpenGallery = onOpenGallery,
            modifier = Modifier.weight(1f),
            content = viewfinder,
        )
        BottomDeck(roll = roll, onShutter = onShutter)
        NamePlate(onSwitchToFrameica = onSwitchToFrameica)
    }
}

@Composable
private fun TopPlate(
    roll: FilmRollState,
    onChangeRoll: () -> Unit,
    onSwitchCamera: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(96.dp)
            .background(metalBrush),
    ) {
        // A single specular highlight line sells the "brushed" read cheaply.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(0f, size.height * 0.28f),
                end = Offset(size.width, size.height * 0.28f),
                strokeWidth = 1.dp.toPx(),
            )
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RewindCrank(onClick = onSwitchCamera)
            PentaprismHump(onClick = onOpenSettings)
            IsoDialCluster(iso = roll.stock?.iso, onClick = onChangeRoll)
        }
    }
}

@Composable
private fun RewindCrank(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(52.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(40.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Ink, radius = size.minDimension / 2f, center = c)
            drawCircle(MetalDark, radius = size.minDimension / 2f - 4.dp.toPx(), center = c)
            drawLine(Ink, c, Offset(c.x + size.width * 0.32f, c.y - size.height * 0.32f), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(Ink, radius = 3.dp.toPx(), center = Offset(c.x + size.width * 0.32f, c.y - size.height * 0.32f))
        }
    }
}

@Composable
private fun PentaprismHump(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(width = 64.dp, height = 44.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.28f, h)
                lineTo(w * 0.38f, h * 0.15f)
                lineTo(w * 0.62f, h * 0.15f)
                lineTo(w * 0.72f, h)
                close()
            }
            drawPath(path, MetalDark)
            // Hot shoe.
            drawRect(
                Ink,
                topLeft = Offset(w * 0.42f, 0f),
                size = androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.18f),
            )
            // A tiny flathead screw — the settings tap target, in plain sight but unlabeled.
            val sc = Offset(w * 0.5f, h * 0.82f)
            drawCircle(Ink, radius = 4.dp.toPx(), center = sc)
            drawLine(MetalLight.copy(alpha = 0.6f), Offset(sc.x - 3.dp.toPx(), sc.y), Offset(sc.x + 3.dp.toPx(), sc.y), 1.dp.toPx())
        }
    }
}

@Composable
private fun IsoDialCluster(iso: Int?, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(Ink, radius = size.minDimension / 2f)
            drawCircle(MetalDark, radius = size.minDimension / 2f - 5.dp.toPx())
            for (i in 0 until 12) {
                val a = Math.toRadians((i * 30).toDouble())
                val r1 = size.minDimension / 2f - 6.dp.toPx()
                val r2 = size.minDimension / 2f - 10.dp.toPx()
                val c = Offset(size.width / 2f, size.height / 2f)
                drawLine(
                    Ink.copy(alpha = 0.6f),
                    Offset(c.x + (r1 * Math.cos(a)).toFloat(), c.y + (r1 * Math.sin(a)).toFloat()),
                    Offset(c.x + (r2 * Math.cos(a)).toFloat(), c.y + (r2 * Math.sin(a)).toFloat()),
                    1.dp.toPx(),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ISO", color = Cream.copy(alpha = 0.7f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(
                text = iso?.toString() ?: "--",
                color = Cream,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ViewfinderBezel(
    roll: FilmRollState,
    exposure: ExposureState,
    lastCapture: Uri?,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(leatherBrush)
            .padding(16.dp),
    ) {
        // Eyepiece housing: a thick dark bezel, a thin metal highlight ring, then the glass.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(30.dp))
                .background(Ink)
                .padding(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.5.dp, MetalLight.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                ) {
                    content()
                    RangefinderReticle(modifier = Modifier.fillMaxSize())
                }
            }
        }

        ExposureNeedle(
            reason = exposure.limitingReason,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
        )
        MemoHolder(
            stock = roll.stock,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 4.dp),
        )
        GalleryPeephole(
            lastCapture = lastCapture,
            onClick = onOpenGallery,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp),
        )
    }
}

/** A classic rangefinder split-image circle — decorative, sells "you're looking through glass." */
@Composable
private fun RangefinderReticle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.09f
        val color = Color.White.copy(alpha = 0.55f)
        drawCircle(color, radius = r, center = c, style = Stroke(width = 1.2.dp.toPx()))
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
    }
}

/** A vertical analog light-meter gauge, reinterpreting the same auto-exposure reasoning as a needle. */
@Composable
private fun ExposureNeedle(reason: LimitingReason, modifier: Modifier = Modifier) {
    val target = when (reason) {
        LimitingReason.BRIGHT -> 0.8f
        LimitingReason.SHUTTER_SLOWED -> -0.5f
        LimitingReason.ISO_CAPPED -> -0.3f
        LimitingReason.LOW_LIGHT_LIMIT -> -0.85f
        LimitingReason.MANUAL -> 0f
        LimitingReason.NONE -> 0.1f
    }
    val needle = remember { Animatable(0f) }
    LaunchedEffect(target) { needle.animateTo(target, tween(400)) }

    Box(
        modifier = modifier.width(20.dp).fillMaxHeight(0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                Color.White.copy(alpha = 0.25f),
                Offset(size.width / 2f, 0f),
                Offset(size.width / 2f, size.height),
                1.dp.toPx(),
            )
            val y = size.height / 2f - (needle.value * size.height * 0.42f)
            drawLine(
                RedDot,
                Offset(size.width * 0.15f, y),
                Offset(size.width * 0.85f, y),
                2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/** A little slid-in memo strip on the back plate, like checking which box the loaded roll came from. */
@Composable
private fun MemoHolder(stock: FilmStock?, modifier: Modifier = Modifier) {
    if (stock == null) return
    Box(
        modifier = modifier
            .rotate(-2f)
            .clip(RoundedCornerShape(3.dp))
            .background(Cream)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "${stock.displayName} · ${stock.iso}",
            color = Ink,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GalleryPeephole(lastCapture: Uri?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Ink)
            .border(1.dp, MetalLight.copy(alpha = 0.4f), CircleShape)
            .clickable(onClick = onClick),
    ) {
        if (lastCapture != null) {
            AsyncImage(
                model = lastCapture,
                contentDescription = "Open gallery",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(3.dp).clip(CircleShape),
            )
        }
    }
}

@Composable
private fun BottomDeck(roll: FilmRollState, onShutter: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(leatherBrush)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FrameCounterWindow(roll = roll)
        MechanicalShutterButton(enabled = !roll.isBusy, onClick = onShutter)
        WindLever(winding = roll.phase == RollPhase.WINDING)
    }
}

@Composable
private fun FrameCounterWindow(roll: FilmRollState) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(Cream)
            .border(3.dp, Ink, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (roll.stock == null) "--" else roll.frame.coerceAtLeast(1).toString(),
            color = Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MechanicalShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = tween(90),
        label = "shutterPress",
    )
    Box(
        modifier = Modifier
            .size(78.dp)
            .scale(pressScale)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(MetalLight, MetalDark)))
            .border(2.dp, Ink, CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(if (enabled) RedDot else RedDot.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun WindLever(winding: Boolean) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(winding) {
        if (winding) {
            angle.animateTo(28f, tween(280))
            angle.animateTo(-8f, tween(420))
            angle.animateTo(0f, tween(200))
        }
    }
    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.CenterEnd) {
        Canvas(modifier = Modifier.size(56.dp)) {
            val pivot = Offset(size.width * 0.85f, size.height * 0.5f)
            drawCircle(Ink, radius = 7.dp.toPx(), center = pivot)
            rotateCanvas(degrees = angle.value, pivot = pivot) {
                drawLine(
                    MetalLight,
                    pivot,
                    Offset(pivot.x - size.width * 0.62f, pivot.y - size.height * 0.06f),
                    5.dp.toPx(),
                    StrokeCap.Round,
                )
                drawCircle(
                    MetalDark,
                    radius = 6.dp.toPx(),
                    center = Offset(pivot.x - size.width * 0.62f, pivot.y - size.height * 0.06f),
                )
            }
        }
    }
}

@Composable
private fun NamePlate(onSwitchToFrameica: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(34.dp)
            .background(LeatherEdge)
            .clickable(onClick = onSwitchToFrameica),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "FRAMEICA MODE  ›",
            color = Cream.copy(alpha = 0.55f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}
