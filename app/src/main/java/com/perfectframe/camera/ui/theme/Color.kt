package com.perfectframe.camera.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic palette for the viewfinder (spec §4). The app is always a dark, full-bleed surface,
 * so there is one considered dark palette rather than a light/dark pair.
 *
 * The language: near-black ground, a single mint [Accent] that means "good / on-target", a warm
 * [Warn] amber for "needs attention", and restrained translucent "glass" tokens for every HUD
 * chrome element so the live scene always reads through.
 */

// Ground
val Surface0 = Color(0xFF08080A)

// Meaning colors
val Accent = Color(0xFF7DF9C6)        // on-target / ideal / level
val AccentSoft = Color(0xFF4FD1A6)    // dimmer accent for fills & trails
val Warn = Color(0xFFFFC24B)          // attention (objects, tilt, guidance)

// Text
val TextPrimary = Color(0xFFF6F7F9)
val TextSecondary = Color(0xB3F6F7F9) // 70%
val TextTertiary = Color(0x80F6F7F9)  // 50%

// Glass chrome (translucent, so the scene shows through — no real background blur is used so
// the look is identical across every supported API level, spec §6 "works on real devices").
val GlassFill = Color(0x59121417)     // ~35% smoked charcoal
val GlassFillStrong = Color(0x8C0E1013)
val GlassBorder = Color(0x2EFFFFFF)   // hairline top-light edge
val GlassHighlight = Color(0x14FFFFFF)

// Overlay
val Scrim = Color(0xAA000000)         // dims the area outside the suggested crop
val GridLine = Color(0x33FFFFFF)      // faint rule-of-thirds guides
val SubjectTint = Color(0x66FFFFFF)   // subtle subject tracker
