package ai.openclaw.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Gateway `board.get` snapshot, reduced to what the native dashboard renders. */
data class NativeBoardTab(
  val id: String,
  val title: String,
)

data class NativeBoardWidget(
  val name: String,
  val tabId: String,
  val title: String?,
  val pluginKind: String?,
  val contentKind: String,
  val position: Int,
  val width: Int,
  val props: JsonObject?,
  val grantState: String,
)

data class NativeBoard(
  val sessionKey: String,
  val revision: Int,
  val tabs: List<NativeBoardTab>,
  val widgets: List<NativeBoardWidget>,
) {
  fun widgetsFor(tabId: String): List<NativeBoardWidget> = widgets.filter { it.tabId == tabId }.sortedBy { it.position }
}

data class ReportMetric(
  val label: String,
  val value: String,
  val detail: String?,
)

data class ReportPoint(
  val label: String,
  val value: Double,
)

data class ReportLink(
  val label: String,
  val url: String,
  val detail: String?,
)

/** Mirror of the data-only `session:report` schema (src/boards/board-report.ts). */
sealed interface ReportBlock {
  data class Text(
    val title: String?,
    val text: String,
  ) : ReportBlock

  data class Metrics(
    val items: List<ReportMetric>,
  ) : ReportBlock

  data class Table(
    val title: String?,
    val columns: List<String>,
    val rows: List<List<String>>,
  ) : ReportBlock

  data class Chart(
    val title: String?,
    val line: Boolean,
    val points: List<ReportPoint>,
  ) : ReportBlock

  data class Links(
    val title: String?,
    val items: List<ReportLink>,
  ) : ReportBlock
}

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

fun parseBoard(root: JsonObject?): NativeBoard? {
  val snapshot = root?.takeIf { it.containsKey("tabs") } ?: root?.get("snapshot").obj() ?: root?.get("board").obj() ?: return null
  val sessionKey = snapshot["sessionKey"].text() ?: return null
  val tabs =
    snapshot["tabs"].arr().orEmpty().mapNotNull { el ->
      val o = el.obj() ?: return@mapNotNull null
      NativeBoardTab(id = o["tabId"].text() ?: return@mapNotNull null, title = o["title"].text() ?: "Tab")
    }
  val widgets =
    snapshot["widgets"].arr().orEmpty().mapNotNull { el ->
      val o = el.obj() ?: return@mapNotNull null
      NativeBoardWidget(
        name = o["name"].text() ?: return@mapNotNull null,
        tabId = o["tabId"].text() ?: return@mapNotNull null,
        title = o["title"].text(),
        pluginKind = o["pluginKind"].text(),
        contentKind = o["contentKind"].text() ?: "html",
        position = o["position"].int() ?: 0,
        width = o["sizeW"].int() ?: 12,
        props = o["props"].obj(),
        grantState = o["grantState"].text() ?: "none",
      )
    }
  return NativeBoard(sessionKey, snapshot["revision"].int() ?: 0, tabs, widgets)
}

/** Unknown or malformed blocks are skipped so one bad block never blanks a dashboard. */
fun parseReportBlocks(props: JsonObject?): List<ReportBlock> =
  props?.get("blocks").arr().orEmpty().mapNotNull { el ->
    val o = el.obj() ?: return@mapNotNull null
    val title = o["title"].text()
    when (o["type"].text()) {
      "text" -> {
        ReportBlock.Text(title, o["text"].text() ?: return@mapNotNull null)
      }

      "metrics" -> {
        val items =
          o["items"].arr().orEmpty().mapNotNull { m ->
            val mo = m.obj() ?: return@mapNotNull null
            ReportMetric(mo["label"].text() ?: return@mapNotNull null, mo["value"].text() ?: return@mapNotNull null, mo["detail"].text())
          }
        items.takeIf { it.isNotEmpty() }?.let(ReportBlock::Metrics)
      }

      "table" -> {
        val columns = o["columns"].arr().orEmpty().mapNotNull { it.text() }
        val rows = o["rows"].arr().orEmpty().map { r -> r.arr().orEmpty().map { it.text() ?: "" } }
        if (columns.isEmpty()) null else ReportBlock.Table(title, columns, rows)
      }

      "chart" -> {
        val points =
          o["points"].arr().orEmpty().mapNotNull { p ->
            val po = p.obj() ?: return@mapNotNull null
            ReportPoint(po["label"].text() ?: return@mapNotNull null, (po["value"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null)
          }
        points.takeIf { it.isNotEmpty() }?.let { ReportBlock.Chart(title, o["style"].text() == "line", it) }
      }

      "links" -> {
        val items =
          o["items"].arr().orEmpty().mapNotNull { l ->
            val lo = l.obj() ?: return@mapNotNull null
            ReportLink(lo["label"].text() ?: return@mapNotNull null, lo["url"].text() ?: return@mapNotNull null, lo["detail"].text())
          }
        items.takeIf { it.isNotEmpty() }?.let { ReportBlock.Links(title, it) }
      }

      else -> {
        null
      }
    }
  }
