package com.perfectframe.camera.film

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perfectframe.camera.ui.components.GlassSurface
import com.perfectframe.camera.ui.theme.TextPrimary
import com.perfectframe.camera.ui.theme.TextSecondary
import com.perfectframe.camera.ui.theme.Warn

/**
 * A quiet SLR-style bright-line frame — the one framing aid a real film camera actually has.
 * No autoframer, no nudges: Film mode is deliberately manual.
 */
@Composable
fun FilmViewfinderFrame(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val inset = size.minDimension * 0.04f
        val stroke = 1.5.dp.toPx()
        val corner = 22.dp.toPx()
        val color = androidx.compose.ui.graphics.Color(0x99FFFFFF)

        val l = inset; val t = inset; val r = size.width - inset; val b = size.height - inset
        val len = 26.dp.toPx()
        // Four corner brackets only — echoes an optical viewfinder's bright-line frame.
        drawLine(color, Offset(l, t + corner), Offset(l, t + corner + len), stroke, StrokeCap.Round)
        drawLine(color, Offset(l + corner, t), Offset(l + corner + len, t), stroke, StrokeCap.Round)
        drawLine(color, Offset(r, t + corner), Offset(r, t + corner + len), stroke, StrokeCap.Round)
        drawLine(color, Offset(r - corner, t), Offset(r - corner - len, t), stroke, StrokeCap.Round)
        drawLine(color, Offset(l, b - corner), Offset(l, b - corner - len), stroke, StrokeCap.Round)
        drawLine(color, Offset(l + corner, b), Offset(l + corner + len, b), stroke, StrokeCap.Round)
        drawLine(color, Offset(r, b - corner), Offset(r, b - corner - len), stroke, StrokeCap.Round)
        drawLine(color, Offset(r - corner, b), Offset(r - corner - len, b), stroke, StrokeCap.Round)
        // Centre focus reticle.
        val cx = size.width / 2f; val cy = size.height / 2f; val half = 14.dp.toPx()
        drawCircle(color, radius = half, center = Offset(cx, cy), style = Stroke(width = stroke))
    }
}

/** Top pill: loaded stock name + ISO, or a prompt to load one. */
@Composable
fun FilmStockBadge(roll: FilmRollState, onChangeRoll: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier = modifier, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier
                .clickable(enabled = !roll.isBusy, onClick = onChangeRoll)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val stock = roll.stock
            if (stock == null) {
                Text("Load film", color = Warn, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(stock.boxColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        "ISO ${stock.iso}", color = stock.accentColor,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Text(stock.displayName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Analog frame-counter window, e.g. "EXP 07 / 24". */
@Composable
fun FrameCounter(roll: FilmRollState, modifier: Modifier = Modifier) {
    if (roll.stock == null) return
    GlassSurface(modifier = modifier, shape = RoundedCornerShape(8.dp), strong = true) {
        Text(
            text = "EXP %02d/%02d".format(roll.frame.coerceAtLeast(1), roll.exposures),
            color = if (roll.phase == RollPhase.FINISHED) Warn else TextSecondary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
