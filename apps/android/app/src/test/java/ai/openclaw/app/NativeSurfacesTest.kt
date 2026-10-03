package ai.openclaw.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NativeSurfacesTest {
  private fun text(buffer: AnsiTerminalBuffer) = buffer.snapshot().joinToString("\n") { l -> l.joinToString("") { it.text } }

  @Test
  fun ansiColorsCarriageReturnAndErase() {
    val t = AnsiTerminalBuffer()
    t.append("\u001b[31mred\u001b[0m ok\r\nprogress 10%\rprogress 99%\r\n")
    t.append("abc\u001b[2K\rx")
    assertEquals("red ok\nprogress 99%\nx\n".trimEnd('\n'), text(t).trimEnd('\n'))
    assertEquals(TermRun("red", 2, false), t.snapshot().first().first())
  }

  @Test
  fun splitEscapeAcrossChunksAndOsc() {
    val t = AnsiTerminalBuffer()
    t.append("a\u001b[3")
    t.append("2mgreen\u001b]0;title\u0007!")
    assertEquals("agreen!", text(t))
    assertEquals(3, t.snapshot().first()[1].fg)
  }

  @Test
  fun scrollbackIsBounded() {
    val t = AnsiTerminalBuffer(maxLines = 3)
    t.append("1\n2\n3\n4\n")
    assertEquals(3, t.snapshot().size)
  }

  @Test
  fun parsesBoardAndReportBlocksSkippingBadOnes() {
    val root =
      Json
        .parseToJsonElement(
          """{"sessionKey":"agent:main:main","revision":3,
            "tabs":[{"tabId":"today","title":"Today","position":0,"chatDock":"right"}],
            "widgets":[{"name":"brief","tabId":"today","contentKind":"plugin","pluginKind":"session:report",
              "sizeW":12,"sizeH":4,"position":0,"grantState":"none","revision":1,
              "props":{"blocks":[{"type":"metrics","items":[{"label":"PRs","value":"3"}]},
                {"type":"bogus"},{"type":"chart","points":[{"label":"Mon","value":2.5}]},
                {"type":"table","columns":["a"],"rows":[["1"]]}]}}]}""",
        ).jsonObject
    val board = parseBoard(root)
    assertNotNull(board)
    assertEquals("Today", board!!.tabs.single().title)
    val blocks = parseReportBlocks(board.widgetsFor("today").single().props)
    assertEquals(3, blocks.size)
    assertEquals(ReportBlock.Metrics(listOf(ReportMetric("PRs", "3", null))), blocks[0])
  }
}

class RfbClientTest {
  private class Script(
    server: ByteArray,
  ) : RfbTransport {
    private var pos = 0
    private val data = server
    val written = java.io.ByteArrayOutputStream()

    override fun read(count: Int): ByteArray {
      if (pos + count > data.size) throw java.io.IOException("eof")
      return data.copyOfRange(pos, pos + count).also { pos += count }
    }

    override fun write(bytes: ByteArray) = written.write(bytes)
  }

  private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

  private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

  @Test
  fun decodesRawAndCopyRectOverNoneAuth() {
    val init = "RFB 003.008\n".toByteArray() + byteArrayOf(1, 1) + be32(0) + be16(2) + be16(1) + ByteArray(16) + be32(2) + "pc".toByteArray()
    val update =
      byteArrayOf(0, 0) + be16(2) +
        be16(0) + be16(0) + be16(1) + be16(1) + be32(0) + byteArrayOf(0x30, 0x20, 0x10, 0) + // B,G,R,X
        be16(1) + be16(0) + be16(1) + be16(1) + be32(1) + be16(0) + be16(0) // copy (0,0) → (1,0)
    val io = Script(init + update)
    var frames = 0
    val client = RfbClient(io, null) { frames++ }
    client.handshake()
    assertEquals(2, client.width)
    assertEquals("pc", client.name)
    try {
      client.run()
    } catch (_: java.io.IOException) {
      // Script ends after one update.
    }
    assertEquals(1, frames)
    assertEquals(0xFF102030.toInt(), client.pixels[0])
    assertEquals(0xFF102030.toInt(), client.pixels[1])
    val sent = io.written.toByteArray()
    assertEquals("RFB 003.008", String(sent.copyOfRange(0, 11)))
  }

  @Test
  fun vncAuthIsDeterministicAndPasswordDependent() {
    val a = RfbClient.vncAuthResponse(ByteArray(16), "password")
    assertEquals(16, a.size)
    assertEquals(a.toList(), RfbClient.vncAuthResponse(ByteArray(16), "password").toList())
    assertNotEquals(a.toList(), RfbClient.vncAuthResponse(ByteArray(16), "passw0rd").toList())
  }
}

class ScreencastFrameTest {
  @Test
  fun splitsHeaderAndJpeg() {
    val header = """{"url":"https://x.test","cssWidth":100,"cssHeight":50}""".toByteArray()
    val len = header.size
    val wire = byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()) + header + byteArrayOf(-1, -40, -1, -39)
    val (parsed, offset, length) = parseScreencastFrame(wire)!!
    assertEquals("https://x.test", (parsed["url"] as kotlinx.serialization.json.JsonPrimitive).content)
    assertEquals(4 + len, offset)
    assertEquals(4, length)
    org.junit.Assert.assertNull(parseScreencastFrame(byteArrayOf(0, 0, 0, 9, 1)))
  }
}
