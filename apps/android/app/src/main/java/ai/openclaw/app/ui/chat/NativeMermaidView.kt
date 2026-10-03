package ai.openclaw.app.ui.chat

import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val NODE_H = 44f
private const val GAP_MAIN = 64f
private const val GAP_CROSS = 28f

private class PlacedNode(
  val node: FlowNode,
  val layout: TextLayoutResult,
  val w: Float,
  val h: Float,
  var x: Float = 0f,
  var y: Float = 0f,
) {
  val cx get() = x + w / 2
  val cy get() = y + h / 2
}

/** Draws a parsed diagram on a Canvas. Wide diagrams scroll horizontally. */
@Composable
internal fun NativeMermaidDiagram(
  diagram: MermaidDiagram,
  modifier: Modifier = Modifier,
) {
  val measurer = rememberTextMeasurer()
  val colors = ClawTheme.colors
  val style = TextStyle(color = colors.text, fontSize = 12.sp)
  val density = androidx.compose.ui.platform.LocalDensity.current
  when (diagram) {
    is MermaidDiagram.Flow -> {
      val layout = remember(diagram) { layoutFlow(diagram, measurer, style) }
      Canvas(modifier.horizontalScroll(rememberScrollState()).width(with(density) { layout.width.toDp() }).height(with(density) { layout.height.toDp() })) {
        drawFlow(diagram, layout, measurer, style, colors.accent, colors.borderStrong, colors.surface, colors.textMuted)
      }
    }

    is MermaidDiagram.Sequence -> {
      val colW = 120f
      val width = colW * diagram.participants.size
      val height = 70f + diagram.messages.size * 38f
      Canvas(modifier.horizontalScroll(rememberScrollState()).width(with(density) { width.toDp() }).height(with(density) { height.toDp() })) {
        drawSequence(diagram, colW, measurer, style, colors.accent, colors.borderStrong, colors.surface)
      }
    }

    is MermaidDiagram.Pie -> {
      Canvas(modifier.fillMaxWidth().height(180.dp)) {
        drawPie(diagram, measurer, style)
      }
    }
  }
}

private class FlowLayout(
  val placed: Map<String, PlacedNode>,
  val width: Float,
  val height: Float,
)

private fun layoutFlow(
  flow: MermaidDiagram.Flow,
  measurer: TextMeasurer,
  style: TextStyle,
): FlowLayout {
  val layers = flowLayers(flow)
  val placed =
    flow.nodes.associate { n ->
      val text = measurer.measure(n.label, style)
      val w = (text.size.width + 36f).coerceAtLeast(72f) + if (n.shape == NodeShape.Diamond) 28f else 0f
      val h = if (n.shape == NodeShape.Diamond) NODE_H + 16f else NODE_H
      n.id to PlacedNode(n, text, w, h)
    }
  // One barycenter pass keeps edges from crossing needlessly.
  val order = HashMap<String, Float>()
  val grouped = flow.nodes.groupBy { layers[it.id] ?: 0 }
  val byLayer = LinkedHashMap<Int, List<FlowNode>>()
  for (layer in grouped.keys.sorted()) {
    val ids = grouped.getValue(layer)
    val sorted =
      if (byLayer.isEmpty()) {
        ids
      } else {
        ids.sortedBy { n -> flow.edges.filter { it.to == n.id }.mapNotNull { order[it.from] }.average().let { if (it.isNaN()) 0.0 else it } }
      }
    sorted.forEachIndexed { i, n -> order[n.id] = i.toFloat() }
    byLayer[layer] = sorted
  }
  var mainPos = 20f
  var maxCross = 0f
  val crossSizes = byLayer.mapValues { (_, ns) -> ns.sumOf { n -> (if (flow.horizontal) placed.getValue(n.id).h else placed.getValue(n.id).w).toDouble() }.toFloat() + GAP_CROSS * (ns.size - 1) }
  crossSizes.values.forEach { maxCross = maxOf(maxCross, it) }
  for ((layer, ns) in byLayer) {
    var cross = 20f + (maxCross - crossSizes.getValue(layer)) / 2
    var layerMain = 0f
    for (n in ns) {
      val p = placed.getValue(n.id)
      if (flow.horizontal) {
        p.x = mainPos
        p.y = cross
        cross += p.h + GAP_CROSS
        layerMain = maxOf(layerMain, p.w)
      } else {
        p.x = cross
        p.y = mainPos
        cross += p.w + GAP_CROSS
        layerMain = maxOf(layerMain, p.h)
      }
    }
    mainPos += layerMain + GAP_MAIN
  }
  var w = if (flow.horizontal) mainPos - GAP_MAIN + 20f else maxCross + 40f
  var h = if (flow.horizontal) maxCross + 40f else mainPos - GAP_MAIN + 20f
  if (flow.reversed) {
    for (p in placed.values) {
      if (flow.horizontal) p.x = w - p.x - p.w else p.y = h - p.y - p.h
    }
  }
  w = w.coerceAtLeast(120f)
  h = h.coerceAtLeast(80f)
  return FlowLayout(placed, w, h)
}

