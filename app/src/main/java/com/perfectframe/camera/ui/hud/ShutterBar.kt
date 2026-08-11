package com.perfectframe.camera.ui.hud

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.perfectframe.camera.ui.components.GlassSurface
import com.perfectframe.camera.ui.theme.Accent
import com.perfectframe.camera.ui.theme.TextPrimary
import com.perfectframe.camera.ui.theme.TextSecondary

/**
 * Bottom control bar (spec §3): a settings entry, the shutter, and an "AUTO" affordance that
 * reinforces the product stance — exposure is automatic, there are no dials to fiddle. The
 * shutter is always enabled (the user is never blocked from shooting) but glows and gently
 * breathes when the framing is ideal, pulling the eye to press at the right moment.
 */
@Composable
fun ShutterBar(
    isIdeal: Boolean,
    lastCapture: android.net.Uri?,
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        GalleryButton(lastCapture = lastCapture, onClick = onOpenGallery)

        ShutterButton(isIdeal = isIdeal, onCapture = onCapture)

        GlassSurface(shape = CircleShape) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clickable(onClick = onSwitchCamera),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.FlipCameraAndroid,
                    contentDescription = "Switch camera",
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** Opens the in-app gallery; shows the most recent shot as its thumbnail once one exists. */
@Composable
private fun GalleryButton(lastCapture: android.net.Uri?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (lastCapture != null) {
            AsyncImage(
                model = lastCapture,
                contentDescription = "Open gallery",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
            )
        } else {
            GlassSurface(shape = RoundedCornerShape(14.dp)) {
                Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = "Open gallery",
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(
    isIdeal: Boolean,
    onCapture: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "press")

    val ringColor by animateColorAsState(
        targetValue = if (isIdeal) Accent else TextPrimary,
        animationSpec = tween(300),
        label = "ring",
    )
    val breathe = rememberInfiniteTransition(label = "shutterBreathe")
    val glow by breathe.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "shutterGlow",
    )

    Box(
        modifier = Modifier
            .size(82.dp)
            .scale(pressScale)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onCapture),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(82.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val outer = size.minDimension / 2f

            if (isIdeal) {
                drawCircle(
                    color = Accent.copy(alpha = 0.18f + 0.14f * (1f - glow)),
                    radius = outer * (0.98f + 0.06f * glow),
                    center = c,
                )
            }
            // Outer ring
            drawCircle(
                color = ringColor,
                radius = outer - 3.dp.toPx(),
                center = c,
                style = Stroke(width = 4.dp.toPx()),
            )
            // Inner fill
            drawCircle(
                color = ringColor,
                radius = outer - 12.dp.toPx(),
                center = c,
            )
        }
    }
}
