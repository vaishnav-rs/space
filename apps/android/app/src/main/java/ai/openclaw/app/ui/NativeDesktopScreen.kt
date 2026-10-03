package ai.openclaw.app.ui

import ai.openclaw.app.DesktopSource
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.NativeDesktopState
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawPill
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawTextField
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Native remote desktop: RFB frames drawn to a Bitmap, touch mapped to pointer and key events. */
@Composable
internal fun NativeDesktopScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val isConnected by viewModel.isConnected.collectAsState()
  ControlUiScreenFrame(
    title = nativeString("Desktop"),
    icon = SettingsRoute.Desktop.icon,
    onBack = onBack,
    modifier = Modifier.imePadding(),
  ) {
    if (!isConnected) {
      ControlUiUnavailable(
        title = nativeString("Desktop needs a connected gateway"),
        detail = nativeString("Connect to your gateway to view a machine screen."),
      )
    } else {
      DesktopBody(viewModel)
    }
  }
}

@Composable
private fun DesktopBody(viewModel: MainViewModel) {
  val desktop = viewModel.nativeDesktop
  val state by desktop.state.collectAsState()
  var sources by remember { mutableStateOf(listOf(DesktopSource("host", null, "This gateway"))) }
  var selected by remember { mutableStateOf(0) }
  var password by remember { mutableStateOf("") }
  var typed by remember { mutableStateOf("") }
  LaunchedEffect(Unit) { sources = desktop.sources() }
  DisposableEffect(Unit) { onDispose { desktop.disconnect() } }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      sources.forEachIndexed { i, s -> ClawPill(s.label, selected = i == selected, onClick = { selected = i }) }
    }
    when (val s = state) {
      NativeDesktopState.Idle, is NativeDesktopState.Failed -> {
        (s as? NativeDesktopState.Failed)?.let { Text(it.message, style = ClawTheme.type.caption, color = ClawTheme.colors.danger) }
        ClawTextField(password, { password = it }, nativeString("Desktop password (only if it asks)"), secret = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ClawPrimaryButton(nativeString("View"), onClick = { desktop.connect(sources[selected], wantControl = false, password = password.ifEmpty { null }) })
          ClawPrimaryButton(nativeString("Control"), onClick = { desktop.connect(sources[selected], wantControl = true, password = password.ifEmpty { null }) })
        }
      }

      NativeDesktopState.Connecting -> {
        Text(nativeString("Connecting…"), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      }

      is NativeDesktopState.Live -> {
        var size by remember { mutableStateOf(IntSize.Zero) }
        val image = remember(s.serial) { s.frame.asImageBitmap() }
        fun remote(p: Offset): Pair<Int, Int> {
          val sx = s.frame.width.toFloat() / size.width.coerceAtLeast(1)
          return (p.x * sx).toInt() to (p.y * sx).toInt()
        }
        Box(
          Modifier
            .fillMaxWidth()
            .background(ClawTheme.colors.codeBg)
            .onSizeChanged { size = it }
            .pointerInput(s.control, s.frame.width) {
              detectTapGestures(
                onTap = { p ->
                  val (x, y) = remote(p)
                  desktop.pointer(x, y, 1)
                  desktop.pointer(x, y, 0)
                },
                onLongPress = { p ->
                  val (x, y) = remote(p)
                  desktop.pointer(x, y, 4)
                  desktop.pointer(x, y, 0)
                },
              )
            }.pointerInput(s.control, s.frame.width) {
              var last = Offset.Zero
              detectDragGestures(
                onDragStart = { p ->
                  last = p
                  val (x, y) = remote(p)
                  desktop.pointer(x, y, 1)
                },
                onDrag = { change, _ ->
                  last = change.position
                  val (x, y) = remote(change.position)
                  desktop.pointer(x, y, 1)
                },
                onDragEnd = {
                  val (x, y) = remote(last)
                  desktop.pointer(x, y, 0)
                },
              )
            },
        ) {
          Image(image, contentDescription = s.name, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
        }
        Text(
          s.name + " · " + if (s.control) nativeString("tap to click, long-press to right-click, drag to drag") else nativeString("view only"),
          style = ClawTheme.type.captionSmall,
          color = ClawTheme.colors.textMuted,
        )
        if (s.control) {
          Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Enter" to 0xff0d, "Esc" to 0xff1b, "Tab" to 0xff09, "Backspace" to 0xff08, "↑" to 0xff52, "↓" to 0xff54, "←" to 0xff51, "→" to 0xff53)
              .forEach { (label, sym) -> ClawPill(label, onClick = { desktop.key(sym) }) }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ClawTextField(typed, { typed = it }, nativeString("Type on the desktop"), modifier = Modifier.weight(1f))
            ClawPrimaryButton(nativeString("Send"), onClick = {
              desktop.type(typed)
              typed = ""
            })
          }
        }
        ClawPrimaryButton(nativeString("Disconnect"), onClick = { desktop.disconnect() })
      }
    }
  }
}
