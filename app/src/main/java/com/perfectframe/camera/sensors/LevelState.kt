package com.perfectframe.camera.sensors

/**
 * Device orientation for the horizon/level indicator (spec §2 Phase 3).
 *
 * [rollDegrees] is the left/right tilt that matters for a level horizon (0° = level);
 * [pitchDegrees] is the forward/back tilt (useful for "keep the phone upright"). [isLevel] is
 * true when roll is within the tolerance band, which drives the distinct "level" visual state
 * rather than a bare number.
 */
data class LevelState(
    val rollDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val isLevel: Boolean = false,
    val hasSensors: Boolean = true,
)
