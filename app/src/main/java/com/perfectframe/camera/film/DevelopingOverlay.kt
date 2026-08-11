package com.perfectframe.camera.film

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perfectframe.camera.ui.theme.TextPrimary
import com.perfectframe.camera.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * The darkroom beat: while a Film-mode frame develops, the shot itself stays hidden — genuinely
 * the point of film — behind a red safelight tint and a slow progress ring counting down the
 * real elapsed time (spec: "30 sec developing phase").
 */
@Composable
fun DevelopingOverlay(progress: Float, totalSeconds: Int, modifier: Modifier = Modifier) {
    val breathe = rememberInfiniteTransition(label = "developBreathe")
    val glow by breathe.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "developGlow",
    )
    val secondsLeft = ((1f - progress) * totalSeconds).roundToInt().coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xE6210606)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(96.dp)) {
                    val stroke = 5.dp.toPx()
                    drawCircle(
                        color = Color(0x33FFFFFF),
                        radius = size.minDimension / 2f - stroke / 2f,
                        style = Stroke(width = stroke),
                    )
                    drawArc(
                        color = Color(0xFFFF7A59).copy(alpha = glow),
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - stroke, size.height - stroke,
                        ),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                Text(
                    text = "${secondsLeft}s",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Developing…",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "No peeking — that's the deal with film",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