private fun DrawScope.arrowHead(
  tip: Offset,
  from: Offset,
  color: Color,
) {
  val angle = atan2(tip.y - from.y, tip.x - from.x)
  val a = 10f
  val p = Path()
  p.moveTo(tip.x, tip.y)
  p.lineTo(tip.x - a * cos(angle - 0.4f), tip.y - a * sin(angle - 0.4f))
  p.lineTo(tip.x - a * cos(angle + 0.4f), tip.y - a * sin(angle + 0.4f))
  p.close()
  drawPath(p, color)
}

/** Point where a line from the node center toward [toward] leaves the node's bounding box. */
private fun edgePoint(
  n: PlacedNode,
  toward: Offset,
): Offset {
  val dx = toward.x - n.cx
  val dy = toward.y - n.cy
  if (dx == 0f && dy == 0f) return Offset(n.cx, n.cy)
  val sx = if (dx != 0f) (n.w / 2) / kotlin.math.abs(dx) else Float.MAX_VALUE
  val sy = if (dy != 0f) (n.h / 2) / kotlin.math.abs(dy) else Float.MAX_VALUE
  val s = minOf(sx, sy)
  return Offset(n.cx + dx * s, n.cy + dy * s)
}

private fun DrawScope.drawFlow(
  flow: MermaidDiagram.Flow,
  layout: FlowLayout,
  measurer: TextMeasurer,
  style: TextStyle,
  accent: Color,
  line: Color,
  fill: Color,
  muted: Color,
) {
  for (e in flow.edges) {
    val a = layout.placed[e.from] ?: continue
    val b = layout.placed[e.to] ?: continue
    val start = edgePoint(a, Offset(b.cx, b.cy))
    val end = edgePoint(b, Offset(a.cx, a.cy))
    drawLine(line, start, end, strokeWidth = 2f, pathEffect = if (e.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null)
    if (e.arrow) arrowHead(end, start, line)
    e.label?.let {
      val t = measurer.measure(it, style.copy(color = muted, fontSize = 11.sp))
      val mid = Offset((start.x + end.x) / 2 - t.size.width / 2f, (start.y + end.y) / 2 - t.size.height / 2f)
      drawRect(fill, mid, Size(t.size.width.toFloat(), t.size.height.toFloat()))
      drawText(t, topLeft = mid)
    }
  }
  for (p in layout.placed.values) {
    val topLeft = Offset(p.x, p.y)
    val size = Size(p.w, p.h)
    when (p.node.shape) {
      NodeShape.Diamond -> {
        val path = Path()
        path.moveTo(p.cx, p.y)
        path.lineTo(p.x + p.w, p.cy)
        path.lineTo(p.cx, p.y + p.h)
        path.lineTo(p.x, p.cy)
        path.close()
        drawPath(path, fill)
        drawPath(path, accent, style = Stroke(2f))
      }

      NodeShape.Circle, NodeShape.Stadium, NodeShape.Round -> {
        val r = if (p.node.shape == NodeShape.Round) 10f else p.h / 2
        drawRoundRect(fill, topLeft, size, androidx.compose.ui.geometry.CornerRadius(r))
        drawRoundRect(accent, topLeft, size, androidx.compose.ui.geometry.CornerRadius(r), style = Stroke(2f))
      }

      NodeShape.Hexagon -> {
        val path = Path()
        val inset = 14f
        path.moveTo(p.x + inset, p.y)
        path.lineTo(p.x + p.w - inset, p.y)
        path.lineTo(p.x + p.w, p.cy)
        path.lineTo(p.x + p.w - inset, p.y + p.h)
        path.lineTo(p.x + inset, p.y + p.h)
        path.lineTo(p.x, p.cy)
        path.close()
        drawPath(path, fill)
        drawPath(path, accent, style = Stroke(2f))
      }

      NodeShape.Rect -> {
        drawRoundRect(fill, topLeft, size, androidx.compose.ui.geometry.CornerRadius(4f))
        drawRoundRect(accent, topLeft, size, androidx.compose.ui.geometry.CornerRadius(4f), style = Stroke(2f))
      }
    }
    drawText(p.layout, topLeft = Offset(p.cx - p.layout.size.width / 2f, p.cy - p.layout.size.height / 2f))
  }
}

private fun DrawScope.drawSequence(
  seq: MermaidDiagram.Sequence,
  colW: Float,
  measurer: TextMeasurer,
  style: TextStyle,
  accent: Color,
  line: Color,
  fill: Color,
) {
  val xs = seq.participants.mapIndexed { i, name -> name to colW * i + colW / 2 }.toMap()
  val bottom = size.height - 6f
  for ((name, x) in xs) {
    val t = measurer.measure(name, style)
    val w = t.size.width + 24f
    drawRoundRect(fill, Offset(x - w / 2, 4f), Size(w, 30f), androidx.compose.ui.geometry.CornerRadius(6f))
    drawRoundRect(accent, Offset(x - w / 2, 4f), Size(w, 30f), androidx.compose.ui.geometry.CornerRadius(6f), style = Stroke(2f))
    drawText(t, topLeft = Offset(x - t.size.width / 2f, 19f - t.size.height / 2f))
    drawLine(line, Offset(x, 34f), Offset(x, bottom), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
  }
  seq.messages.forEachIndexed { i, m ->
    val y = 70f + i * 38f
    val x1 = xs[m.from] ?: return@forEachIndexed
    val x2 = xs[m.to] ?: return@forEachIndexed
    val self = m.from == m.to
    if (self) {
      drawLine(line, Offset(x1, y), Offset(x1 + 28f, y), strokeWidth = 2f)
      drawLine(line, Offset(x1 + 28f, y), Offset(x1 + 28f, y + 14f), strokeWidth = 2f)
      drawLine(line, Offset(x1 + 28f, y + 14f), Offset(x1, y + 14f), strokeWidth = 2f)
      arrowHead(Offset(x1, y + 14f), Offset(x1 + 28f, y + 14f), line)
    } else {
      drawLine(line, Offset(x1, y), Offset(x2, y), strokeWidth = 2f, pathEffect = if (m.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null)
      arrowHead(Offset(x2, y), Offset(x1, y), line)
    }
    val t = measurer.measure(m.text, style.copy(fontSize = 11.sp))
    drawText(t, topLeft = Offset(minOf(x1, x2) + (if (self) 34f else (kotlin.math.abs(x2 - x1) - t.size.width) / 2f), y - t.size.height - 2f))
  }
}

private val pieColors = listOf(Color(0xFF539BF5), Color(0xFF57AB5A), Color(0xFFC69026), Color(0xFFE5534B), Color(0xFFB083F0), Color(0xFF39C5CF), Color(0xFFFF938A), Color(0xFF6BC46D))

private fun DrawScope.drawPie(
  pie: MermaidDiagram.Pie,
  measurer: TextMeasurer,
  style: TextStyle,
) {
  val total = pie.slices.sumOf { it.second }.takeIf { it > 0 } ?: return
  val d = size.height - 16f
  var start = -90f
  pie.slices.forEachIndexed { i, (_, v) ->
    val sweep = (v / total * 360).toFloat()
    drawArc(pieColors[i % pieColors.size], start, sweep, true, Offset(8f, 8f), Size(d, d))
    start += sweep
  }
  var y = 8f
  pie.slices.forEachIndexed { i, (label, v) ->
    drawRect(pieColors[i % pieColors.size], Offset(d + 28f, y + 4f), Size(12f, 12f))
    val t = measurer.measure("$label  ${"%.0f".format(v / total * 100)}%", style)
    drawText(t, topLeft = Offset(d + 46f, y))
    y += 22f
  }
}
