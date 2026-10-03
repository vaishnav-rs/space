package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.OrionAdminSummary
import ai.openclaw.app.OrionConnector
import ai.openclaw.app.OrionPairingState
import ai.openclaw.app.OrionTask
import ai.openclaw.app.ORION_ROLES
import ai.openclaw.app.orionRoleBlurb
import ai.openclaw.app.orionRoleLabel
import ai.openclaw.app.ui.design.ClawListItem
import ai.openclaw.app.ui.design.ClawListPanel
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawPill
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawStatus
import ai.openclaw.app.ui.design.ClawStatusPill
import ai.openclaw.app.ui.design.ClawTextBadge
import ai.openclaw.app.ui.design.ClawTextField
import ai.openclaw.app.ui.design.ClawTheme
import ai.openclaw.app.ui.design.badgeInitials
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private enum class OrionTab(
  val label: String,
) {
  Overview("Overview"),
  People("People"),
  Connections("Connections"),
  Tasks("Tasks"),
  Workspaces("Workspaces"),
  Activity("Activity"),
}

private fun op(
  name: String,
  vararg fields: Pair<String, JsonElement>,
): String =
  buildJsonObject {
    put("op", JsonPrimitive(name))
    fields.forEach { (k, v) -> put(k, v) }
  }.toString()

private fun str(value: String) = JsonPrimitive(value)

private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

private fun csv(value: String): List<String> = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun statusFor(task: OrionTask): ClawStatus =
  when {
    task.status == "READY_FOR_HUMAN" || task.status == "COMPLETED" -> ClawStatus.Success
    task.status == "FAILED" || task.status == "BLOCKED" -> ClawStatus.Danger
    task.status.startsWith("NEEDS") -> ClawStatus.Warning
    else -> ClawStatus.Neutral
  }

private fun pretty(status: String) = status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

/** Native admin screen for Orion: people and roles, org and personal connections, tasks, workspaces, audit. */
@Composable
internal fun OrionAdminSettingsScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val state by viewModel.orionState.collectAsState()
  val notice by viewModel.orionNotice.collectAsState()
  val busy by viewModel.orionBusy.collectAsState()
  val pairing by viewModel.orionPairing.collectAsState()
  val isConnected by viewModel.isConnected.collectAsState()
  var tab by rememberSaveable { mutableStateOf(OrionTab.Overview) }

  LaunchedEffect(isConnected) {
    if (isConnected) viewModel.refreshOrion()
  }

  SettingsDetailFrame(
    title = "Orion admin",
    subtitle = "People, roles, connections and engineering tasks.",
    icon = SettingsRoute.Orion.icon,
    onBack = onBack,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(ClawTheme.spacing.xs)) {
      SettingsRefreshControls(isConnected, state.refreshing, state.errorText, viewModel::refreshOrion)
      notice?.let { n ->
        ClawPanel {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = n.text,
              style = ClawTheme.type.body,
              color = if (n.isError) ClawTheme.colors.danger else ClawTheme.colors.success,
              modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::dismissOrionNotice) { Text("Dismiss") }
          }
        }
      }
      PairingPanel(pairing, viewModel)
      when (val summary = state.summary) {
        null -> if (!isConnected) SettingsMessagePanel("Connect the gateway to manage Orion.")
        is OrionAdminSummary.Unconfigured ->
          SettingsMessagePanel(
            title = "Set up Orion access control",
            text = summary.reason + "\n\nORION_STATE_DIR=~/.orion\nORION_VAULT_KEY=\$(openssl rand -base64 32)",
          )
        is OrionAdminSummary.Ready ->
          if (summary.needsBootstrap) {
            ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("Claim ownership", style = ClawTheme.type.section, color = ClawTheme.colors.text)
              Text(
                "No owner exists yet. The first person to claim it becomes the Orion owner and can add everyone else.",
                style = ClawTheme.type.body,
                color = ClawTheme.colors.textMuted,
              )
              ClawPrimaryButton("I'm the owner", enabled = !busy, onClick = { viewModel.applyOrionOp(op("bootstrap"), "You are the owner.") })
            }
          } else {
            OrionReady(summary, tab, { tab = it }, busy, viewModel)
          }
      }
    }
  }
}

