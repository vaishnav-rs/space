package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.NativeBoard
import ai.openclaw.app.NativeBoardWidget
import ai.openclaw.app.ReportBlock
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.parseReportBlocks
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawPill
import ai.openclaw.app.ui.design.ClawPlainIconButton
import ai.openclaw.app.ui.design.ClawTheme
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private sealed interface BoardLoad {
  data object Loading : BoardLoad

  data class Ready(
    val board: NativeBoard,
  ) : BoardLoad

  data class Failed(
    val message: String,
  ) : BoardLoad
}

/**
 * Native session dashboard: the agent's board tabs (Today, reports, progress) rendered as
 * Compose, from the same `board.get` snapshot the web dashboard uses. No web content.
 */
@Composable
internal fun NativeBoardScreen(
  viewModel: MainViewModel,
  sessionKey: String,
  onBack: () -> Unit,
) {
  var load by remember(sessionKey) { mutableStateOf<BoardLoad>(BoardLoad.Loading) }
  var reload by remember { mutableIntStateOf(0) }
  var selected by rememberSaveable(sessionKey) { mutableStateOf<String?>(null) }

  LaunchedEffect(sessionKey, reload) {
    while (true) {
      try {
        load = BoardLoad.Ready(viewModel.loadBoard(sessionKey))
      } catch (err: CancellationException) {
        throw err
      } catch (err: Throwable) {
        if (load !is BoardLoad.Ready) load = BoardLoad.Failed(err.message ?: nativeString("Could not load the dashboard."))
      }
      delay(10_000)
    }
  }

  ControlUiScreenFrame(
    title = nativeString("Dashboard"),
    icon = Icons.Outlined.Dashboard,
    onBack = onBack,
    headerActions = {
      ClawPlainIconButton(
        icon = Icons.Outlined.Refresh,
        contentDescription = nativeString("Refresh"),
        onClick = { reload++ },
      )
    },
  ) {
    when (val state = load) {
      BoardLoad.Loading -> {
        ControlUiUnavailable(nativeString("Loading dashboard"), nativeString("Fetching this session's tabs."))
      }

      is BoardLoad.Failed -> {
        ControlUiUnavailable(nativeString("Dashboard unavailable"), state.message)
      }

      is BoardLoad.Ready -> {
        BoardContent(state.board, selected, onSelect = { selected = it })
      }
    }
  }
}

@Composable
private fun BoardContent(
  board: NativeBoard,
  selected: String?,
  onSelect: (String) -> Unit,
) {
  if (board.tabs.isEmpty()) {
    ControlUiUnavailable(
      nativeString("Nothing here yet"),
      nativeString("Ask the agent to build a dashboard tab, for example \"keep a Today tab\"."),
    )
    return
  }
  val tabId = board.tabs.firstOrNull { it.id == selected }?.id ?: board.tabs.first().id
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      board.tabs.forEach { tab -> ClawPill(tab.title, selected = tab.id == tabId, onClick = { onSelect(tab.id) }) }
    }
    val widgets = board.widgetsFor(tabId)
    if (widgets.isEmpty()) {
      Text(nativeString("This tab has no widgets yet."), style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(widgets, key = { it.name }) { widget -> WidgetCard(widget) }
    }
  }
}

@Composable
private fun WidgetCard(widget: NativeBoardWidget) {
  ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    widget.title?.let { Text(it, style = ClawTheme.type.section, color = ClawTheme.colors.text) }
    when (widget.pluginKind) {
      "session:report" -> {
        val blocks = remember(widget.props) { parseReportBlocks(widget.props) }
        if (blocks.isEmpty()) MutedText(nativeString("This report is empty."))
        blocks.forEach { ReportBlockView(it) }
      }

      else -> {
        PropsView(widget)
      }
    }
  }
}

@Composable
private fun MutedText(text: String) = Text(text, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)

