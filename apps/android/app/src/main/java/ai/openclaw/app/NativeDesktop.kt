package ai.openclaw.app

import ai.openclaw.app.gateway.GatewayTlsParams
import ai.openclaw.app.gateway.buildGatewayTlsConfig
import android.graphics.Bitmap
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

sealed interface NativeDesktopState {
  data object Idle : NativeDesktopState

  data object Connecting : NativeDesktopState

  data class Live(
    val name: String,
    val frame: Bitmap,
    val serial: Int,
    val control: Boolean,
  ) : NativeDesktopState

  data class Failed(
    val message: String,
  ) : NativeDesktopState
}

/** A machine the gateway can show: its own host, or an environment/node it manages. */
data class DesktopSource(
  val kind: String,
  val id: String?,
  val label: String,
)

/**
 * OkHttp client and request for a gateway-tunnelled WebSocket (desktop RFB, browser screencast).
 * [wsPath] is absolute within the gateway's base path; TLS follows the accepted pin.
 */
internal fun gatewayStreamRequest(
  page: NodeRuntime.GatewayControlPage,
  wsPath: String,
): Pair<OkHttpClient, Request> {
  val base = page.baseUrl.trimEnd('/')
  val tls = buildGatewayTlsConfig(GatewayTlsParams(required = base.startsWith("https"), expectedFingerprint = page.tlsFingerprintSha256, allowTOFU = false, stableId = "stream"))
  val http =
    OkHttpClient
      .Builder()
      .readTimeout(0, TimeUnit.SECONDS)
      .pingInterval(30, TimeUnit.SECONDS)
      .apply {
        if (tls != null) {
          sslSocketFactory(tls.sslSocketFactory, tls.trustManager)
          hostnameVerifier(tls.hostnameVerifier)
        }
      }.build()
  val origin = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/')
  val prefix = base.substringAfter("://").substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
  return http to Request.Builder().url(origin + prefix + wsPath).build()
}

/** WebSocket binary frames → blocking byte reads for [RfbClient]. */
private class WebSocketRfbTransport : RfbTransport {
  @Volatile lateinit var socket: WebSocket

  private val inbox = LinkedBlockingQueue<ByteArray>()
  private var current = ByteArray(0)
  private var offset = 0

  fun push(bytes: ByteArray) = inbox.put(bytes)

  fun close() = inbox.put(CLOSED)

  override fun read(count: Int): ByteArray {
    val out = ByteArray(count)
    var filled = 0
    while (filled < count) {
      if (offset >= current.size) {
        val next = inbox.poll(30, TimeUnit.SECONDS) ?: throw IOException("Desktop stream timed out.")
        if (next === CLOSED) {
          inbox.put(CLOSED)
          throw IOException("Desktop stream closed.")
        }
        current = next
        offset = 0
      }
      val n = minOf(count - filled, current.size - offset)
      System.arraycopy(current, offset, out, filled, n)
      offset += n
      filled += n
    }
    return out
  }

  override fun write(bytes: ByteArray) {
    if (!socket.send(ByteString.of(*bytes))) throw IOException("Desktop stream closed.")
  }

  private companion object {
    val CLOSED = ByteArray(0)
  }
}

/**
 * Native remote-desktop viewer: `desktop.observe` mints a one-shot token, then the gateway
 * tunnels raw RFB over a WebSocket that [RfbClient] speaks directly. Rendering is a Bitmap.
 */
class NativeDesktopSession(
  private val scope: CoroutineScope,
  private val call: suspend (method: String, paramsJson: String) -> String,
  private val control: () -> NodeRuntime.GatewayControlPage?,
  private val json: Json,
) {
  private val mutableState = MutableStateFlow<NativeDesktopState>(NativeDesktopState.Idle)
  val state: StateFlow<NativeDesktopState> = mutableState
  private var socket: WebSocket? = null
  private var client: RfbClient? = null
  private var bitmap: Bitmap? = null
  private var serial = 0
  private var observeControl = false

  suspend fun sources(): List<DesktopSource> {
    val list = mutableListOf(DesktopSource("host", null, "This gateway"))
    runCatching {
      val root = json.parseToJsonElement(call("environments.list", "{}")) as? JsonObject
      (root?.get("environments") as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
        val o = el as? JsonObject ?: return@forEach
        val id = (o["id"] as? JsonPrimitive)?.content ?: return@forEach
        list += DesktopSource("environment", id, (o["name"] as? JsonPrimitive)?.content ?: id)
      }
    }
    return list
  }

  fun connect(
    source: DesktopSource,
    wantControl: Boolean,
    password: String? = null,
  ) {
    disconnect()
    mutableState.value = NativeDesktopState.Connecting
    scope.launch(Dispatchers.IO) {
      try {
        val page = control() ?: error("Not connected to a gateway.")
        val params =
          buildJsonObject {
            put(
              "source",
              buildJsonObject {
                put("kind", JsonPrimitive(source.kind))
                source.id?.let { put(if (source.kind == "node") "nodeId" else "environmentId", JsonPrimitive(it)) }
              },
            )
            put("control", JsonPrimitive(wantControl))
          }
        val result = json.parseToJsonElement(call("desktop.observe", params.toString())) as JsonObject
        val wsPath = (result["wsPath"] as? JsonPrimitive)?.content ?: error("The gateway returned no desktop stream.")
        observeControl = (result["control"] as? JsonPrimitive)?.content == "true"
        runStream(page, wsPath, password)
      } catch (err: CancellationException) {
        throw err
      } catch (err: Throwable) {
        mutableState.value = NativeDesktopState.Failed(err.message ?: "Could not open the desktop.")
      }
    }
  }

  private fun runStream(
    page: NodeRuntime.GatewayControlPage,
    wsPath: String,
    password: String?,
  ) {
    val stream = WebSocketRfbTransport()
    val (http, request) = gatewayStreamRequest(page, wsPath)
    val ws =
      http.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
          ) {
            stream.push(bytes.toByteArray())
          }

          override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
          ) {
            stream.close()
          }

          override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
          ) {
            stream.close()
          }
        },
      )
    socket = ws
    stream.socket = ws
    val rfb =
      RfbClient(stream, password) { c ->
        val bmp = bitmap?.takeIf { it.width == c.width && it.height == c.height } ?: Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        bmp.setPixels(c.pixels, 0, c.width, 0, 0, c.width, c.height)
        mutableState.value = NativeDesktopState.Live(c.name, bmp, ++serial, observeControl)
      }
    client = rfb
    try {
      rfb.handshake()
      rfb.run()
    } catch (err: IOException) {
      if (mutableState.value !is NativeDesktopState.Idle) mutableState.value = NativeDesktopState.Failed(err.message ?: "Desktop disconnected.")
    } finally {
      stream.close()
      ws.cancel()
      http.dispatcher.executorService.shutdown()
    }
  }

  fun pointer(
    x: Int,
    y: Int,
    mask: Int,
  ) {
    val c = client ?: return
    if (observeControl) scope.launch(Dispatchers.IO) { runCatching { c.pointer(x, y, mask) } }
  }

  fun type(text: String) {
    val c = client ?: return
    if (observeControl) scope.launch(Dispatchers.IO) { runCatching { text.forEach { c.tap(RfbClient.keysymForChar(it)) } } }
  }

  fun key(keysym: Int) {
    val c = client ?: return
    if (observeControl) scope.launch(Dispatchers.IO) { runCatching { c.tap(keysym) } }
  }

  fun disconnect() {
    mutableState.value = NativeDesktopState.Idle
    socket?.cancel()
    socket = null
    client = null
    bitmap = null
  }
}
