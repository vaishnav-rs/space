package ai.openclaw.app.ui.chat

import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily

/** Display math as Unicode text (see [latexToUnicode]); scrolls sideways instead of wrapping. */
@Composable
internal fun ChatMathBlock(
  latex: String,
  textColor: Color,
) {
  val text = remember(latex) { latexToUnicode(latex) }
  Text(
    text = text,
    color = textColor,
    style = ClawTheme.type.body.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic),
    softWrap = false,
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
  )
}
