package com.perfectframe.camera.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.perfectframe.camera.camera.CameraController

/**
 * The single screen. A full-bleed viewfinder with overlays drawn on top (spec §3).
 *
 * Commit 1: just the live preview surface. Overlays (HUD, perfect-frame box, level indicator,
 * shutter bar) are layered into this [Box] in later passes.
 */
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { CameraController(context.applicationContext) }

    // A single PreviewView instance, created once and reused across recompositions.
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Bind (and re-bind if the lifecycle owner changes). CameraController.bindPreview suspends
    // until the ProcessCameraProvider is ready, so this stays off the main thread's critical path.
    LaunchedEffect(lifecycleOwner, previewView) {
        controller.bindPreview(lifecycleOwner, previewView)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView }
        )
        // Overlays go here in later passes.
    }
}
