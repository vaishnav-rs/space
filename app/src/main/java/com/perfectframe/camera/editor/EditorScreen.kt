package com.perfectframe.camera.editor

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.perfectframe.camera.ui.theme.Accent
import com.perfectframe.camera.ui.theme.Surface0
import com.perfectframe.camera.ui.theme.TextPrimary
import com.perfectframe.camera.ui.theme.TextSecondary
import com.perfectframe.camera.ui.theme.TextTertiary
import kotlinx.coroutines.launch

/**
 * Full-screen non-destructive editor. Live preview applies the exact same [ColorMatrix] used on
 * export (via Compose [ColorFilter]), so what you see is what gets saved as a *new* photo.
 */
@Composable
fun EditorScreen(
    imageUri: Uri,
    onClose: () -> Unit,
    onSaved: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var adjustments by remember { mutableStateOf(EditAdjustments()) }
    var saving by remember { mutableStateOf(false) }

    val liveFilter = ColorFilter.colorMatrix(ColorMatrix(adjustments.toFloatArray()))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface0),
    ) {
        // Top bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(Icons.Rounded.Close, "Close", onClose)
            Spacer(Modifier.width(12.dp))
            Text("Edit", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (saving) {
                CircularProgressIndicator(
                    color = Accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Text(
                    text = "Save",
                    color = if (adjustments.isModified) Accent else TextTertiary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = adjustments.isModified) {
                            saving = true
                            scope.launch {
                                val uri = renderEditedImage(context, imageUri, adjustments)
                                saving = false
                                if (uri != null) onSaved(uri)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

        // Preview.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = imageUri,
                contentDescription = "Editing preview",
                colorFilter = liveFilter,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(adjustments.rotationDeg.toFloat()),
            )
        }

        // Controls.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D0F12))
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(FilmLook.entries.toList()) { look ->
                    LookThumb(
                        imageUri = imageUri,
                        look = look,
                        selected = look == adjustments.look,
                        onClick = { adjustments = adjustments.copy(look = look) },
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                EditSlider("Exposure", adjustments.exposure) { adjustments = adjustments.copy(exposure = it) }
                EditSlider("Contrast", adjustments.contrast) { adjustments = adjustments.copy(contrast = it) }
                EditSlider("Saturation", adjustments.saturation) { adjustments = adjustments.copy(saturation = it) }
                EditSlider("Warmth", adjustments.temperature) { adjustments = adjustments.copy(temperature = it) }
                EditSlider("Tint", adjustments.tint) { adjustments = adjustments.copy(tint = it) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircle(Icons.Rounded.RotateRight, "Rotate") {
                    adjustments = adjustments.copy(rotationDeg = (adjustments.rotationDeg + 90) % 360)
                }
                Text("Rotate", color = TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LookThumb(imageUri: Uri, look: FilmLook, selected: Boolean, onClick: () -> Unit) {
    val filter = ColorFilter.colorMatrix(ColorMatrix(EditAdjustments(look = look).toFloatArray()))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(
            model = imageUri,
            contentDescription = look.displayName,
            colorFilter = filter,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) Accent else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                )
                .clickable(onClick = onClick),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = look.displayName,
            color = if (selected) Accent else TextTertiary,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun EditSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            Text("%+.0f".format(value * 100), color = TextTertiary, fontSize = 12.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = -1f..1f)
    }
}

@Composable
private fun IconCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x22FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = TextPrimary, modifier = Modifier.size(20.dp))
    }
}
