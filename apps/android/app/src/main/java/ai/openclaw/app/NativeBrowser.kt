package ai.openclaw.app

import ai.openclaw.app.chat.ChatBrowserTab
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

sealed interface NativeBrowserState {
  data object Idle : NativeBrowserState

  data object Connecting : NativeBrowserState

  data class Live(
    val frame: Bitmap,
    val serial: Int,
    val url: String,
    val cssWidth: Int,
    val cssHeight: Int,
  ) : NativeBrowserState

  data class Failed(
    val message: String,
  ) : NativeBrowserState
}

/** Splits a screencast wire frame: `[u32 header length][JSON header][JPEG]`. Null when malformed. */
internal fun parseScreencastFrame(bytes: ByteArray): Triple<JsonObject, Int, Int>? {
  if (bytes.size < 4) return null
  val headerLength = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
  if (headerLength < 2 || 4 + headerLength >= bytes.size) return null
  val header = runCatching { Json.parseToJsonElement(String(bytes, 4, headerLength, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
  return Triple(header, 4 + headerLength, bytes.size - 4 - headerLength)
}

/**
 * Native view of the agent's browser tab: the gateway's CDP screencast (JPEG frames over a token
 * WebSocket) drawn to a Bitmap, with clicks, keys and text sent as `browser.request` actions.
 */
class NativeBrowserSession(
  private val scope: CoroutineScope,
  private val call: suspend (method: String, paramsJson: String) -> String,
  private val control: () -> NodeRuntime.GatewayControlPage?,
  private val json: Json,
) {
  private val mutableState = MutableStateFlow<NativeBrowserState>(NativeBrowserState.Idle)
  val state: StateFlow<NativeBrowserState> = mutableState
  private var socket: WebSocket? = null
  private var tab: ChatBrowserTab? = null
  private var sessionKey: String = ""
  private var serial = 0

  private fun envelope(
    tab: ChatBrowserTab,
    method: String,
    path: String,
    body: JsonObject,
  ): String =
    buildJsonObject {
      put("method", JsonPrimitive(method))
      put("path", JsonPrimitive(path))
      put("body", body)
      put("target", JsonPrimitive(tab.target))
      tab.node?.let { put("node", JsonPrimitive(it)) }
      put("query", buildJsonObject { put("profile", JsonPrimitive(tab.profile)) })
      put("tabScope", buildJsonObject { put("sessionKey", JsonPrimitive(sessionKey)) })
    }.toString()

  fun start(
    tab: ChatBrowserTab,
    sessionKey: String,
  ) {
    stop()
    this.tab = tab
    this.sessionKey = sessionKey
    mutableState.value = NativeBrowserState.Connecting
    scope.launch(Dispatchers.IO) {
      try {
        val page = control() ?: error("Not connected to a gateway.")
        val body =
          buildJsonObject {
            put("targetId", JsonPrimitive(tab.targetId))
            put("maxWidth", JsonPrimitive(1280))
            put("maxHeight", JsonPrimitive(1280))
          }
        val result = json.parseToJsonElement(call("browser.request", envelope(tab, "POST", "/screencast", body))) as JsonObject
        val wsPath = (result["wsPath"] as? JsonPrimitive)?.content ?: error("Browser view is unavailable for this profile.")
        val (http, request) = gatewayStreamRequest(page, wsPath)
        socket =
          http.newWebSocket(
            request,
            object : WebSocketListener() {
              override fun onMessage(
                webSocket: WebSocket,
                bytes: ByteString,
              ) {
                val raw = bytes.toByteArray()
                val (header, offset, length) = parseScreencastFrame(raw) ?: return
                val bitmap = BitmapFactory.decodeByteArray(raw, offset, length) ?: return
                fun num(k: String) = (header[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
                mutableState.value =
                  NativeBrowserState.Live(
                    frame = bitmap,
                    serial = ++serial,
                    url = (header["url"] as? JsonPrimitive)?.content.orEmpty(),
                    cssWidth = num("cssWidth").takeIf { it > 0 } ?: bitmap.width,
                    cssHeight = num("cssHeight").takeIf { it > 0 } ?: bitmap.height,
                  )
              }

              override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
              ) {
                if (socket === webSocket) mutableState.value = NativeBrowserState.Failed(t.message ?: "Browser view disconnected.")
              }

              override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
              ) {
                if (socket === webSocket && mutableState.value is NativeBrowserState.Live) {
                  mutableState.value = NativeBrowserState.Failed(reason.ifEmpty { "Browser view closed." })
                }
              }
            },
          )
      } catch (err: CancellationException) {
        throw err
      } catch (err: Throwable) {
        mutableState.value = NativeBrowserState.Failed(err.message ?: "Could not open the browser view.")
      }
    }
  }

  private fun act(body: JsonObject) {
    val t = tab ?: return
    scope.launch(Dispatchers.IO) { runCatching { call("browser.request", envelope(t, "POST", "/act", body)) } }
  }

  /** Coordinates are remote CSS pixels. */
  fun click(
    x: Int,
    y: Int,
  ) {
    val t = tab ?: return
    act(
      buildJsonObject {
        put("kind", JsonPrimitive("clickCoords"))
        put("targetId", JsonPrimitive(t.targetId))
        put("x", JsonPrimitive(x.coerceAtLeast(0)))
        put("y", JsonPrimitive(y.coerceAtLeast(0)))
      },
    )
  }

  fun press(key: String) {
    val t = tab ?: return
    act(
      buildJsonObject {
        put("kind", JsonPrimitive("press"))
        put("targetId", JsonPrimitive(t.targetId))
        put("key", JsonPrimitive(key))
      },
    )
  }

  fun type(text: String) {
    val t = tab ?: return
    act(
      buildJsonObject {
        put("kind", JsonPrimitive("insertText"))
        put("targetId", JsonPrimitive(t.targetId))
        put("text", JsonPrimitive(text))
      },
    )
  }

  fun stop() {
    mutableState.value = NativeBrowserState.Idle
    socket?.cancel()
    socket = null
    tab = null
  }
}
