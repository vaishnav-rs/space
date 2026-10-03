package ai.openclaw.app

import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Blocking byte transport under an RFB session (the gateway's `/desktop/observe` WebSocket). */
interface RfbTransport {
  /** Returns exactly [count] bytes; throws [IOException] when the stream ends first. */
  fun read(count: Int): ByteArray

  fun write(bytes: ByteArray)
}

/**
 * Native RFB 3.3/3.7/3.8 client: None and VNC authentication, Raw + CopyRect + DesktopSize
 * encodings, pointer and key input. It decodes into [pixels] (0xAARRGGBB) and calls [onUpdate]
 * after each framebuffer update. Run [run] on a worker thread.
 */
class RfbClient(
  private val io: RfbTransport,
  private val password: String? = null,
  private val onUpdate: (RfbClient) -> Unit,
) {
  @Volatile var width = 0
    private set

  @Volatile var height = 0
    private set

  @Volatile var pixels = IntArray(0)
    private set

  @Volatile var name = ""
    private set

  private fun u8() = io.read(1)[0].toInt() and 0xff

  private fun u16(): Int {
    val b = io.read(2)
    return ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
  }

  private fun s32(): Int {
    val b = io.read(4)
    return ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
  }

  private fun failureReason(): String = String(io.read(s32()), Charsets.UTF_8)

  fun handshake() {
    val version = String(io.read(12), Charsets.US_ASCII)
    if (!version.startsWith("RFB ")) throw IOException("Not an RFB server")
    val minor = version.substring(8, 11).toIntOrNull() ?: 3
    val use = if (minor >= 8) 8 else if (minor >= 7) 7 else 3
    io.write("RFB 003.00$use\n".toByteArray(Charsets.US_ASCII))
    val securityType: Int
    if (use == 3) {
      securityType = s32()
      if (securityType == 0) throw IOException(failureReason())
    } else {
      val count = u8()
      if (count == 0) throw IOException(failureReason())
      val offered = io.read(count).map { it.toInt() and 0xff }
      securityType =
        when {
          1 in offered -> 1
          2 in offered && password != null -> 2
          2 in offered -> throw IOException("This desktop needs a password.")
          else -> throw IOException("Unsupported desktop security types: $offered")
        }
      io.write(byteArrayOf(securityType.toByte()))
    }
    if (securityType == 2) io.write(vncAuthResponse(io.read(16), password.orEmpty()))
    // 3.3 has no result for None; everything else answers with a 32-bit status.
    if (!(use == 3 && securityType == 1)) {
      if (s32() != 0) throw IOException(if (use >= 8) failureReason() else "Authentication failed")
    }
    io.write(byteArrayOf(1)) // ClientInit: shared
    width = u16()
    height = u16()
    io.read(16) // server pixel format, replaced below
    name = String(io.read(s32()), Charsets.UTF_8)
    pixels = IntArray(width * height)
    // 32bpp little-endian true color: bytes B,G,R,X → 0x00RRGGBB.
    io.write(
      byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0),
    )
    io.write(byteArrayOf(2, 0, 0, 3) + int32(0) + int32(1) + int32(-223))
    requestUpdate(incremental = false)
  }

  fun run() {
    while (true) {
      when (val type = u8()) {
        0 -> {
          framebufferUpdate()
          onUpdate(this)
          requestUpdate(incremental = true)
        }

        1 -> {
          io.read(1)
          u16()
          io.read(u16() * 6)
        }

        2 -> {}

        3 -> {
          io.read(3)
          io.read(s32())
        }

        else -> {
          throw IOException("Unsupported RFB message $type")
        }
      }
    }
  }

  private fun framebufferUpdate() {
    io.read(1)
    repeat(u16()) {
      val x = u16()
      val y = u16()
      val w = u16()
      val h = u16()
      when (val enc = s32()) {
        0 -> {
          val data = io.read(w * h * 4)
          for (row in 0 until h) {
            for (col in 0 until w) {
              val i = (row * w + col) * 4
              val px = 0xFF000000.toInt() or ((data[i + 2].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8) or (data[i].toInt() and 0xff)
              put(x + col, y + row, px)
            }
          }
        }

        1 -> {
          val sx = u16()
          val sy = u16()
          val copy = IntArray(w * h)
          for (row in 0 until h) for (col in 0 until w) copy[row * w + col] = get(sx + col, sy + row)
          for (row in 0 until h) for (col in 0 until w) put(x + col, y + row, copy[row * w + col])
        }

        -223 -> {
          width = w
          height = h
          pixels = IntArray(w * h)
        }

        else -> {
          throw IOException("Unsupported RFB encoding $enc")
        }
      }
    }
  }

  private fun put(
    x: Int,
    y: Int,
    px: Int,
  ) {
    if (x in 0 until width && y in 0 until height) pixels[y * width + x] = px
  }

  private fun get(
    x: Int,
    y: Int,
  ): Int = if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0

  private fun requestUpdate(incremental: Boolean) {
    io.write(byteArrayOf(3, if (incremental) 1 else 0) + int16(0) + int16(0) + int16(width) + int16(height))
  }

  fun pointer(
    x: Int,
    y: Int,
    buttonMask: Int,
  ) = io.write(byteArrayOf(5, buttonMask.toByte()) + int16(x.coerceIn(0, width - 1)) + int16(y.coerceIn(0, height - 1)))

  fun key(
    keysym: Int,
    down: Boolean,
  ) = io.write(byteArrayOf(4, if (down) 1 else 0, 0, 0) + int32(keysym))

  /** Press and release a key. */
  fun tap(keysym: Int) {
    key(keysym, true)
    key(keysym, false)
  }

  companion object {
    private fun int16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** VNC authentication: DES-encrypt the challenge with the password's bit-reversed first 8 bytes. */
    internal fun vncAuthResponse(
      challenge: ByteArray,
      password: String,
    ): ByteArray {
      val key = ByteArray(8)
      password.toByteArray(Charsets.ISO_8859_1).take(8).forEachIndexed { i, b -> key[i] = Integer.reverse(b.toInt() and 0xff).ushr(24).toByte() }
      val cipher = Cipher.getInstance("DES/ECB/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
      return cipher.doFinal(challenge)
    }

    /** X11 keysym for a typed character (Latin-1 maps directly; others use the Unicode range). */
    fun keysymForChar(ch: Char): Int =
      when (ch) {
        '\n' -> 0xff0d
        '\t' -> 0xff09
        else -> if (ch.code in 0x20..0xff) ch.code else 0x01000000 + ch.code
      }
  }
}
