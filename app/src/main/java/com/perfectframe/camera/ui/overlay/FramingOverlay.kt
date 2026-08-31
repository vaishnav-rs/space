package com.perfectframe.camera.ui.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.perfectframe.camera.composition.NormRect
import com.perfectframe.camera.composition.PerfectFrame
import com.perfectframe.camera.ui.theme.Accent
import com.perfectframe.camera.ui.theme.GridLine
import com.perfectframe.camera.ui.theme.Scrim
import com.perfectframe.camera.ui.theme.SubjectTint
import com.perfectframe.camera.ui.theme.Warn
import kotlin.math.min

/**
 * The signature overlay (spec §3): the suggested crop rendered as a bracketed frame over a
 * dimmed surround, so the "perfect frame" is spotlit within the wider live scene. It animates
 * smoothly toward each new suggestion (never snapping), turns from amber → mint as confidence
 * crosses "ideal", and gains a soft breathing glow at that moment — the visual reward that makes
 * the good shot feel earned rather than announced by text.
 *
 * The whole overlay is confidence-gated by [show]; below threshold it fades fully out so a shaky,
 * low-confidence box never nags the user (spec §3 "earns trust").
 */
@Composable
fun FramingOverlay(
    frame: PerfectFrame,
    subjectBox: NormRect?,
    show: Boolean,
    isAutoFraming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val vis by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(durationMillis = if (isAutoFraming) 100 else 420, easing = FastOutSlowInEasing),
        label = "framingVisibility",
    )

    // Smoothly chase the target crop — springy, so recomposition of the box reads as motion.
    val boxSpring = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    val l by animateFloatAsState(frame.box.left, boxSpring, label = "cropL")
    val t by animateFloatAsState(frame.box.top, boxSpring, label = "cropT")
    val r by animateFloatAsState(frame.box.right, boxSpring, label = "cropR")
    val b by animateFloatAsState(frame.box.bottom, boxSpring, label = "cropB")

    val strokeColor by animateColorAsState(
        targetValue = if (frame.isIdeal) Accent else Warn,
        animationSpec = tween(300),
        label = "cropColor",
    )

    val breathe = rememberInfiniteTransition(label = "idealBreathe")
    val pulse by breathe.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idealPulse",
    )

    Canvas(modifier = modifier) {
        if (vis <= 0.01f) return@Canvas

        val w = size.width
        val h = size.height
        val left = l * w
        val top = t * h
        val right = r * w
        val bottom = b * h
        val cropW = (right - left).coerceAtLeast(1f)
        val cropH = (bottom - top).coerceAtLeast(1f)
        val corner = 20.dp.toPx()

        // 1. Dim everything outside the suggested crop (four rects around it).
        val scrimAlpha = Scrim.alpha * vis * (if (isAutoFraming) 0.92f else if (frame.isIdeal) 1f else 0.78f)
        val scrim = Scrim.copy(alpha = scrimAlpha)
        drawRect(scrim, size = Size(w, top))
        drawRect(scrim, topLeft = Offset(0f, bottom), size = Size(w, (h - bottom).coerceAtLeast(0f)))
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, cropH))
        drawRect(scrim, topLeft = Offset(right, top), size = Size((w - right).coerceAtLeast(0f), cropH))

        // 2. Faint rule-of-thirds guides inside the crop.
        val grid = GridLine.copy(alpha = GridLine.alpha * vis)
        for (i in 1..2) {
            val gx = left + cropW * i / 3f
            drawLine(grid, Offset(gx, top), Offset(gx, bottom), strokeWidth = 1f)
            val gy = top + cropH * i / 3f
            drawLine(grid, Offset(left, gy), Offset(right, gy), strokeWidth = 1f)
        }

        // 3. Subtle subject tracker (thin, so it informs without competing with the crop).
        subjectBox?.let { s ->
            drawRoundRect(
                color = SubjectTint.copy(alpha = SubjectTint.alpha * vis),
                topLeft = Offset(s.left * w, s.top * h),
                size = Size(s.width * w, s.height * h),
                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        // 4. Breathing glow when the framing is ideal.
        if (frame.isIdeal) {
            val grow = (6f + pulse * 14f).dp.toPx()
            drawRoundRect(
                color = Accent.copy(alpha = (0.28f * (1f - pulse) + 0.08f) * vis),
                topLeft = Offset(left - grow, top - grow),
                size = Size(cropW + grow * 2, cropH + grow * 2),
                cornerRadius = CornerRadius(corner + grow, corner + grow),
                style = Stroke(width = (10f + pulse * 8f).dp.toPx() * 0.5f),
            )
        }

        // 5. The crop frame itself — a soft continuous rounded rect...
        val frameAlpha = if (isAutoFraming) 0.85f else 0.55f
        val frameStroke = if (isAutoFraming) 2.5f.dp.toPx() else 1.5f.dp.toPx()
        drawRoundRect(
            color = strokeColor.copy(alpha = frameAlpha * vis),
            topLeft = Offset(left, top),
            size = Size(cropW, cropH),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = frameStroke),
        )
        // ...with emphasized corner brackets for that "framing" read.
        drawCornerBrackets(
            left, top, right, bottom,
            length = min(cropW, cropH) * 0.16f,
            corner = corner,
            color = strokeColor.copy(alpha = vis),
            stroke = (if (isAutoFraming) 4.5f else 3f).dp.toPx(),
        )
    }
}

/** Draws four L-shaped brackets that hug the rounded corners of the crop. */
private fun DrawScope.drawCornerBrackets(
    left: Float, top: Float, right: Float, bottom: Float,
    length: Float, corner: Float, color: Color, stroke: Float,
) {
    val cap = androidx.compose.ui.graphics.StrokeCap.Round
    val len = length.coerceIn(12f, 90f)
    // Top-left
    drawLine(color, Offset(left, top + corner), Offset(left, top + corner + len), stroke, cap)
    drawLine(color, Offset(left + corner, top), Offset(left + corner + len, top), stroke, cap)
    // Top-right
    drawLine(color, Offset(right, top + corner), Offset(right, top + corner + len), stroke, cap)
    drawLine(color, Offset(right - corner, top), Offset(right - corner - len, top), stroke, cap)
    // Bottom-left
    drawLine(color, Offset(left, bottom - corner), Offset(left, bottom - corner - len), stroke, cap)
    drawLine(color, Offset(left + corner, bottom), Offset(left + corner + len, bottom), stroke, cap)
    // Bottom-right
    drawLine(color, Offset(right, bottom - corner), Offset(right, bottom - corner - len), stroke, cap)
    drawLine(color, Offset(right - corner, bottom), Offset(right - corner - len, bottom), stroke, cap)
}
