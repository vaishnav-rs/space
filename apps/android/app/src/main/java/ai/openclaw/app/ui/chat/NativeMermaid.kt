package ai.openclaw.app.ui.chat

import kotlin.math.max

/** Parsed Mermaid diagram that the native renderer can draw. */
sealed interface MermaidDiagram {
  data class Flow(
    val horizontal: Boolean,
    val reversed: Boolean,
    val nodes: List<FlowNode>,
    val edges: List<FlowEdge>,
  ) : MermaidDiagram

  data class Sequence(
    val participants: List<String>,
    val messages: List<SeqMessage>,
  ) : MermaidDiagram

  data class Pie(
    val title: String?,
    val slices: List<Pair<String, Double>>,
  ) : MermaidDiagram
}

enum class NodeShape { Rect, Round, Stadium, Circle, Diamond, Hexagon }

data class FlowNode(
  val id: String,
  val label: String,
  val shape: NodeShape,
)

data class FlowEdge(
  val from: String,
  val to: String,
  val label: String?,
  val dashed: Boolean,
  val arrow: Boolean,
)

data class SeqMessage(
  val from: String,
  val to: String,
  val text: String,
  val dashed: Boolean,
)

private val shapeOpeners =
  listOf(
    "((" to ("))" to NodeShape.Circle),
    "([" to ("])" to NodeShape.Stadium),
    "[[" to ("]]" to NodeShape.Rect),
    "{{" to ("}}" to NodeShape.Hexagon),
    "[" to ("]" to NodeShape.Rect),
    "(" to (")" to NodeShape.Round),
    "{" to ("}" to NodeShape.Diamond),
  )

private val edgeRegex = Regex("""^\s*(<?)(-{2,}|={2,}|-\.+-?|\.-)(>|x|o)?""")

private fun unquote(text: String) = text.trim().removeSurrounding("\"").replace("<br/>", "\n").replace("<br>", "\n")

private class FlowParser {
  val nodes = LinkedHashMap<String, FlowNode>()
  val edges = ArrayList<FlowEdge>()

  /** Parses `id`, `id[label]`, `id(label)`, ...; returns the node id and the index after it. */
  fun node(
    s: String,
    start: Int,
  ): Pair<String, Int>? {
    var i = start
    while (i < s.length && s[i] == ' ') i++
    val idStart = i
    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
    if (i == idStart) return null
    val id = s.substring(idStart, i)
    var label: String? = null
    var shape = NodeShape.Rect
    for ((open, close) in shapeOpeners) {
      if (s.startsWith(open, i)) {
        val end = s.indexOf(close.first, i + open.length)
        if (end > 0) {
          label = unquote(s.substring(i + open.length, end))
          shape = close.second
          i = end + close.first.length
        }
        break
      }
    }
    val existing = nodes[id]
    nodes[id] = FlowNode(id, label ?: existing?.label ?: id, if (label != null) shape else existing?.shape ?: NodeShape.Rect)
    return id to i
  }

  fun statement(line: String) {
    var i = 0
    var current = node(line, 0) ?: return
    i = current.second
    while (true) {
      val rest = line.substring(i)
      val m = edgeRegex.find(rest) ?: return
      var label: String? = null
      var after = i + m.range.last + 1
      // `-- text -->` form: the opener `--` is followed by free text before the real arrow.
      val text = line.substring(after)
      if (m.groupValues[3].isEmpty() && !m.groupValues[2].contains('.') && text.isNotBlank()) {
        val real = Regex("""\s*(-{2,}|={2,})(>|x|o)""").find(text)
        if (real != null && !text.trimStart().startsWith("|")) {
          label = text.substring(0, real.range.first).trim()
          after += real.range.last + 1
        }
      }
      val tail = line.substring(after)
      val pipe = Regex("""^\s*\|([^|]*)\|""").find(tail)
      if (pipe != null) {
        label = pipe.groupValues[1].trim()
        after += pipe.range.last + 1
      }
      val target = node(line, after) ?: return
      edges +=
        FlowEdge(
          from = current.first,
          to = target.first,
          label = label?.let(::unquote)?.takeIf { it.isNotEmpty() },
          dashed = m.groupValues[2].contains('.'),
          arrow = m.groupValues[3] == ">" || m.groupValues[1] == "<",
        )
      current = target
      i = target.second
    }
  }
}

