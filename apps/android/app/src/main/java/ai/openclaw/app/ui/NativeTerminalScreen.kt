package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.NativeTerminalState
import ai.openclaw.app.TermRun
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawPill
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawTextField
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private val ansiPalette =
  listOf(
    Color(0xFF000000), Color(0xFFE5534B), Color(0xFF57AB5A), Color(0xFFC69026),
    Color(0xFF539BF5), Color(0xFFB083F0), Color(0xFF39C5CF), Color(0xFFADBAC7),
    Color(0xFF636E7B), Color(0xFFFF938A), Color(0xFF6BC46D), Color(0xFFDAAA3F),
    Color(0xFF6CB6FF), Color(0xFFDCBDFB), Color(0xFF56D4DD), Color(0xFFCDD9E5),
  )

internal fun termLine(
  runs: List<TermRun>,
  default: Color,
): AnnotatedString =
  buildAnnotatedString {
    runs.forEach { run ->
      val color = if (run.fg in 1..16) ansiPalette[run.fg - 1] else default
      withStyle(SpanStyle(color = color, fontWeight = if (run.bold) FontWeight.Bold else null)) { append(run.text) }
    }
  }

/** Native gateway shell: ANSI text view plus a command line and control keys. No web content. */
@Composable
internal fun NativeTerminalScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val isConnected by viewModel.isConnected.collectAsState()
  ControlUiScreenFrame(
    title = nativeString("Terminal"),
    icon = SettingsRoute.Terminal.icon,
    onBack = onBack,
    modifier = Modifier.imePadding(),
  ) {
    if (!isConnected) {
      ControlUiUnavailable(
        title = nativeString("Terminal needs a connected gateway"),
        detail = nativeString("Connect to your gateway to open a shell in the agent workspace."),
      )
    } else {
      TerminalBody(viewModel)
    }
  }
}

@Composable
private fun TerminalBody(viewModel: MainViewModel) {
  val term = viewModel.nativeTerminal
  val state by term.state.collectAsState()
  val revision by term.revision.collectAsState()
  var command by remember { mutableStateOf("") }
  val listState = rememberLazyListState()
  val textColor = ClawTheme.colors.codeText

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val cols = (maxWidth.value / 7.2f).toInt()
    val rows = (maxHeight.value / 17f).toInt()
    LaunchedEffect(Unit) { term.open(cols, rows) }
    LaunchedEffect(cols, rows) { term.resize(cols, rows) }
    DisposableEffect(Unit) { onDispose { term.close() } }
    val lines = remember(revision) { term.lines() }
    LaunchedEffect(lines.size, revision) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      when (val s = state) {
        is NativeTerminalState.Open -> {
          Text(
            "${s.shell} · ${s.cwd}" + if (s.confined) " · " + nativeString("sandboxed") else "",
            style = ClawTheme.type.captionSmall,
            color = ClawTheme.colors.textMuted,
          )
        }

        is NativeTerminalState.Ended -> {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.reason, style = ClawTheme.type.caption, color = ClawTheme.colors.danger, modifier = Modifier.weight(1f))
            ClawPrimaryButton(nativeString("Reopen"), onClick = { term.close(); term.open(cols, rows) })
          }
        }

        else -> {
          Text(nativeString("Opening shell…"), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
        }
      }
      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth().background(ClawTheme.colors.codeBg).padding(8.dp),
      ) {
        itemsIndexed(lines) { _, runs ->
          Text(
            termLine(runs, textColor),
            style = ClawTheme.type.mono,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
          )
        }
      }
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
          "Ctrl-C" to "\u0003", "Ctrl-D" to "\u0004", "Tab" to "\t", "Esc" to "\u001b",
          "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
        ).forEach { (label, seq) -> ClawPill(label, onClick = { term.send(seq) }) }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ClawTextField(
          value = command,
          onValueChange = { command = it },
          placeholder = nativeString("Command"),
          modifier = Modifier.weight(1f),
          maxLines = 3,
        )
        ClawPrimaryButton(nativeString("Run"), onClick = {
          term.send(command + "\r")
          command = ""
        })
      }
    }
  }
}