@Composable
private fun OrionReady(
  s: OrionAdminSummary.Ready,
  tab: OrionTab,
  onTab: (OrionTab) -> Unit,
  busy: Boolean,
  vm: MainViewModel,
) {
  val tabs =
    buildList {
      add(OrionTab.Overview)
      if (s.can("members.manage")) add(OrionTab.People)
      add(OrionTab.Connections)
      add(OrionTab.Tasks)
      if (s.can("connectors.audit")) {
        add(OrionTab.Workspaces)
        add(OrionTab.Activity)
      }
    }
  val current = if (tab in tabs) tab else OrionTab.Overview
  Column(verticalArrangement = Arrangement.spacedBy(ClawTheme.spacing.xs)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      ClawTextBadge(text = badgeInitials(s.meId, fallback = "O"))
      Column(modifier = Modifier.weight(1f)) {
        Text(s.meId, style = ClawTheme.type.section, color = ClawTheme.colors.text)
        Text(orionRoleLabel(s.meRole), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      }
    }
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      tabs.forEach { t ->
        ClawPill(
          text = if (t == OrionTab.Tasks && s.tasks.isNotEmpty()) "${t.label} ${s.tasks.size}" else t.label,
          selected = t == current,
          onClick = { onTab(t) },
        )
      }
    }
    when (current) {
      OrionTab.Overview -> OverviewTab(s)
      OrionTab.People -> PeopleTab(s, busy, vm)
      OrionTab.Connections -> ConnectionsTab(s, busy, vm)
      OrionTab.Tasks -> TasksTab(s, busy, vm)
      OrionTab.Workspaces -> WorkspacesTab(s)
      OrionTab.Activity -> ActivityTab(s)
    }
  }
}

@Composable
private fun HealthChip(
  ok: Boolean,
  label: String,
) = ClawStatusPill(text = label, status = if (ok) ClawStatus.Success else ClawStatus.Warning)

@Composable
private fun OverviewTab(s: OrionAdminSummary.Ready) {
  val active = s.tasks.count { !it.finished }
  val waiting = s.tasks.count { it.needsPerson }
  val live = s.connectors.count { it.orgConnected || it.mineConnected }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    HealthChip(s.vaultAvailable, "Credential vault")
    HealthChip(s.health.githubToken, "GitHub identity")
    HealthChip(s.health.webhookSecret, "Webhook secret")
    HealthChip(s.health.agentBound, "Engineering runtime")
  }
  SettingsMetricPanel(
    rows =
      listOf(
        SettingsMetric(if (s.can("members.manage")) "People" else "You", s.members.size.toString()),
        SettingsMetric("Connections live", "$live/${s.connectors.size}"),
        SettingsMetric("Active tasks", active.toString()),
        SettingsMetric("Waiting on a person", waiting.toString()),
      ),
  )
  ClawPanel(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(orionRoleLabel(s.meRole), style = ClawTheme.type.section, color = ClawTheme.colors.text)
    Text(orionRoleBlurb(s.meRole), style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      s.permissions.sorted().forEach { ClawPill(text = it) }
    }
  }
  val attention = s.tasks.filter { it.needsPerson }.take(5)
  if (attention.isNotEmpty()) {
    Text("Needs attention", style = ClawTheme.type.section, color = ClawTheme.colors.text)
    ClawListPanel(items = attention) { t ->
      ClawListItem(
        title = t.title.ifBlank { "(no title)" },
        subtitle = "${t.workspace} · ${t.source}",
        trailing = { ClawStatusPill(pretty(t.status), statusFor(t)) },
      )
    }
  }
}

