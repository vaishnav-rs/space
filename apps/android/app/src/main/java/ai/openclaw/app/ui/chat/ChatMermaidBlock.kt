package ai.openclaw.app.ui.chat

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.commonmark.node.FencedCodeBlock

internal fun isChatMermaidFence(
  block: FencedCodeBlock,
  isStreaming: Boolean,
): Boolean =
  block.info
    .orEmpty()
    .trim()
    .takeWhile { !it.isWhitespace() }
    .equals("mermaid", ignoreCase = true) &&
    (!isStreaming || block.closingFenceLength != null)

/** Mermaid fence drawn natively (flowcharts, sequence diagrams, pie charts); other kinds show their source. */
@Composable
internal fun ChatMermaidBlock(source: String) {
  val context = LocalContext.current
  val colors = ClawTheme.colors
  val diagram = remember(source) { parseMermaid(source) }
  var showSource by rememberSaveable(source) { mutableStateOf(false) }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, colors.border),
    color = colors.surfaceRaised,
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      if (diagram == null || showSource) {
        Column(modifier = Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (diagram == null) {
            Text(
              nativeString("This diagram type is shown as source."),
              modifier = Modifier.padding(horizontal = 10.dp),
              style = ClawTheme.type.caption,
              color = colors.textMuted,
            )
          }
          ChatCodeBlock(source, language = null)
        }
      } else {
        Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 48.dp, bottom = 8.dp)) { NativeMermaidDiagram(diagram) }
      }
      Surface(modifier = Modifier.align(Alignment.TopEnd), shape = RoundedCornerShape(8.dp), color = colors.surfaceRaised.copy(alpha = 0.92f)) {
        Row {
          if (diagram != null) {
            IconButton(onClick = { showSource = !showSource }) {
              Icon(Icons.Default.Code, contentDescription = if (showSource) nativeString("View diagram") else nativeString("View source"), modifier = Modifier.size(18.dp), tint = colors.textMuted)
            }
          }
          IconButton(onClick = { copyChatText(context, source) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = nativeString("Copy diagram source"), modifier = Modifier.size(18.dp), tint = colors.textMuted)
          }
        }
      }
    }
  }
}
