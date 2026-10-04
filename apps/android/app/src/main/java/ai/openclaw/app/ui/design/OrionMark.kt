package ai.openclaw.app.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

private val OrionRing = Color(0xFF8FA1FF)
private val OrionStar = Color(0xFFFFFFFF)
private val OrionBetelgeuse = Color(0xFF5EEAD4)

/**
 * The Orion mark: a ring holding the belt and the four corner stars. Same geometry as the launcher
 * icon. [tint] renders it in one color for monochrome uses.
 */
@Composable
fun OrionMark(
  modifier: Modifier = Modifier,
  tint: Color? = null,
  contentDescription: String? = null,
) {
  val semantics =
    if (contentDescription == null) {
      Modifier
    } else {
      Modifier.semantics {
        this.contentDescription = contentDescription
        role = Role.Image
      }
    }
  Canvas(modifier = modifier.then(semantics)) {
    val scale = size.minDimension / 108f
    fun at(
      x: Float,
      y: Float,
    ) = Offset(x * scale, y * scale)
    drawCircle(tint ?: OrionRing, radius = 25f * scale, center = at(54f, 54f), style = Stroke(width = 3.5f * scale))
    val stars =
      listOf(
        Triple(at(40f, 47f), 3.6f, OrionStar),
        Triple(at(54f, 54f), 3.6f, OrionStar),
        Triple(at(68f, 61f), 3.6f, OrionStar),
        Triple(at(37f, 33f), 4.6f, OrionBetelgeuse),
        Triple(at(71f, 33f), 4.2f, OrionStar),
        Triple(at(39f, 76f), 4.2f, OrionStar),
        Triple(at(69f, 76f), 4.2f, OrionStar),
      )
    for ((center, radius, color) in stars) drawCircle(tint ?: color, radius = radius * scale, center = center)
  }
}
