package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.ondevice.CREDENTIAL_KEYS
import ai.openclaw.app.ondevice.OnDeviceGatewayService
import ai.openclaw.app.ondevice.OnDeviceSettings
import ai.openclaw.app.ondevice.OnDeviceState
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawStatus
import ai.openclaw.app.ui.design.ClawStatusPill
import ai.openclaw.app.ui.design.ClawTextField
import ai.openclaw.app.ui.design.ClawTheme
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Runs the whole Orion gateway on this phone: set up, start, stop, test, and watch its log. */
@Composable
internal fun OnDeviceGatewaySettingsScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val gateway = viewModel.onDeviceGateway
  val state by gateway.state.collectAsState()
  val log by gateway.log.collectAsState()
  val scope = rememberCoroutineScope()
  var settings by remember { mutableStateOf(gateway.settings()) }
  var testResults by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
  var testing by remember { mutableStateOf(false) }
  var confirmReset by remember { mutableStateOf(false) }

  fun persist(next: OnDeviceSettings) {
    settings = next
    gateway.saveSettings(next)
  }

  SettingsDetailFrame(
    title = "On this phone",
    subtitle = "Run your Orion gateway inside the app. Nothing else to install.",
    icon = SettingsRoute.OnDevice.icon,
    onBack = onBack,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(ClawTheme.spacing.xs)) {
      ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val (label, status) =
          when (val s = state) {
            is OnDeviceState.Running -> "Running on port ${s.port}" to ClawStatus.Success
            is OnDeviceState.Installing -> s.step to ClawStatus.Warning
            OnDeviceState.Starting -> "Starting…" to ClawStatus.Warning
            OnDeviceState.Stopped -> "Stopped" to ClawStatus.Neutral
            is OnDeviceState.Failed -> s.message to ClawStatus.Danger
            is OnDeviceState.Unsupported -> s.reason to ClawStatus.Danger
          }
        ClawStatusPill(label, status)
        if (state !is OnDeviceState.Unsupported) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val running = state is OnDeviceState.Running || state is OnDeviceState.Starting || state is OnDeviceState.Installing
            ClawPrimaryButton(
              text = if (running) "Restart" else "Set up and start",
              onClick = {
                gateway.saveSettings(settings)
                if (running) {
                  OnDeviceGatewayService.stop(context)
                }
                OnDeviceGatewayService.start(context)
              },
            )
            if (running) ClawSecondaryButton(text = "Stop", onClick = { OnDeviceGatewayService.stop(context) })
          }
        }
      }

      ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("You", style = ClawTheme.type.section, color = ClawTheme.colors.text)
        ClawTextField(settings.ownerEmail, { persist(settings.copy(ownerEmail = it)) }, "Your email", label = "Email")
        ClawTextField(settings.ownerWhatsapp, { persist(settings.copy(ownerWhatsapp = it)) }, "+971…", label = "WhatsApp number (international format)")
      }

      ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reachability", style = ClawTheme.type.section, color = ClawTheme.colors.text)
        ToggleRow("Allow other devices on my network", "Off keeps the gateway private to this phone.", settings.lan) { persist(settings.copy(lan = it)) }
        ToggleRow("Start when the phone boots", "Orion keeps working without opening the app.", settings.startOnBoot) { persist(settings.copy(startOnBoot = it)) }
        if (settings.lan) {
          val addresses = remember { gateway.networkAddresses() }
          Text(
            if (addresses.isEmpty()) "No network address found. Connect to Wi-Fi." else "Other devices: " + addresses.joinToString { "ws://$it:${settings.port}" } + " (token under Gateway settings)",
            style = ClawTheme.type.caption,
            color = ClawTheme.colors.textMuted,
          )
        }
        ClawSecondaryButton(
          text = "Allow running in the background",
          onClick = {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
              context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }
          },
        )
      }

      ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connections", style = ClawTheme.type.section, color = ClawTheme.colors.text)
        Text("Saved on this phone only. WhatsApp and GitHub sign-in are under Orion admin.", style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
        CREDENTIAL_KEYS.forEach { key ->
          ClawTextField(
            value = settings.credentials[key].orEmpty(),
            onValueChange = { persist(settings.copy(credentials = settings.credentials + (key to it))) },
            placeholder = key,
            label = key,
            secret = key.endsWith("SECRET") || key.endsWith("TOKEN") || key.endsWith("KEY"),
          )
        }
        Text("Restart the gateway after changing these.", style = ClawTheme.type.captionSmall, color = ClawTheme.colors.textMuted)
      }

      ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Check this phone", style = ClawTheme.type.section, color = ClawTheme.colors.text)
        ClawSecondaryButton(
          text = if (testing) "Testing…" else "Run self-test",
          enabled = !testing && state !is OnDeviceState.Unsupported,
          onClick = {
            testing = true
            scope.launch {
              testResults = gateway.selfTest()
              testing = false
            }
          },
        )
        testResults?.forEach { (name, result) ->
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            ClawStatusPill(name, if (result.startsWith("OK")) ClawStatus.Success else ClawStatus.Danger)
            Text(result, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted, modifier = Modifier.weight(1f))
          }
        }
      }

      ClawPanel(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Log", style = ClawTheme.type.section, color = ClawTheme.colors.text)
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
          log.takeLast(60).forEach { Text(it, style = ClawTheme.type.mono, color = ClawTheme.colors.codeText, softWrap = false) }
          if (log.isEmpty()) Text("Nothing yet.", style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
        }
      }

      ClawSecondaryButton(text = "Erase gateway data", onClick = { confirmReset = true })
    }
  }

  if (confirmReset) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      title = { Text("Erase gateway data?") },
      text = { Text("Chats, tasks and stored connections on this phone are deleted and the gateway is reset. This cannot be undone.") },
      confirmButton = {
        TextButton(onClick = {
          OnDeviceGatewayService.stop(context)
          gateway.resetData()
          confirmReset = false
        }) { Text("Erase") }
      },
      dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun ToggleRow(
  title: String,
  detail: String,
  checked: Boolean,
  onChange: (Boolean) -> Unit,
) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Column(Modifier.weight(1f)) {
      Text(title, style = ClawTheme.type.body, color = ClawTheme.colors.text)
      Text(detail, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
    }
    Switch(checked = checked, onCheckedChange = onChange)
  }
}
