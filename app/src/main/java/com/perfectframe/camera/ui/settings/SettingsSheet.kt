package com.perfectframe.camera.ui.settings

import androidx.camera.core.ExposureState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perfectframe.camera.ui.theme.Accent
import com.perfectframe.camera.ui.theme.Surface0
import com.perfectframe.camera.ui.theme.TextPrimary
import com.perfectframe.camera.ui.theme.TextSecondary
import com.perfectframe.camera.ui.theme.TextTertiary
import kotlin.math.roundToInt

/** All viewfinder preferences that live purely in the UI layer + the two exposure overrides. */
data class ViewfinderSettings(
    val showGuidance: Boolean = true,
    val showGrid: Boolean = true,
    val showHorizon: Boolean = true,
    val strictLevel: Boolean = false,
    val exposureLocked: Boolean = false,
    val evIndex: Int = 0,
)

/**
 * The one and only settings surface (spec §3: no deep menus). A bottom sheet with a handful of
 * honest toggles and a *single* manual exposure override — EV compensation — layered on top of the
 * auto engine. Aperture is not here because it is physically fixed; there is no fake control
 * (spec §0, §6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: ViewfinderSettings,
    exposureState: ExposureState?,
    onChange: (ViewfinderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surface0,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionLabel("Composition")
            ToggleRow("Framing guidance", settings.showGuidance) {
                onChange(settings.copy(showGuidance = it))
            }
            ToggleRow("Rule-of-thirds grid", settings.showGrid) {
                onChange(settings.copy(showGrid = it))
            }
            ToggleRow("Horizon level", settings.showHorizon) {
                onChange(settings.copy(showHorizon = it))
            }
            ToggleRow("Strict level (±1.5°)", settings.strictLevel) {
                onChange(settings.copy(strictLevel = it))
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = TextTertiary.copy(alpha = 0.2f),
            )

            SectionLabel("Exposure  ·  auto by default")
            ToggleRow("Lock exposure (AE/AWB)", settings.exposureLocked) {
                onChange(settings.copy(exposureLocked = it))
            }
            EvRow(settings, exposureState, onChange)
        }
    }
}

@Composable
private fun EvRow(
    settings: ViewfinderSettings,
    exposureState: ExposureState?,
    onChange: (ViewfinderSettings) -> Unit,
) {
    val supported = exposureState?.isExposureCompensationSupported == true
    if (!supported || exposureState == null) {
        Text(
            "EV compensation not supported on this camera",
            color = TextTertiary,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    val range = exposureState.exposureCompensationRange
    val stepRational = exposureState.exposureCompensationStep
    // Rational lacks a stable doubleValue() accessor across the compile SDK, so derive it.
    val step = stepRational.numerator.toDouble() / stepRational.denominator.toDouble()
    val ev: Double = settings.evIndex * step

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("EV compensation", color = TextPrimary, fontSize = 15.sp)
        Text(
            text = (if (ev >= 0) "+%.1f" else "%.1f").format(ev),
            color = Accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Slider(
        value = settings.evIndex.toFloat().coerceIn(range.lower.toFloat(), range.upper.toFloat()),
        onValueChange = { onChange(settings.copy(evIndex = it.roundToInt())) },
        valueRange = range.lower.toFloat()..range.upper.toFloat(),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = TextTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, fontSize = 15.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Surface0,
                checkedTrackColor = Accent,
                uncheckedTrackColor = TextTertiary.copy(alpha = 0.3f),
            ),
        )
    }
}
