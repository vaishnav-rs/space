package ai.openclaw.app

import ai.openclaw.app.node.asObjectOrNull
import ai.openclaw.app.node.asStringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** View model for the gateway's `orion.admin.overview` response (see src/orion/access/admin-api.ts). */
data class OrionMember(
  val profileId: String,
  val role: String,
  val workspaces: List<String>,
  val githubLogin: String?,
  val addresses: List<String>,
)

data class OrionPersonConnection(
  val userId: String,
  val updatedAt: String?,
)

data class OrionConnector(
  val kind: String,
  val label: String,
  val scopes: List<String>,
  val fields: List<String>,
  val userScopeAllowed: Boolean,
  val orgFallback: Boolean,
  val orgConnected: Boolean,
  val orgBy: String?,
  val orgUpdatedAt: String?,
  val mineConnected: Boolean,
  val mineUpdatedAt: String?,
  val people: List<OrionPersonConnection>?,
) {
  val orgOnly: Boolean get() = !scopes.contains("user")
}

data class OrionTask(
  val id: String,
  val workspace: String,
  val status: String,
  val source: String,
  val title: String,
  val owner: String?,
  val sharedWith: List<String>,
  val updatedAt: String,
  val pullRequest: String?,
  val summary: String,
  val canShare: Boolean,
) {
  val needsPerson: Boolean get() = status.startsWith("NEEDS") || status == "BLOCKED" || status == "READY_FOR_HUMAN"
  val finished: Boolean get() = status == "COMPLETED" || status == "FAILED"
}

data class OrionWorkspace(
  val id: String,
  val name: String,
  val kind: String,
  val repo: String?,
  val grants: List<String>,
  val requireApproval: List<String>,
  val production: Boolean,
  val members: List<String>,
)

data class OrionHealth(
  val agentBound: Boolean,
  val githubToken: Boolean,
  val webhookSecret: Boolean,
  val vaultKey: Boolean,
)

data class OrionAuditEntry(
  val at: String,
  val actor: String,
  val action: String,
  val connector: String?,
  val scope: String?,
)

sealed interface OrionAdminSummary {
  data class Unconfigured(
    val reason: String,
  ) : OrionAdminSummary

  data class Ready(
    val needsBootstrap: Boolean,
    val vaultAvailable: Boolean,
    val meId: String,
    val meRole: String,
    val permissions: Set<String>,
    val members: List<OrionMember>,
    val connectors: List<OrionConnector>,
    val tasks: List<OrionTask>,
    val workspaces: List<OrionWorkspace>,
    val health: OrionHealth,
    val audit: List<OrionAuditEntry>,
  ) : OrionAdminSummary {
    fun can(permission: String): Boolean = permissions.contains(permission)

    val isAdmin: Boolean get() = meRole == "owner" || meRole == "admin"
  }
}

val ORION_ROLES: List<String> = listOf("owner", "admin", "engineer", "member", "viewer")

fun orionRoleLabel(role: String): String = role.replaceFirstChar { it.uppercase() }

fun orionRoleBlurb(role: String): String =
  when (role) {
    "owner" -> "Everything, including creating admins."
    "admin" -> "Manages people, org connections and approvals; sees all tasks."
    "engineer" -> "Personal tools; starts and steers engineering tasks in their workspaces."
    "member" -> "Personal assistant with their own accounts; no engineering tasks."
    else -> "Read-only: sees what has been shared with them."
  }

private fun JsonObject?.str(key: String): String? = this?.get(key).asStringOrNull()?.takeIf { it.isNotBlank() }

