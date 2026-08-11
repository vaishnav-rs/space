package com.perfectframe.camera.ui.controls

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perfectframe.camera.camera.AspectRatioOption
import com.perfectframe.camera.camera.ZoomInfo
import com.perfectframe.camera.ui.components.GlassSurface
import com.perfectframe.camera.ui.theme.Accent
import com.perfectframe.camera.ui.theme.Surface0
import com.perfectframe.camera.ui.theme.TextSecondary
import kotlin.math.abs

/** Segmented glass control to pick the capture aspect ratio. */
@Composable
fun AspectRatioSelector(
    current: AspectRatioOption,
    onSelect: (AspectRatioOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier = modifier, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AspectRatioOption.entries.forEach { option ->
                Segment(
                    label = option.label,
                    active = option == current,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/**
 * Quick-zoom stops (like a phone camera's .5/1/2). The active stop shows the *live* ratio so the
 * user always sees exactly how far they've pinched.
 */
@Composable
fun ZoomBar(
    zoom: ZoomInfo,
    onJump: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!zoom.hasRange) return
    val stops = buildList {
        if (zoom.minRatio < 0.99f) add(zoom.minRatio)
        add(1f)
        if (zoom.maxRatio >= 2f) add(2f)
        if (zoom.maxRatio >= 5f) add(5f)
    }.distinct()
    val active = stops.minByOrNull { abs(it - zoom.ratio) }

    GlassSurface(modifier = modifier, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            stops.forEach { stop ->
                val isActive = stop == active
                val label = if (isActive) "%.1f×".format(zoom.ratio) else stopLabel(stop)
                Segment(label = label, active = isActive, onClick = { onJump(stop) })
            }
        }
    }
}

private fun stopLabel(stop: Float): String = when {
    stop < 1f -> "%.1f×".format(stop)
    stop == 1f -> "1×"
    else -> "${stop.toInt()}×"
}

@Composable
private fun Segment(label: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) Accent else Color.Transparent, label = "segBg")
    Text(
        text = label,
        color = if (active) Surface0 else TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
