package com.perfectframe.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.perfectframe.camera.ui.theme.GlassBorder
import com.perfectframe.camera.ui.theme.GlassFill
import com.perfectframe.camera.ui.theme.GlassFillStrong
import com.perfectframe.camera.ui.theme.GlassHighlight

/**
 * A translucent "glass" container for HUD chrome (spec §4 Liquid Glass).
 *
 * Deliberately implemented with a smoked translucent fill + a soft top-down highlight gradient +
 * a hairline border, rather than a real background blur. `Modifier.blur` blurs a node's own
 * content (not the scene behind it), and true backdrop blur needs API 31+ RenderEffect plumbing
 * that behaves inconsistently over a `SurfaceView` camera preview — so this look is chosen to be
 * identical and cheap on every supported device (minSdk 28), which matters more than a blur that
 * only some phones honor (spec §6).
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    strong: Boolean = false,
    content: @Composable () -> Unit,
) {
    val fill = if (strong) GlassFillStrong else GlassFill
    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .background(
                Brush.verticalGradient(
                    colors = listOf(GlassHighlight, androidx.compose.ui.graphics.Color.Transparent),
                )
            )
            .border(0.8.dp, GlassBorder, shape),
    ) {
        content()
    }
}