private fun JsonObject?.flag(key: String): Boolean = (this?.get(key) as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject?.strings(key: String): List<String> = (this?.get(key) as? JsonArray)?.mapNotNull { it.asStringOrNull() }.orEmpty()

private fun JsonObject?.objects(key: String): List<JsonObject> = (this?.get(key) as? JsonArray)?.mapNotNull { it.asObjectOrNull() }.orEmpty()

/** Parses the overview. Returns null for a malformed response so callers can show an error instead of stale data. */
fun parseOrionOverview(root: JsonObject?): OrionAdminSummary? {
  root ?: return null
  if (!root.flag("configured")) {
    return OrionAdminSummary.Unconfigured(root.str("reason") ?: "Orion access control is not configured on this gateway.")
  }
  val me = root["me"].asObjectOrNull() ?: return null
  val meId = me.str("profileId") ?: return null
  val health = root["health"].asObjectOrNull()
  return OrionAdminSummary.Ready(
    needsBootstrap = root.flag("needsBootstrap"),
    vaultAvailable = root.flag("vaultAvailable"),
    meId = meId,
    meRole = me.str("role") ?: "viewer",
    permissions = me.strings("permissions").toSet(),
    members =
      root.objects("members").mapNotNull { m ->
        OrionMember(
          profileId = m.str("profileId") ?: return@mapNotNull null,
          role = m.str("role") ?: "viewer",
          workspaces = m.strings("workspaces"),
          githubLogin = m.str("githubLogin"),
          addresses = m.strings("addresses"),
        )
      },
    connectors =
      root.objects("connectors").mapNotNull { c ->
        val policy = c["policy"].asObjectOrNull()
        val org = c["org"].asObjectOrNull()
        val mine = c["mine"].asObjectOrNull()
        OrionConnector(
          kind = c.str("kind") ?: return@mapNotNull null,
          label = c.str("label") ?: c.str("kind").orEmpty(),
          scopes = c.strings("scopes"),
          fields = c.strings("fields"),
          userScopeAllowed = policy.flag("userScope"),
          orgFallback = policy.flag("orgFallback"),
          orgConnected = org.flag("connected"),
          orgBy = org.str("by"),
          orgUpdatedAt = org.str("updatedAt"),
          mineConnected = mine.flag("connected"),
          mineUpdatedAt = mine.str("updatedAt"),
          people =
            (c["people"] as? JsonArray)?.mapNotNull { p ->
              p.asObjectOrNull()?.let { po -> OrionPersonConnection(po.str("userId") ?: return@let null, po.str("updatedAt")) }
            },
        )
      },
    tasks =
      root.objects("tasks").mapNotNull { t ->
        OrionTask(
          id = t.str("id") ?: return@mapNotNull null,
          workspace = t.str("workspace").orEmpty(),
          status = t.str("status").orEmpty(),
          source = t.str("source").orEmpty(),
          title = t.str("title").orEmpty(),
          owner = t.str("owner"),
          sharedWith = t.strings("sharedWith"),
          updatedAt = t.str("updatedAt").orEmpty(),
          pullRequest = t.str("pullRequest"),
          summary = t.str("summary").orEmpty(),
          canShare = t.flag("canShare"),
        )
      },
    workspaces =
      root.objects("workspaces").mapNotNull { w ->
        OrionWorkspace(
          id = w.str("id") ?: return@mapNotNull null,
          name = w.str("name") ?: w.str("id").orEmpty(),
          kind = w.str("kind").orEmpty(),
          repo = w.str("repo"),
          grants = w.strings("grants"),
          requireApproval = w.strings("requireApproval"),
          production = w.flag("production"),
          members = w.strings("members"),
        )
      },
    health =
      OrionHealth(
        agentBound = health.flag("agentBound"),
        githubToken = health.flag("githubToken"),
        webhookSecret = health.flag("webhookSecret"),
        vaultKey = health.flag("vaultKey"),
      ),
    audit =
      root.objects("audit").mapNotNull { a ->
        OrionAuditEntry(
          at = a.str("at") ?: return@mapNotNull null,
          actor = a.str("actor").orEmpty(),
          action = a.str("action").orEmpty(),
          connector = a.str("connector"),
          scope = a.str("scope"),
        )
      },
  )
}

/** Progress of a pairing flow the person started from the phone. */
sealed interface OrionPairingState {
  data object Idle : OrionPairingState

  data class Working(
    val label: String,
  ) : OrionPairingState

  /** WhatsApp: a QR to scan from WhatsApp → Linked devices. [qrPng] is the decoded PNG. */
  data class WhatsAppQr(
    val qrPng: ByteArray,
    val message: String?,
  ) : OrionPairingState

  /** GitHub device flow: enter [userCode] at [verificationUri]. */
  data class GitHubCode(
    val requestId: String,
    val userCode: String,
    val verificationUri: String,
    val pollAfterMs: Long,
  ) : OrionPairingState

  data class Done(
    val message: String,
  ) : OrionPairingState

  data class Failed(
    val message: String,
  ) : OrionPairingState
}

/** One-line result of an admin action, shown until dismissed. */
data class OrionNotice(
  val text: String,
  val isError: Boolean,
)

private const val PNG_DATA_URL_PREFIX = "data:image/png;base64,"

/** Decodes the gateway's QR image (`data:image/png;base64,...`). Null when the value is not a PNG data URL. */
fun decodePngDataUrl(value: String): ByteArray? {
  if (!value.startsWith(PNG_DATA_URL_PREFIX)) return null
  return runCatching { java.util.Base64.getDecoder().decode(value.substring(PNG_DATA_URL_PREFIX.length)) }.getOrNull()
}