/** Returns null for unsupported diagram kinds so the caller can show the source instead. */
fun parseMermaid(source: String): MermaidDiagram? {
  val lines =
    source
      .lineSequence()
      .map { it.substringBefore("%%").trim() }
      .filter { it.isNotEmpty() }
      .toList()
  val head = lines.firstOrNull()?.lowercase() ?: return null
  return when {
    head.startsWith("graph") || head.startsWith("flowchart") -> {
      val dir = head.split(' ').getOrNull(1) ?: "td"
      val parser = FlowParser()
      for (line in lines.drop(1)) {
        val keyword = line.substringBefore(' ').lowercase()
        if (keyword in setOf("subgraph", "end", "style", "classdef", "class", "click", "linkstyle", "direction")) continue
        line.split(';').forEach { parser.statement(it.trim()) }
      }
      if (parser.nodes.isEmpty()) {
        null
      } else {
        MermaidDiagram.Flow(dir == "lr" || dir == "rl", dir == "bt" || dir == "rl", parser.nodes.values.toList(), parser.edges)
      }
    }

    head.startsWith("sequencediagram") -> {
      val participants = LinkedHashSet<String>()
      val aliases = HashMap<String, String>()
      val messages = ArrayList<SeqMessage>()
      val message = Regex("""^(\S+?)\s*(-->>|->>|--\)|-\)|-->|->|--x|-x)\s*([+-]?)(\S+?)\s*:\s*(.*)$""")
      for (line in lines.drop(1)) {
        val decl = Regex("""^(participant|actor)\s+(\S+)(?:\s+as\s+(.+))?$""").find(line)
        if (decl != null) {
          val label = decl.groupValues[3].ifEmpty { decl.groupValues[2] }
          aliases[decl.groupValues[2]] = label
          participants += label
          continue
        }
        val m = message.find(line) ?: continue
        val from = aliases[m.groupValues[1]] ?: m.groupValues[1]
        val to = aliases[m.groupValues[4]] ?: m.groupValues[4]
        participants += from
        participants += to
        messages += SeqMessage(from, to, m.groupValues[5], m.groupValues[2].startsWith("--"))
      }
      if (participants.isEmpty()) null else MermaidDiagram.Sequence(participants.toList(), messages)
    }

    head.startsWith("pie") -> {
      val title = Regex("""(?i)title\s+(.+)""").find(lines.first().drop(3))?.groupValues?.get(1)
      var heading = title
      val slices = ArrayList<Pair<String, Double>>()
      for (line in lines.drop(1)) {
        if (line.lowercase().startsWith("title ")) {
          heading = line.substring(6).trim()
          continue
        }
        val m = Regex("""^"?([^":]+?)"?\s*:\s*([0-9.]+)$""").find(line) ?: continue
        m.groupValues[2].toDoubleOrNull()?.let { slices += m.groupValues[1].trim() to it }
      }
      if (slices.isEmpty()) null else MermaidDiagram.Pie(heading, slices)
    }

    else -> {
      null
    }
  }
}

/** Layer index per node: longest path from a source, ignoring edges that would close a cycle. */
internal fun flowLayers(flow: MermaidDiagram.Flow): Map<String, Int> {
  val layers = HashMap<String, Int>()
  val outgoing = flow.edges.groupBy { it.from }
  val state = HashMap<String, Int>() // 1 = on stack, 2 = done
  val forward = ArrayList<FlowEdge>()
  fun visit(id: String) {
    state[id] = 1
    for (e in outgoing[id].orEmpty()) {
      if (state[e.to] == 1) continue
      forward += e
      if (state[e.to] == null) visit(e.to)
    }
    state[id] = 2
  }
  val targets = flow.edges.map { it.to }.toSet()
  (flow.nodes.map { it.id }.filter { it !in targets } + flow.nodes.map { it.id }).forEach { if (state[it] == null) visit(it) }
  flow.nodes.forEach { layers[it.id] = 0 }
  // Relax in rounds; forward edges form a DAG so this terminates within |nodes| passes.
  repeat(flow.nodes.size) {
    for (e in forward) layers[e.to] = max(layers[e.to] ?: 0, (layers[e.from] ?: 0) + 1)
  }
  return layers
}
