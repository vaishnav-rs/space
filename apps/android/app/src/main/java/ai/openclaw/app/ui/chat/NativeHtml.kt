package ai.openclaw.app.ui.chat

/** Native stand-in for agent HTML widgets: structure and text only, never executed or embedded. */
sealed interface HtmlBlock {
  data class Heading(
    val level: Int,
    val text: String,
  ) : HtmlBlock

  data class Paragraph(
    val text: String,
  ) : HtmlBlock

  data class Item(
    val text: String,
  ) : HtmlBlock

  data class Code(
    val text: String,
  ) : HtmlBlock

  data class Table(
    val rows: List<List<String>>,
  ) : HtmlBlock
}

private val htmlEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…", "copy" to "©", "middot" to "·", "bull" to "•", "rarr" to "→", "larr" to "←", "times" to "×")

internal fun decodeWidgetHtmlEntities(text: String): String =
  Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(text) { m ->
    val v = m.groupValues[1]
    when {
      v.startsWith("#x") -> v.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
      v.startsWith("#") -> v.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
      else -> htmlEntities[v] ?: m.value
    }
  }

private fun clean(raw: String): String = decodeWidgetHtmlEntities(raw.replace(Regex("<[^>]*>"), "")).replace(Regex("\\s+"), " ").trim()

/** Parses headings, paragraphs, list items, code and tables; script/style/svg/iframe content is dropped. */
fun htmlToBlocks(html: String): List<HtmlBlock> {
  val body =
    html
      .replace(Regex("(?is)<(script|style|svg|iframe|object|embed|head)\\b.*?</\\1\\s*>"), "")
      .replace(Regex("(?s)<!--.*?-->"), "")
  val blocks = ArrayList<HtmlBlock>()
  val leaf = Regex("(?is)<(h[1-6]|p|li|pre|table)\\b[^>]*>(.*?)</\\1\\s*>")
  for (m in leaf.findAll(body)) {
    val name = m.groupValues[1].lowercase()
    val inner = m.groupValues[2]
    when {
      name.startsWith("h") -> clean(inner).takeIf { it.isNotEmpty() }?.let { blocks += HtmlBlock.Heading(name.drop(1).toInt(), it) }
      name == "p" -> clean(inner).takeIf { it.isNotEmpty() }?.let { blocks += HtmlBlock.Paragraph(it) }
      name == "li" -> clean(inner).takeIf { it.isNotEmpty() }?.let { blocks += HtmlBlock.Item(it) }
      name == "pre" -> decodeWidgetHtmlEntities(inner.replace(Regex("<[^>]*>"), "")).trim('\n').takeIf { it.isNotBlank() }?.let { blocks += HtmlBlock.Code(it) }
      name == "table" -> {
        val rows =
          Regex("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>").findAll(inner).map { tr ->
            Regex("(?is)<t[hd]\\b[^>]*>(.*?)</t[hd]\\s*>").findAll(tr.groupValues[1]).map { clean(it.groupValues[1]) }.toList()
          }.filter { it.isNotEmpty() }.toList()
        if (rows.isNotEmpty()) blocks += HtmlBlock.Table(rows)
      }
    }
  }
  if (blocks.isEmpty()) {
    // No semantic elements: fall back to the visible text, split on block boundaries.
    body
      .replace(Regex("(?i)<br\\s*/?>|</(div|section|article|tr|li|p)>"), "\n")
      .split('\n')
      .map(::clean)
      .filter { it.isNotEmpty() }
      .forEach { blocks += HtmlBlock.Paragraph(it) }
  }
  return blocks
}
