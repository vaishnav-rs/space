package com.perfectframe.camera.ui.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perfectframe.camera.camera.CameraCapabilities
import com.perfectframe.camera.camera.ExposureState
import com.perfectframe.camera.ui.components.GlassSurface
import com.perfectframe.camera.ui.theme.Accent
import com.perfectframe.camera.ui.theme.TextPrimary
import com.perfectframe.camera.ui.theme.TextSecondary
import com.perfectframe.camera.ui.theme.TextTertiary
import com.perfectframe.camera.ui.theme.Warn

/**
 * Top glass HUD (spec §0.2): exposure is fully automatic but *legible*. A single monospaced row
 * of ISO · shutter · f · WB, a second line explaining *why* the auto system settled there, and a
 * small honest capability chip so the user knows what the device actually captures. No fake
 * precision, no controls that don't exist.
 */
@Composable
fun MetadataHud(
    exposure: ExposureState,
    capabilities: CameraCapabilities?,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaValue(exposure.isoLabel)
                Dot()
                MetaValue(exposure.shutterLabel)
                Dot()
                MetaValue(exposure.apertureLabel)
                Dot()
                MetaValue(exposure.whiteBalanceLabel, tint = if (exposure.isManualOverride) Warn else TextPrimary)
            }
            Text(
                text = exposure.reasonLabel,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            capabilities?.let {
                Text(
                    text = it.summary(),
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun MetaValue(text: String, tint: androidx.compose.ui.graphics.Color = TextPrimary) {
    Text(
        text = text,
        color = tint,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun Dot() {
    Text("·", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}
