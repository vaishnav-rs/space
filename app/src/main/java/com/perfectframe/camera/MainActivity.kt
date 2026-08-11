package com.perfectframe.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.perfectframe.camera.ui.CameraScreen
import com.perfectframe.camera.ui.theme.PerfectFrameTheme

/**
 * Single-activity, single-screen app. Everything is an overlay on the live viewfinder — no
 * navigation, no tabs (spec §3). This Activity only owns the CAMERA runtime permission gate;
 * all camera and UI state lives in Compose + [CameraViewModel].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PerfectFrameTheme {
                CameraPermissionGate {
                    CameraScreen()
                }
            }
        }
    }
}

/**
 * Requests CAMERA at runtime and only renders [content] once granted. Kept intentionally
 * plain — this is a gate, not part of the premium viewfinder UI.
 */
@androidx.compose.runtime.Composable
private fun CameraPermissionGate(content: @androidx.compose.runtime.Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> granted = isGranted }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.camera_permission_rationale),
                textAlign = TextAlign.Center
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text(androidx.compose.ui.res.stringResource(R.string.grant_permission))
            }
        }
    }
}
