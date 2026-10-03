package ai.openclaw.app.ui

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawPlainIconButton
import ai.openclaw.app.ui.design.ClawScaffold
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared frame for the native gateway tool screens (dashboard, terminal, desktop). */
@Composable
internal fun ControlUiScreenFrame(
  title: String,
  icon: ImageVector,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  headerActions: @Composable () -> Unit = {},
  content: @Composable BoxScope.() -> Unit,
) {
  ClawScaffold(
    contentPadding = PaddingValues(start = ClawTheme.spacing.lg, top = 14.dp, end = ClawTheme.spacing.lg, bottom = 6.dp),
  ) {
    Column(modifier = Modifier.fillMaxSize().then(modifier), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
      ) {
        ClawPlainIconButton(
          icon = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = nativeString("Back"),
          onClick = onBack,
        )
        Text(
          text = title,
          style = ClawTheme.type.title,
          color = ClawTheme.colors.text,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        headerActions()
        Icon(imageVector = icon, contentDescription = null, tint = ClawTheme.colors.textMuted)
      }
      Box(modifier = Modifier.fillMaxWidth().weight(1f), content = content)
    }
  }
}

@Composable
internal fun ControlUiUnavailable(
  title: String,
  detail: String,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(text = title, style = ClawTheme.type.section, color = ClawTheme.colors.text)
    Text(text = detail, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
  }
}
