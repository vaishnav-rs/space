package com.perfectframe.camera.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.perfectframe.camera.ui.theme.GridLine

/**
 * A plain full-frame rule-of-thirds grid, shown when the user wants gridlines but the framing
 * guidance isn't currently drawing its own (its grid lives inside the suggested crop).
 */
@Composable
fun ThirdsGrid(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        for (i in 1..2) {
            val x = w * i / 3f
            drawLine(GridLine, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            val y = h * i / 3f
            drawLine(GridLine, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
    }
}
