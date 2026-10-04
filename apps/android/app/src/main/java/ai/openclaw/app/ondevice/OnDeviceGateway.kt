package ai.openclaw.app.ondevice

import android.content.Context
import android.os.Build
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface OnDeviceState {
  data class Unsupported(
    val reason: String,
  ) : OnDeviceState

  data object Stopped : OnDeviceState

  data class Installing(
    val step: String,
  ) : OnDeviceState

  data object Starting : OnDeviceState

  data class Running(
    val port: Int,
    val lan: Boolean,
  ) : OnDeviceState

  data class Failed(
    val message: String,
  ) : OnDeviceState
}

private const val ASSET_DIR = "orion-runtime"
private const val LOG_LINES = 400
private const val START_TIMEOUT_MS = 180_000L

/**
 * Runs the Orion gateway as a child process of this app: Node, git and ssh are native executables
 * shipped in the APK, the gateway itself is unpacked from assets on first run. The app then
 * connects to it over loopback like to any other gateway.
 */
class OnDeviceGateway(
  private val context: Context,
  private val scope: CoroutineScope,
) {
  val layout = OnDeviceLayout(File(context.filesDir, "orion"), File(context.applicationInfo.nativeLibraryDir.orEmpty()))
  private val prefs = context.getSharedPreferences("orion_ondevice", Context.MODE_PRIVATE)
  private val mutableState = MutableStateFlow<OnDeviceState>(unsupportedReason()?.let(OnDeviceState::Unsupported) ?: OnDeviceState.Stopped)
  val state: StateFlow<OnDeviceState> = mutableState
  private val mutableLog = MutableStateFlow<List<String>>(emptyList())
  val log: StateFlow<List<String>> = mutableLog
  private var process: Process? = null
  private var supervisor: Job? = null
  @Volatile private var stopRequested = false

  fun settings(): OnDeviceSettings {
    val json = runCatching { JSONObject(prefs.getString("settings", "{}") ?: "{}") }.getOrDefault(JSONObject())
    val creds = json.optJSONObject("credentials")
    return OnDeviceSettings(
      ownerEmail = json.optString("ownerEmail"),
      ownerWhatsapp = json.optString("ownerWhatsapp"),
      lan = json.optBoolean("lan", false),
      startOnBoot = json.optBoolean("startOnBoot", true),
      port = json.optInt("port", OnDeviceSettings.DEFAULT_PORT),
      credentials = CREDENTIAL_KEYS.mapNotNull { k -> creds?.optString(k)?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap(),
    )
  }

  fun saveSettings(settings: OnDeviceSettings) {
    val json =
      JSONObject()
        .put("ownerEmail", settings.ownerEmail)
        .put("ownerWhatsapp", settings.ownerWhatsapp)
        .put("lan", settings.lan)
        .put("startOnBoot", settings.startOnBoot)
        .put("port", settings.port)
        .put("credentials", JSONObject(settings.credentials))
    prefs.edit().putString("settings", json.toString()).apply()
  }

  /** Whether the user left the gateway switched on; used to restart it after a reboot. */
  fun isEnabled(): Boolean = prefs.getBoolean("enabled", false)

  fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean("enabled", enabled).apply()

  /** The token the app presents to its own gateway; null until the first start has generated secrets. */
  fun gatewayToken(): String? = loadSecrets()?.gatewayToken

  fun unsupportedReason(): String? =
    when {
      !Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "The on-device gateway needs a 64-bit ARM phone."
      !layout.node.exists() -> "This build does not include the on-device runtime. Install the release APK."
      else -> null
    }

  /** Addresses other devices can use when the gateway is bound to the network. */
  fun networkAddresses(): List<String> =
    runCatching {
      NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { nif ->
        nif.inetAddresses.toList().filter { !it.isLoopbackAddress && it.address.size == 4 }.map { it.hostAddress.orEmpty() }
      }
    }.getOrDefault(emptyList())

  private fun loadSecrets(): OnDeviceSecrets? =
    runCatching {
      val json = JSONObject(layout.secretsFile.readText())
      OnDeviceSecrets(json.getString("vaultKey"), json.getString("webhookSecret"), json.getString("gatewayToken"))
    }.getOrNull()

  private fun secrets(): OnDeviceSecrets =
    loadSecrets() ?: OnDeviceSecrets.generate().also { s ->
      layout.root.mkdirs()
      layout.secretsFile.writeText(JSONObject().put("vaultKey", s.vaultKey).put("webhookSecret", s.webhookSecret).put("gatewayToken", s.gatewayToken).toString())
      layout.secretsFile.setReadable(false, false)
      layout.secretsFile.setReadable(true, true)
    }

  private fun appendLog(line: String) {
    mutableLog.value = (mutableLog.value + line).takeLast(LOG_LINES)
  }

  /**
   * Opens a bundled archive. AssetManager is tried first; if it refuses (large or oddly packaged entries),
   * the APK is read directly as a zip. When both fail the error lists what the APK really contains.
   */
  private fun openBundled(name: String): java.io.InputStream {
    runCatching { return context.assets.open("$ASSET_DIR/$name") }
    val apk = java.util.zip.ZipFile(context.applicationInfo.sourceDir)
    val entry = apk.getEntry("assets/$ASSET_DIR/$name")
    if (entry != null) {
      val input = apk.getInputStream(entry)
      return object : java.io.FilterInputStream(input) {
        override fun close() {
          super.close()
          apk.close()
        }
      }
    }
    val present = apk.entries().asSequence().filter { it.name.contains(ASSET_DIR) || it.name.startsWith("lib/") }.joinToString { "${it.name} (${it.size / 1_000_000} MB)" }
    apk.close()
    error("$name is not in this APK. Found: ${present.ifEmpty { "nothing from the runtime" }}")
  }

  /** Unpacks the runtime libraries and the gateway when their bundled version changed. Idempotent. */
  suspend fun prepare() =
    withContext(Dispatchers.IO) {
      unsupportedReason()?.let { error(it) }
      for (dir in listOf(layout.home, layout.tmp, layout.binDir, layout.gitCoreDir, layout.gitTemplates, layout.stateDir, layout.openclawStateDir, layout.workspacesDir, layout.agentWorkspace, File(layout.home, ".ssh"))) dir.mkdirs()
      val manifest = runCatching { openBundled("runtime.json").bufferedReader().use { it.readText() } }.getOrElse { error("Runtime manifest missing from this build: ${it.message}") }
      val runtimeVersion = JSONObject(manifest).optString("libsSha256")
      if (File(layout.runtimeDir, ".version").takeIf { it.exists() }?.readText() != runtimeVersion) {
        mutableState.value = OnDeviceState.Installing("Unpacking runtime libraries")
        layout.runtimeDir.deleteRecursively()
        openBundled("libs.bin").use { TarGz.extract(it, layout.runtimeDir) }
        File(layout.runtimeDir, ".version").writeText(runtimeVersion)
      }
      val gatewayVersion = runCatching { openBundled("gateway.version").bufferedReader().use { it.readText().trim() } }.getOrElse { error("Gateway bundle missing from this build: ${it.message}") }
      if (File(layout.gatewayDir, ".version").takeIf { it.exists() }?.readText() != gatewayVersion) {
        mutableState.value = OnDeviceState.Installing("Unpacking the gateway (first run takes a minute)")
        layout.gatewayDir.deleteRecursively()
        var count = 0
        openBundled("gateway.bin").use {
          TarGz.extract(it, layout.gatewayDir) {
            if (++count % 2000 == 0) mutableState.value = OnDeviceState.Installing("Unpacking the gateway ($count files)")
          }
        }
        File(layout.gatewayDir, ".version").writeText(gatewayVersion)
      }
      for ((link, target) in layout.toolLinks()) {
        Files.deleteIfExists(link.toPath())
        link.parentFile?.mkdirs()
        Files.createSymbolicLink(link.toPath(), target.toPath())
      }
      // Persona files and the workspace manifest are only seeded; the user's edits are never overwritten.
      File(layout.workspacesDir, "personal.json").takeIf { !it.exists() }?.writeText(OnDevicePlan.personalManifest())
      val persona = File(layout.gatewayDir, "personal/workspace")
      persona.listFiles()?.forEach { f -> File(layout.agentWorkspace, f.name).takeIf { !it.exists() }?.let { f.copyTo(it) } }
      val settings = settings()
      layout.configFile.writeText(OnDevicePlan.configJson(layout, settings))
      File(layout.home, ".ssh/config").takeIf { !it.exists() }?.writeText("Host *\n  StrictHostKeyChecking accept-new\n  UserKnownHostsFile ${File(layout.home, ".ssh/known_hosts").path}\n  IdentityFile ${File(layout.home, ".ssh/id_ed25519").path}\n")
    }

  fun start() {
    if (supervisor?.isActive == true) return
    stopRequested = false
    supervisor =
      scope.launch(Dispatchers.IO) {
        var attempt = 0
        while (isActive && !stopRequested) {
          try {
            prepare()
            val settings = settings()
            mutableState.value = OnDeviceState.Starting
            val builder = ProcessBuilder(OnDevicePlan.command(layout, settings)).directory(layout.gatewayDir).redirectErrorStream(true)
            builder.environment().apply {
              clear()
              putAll(OnDevicePlan.environment(layout, secrets(), settings))
            }
            appendLog("Starting gateway on port ${settings.port} (${if (settings.lan) "network" else "this phone only"})")
            val p = builder.start()
            process = p
            val reader =
              scope.launch(Dispatchers.IO) {
                p.inputStream.bufferedReader().forEachLine { line ->
                  appendLog(line)
                  runCatching { layout.logFile.appendText(line + "\n") }
                }
              }
            if (waitForPort(settings.port, p)) {
              mutableState.value = OnDeviceState.Running(settings.port, settings.lan)
              attempt = 0
            } else if (p.isAlive) {
              p.destroy()
              mutableState.value = OnDeviceState.Failed("The gateway did not start listening within ${START_TIMEOUT_MS / 1000}s. See the log.")
            }
            val code = withContext(Dispatchers.IO) { p.waitFor() }
            reader.cancel()
            process = null
            if (stopRequested) break
            val failed = "The gateway stopped (exit $code). Restarting…"
            appendLog(failed)
            mutableState.value = OnDeviceState.Failed(failed)
          } catch (err: CancellationException) {
            throw err
          } catch (err: Throwable) {
            appendLog("Error: ${err.message}")
            mutableState.value = OnDeviceState.Failed("${err::class.java.simpleName}: ${err.message ?: "could not start the gateway"}")
          }
          attempt++
          delay(minOf(60_000L, 3_000L shl minOf(attempt, 4)))
        }
        if (stopRequested) mutableState.value = OnDeviceState.Stopped
      }
  }

  private suspend fun waitForPort(
    port: Int,
    p: Process,
  ): Boolean {
    val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline && p.isAlive) {
      val open = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) } }.isSuccess
      if (open) return true
      delay(1_000)
    }
    return false
  }

  fun stop() {
    stopRequested = true
    process?.let { p ->
      p.destroy()
      scope.launch(Dispatchers.IO) {
        if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
      }
    }
    supervisor?.cancel()
    supervisor = null
    mutableState.value = unsupportedReason()?.let(OnDeviceState::Unsupported) ?: OnDeviceState.Stopped
  }

  /** Runs each bundled tool once so problems on a particular phone show up with an explanation. */
  suspend fun selfTest(): List<Pair<String, String>> =
    withContext(Dispatchers.IO) {
      val results = mutableListOf<Pair<String, String>>()
      try {
        prepare()
      } catch (err: Throwable) {
        return@withContext listOf("Prepare" to "FAILED: ${err.message}")
      }
      val settings = settings()
      val env = OnDevicePlan.environment(layout, secrets(), settings)
      fun run(
        label: String,
        vararg cmd: String,
      ) {
        val outcome =
          runCatching {
            val b = ProcessBuilder(*cmd).redirectErrorStream(true)
            b.environment().apply {
              clear()
              putAll(env)
            }
            val p = b.start()
            val text = p.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull().orEmpty()
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
              p.destroyForcibly()
              "FAILED: timed out"
            } else if (p.exitValue() == 0) {
              "OK $text"
            } else {
              "FAILED (exit ${p.exitValue()}): $text"
            }
          }.getOrElse { "FAILED: ${it.message}" }
        results += label to outcome
      }
      run("node", File(layout.binDir, "node").path, "-p", "process.version + ' ' + process.platform + ' ' + process.arch")
      run("node:sqlite", File(layout.binDir, "node").path, "-e", "require('node:sqlite'); console.log('available')")
      run("git", File(layout.binDir, "git").path, "--version")
      run("ssh", File(layout.binDir, "ssh").path, "-V")
      run("gateway", layout.node.path, layout.entry.path, "--version")
      results
    }

  /** Wipes the gateway's data (chats, tasks, vault) but keeps the runtime so setup is quick again. */
  fun resetData() {
    stop()
    listOf(layout.stateDir, layout.openclawStateDir, layout.workspacesDir, layout.agentWorkspace, layout.secretsFile, layout.configFile, layout.logFile).forEach { it.deleteRecursively() }
    mutableLog.value = emptyList()
  }
}