/** Progress, website, and unknown widgets: show their declared data as native rows. */
@Composable
private fun PropsView(widget: NativeBoardWidget) {
  val rows = remember(widget.props) { flattenProps(widget.props) }
  if (rows.isEmpty()) {
    MutedText(nativeString("%1\$s widgets are shown in the web dashboard only.").replace("%1\$s", widget.pluginKind ?: widget.contentKind))
    return
  }
  rows.forEach { (k, v) ->
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(k, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted, modifier = Modifier.width(96.dp))
      Text(v, style = ClawTheme.type.body, color = ClawTheme.colors.text, modifier = Modifier.weight(1f))
    }
  }
}

internal fun flattenProps(props: JsonObject?): List<Pair<String, String>> =
  props.orEmpty().entries.mapNotNull { (k, v) ->
    when (v) {
      is JsonPrimitive -> k to v.content
      is JsonArray -> k to v.joinToString(", ") { (it as? JsonPrimitive)?.content ?: "…" }
      else -> null
    }
  }

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

@Composable
private fun ReportBlockView(block: ReportBlock) {
  when (block) {
    is ReportBlock.Text -> {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.title?.let { Text(it, style = ClawTheme.type.label, color = ClawTheme.colors.text) }
        Text(block.text, style = ClawTheme.type.body, color = ClawTheme.colors.text)
      }
    }

    is ReportBlock.Metrics -> {
      block.items.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          pair.forEach { m ->
            Column(Modifier.weight(1f)) {
              Text(m.value, style = ClawTheme.type.title, color = ClawTheme.colors.text, fontWeight = FontWeight.SemiBold)
              Text(m.label, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
              m.detail?.let { Text(it, style = ClawTheme.type.captionSmall, color = ClawTheme.colors.textSubtle) }
            }
          }
          if (pair.size == 1) Box(Modifier.weight(1f))
        }
      }
    }

    is ReportBlock.Table -> {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.title?.let { Text(it, style = ClawTheme.type.label, color = ClawTheme.colors.text) }
        Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            block.columns.forEach { Text(it, Modifier.width(110.dp), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted, fontWeight = FontWeight.SemiBold) }
          }
          block.rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              row.forEach { Text(it, Modifier.width(110.dp), style = ClawTheme.type.body, color = ClawTheme.colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
          }
        }
      }
    }

    is ReportBlock.Chart -> {
      ChartView(block)
    }

    is ReportBlock.Links -> {
      val context = LocalContext.current
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.title?.let { Text(it, style = ClawTheme.type.label, color = ClawTheme.colors.text) }
        block.items.forEach { link ->
          Column(Modifier.clickable { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(link.label, link.url)) }) {
            Text(link.label, style = ClawTheme.type.body, color = ClawTheme.colors.accent)
            Text(link.detail ?: link.url, style = ClawTheme.type.captionSmall, color = ClawTheme.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
        MutedText(nativeString("Tap a link to copy it."))
      }
    }
  }
}

@Composable
private fun ChartView(block: ReportBlock.Chart) {
  val accent = ClawTheme.colors.accent
  val grid = ClawTheme.colors.border
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    block.title?.let { Text(it, style = ClawTheme.type.label, color = ClawTheme.colors.text) }
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
      val values = block.points.map { it.value }
      val max = maxOf(values.max(), 0.0)
      val min = minOf(values.min(), 0.0)
      val span = (max - min).takeIf { it > 0 } ?: 1.0
      fun y(v: Double) = (size.height * (1 - (v - min) / span)).toFloat()
      drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1f)
      val step = size.width / block.points.size
      if (block.line) {
        val path = Path()
        block.points.forEachIndexed { i, p ->
          val pt = Offset(step * i + step / 2, y(p.value))
          if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        drawPath(path, accent, style = Stroke(width = 4f))
      } else {
        block.points.forEachIndexed { i, p ->
          val top = y(maxOf(p.value, 0.0))
          val bottom = y(minOf(p.value, 0.0))
          drawRect(accent, Offset(step * i + step * 0.15f, top), Size(step * 0.7f, maxOf(bottom - top, 2f)))
        }
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(block.points.first().label, style = ClawTheme.type.captionSmall, color = ClawTheme.colors.textMuted)
      Text(block.points.last().label, style = ClawTheme.type.captionSmall, color = ClawTheme.colors.textMuted)
    }
  }
}
