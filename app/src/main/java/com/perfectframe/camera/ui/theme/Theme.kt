package com.perfectframe.camera.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// NOTE: minimal in commit 1 (just enough for the preview to render on a black ground).
// The full Liquid-Glass color/type system (spec §4) is filled in during the UI pass.
private val PerfectFrameColorScheme = darkColorScheme(
    primary = Accent,
    background = Surface0,
    surface = Surface0,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

@Composable
fun PerfectFrameTheme(
    // The viewfinder is always a dark, full-bleed surface regardless of system setting.
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PerfectFrameColorScheme,
        typography = PerfectFrameTypography,
        content = content
    )
}
