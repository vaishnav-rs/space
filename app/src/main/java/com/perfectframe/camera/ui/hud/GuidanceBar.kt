package com.perfectframe.camera.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perfectframe.camera.composition.Nudge
import com.perfectframe.camera.composition.PerfectFrame
import com.perfectframe.camera.ui.theme.Accent
import com.perfectframe.camera.ui.theme.GlassBorder
import com.perfectframe.camera.ui.theme.GlassFillStrong
import com.perfectframe.camera.ui.theme.Surface0
import com.perfectframe.camera.ui.theme.TextPrimary
import com.perfectframe.camera.ui.theme.Warn

/**
 * The coaching layer (spec §3, §4): one plain-language line telling the user what to do, plus
 * small directional nudge chips. When the framing is ideal it collapses to a single confident
 * "Perfect" pill — the reward, not another instruction.
 */
@Composable
fun GuidanceBar(
    frame: PerfectFrame,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        if (frame.isIdeal) {
            PerfectPill()
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (frame.nudges.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        frame.nudges.forEach { NudgeChip(it) }
                    }
                }
                Text(
                    text = frame.reasoning,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PerfectPill() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Accent)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("✓", color = Surface0, fontSize = 14.sp, fontWeight = FontWeight.Black)
        Text("Perfect frame", color = Surface0, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NudgeChip(nudge: Nudge) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(GlassFillStrong)
            .border(0.8.dp, GlassBorder, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(nudge.glyph(), color = Warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(nudge.label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** A small directional glyph for each nudge (drawn as text so no icon font is required). */
private fun Nudge.glyph(): String = when (this) {
    Nudge.PAN_LEFT -> "←"
    Nudge.PAN_RIGHT -> "→"
    Nudge.TILT_UP -> "↑"
    Nudge.TILT_DOWN -> "↓"
    Nudge.STEP_CLOSER -> "＋"
    Nudge.STEP_BACK -> "－"
    Nudge.LEVEL_HORIZON -> "⟲"
}
