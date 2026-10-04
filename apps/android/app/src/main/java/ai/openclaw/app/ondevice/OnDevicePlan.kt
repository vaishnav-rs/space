package ai.openclaw.app.ondevice

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** User-editable settings for the gateway that runs on this phone. */
data class OnDeviceSettings(
  val ownerEmail: String = "",
  val ownerWhatsapp: String = "",
  /** True binds the gateway to the network (Wi-Fi/Tailscale) so other devices can reach it. */
  val lan: Boolean = false,
  val startOnBoot: Boolean = true,
  val port: Int = DEFAULT_PORT,
  /** Whitelisted credentials (see [CREDENTIAL_KEYS]). */
  val credentials: Map<String, String> = emptyMap(),
) {
  companion object {
    const val DEFAULT_PORT = 18789
  }
}

/** Generated once and kept: rotating the vault key orphans stored credentials, the token unpairs devices. */
data class OnDeviceSecrets(
  val vaultKey: String,
  val webhookSecret: String,
  val gatewayToken: String,
) {
  companion object {
    fun generate(random: SecureRandom = SecureRandom()): OnDeviceSecrets {
      fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
      return OnDeviceSecrets(
        vaultKey = Base64.getEncoder().encodeToString(bytes(32)),
        webhookSecret = bytes(24).joinToString("") { "%02x".format(it) },
        gatewayToken = bytes(24).joinToString("") { "%02x".format(it) },
      )
    }
  }
}

/** Environment variables the user may supply from the app; anything else is ignored. */
val CREDENTIAL_KEYS =
  listOf(
    "GOOGLE_CLIENT_ID",
    "GOOGLE_CLIENT_SECRET",
    "GOOGLE_REFRESH_TOKEN",
    "RESEND_API_KEY",
    "RESEND_FROM",
    "ORION_GITHUB_TOKEN",
    "PERSONAL_DEV_REPOS",
  )

/** Where everything lives. Executables come from the read-only native library directory (exec is allowed there). */
class OnDeviceLayout(
  val root: File,
  val nativeLibDir: File,
) {
  val runtimeDir = File(root, "runtime")
  val libDir = File(runtimeDir, "lib")
  val caBundle = File(runtimeDir, "etc/cert.pem")
  val gatewayDir = File(root, "gateway")
  val home = File(root, "home")
  val tmp = File(root, "tmp")
  val binDir = File(root, "bin")
  val gitCoreDir = File(root, "libexec/git-core")
  val gitTemplates = File(root, "git-templates")
  val stateDir = File(root, "state")
  val openclawStateDir = File(root, "openclaw")
  val workspacesDir = File(root, "workspaces")
  val agentWorkspace = File(root, "agent-workspace")
  val configFile = File(root, "openclaw.json")
  val secretsFile = File(root, "secrets.json")
  val logFile = File(root, "gateway.log")
  val node = File(nativeLibDir, "libnode.so")
  val entry = File(gatewayDir, "openclaw.mjs")

  /** Symlink name → native executable. These are what PATH, git and ssh resolve. */
  fun toolLinks(): Map<File, File> =
    mapOf(
      File(binDir, "node") to File(nativeLibDir, "libnode.so"),
      File(binDir, "git") to File(nativeLibDir, "libgit.so"),
      File(binDir, "ssh") to File(nativeLibDir, "libssh.so"),
      File(binDir, "ssh-keygen") to File(nativeLibDir, "libssh-keygen.so"),
      File(gitCoreDir, "git-remote-http") to File(nativeLibDir, "libgit-remote-http.so"),
      File(gitCoreDir, "git-remote-https") to File(nativeLibDir, "libgit-remote-http.so"),
    )
}

