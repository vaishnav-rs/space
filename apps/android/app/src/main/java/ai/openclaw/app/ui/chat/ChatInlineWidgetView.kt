package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatWidgetPreview
import ai.openclaw.app.chat.ChatWidgetResource
import ai.openclaw.app.chat.ChatWidgetSurfaceRole
import ai.openclaw.app.gateway.GatewayTlsParams
import ai.openclaw.app.gateway.buildGatewayTlsConfig
import ai.openclaw.app.gateway.normalizeGatewayTlsFingerprint
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.util.concurrent.TimeUnit

private const val INLINE_WIDGET_DOCUMENT_MAX_BYTES = 10L * 1024 * 1024
private const val INLINE_WIDGET_FETCH_TIMEOUT_SECONDS = 8L

private sealed interface WidgetLoad {
  data object Loading : WidgetLoad

  data object Unavailable : WidgetLoad

  data class Ready(
    val blocks: List<HtmlBlock>,
  ) : WidgetLoad
}

/**
 * Agent HTML widgets are fetched from the gateway and shown as native text, lists, code and
 * tables. Scripts and styles are never executed; interactive widgets show their static content.
 */
@Composable
internal fun ChatInlineWidget(
  preview: ChatWidgetPreview,
  resolverReady: Boolean,
  resolveResource: suspend (String, ChatWidgetResource?) -> ChatWidgetResource?,
) {
  var load by remember(preview.path) { mutableStateOf<WidgetLoad>(WidgetLoad.Loading) }
  LaunchedEffect(preview.path, resolverReady) {
    if (!resolverReady) return@LaunchedEffect
    var resource = resolveResource(preview.path, null)
    var attempts = 0
    while (resource != null) {
      val html = fetchWidgetHtml(resource)
      if (html != null) {
        load = WidgetLoad.Ready(htmlToBlocks(html))
        return@LaunchedEffect
      }
      // The resolver rotates through gateway surface roles when one is unreachable.
      if (++attempts >= ChatWidgetSurfaceRole.entries.size) break
      resource = resolveResource(preview.path, resource)
    }
    load = WidgetLoad.Unavailable
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    preview.title?.trim()?.takeIf(String::isNotEmpty)?.let { title ->
      Text(title, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted, modifier = Modifier.padding(bottom = 6.dp))
    }
    when (val state = load) {
      WidgetLoad.Loading -> {
        CircularProgressIndicator(color = ClawTheme.colors.textMuted)
      }

      WidgetLoad.Unavailable -> {
        Text(nativeString("Widget unavailable"), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      }

      is WidgetLoad.Ready -> {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(10.dp),
          border = BorderStroke(1.dp, ClawTheme.colors.border),
          color = ClawTheme.colors.surface,
        ) {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.blocks.take(200).forEach { HtmlBlockView(it) }
          }
        }
      }
    }
  }
}

@Composable
private fun HtmlBlockView(block: HtmlBlock) {
  val colors = ClawTheme.colors
  when (block) {
    is HtmlBlock.Heading -> Text(block.text, style = if (block.level <= 2) ClawTheme.type.section else ClawTheme.type.label, color = colors.text, fontWeight = FontWeight.SemiBold)
    is HtmlBlock.Paragraph -> Text(block.text, style = ClawTheme.type.body, color = colors.text)
    is HtmlBlock.Item -> Text("•  ${block.text}", style = ClawTheme.type.body, color = colors.text)
    is HtmlBlock.Code -> Text(block.text, style = ClawTheme.type.mono, color = colors.codeText, modifier = Modifier.horizontalScroll(rememberScrollState()))
    is HtmlBlock.Table -> {
      Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.rows.forEachIndexed { i, row ->
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { Text(it, Modifier.width(110.dp), style = ClawTheme.type.body, color = if (i == 0) colors.textMuted else colors.text) }
          }
        }
      }
    }
  }
}

private suspend fun fetchWidgetHtml(resource: ChatWidgetResource): String? =
  withContext(Dispatchers.IO) {
    val client = buildWidgetClient(resource.tlsFingerprintSha256) ?: return@withContext null
    if (resource.tlsFingerprintSha256 != null && !resource.url.startsWith("https://")) return@withContext null
    try {
      val request = Request.Builder().url(resource.url).header("Accept", "text/html").header("Cache-Control", "no-cache").get().build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use null
        val body = response.body
        val type = body.contentType() ?: return@use null
        if ("${type.type}/${type.subtype}".lowercase() != "text/html") return@use null
        if (body.contentLength() > INLINE_WIDGET_DOCUMENT_MAX_BYTES) return@use null
        readBoundedWidgetDocument(body.source(), INLINE_WIDGET_DOCUMENT_MAX_BYTES.toInt())?.toString(type.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
      }
    } catch (_: Exception) {
      null
    } finally {
      client.dispatcher.cancelAll()
      client.connectionPool.evictAll()
    }
  }

private fun buildWidgetClient(rawFingerprint: String?): OkHttpClient? {
  val builder = OkHttpClient.Builder()
  if (rawFingerprint != null) {
    val fingerprint = normalizeGatewayTlsFingerprint(rawFingerprint)
    if (fingerprint.length != 64) return null
    val tls =
      buildGatewayTlsConfig(
        GatewayTlsParams(required = true, expectedFingerprint = fingerprint, allowTOFU = false, stableId = "inline-widget"),
      ) ?: return null
    builder.sslSocketFactory(tls.sslSocketFactory, tls.trustManager).hostnameVerifier(tls.hostnameVerifier)
  }
  return builder
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .cache(null)
    .callTimeout(INLINE_WIDGET_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()
}

internal fun readBoundedWidgetDocument(
  source: BufferedSource,
  maxBytes: Int,
): ByteArray? {
  require(maxBytes in 0 until Int.MAX_VALUE)
  val buffer = ByteArray(maxBytes + 1)
  var offset = 0
  while (offset < buffer.size) {
    val read = source.read(buffer, offset, buffer.size - offset)
    if (read == -1) break
    if (read == 0) return null
    offset += read
  }
  if (offset > maxBytes) return null
  return buffer.copyOf(offset)
}
