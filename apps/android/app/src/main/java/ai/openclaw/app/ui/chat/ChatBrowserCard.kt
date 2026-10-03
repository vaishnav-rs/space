package ai.openclaw.app.ui.chat

import ai.openclaw.app.NodeRuntime
import ai.openclaw.app.chat.ChatBrowserTab
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.NativeBrowserSession
import ai.openclaw.app.NativeBrowserState
import ai.openclaw.app.ui.design.ClawPill
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawTextField
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import ai.openclaw.app.ui.design.ClawPlainIconButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keep the browser mounted outside virtualized messages without changing transcript item indices. */
@Composable
internal fun ChatBrowserLayout(
  browser: @Composable (Dp) -> Unit,
  modifier: Modifier = Modifier,
  transcript: @Composable BoxScope.() -> Unit,
) {
  BoxWithConstraints(modifier) {
    val availableHeight = maxHeight
    Column {
      Box(Modifier.weight(1f), content = transcript)
      browser(availableHeight)
    }
  }
}

@Composable
internal fun ChatBrowserCard(
  tab: ChatBrowserTab,
  sessionKey: String,
  page: NodeRuntime.GatewayControlPage?,
  connected: Boolean,
  canControl: Boolean,
  availableHeight: Dp,
  session: NativeBrowserSession,
  onClose: () -> Unit,
) {
  val identity = listOf(sessionKey, tab.target, tab.node, tab.profile, tab.targetId)
  var expanded by rememberSaveable(page, identity) { mutableStateOf(false) }
  val state by session.state.collectAsState()
  BackHandler(enabled = expanded) { expanded = false }
  val notice =
    when {
      !connected || page == null -> nativeString("Browser offline. Reconnect to continue in this tab.")
      !canControl -> nativeString("Browser control is unavailable with your current Gateway permissions.")
      !page.browserFocusAvailable -> nativeString("Browser view unavailable. Update your Gateway and use its bundled Control UI.")
      else -> null
    }
  if (notice == null) {
    DisposableEffect(page, identity) {
      session.start(tab, sessionKey)
      onDispose { session.stop() }
    }
  }
  Surface(
    shape = RoundedCornerShape(ClawTheme.radii.sheet),
    color = ClawTheme.colors.surfaceRaised,
    border = BorderStroke(1.dp, ClawTheme.colors.border),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(nativeString("Agent browser"), style = ClawTheme.type.body, color = ClawTheme.colors.text)
          (tab.title?.takeIf { it.isNotBlank() } ?: tab.url)?.let { title ->
            Text(title, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
        ClawPlainIconButton(
          icon = if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
          contentDescription = if (expanded) nativeString("Collapse browser") else nativeString("Control browser"),
          onClick = { expanded = !expanded },
          enabled = notice == null,
        )
        ClawPlainIconButton(icon = Icons.Default.Close, contentDescription = nativeString("Close"), onClick = onClose)
      }
      if (notice != null) {
        Text(notice, modifier = Modifier.padding(12.dp), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      } else {
        val viewHeight = if (expanded) (availableHeight * 0.6f).coerceIn(0.dp, 420.dp) else (availableHeight * 0.3f).coerceIn(0.dp, 180.dp)
        var size by remember { mutableStateOf(IntSize.Zero) }
        Box(Modifier.fillMaxWidth().height(viewHeight).onSizeChanged { size = it }) {
          when (val s = state) {
            is NativeBrowserState.Live -> {
              val image = remember(s.serial) { s.frame.asImageBitmap() }
              Image(
                image,
                contentDescription = s.url,
                contentScale = ContentScale.Fit,
                modifier =
                  Modifier
                    .matchParentSize()
                    .pointerInput(expanded, s.cssWidth, s.frame.width) {
                      detectTapGestures { p ->
                        if (!expanded) {
                          expanded = true
                        } else {
                          // The frame is fit inside the box; map the tap through the same scale.
                          val scale = minOf(size.width.toFloat() / s.frame.width, size.height.toFloat() / s.frame.height)
                          val offX = (size.width - s.frame.width * scale) / 2f
                          val offY = (size.height - s.frame.height * scale) / 2f
                          val css = s.cssWidth.toFloat() / s.frame.width
                          val fx = ((p.x - offX) / scale).coerceIn(0f, s.frame.width.toFloat())
                          val fy = ((p.y - offY) / scale).coerceIn(0f, s.frame.height.toFloat())
                          session.click((fx * css).toInt(), (fy * css).toInt())
                        }
                      }
                    },
              )
            }

            NativeBrowserState.Idle, NativeBrowserState.Connecting -> {
              Text(nativeString("Connecting to the browser…"), modifier = Modifier.padding(12.dp), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
            }

            is NativeBrowserState.Failed -> {
              Text(s.message, modifier = Modifier.padding(12.dp), style = ClawTheme.type.caption, color = ClawTheme.colors.danger)
            }
          }
        }
        if (expanded && state is NativeBrowserState.Live) {
          var typed by remember { mutableStateOf("") }
          Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Enter", "Escape", "Tab", "Backspace", "ArrowUp", "ArrowDown").forEach { k -> ClawPill(k, onClick = { session.press(k) }) }
          }
          Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ClawTextField(typed, { typed = it }, nativeString("Type into the page"), modifier = Modifier.weight(1f))
            ClawPrimaryButton(nativeString("Send"), onClick = {
              session.type(typed)
              typed = ""
            })
          }
        }
      }
    }
  }
}
