package com.perfectframe.camera.film

import android.content.Context
import android.net.Uri
import com.perfectframe.camera.camera.CameraController
import com.perfectframe.camera.editor.processCapturedPhoto
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a loaded roll currently is in the shoot → wind → develop cycle. */
enum class RollPhase { EMPTY, LOADED, WINDING, DEVELOPING, FINISHED }

data class FilmRollState(
    val stock: FilmStock? = null,
    val phase: RollPhase = RollPhase.EMPTY,
    val frame: Int = 0,
    val exposures: Int = 24,
    val developProgress: Float = 0f,
) {
    val isBusy: Boolean get() = phase == RollPhase.WINDING || phase == RollPhase.DEVELOPING
    val canShoot: Boolean get() = phase == RollPhase.LOADED
}

/**
 * Drives the Film-mode ritual: load a stock, shoot a frame (wind → shutter → develop), advance,
 * repeat until the roll runs out. No auto-framing here — a real camera doesn't reframe for you —
 * just the loaded stock's look plus a light auto-tone.
 *
 * The 30-second develop is real elapsed time by design: [shoot] waits for it in full even if the
 * image processing itself finishes sooner, so the pacing is authentic rather than a progress bar
 * racing ahead of a "photo's already done" reveal.
 */
class FilmRollController(private val developMillis: Long = 30_000L) {

    private val _state = MutableStateFlow(FilmRollState())
    val state: StateFlow<FilmRollState> = _state.asStateFlow()

    fun loadRoll(stock: FilmStock, exposures: Int = 24) {
        _state.value = FilmRollState(stock = stock, phase = RollPhase.LOADED, frame = 1, exposures = exposures)
    }

    fun unload() {
        _state.value = FilmRollState()
    }

    suspend fun shoot(
        context: Context,
        camera: CameraController,
        onDeveloped: (Uri) -> Unit,
    ) = coroutineScope {
        val loaded = _state.value
        if (!loaded.canShoot || loaded.stock == null) return@coroutineScope

        _state.value = loaded.copy(phase = RollPhase.WINDING)
        delay(900) // the wind-on beat before the shutter fires

        val file = camera.captureToTempFile()

        _state.value = _state.value.copy(phase = RollPhase.DEVELOPING, developProgress = 0f)
        val progressJob = launch {
            val stepMs = 200L
            var elapsed = 0L
            while (elapsed < developMillis) {
                delay(stepMs)
                elapsed += stepMs
                _state.value = _state.value.copy(
                    developProgress = (elapsed.toFloat() / developMillis).coerceAtMost(1f),
                )
            }
        }

        val uri = file?.let {
            processCapturedPhoto(
                context = context,
                sourceFile = it,
                cropBox = null,
                straightenDegrees = null,
                autoTone = true,
                look = loaded.stock.look,
            )
        }
        progressJob.join()
        if (uri != null) onDeveloped(uri)

        val after = _state.value
        _state.value = if (after.frame >= after.exposures) {
            after.copy(phase = RollPhase.FINISHED)
        } else {
            after.copy(phase = RollPhase.LOADED, frame = after.frame + 1, developProgress = 0f)
        }
    }
}