@Composable
private fun PeopleTab(
  s: OrionAdminSummary.Ready,
  busy: Boolean,
  vm: MainViewModel,
) {
  var removing by remember { mutableStateOf<String?>(null) }
  var rolePicker by remember { mutableStateOf<String?>(null) }
  var newId by rememberSaveable { mutableStateOf("") }
  var newRole by rememberSaveable { mutableStateOf("member") }
  var newWorkspaces by rememberSaveable { mutableStateOf("") }
  var newGithub by rememberSaveable { mutableStateOf("") }
  var newAddresses by rememberSaveable { mutableStateOf("") }

  ClawListPanel(items = s.members) { m ->
    ClawListItem(
      title = m.profileId + if (m.profileId == s.meId) " (you)" else "",
      subtitle =
        listOfNotNull(
          m.workspaces.takeIf { it.isNotEmpty() }?.joinToString(", "),
          m.githubLogin?.let { "GitHub $it" },
        ).joinToString(" · ").ifBlank { "No workspaces" },
      leading = { ClawTextBadge(text = badgeInitials(m.profileId, fallback = "P")) },
      trailing = { ClawPill(text = orionRoleLabel(m.role), selected = true, onClick = { if (!busy) rolePicker = m.profileId }) },
      onClick = { if (!busy) rolePicker = m.profileId },
    )
  }
  ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Add or update a person", style = ClawTheme.type.section, color = ClawTheme.colors.text)
    ClawTextField(value = newId, onValueChange = { newId = it }, placeholder = "Profile ID", label = "Profile ID")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      ORION_ROLES.forEach { r -> ClawPill(text = orionRoleLabel(r), selected = r == newRole, onClick = { newRole = r }) }
    }
    Text(orionRoleBlurb(newRole), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
    ClawTextField(value = newWorkspaces, onValueChange = { newWorkspaces = it }, placeholder = "Workspaces (comma separated)", label = "Workspaces")
    ClawTextField(value = newGithub, onValueChange = { newGithub = it }, placeholder = "GitHub login", label = "GitHub login")
    ClawTextField(
      value = newAddresses,
      onValueChange = { newAddresses = it },
      placeholder = "Their own emails / numbers",
      label = "Addresses",
    )
    ClawPrimaryButton(
      text = "Save person",
      enabled = !busy && newId.isNotBlank(),
      modifier = Modifier.fillMaxWidth(),
      onClick = {
        vm.applyOrionOp(
          op(
            "member.set",
            "profileId" to str(newId.trim()),
            "role" to str(newRole),
            "workspaces" to strings(csv(newWorkspaces)),
            "addresses" to strings(csv(newAddresses)),
            *(if (newGithub.isNotBlank()) arrayOf("githubLogin" to str(newGithub.trim())) else emptyArray()),
          ),
          "Saved ${newId.trim()}.",
        )
        newId = ""
        newWorkspaces = ""
        newGithub = ""
        newAddresses = ""
      },
    )
  }
  rolePicker?.let { target ->
    AlertDialog(
      onDismissRequest = { rolePicker = null },
      title = { Text(target) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          ORION_ROLES.forEach { r ->
            ClawListItem(
              title = orionRoleLabel(r),
              subtitle = orionRoleBlurb(r),
              onClick = {
                vm.applyOrionOp(op("member.set", "profileId" to str(target), "role" to str(r)), "Updated $target.")
                rolePicker = null
              },
            )
          }
        }
      },
      confirmButton = { TextButton(onClick = { removing = target; rolePicker = null }) { Text("Remove person") } },
      dismissButton = { TextButton(onClick = { rolePicker = null }) { Text("Cancel") } },
    )
  }
  removing?.let { target ->
    AlertDialog(
      onDismissRequest = { removing = null },
      title = { Text("Remove $target?") },
      text = { Text("They lose their Orion role and workspace access. Their personal connections stay until revoked.") },
      confirmButton = {
        TextButton(onClick = {
          vm.applyOrionOp(op("member.remove", "profileId" to str(target)), "Removed $target.")
          removing = null
        }) { Text("Remove") }
      },
      dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
    )
  }
}

private val FIELD_LABELS =
  mapOf(
    "clientId" to "OAuth client ID",
    "clientSecret" to "OAuth client secret",
    "refreshToken" to "Refresh token",
    "token" to "Access token",
    "apiKey" to "API key",
    "from" to "From address",
    "account" to "Account label (e.g. the number)",
  )
private val SECRET_FIELDS = setOf("clientSecret", "refreshToken", "token", "apiKey")

@Composable
private fun ConnectionsTab(
  s: OrionAdminSummary.Ready,
  busy: Boolean,
  vm: MainViewModel,
) {
  s.connectors.forEach { c -> ConnectorCard(s, c, busy, vm) }
}