object OnDevicePlan {
  /** Process environment for the gateway and everything it spawns (git, ssh, node). */
  fun environment(
    layout: OnDeviceLayout,
    secrets: OnDeviceSecrets,
    settings: OnDeviceSettings,
  ): Map<String, String> {
    val env = linkedMapOf<String, String>()
    env["HOME"] = layout.home.path
    env["TMPDIR"] = layout.tmp.path
    env["PATH"] = "${layout.binDir.path}:/system/bin"
    env["LD_LIBRARY_PATH"] = layout.libDir.path
    env["SSL_CERT_FILE"] = layout.caBundle.path
    env["GIT_SSL_CAINFO"] = layout.caBundle.path
    env["GIT_EXEC_PATH"] = layout.gitCoreDir.path
    env["GIT_TEMPLATE_DIR"] = layout.gitTemplates.path
    env["GIT_CONFIG_NOSYSTEM"] = "1"
    env["GIT_PAGER"] = "cat"
    env["GIT_TERMINAL_PROMPT"] = "0"
    // Full failure details (reason and stack) in the in-app log.
    env["OPENCLAW_DEBUG"] = "1"
    env["GIT_SSH_COMMAND"] = "${File(layout.binDir, "ssh").path} -F ${File(layout.home, ".ssh/config").path}"
    env["OPENCLAW_STATE_DIR"] = layout.openclawStateDir.path
    env["OPENCLAW_CONFIG_PATH"] = layout.configFile.path
    env["OPENCLAW_GATEWAY_TOKEN"] = secrets.gatewayToken
    env["ORION_STATE_DIR"] = layout.stateDir.path
    env["ORION_WORKSPACES_DIR"] = layout.workspacesDir.path
    env["ORION_VAULT_KEY"] = secrets.vaultKey
    env["ORION_GITHUB_WEBHOOK_SECRET"] = secrets.webhookSecret
    env["ORION_DEFAULT_REQUESTER"] = "owner"
    if (settings.ownerEmail.isNotBlank()) env["PERSONAL_OWNER_EMAILS"] = settings.ownerEmail.trim()
    if (settings.ownerWhatsapp.isNotBlank()) env["PERSONAL_OWNER_TARGETS"] = settings.ownerWhatsapp.trim()
    for (key in CREDENTIAL_KEYS) settings.credentials[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { env[key] = it }
    return env
  }

  fun configJson(
    layout: OnDeviceLayout,
    settings: OnDeviceSettings,
  ): String {
    val owner = JSONArray()
    if (settings.ownerWhatsapp.isNotBlank()) owner.put("whatsapp:${settings.ownerWhatsapp.trim()}")
    val heartbeat =
      JSONObject()
        .put("every", "10m")
        .put("target", "owner")
        .put("directPolicy", "allow")
        .put("lightContext", false)
        .put("activeHours", JSONObject().put("start", "07:30").put("end", "23:30"))
        .put(
          "prompt",
          "Proactive sweep. Check unread/important Gmail, today's calendar (next 3h), open PRs/reviews/CI on my repos, " +
            "due Google Tasks, and my own plan progress. Message me on WhatsApp only when something needs me, " +
            "is about to be late, or has an obvious next step you can propose. Otherwise reply NO_REPLY.",
        )
    return JSONObject()
      .put("gateway", JSONObject().put("mode", "local").put("port", settings.port).put("bind", if (settings.lan) "lan" else "loopback").put("auth", JSONObject().put("mode", "token")))
      .put("commands", JSONObject().put("ownerAllowFrom", owner))
      .put("agents", JSONObject().put("defaults", JSONObject().put("workspace", layout.agentWorkspace.path).put("heartbeat", heartbeat)))
      .toString(2)
  }

  fun command(
    layout: OnDeviceLayout,
    settings: OnDeviceSettings,
  ): List<String> = listOf(layout.node.path, layout.entry.path, "gateway", "run", "--port", settings.port.toString())

  /** Minimal personal workspace manifest (no repository access); Hewar is added from the app later. */
  fun personalManifest(): String =
    JSONObject()
      .put("id", "personal")
      .put("name", "Personal")
      .put("kind", "personal")
      .put("policy", JSONObject().put("grant", JSONArray(listOf("filesystem.read", "knowledge.read"))).put("requireApproval", JSONArray()))
      .toString(2)
}
