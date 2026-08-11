package com.perfectframe.camera.ui.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.perfectframe.camera.sensors.LevelState
import com.perfectframe.camera.ui.theme.Accent
import com.perfectframe.camera.ui.theme.TextTertiary
import kotlin.math.abs

/**
 * A quiet horizon level (spec §2 Phase 3): a fixed centre reference and a line that rolls with the
 * device. It sits still and pale until the phone is nearly level, then the rolling line snaps flat,
 * closes the centre gap, and glows mint — a calm confirmation rather than a noisy meter.
 */
@Composable
fun HorizonIndicator(
    level: LevelState,
    modifier: Modifier = Modifier,
) {
    if (!level.hasSensors) return

    // Clamp the visualized roll so extreme tilt stays legible; smooth it lightly on top of the
    // detector's own low-pass filter.
    val shownRoll by animateFloatAsState(
        targetValue = level.rollDegrees.coerceIn(-45f, 45f),
        animationSpec = spring(stiffness = 220f),
        label = "roll",
    )
    val color by animateColorAsState(
        targetValue = if (level.isLevel) Accent else TextTertiary,
        animationSpec = tween(220),
        label = "levelColor",
    )
    // When level, the two line halves close toward the centre; otherwise they leave a gap.
    val gap by animateFloatAsState(
        targetValue = if (level.isLevel) 0f else 1f,
        animationSpec = tween(220),
        label = "levelGap",
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = size.width * 0.14f
        val gapPx = 16.dp.toPx() * gap
        val stroke = 3.dp.toPx()

        // Fixed reference ticks (do not rotate) — the target the rolling line aligns to.
        val refColor = if (level.isLevel) Accent else TextTertiary.copy(alpha = 0.5f)
        drawLine(refColor, Offset(cx - half - 12f, cy), Offset(cx - half, cy), stroke, StrokeCap.Round)
        drawLine(refColor, Offset(cx + half, cy), Offset(cx + half + 12f, cy), stroke, StrokeCap.Round)

        // Rolling horizon line (negative: screen y grows downward vs. sensor roll sign).
        rotate(degrees = -shownRoll, pivot = Offset(cx, cy)) {
            drawLine(color, Offset(cx - half, cy), Offset(cx - gapPx, cy), stroke, StrokeCap.Round)
            drawLine(color, Offset(cx + gapPx, cy), Offset(cx + half, cy), stroke, StrokeCap.Round)
            // Centre bubble only while still searching for level.
            if (!level.isLevel && abs(shownRoll) > 0.5f) {
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(cx, cy))
            }
        }
    }
}