@Composable
private fun ConnectorCard(
  s: OrionAdminSummary.Ready,
  c: OrionConnector,
  busy: Boolean,
  vm: MainViewModel,
) {
  val admin = s.can("connectors.org.manage")
  var form by remember(c.kind) { mutableStateOf<String?>(null) } // "org" | "user"
  var values by remember(c.kind) { mutableStateOf<Map<String, String>>(emptyMap()) }
  var confirm by remember(c.kind) { mutableStateOf<(() -> Unit)?>(null) }
  ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(c.label, style = ClawTheme.type.section, color = ClawTheme.colors.text)
      if (c.orgOnly) ClawPill(text = "Organization only", selected = true)
    }
    if (c.scopes.contains("org")) {
      ClawListItem(
        title = "Organization",
        subtitle = if (c.orgConnected) "Connected${c.orgBy?.let { " by $it" } ?: ""}" else "Not connected",
        trailing = { ClawStatusPill(if (c.orgConnected) "Connected" else "Not connected", if (c.orgConnected) ClawStatus.Success else ClawStatus.Neutral) },
      )
      if (admin) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (c.kind == "whatsapp") {
            ClawPrimaryButton(if (c.orgConnected) "Re-link with QR" else "Link with QR", enabled = !busy, onClick = { vm.startWhatsAppPairing(force = c.orgConnected) })
          }
          ClawSecondaryButton(if (c.orgConnected) "Replace" else if (c.kind == "whatsapp") "Record number" else "Connect", enabled = !busy, onClick = { form = "org"; values = emptyMap() })
          if (c.orgConnected) {
            ClawSecondaryButton("Disconnect", enabled = !busy, onClick = {
              confirm = { vm.applyOrionOp(op("connector.disconnect", "connector" to str(c.kind), "scope" to str("org")), "${c.label} disconnected.") }
            })
          }
        }
      }
    }
    if (c.scopes.contains("user")) {
      ClawListItem(
        title = "You",
        subtitle =
          when {
            !c.userScopeAllowed -> "Personal connections are turned off by your admin"
            c.mineConnected -> "Connected"
            c.orgFallback && c.orgConnected -> "Not connected, using the organization's"
            else -> "Not connected"
          },
        trailing = { if (c.mineConnected) ClawStatusPill("Yours", ClawStatus.Success) },
      )
      if (c.userScopeAllowed && s.can("connectors.user.manage")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (c.kind == "github" && !c.mineConnected) {
            ClawPrimaryButton("Sign in with GitHub", enabled = !busy, onClick = vm::startGitHubPairing)
          }
          ClawSecondaryButton(if (c.mineConnected) "Replace mine" else "Connect mine", enabled = !busy, onClick = { form = "user"; values = emptyMap() })
          if (c.mineConnected) {
            ClawSecondaryButton("Disconnect mine", enabled = !busy, onClick = {
              vm.applyOrionOp(op("connector.disconnect", "connector" to str(c.kind), "scope" to str("user")), "Your ${c.label} was disconnected.")
            })
          }
        }
      }
    }
    form?.let { scope ->
      Text("Credentials go straight to the gateway's encrypted vault and are never shown again.", style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      c.fields.forEach { f ->
        ClawTextField(
          value = values[f].orEmpty(),
          onValueChange = { values = values + (f to it) },
          placeholder = FIELD_LABELS[f] ?: f,
          label = FIELD_LABELS[f] ?: f,
          secret = f in SECRET_FIELDS,
          maxLines = 1,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClawSecondaryButton("Cancel", onClick = { form = null; values = emptyMap() })
        ClawPrimaryButton(
          "Save securely",
          enabled = !busy && c.fields.all { !values[it].isNullOrBlank() },
          onClick = {
            vm.applyOrionOp(
              op(
                "connector.connect",
                "connector" to str(c.kind),
                "scope" to str(scope),
                "fields" to buildJsonObject { c.fields.forEach { put(it, JsonPrimitive(values[it].orEmpty().trim())) } },
              ),
              "${c.label} connected (${if (scope == "org") "organization" else "personal"}).",
            )
            form = null
            values = emptyMap()
          },
        )
      }
    }
    if (admin && c.scopes.contains("user")) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Let people connect their own", style = ClawTheme.type.body, color = ClawTheme.colors.text, modifier = Modifier.weight(1f))
        Switch(checked = c.userScopeAllowed, enabled = !busy, onCheckedChange = { vm.applyOrionOp(op("connector.policy", "connector" to str(c.kind), "userScope" to JsonPrimitive(it)), "Policy updated.") })
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("People without their own may use the organization's", style = ClawTheme.type.body, color = ClawTheme.colors.text, modifier = Modifier.weight(1f))
        Switch(checked = c.orgFallback, enabled = !busy, onCheckedChange = { vm.applyOrionOp(op("connector.policy", "connector" to str(c.kind), "orgFallback" to JsonPrimitive(it)), "Policy updated.") })
      }
    }
    c.people?.takeIf { it.isNotEmpty() }?.let { people ->
      Text("Personal connections", style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      people.forEach { p ->
        ClawListItem(
          title = p.userId,
          trailing = {
            ClawSecondaryButton("Revoke", enabled = !busy, onClick = {
              confirm = { vm.applyOrionOp(op("connector.disconnect", "connector" to str(c.kind), "scope" to str("user"), "userId" to str(p.userId)), "Revoked ${p.userId}'s ${c.label}.") }
            })
          },
        )
      }
    }
  }
  confirm?.let { action ->
    AlertDialog(
      onDismissRequest = { confirm = null },
      title = { Text("Are you sure?") },
      text = { Text("This removes the connection. People using it will need to reconnect.") },
      confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Confirm") } },
      dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun TasksTab(
  s: OrionAdminSummary.Ready,
  busy: Boolean,
  vm: MainViewModel,
) {
  var expanded by rememberSaveable { mutableStateOf("") }
  var sharing by remember { mutableStateOf<OrionTask?>(null) }
  var shareWith by remember { mutableStateOf("") }
  var approving by remember { mutableStateOf<OrionTask?>(null) }
  var capability by remember { mutableStateOf("") }
  val context = LocalContext.current
  if (s.tasks.isEmpty()) {
    SettingsMessagePanel("No tasks yet. Ask Orion to investigate a bug, or mention it on a GitHub issue.")
    return
  }
  s.tasks.forEach { t ->
    ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      ClawListItem(
        title = t.title.ifBlank { "(no title)" },
        subtitle = listOfNotNull(t.workspace, t.source, t.owner, t.sharedWith.takeIf { it.isNotEmpty() }?.let { "shared with ${it.joinToString()}" }).joinToString(" · "),
        trailing = { ClawStatusPill(pretty(t.status), statusFor(t)) },
        onClick = { expanded = if (expanded == t.id) "" else t.id },
      )
      if (expanded == t.id) {
        Text(t.summary, style = ClawTheme.type.code, color = ClawTheme.colors.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          t.pullRequest?.let { url -> ClawSecondaryButton("Open PR", onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) }
          if (s.can("tasks.steer")) {
            ClawSecondaryButton("Retry", enabled = !busy, onClick = { vm.applyOrionOp(op("task.retry", "taskId" to str(t.id)), "Retrying.") })
            ClawSecondaryButton("Stop", enabled = !busy, onClick = { vm.applyOrionOp(op("task.stop", "taskId" to str(t.id)), "Stopped.") })
          }
          if (s.can("tasks.approve") && t.status == "NEEDS_APPROVAL") ClawPrimaryButton("Approve…", enabled = !busy, onClick = { approving = t; capability = "" })
          if (t.canShare) ClawSecondaryButton("Share…", enabled = !busy, onClick = { sharing = t; shareWith = "" })
        }
      }
    }
  }
  sharing?.let { t ->
    AlertDialog(
      onDismissRequest = { sharing = null },
      title = { Text("Share this task") },
      text = { ClawTextField(value = shareWith, onValueChange = { shareWith = it }, placeholder = "Profile ID", label = "Share with", maxLines = 1) },
      confirmButton = {
        TextButton(enabled = shareWith.isNotBlank(), onClick = {
          vm.applyOrionOp(op("task.share", "taskId" to str(t.id), "with" to str(shareWith.trim())), "Shared with ${shareWith.trim()}.")
          sharing = null
        }) { Text("Share") }
      },
      dismissButton = { TextButton(onClick = { sharing = null }) { Text("Cancel") } },
    )
  }
  approving?.let { t ->
    AlertDialog(
      onDismissRequest = { approving = null },
      title = { Text("Approve a capability") },
      text = { ClawTextField(value = capability, onValueChange = { capability = it }, placeholder = "e.g. prod.read.database", label = "Capability", maxLines = 1) },
      confirmButton = {
        TextButton(enabled = capability.isNotBlank(), onClick = {
          vm.applyOrionOp(op("task.approve", "taskId" to str(t.id), "capability" to str(capability.trim())), "Approved ${capability.trim()}.")
          approving = null
        }) { Text("Approve") }
      },
      dismissButton = { TextButton(onClick = { approving = null }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun WorkspacesTab(s: OrionAdminSummary.Ready) {
  if (s.workspaces.isEmpty()) {
    SettingsMessagePanel("No workspaces configured. Add manifests to ORION_WORKSPACES_DIR on the gateway.")
    return
  }
  s.workspaces.forEach { w ->
    ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(w.name, style = ClawTheme.type.section, color = ClawTheme.colors.text)
          Text(listOfNotNull(w.kind, w.repo).joinToString(" · "), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
        }
        if (w.production) ClawStatusPill("Production access", ClawStatus.Warning)
      }
      Text("Allowed without asking", style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { w.grants.forEach { ClawPill(text = it) } }
      if (w.requireApproval.isNotEmpty()) {
        Text("Needs human approval", style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { w.requireApproval.forEach { ClawPill(text = it) } }
      }
      Text("People with access", style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { w.members.forEach { ClawPill(text = it) } }
    }
  }
}

@Composable
private fun ActivityTab(s: OrionAdminSummary.Ready) {
  if (s.audit.isEmpty()) {
    SettingsMessagePanel("No activity yet.")
    return
  }
  ClawListPanel(items = s.audit) { a ->
    ClawListItem(
      title = "${a.actor} · ${a.action}",
      subtitle = listOfNotNull(a.connector, a.scope?.takeIf { it != "org:" }?.removePrefix("user:")).joinToString(" · "),
      metadata = a.at.take(16).replace('T', ' '),
    )
  }
}

@Composable
private fun PairingPanel(
  pairing: OrionPairingState,
  vm: MainViewModel,
) {
  val context = LocalContext.current
  when (pairing) {
    OrionPairingState.Idle -> Unit
    is OrionPairingState.Working ->
      ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pairing.label, style = ClawTheme.type.body, color = ClawTheme.colors.text)
        ClawSecondaryButton("Cancel", onClick = vm::cancelOrionPairing)
      }
    is OrionPairingState.WhatsAppQr ->
      ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Link WhatsApp", style = ClawTheme.type.section, color = ClawTheme.colors.text)
        Text(
          "On the phone that has the number: WhatsApp → Settings → Linked devices → Link a device, then scan this code.",
          style = ClawTheme.type.body,
          color = ClawTheme.colors.textMuted,
        )
        val bitmap = remember(pairing.qrPng) { BitmapFactory.decodeByteArray(pairing.qrPng, 0, pairing.qrPng.size)?.asImageBitmap() }
        bitmap?.let {
          Image(bitmap = it, contentDescription = "WhatsApp link code", modifier = Modifier.fillMaxWidth().height(280.dp))
        }
        pairing.message?.let { Text(it, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted) }
        ClawSecondaryButton("Cancel", onClick = vm::cancelOrionPairing)
      }
    is OrionPairingState.GitHubCode ->
      ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sign in with GitHub", style = ClawTheme.type.section, color = ClawTheme.colors.text)
        Text("Enter this code on GitHub:", style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
        Text(pairing.userCode, style = ClawTheme.type.display, color = ClawTheme.colors.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ClawPrimaryButton("Open GitHub", onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pairing.verificationUri))) })
          ClawSecondaryButton("Cancel", onClick = vm::cancelOrionPairing)
        }
        Text("Waiting for you to approve…", style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      }
    is OrionPairingState.Done ->
      ClawPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(pairing.message, style = ClawTheme.type.body, color = ClawTheme.colors.success, modifier = Modifier.weight(1f))
          TextButton(onClick = vm::cancelOrionPairing) { Text("Dismiss") }
        }
      }
    is OrionPairingState.Failed ->
      ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pairing.message, style = ClawTheme.type.body, color = ClawTheme.colors.danger)
        ClawSecondaryButton("Dismiss", onClick = vm::cancelOrionPairing)
      }
  }
}
