package ai.openclaw.app.ondevice

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Minimal tar.gz extractor (ustar, GNU long names, pax paths) with path-traversal protection.
 * Only regular files, directories and symlinks are materialized; the executable bit is kept.
 */
object TarGz {
  fun extract(
    gzipped: InputStream,
    dest: File,
    onFile: (String) -> Unit = {},
  ) {
    val root = dest.canonicalFile
    root.mkdirs()
    val input = DataInputStream(GZIPInputStream(gzipped.buffered(1 shl 16), 1 shl 16))
    val header = ByteArray(512)
    var longName: String? = null
    var paxPath: String? = null
    while (true) {
      try {
        input.readFully(header)
      } catch (_: EOFException) {
        return
      }
      if (header.all { it == 0.toByte() }) return
      val name0 = cString(header, 0, 100)
      val size = octal(header, 124, 12)
      val type = header[156].toInt().toChar()
      val link = cString(header, 157, 100)
      val prefix = cString(header, 345, 155)
      val mode = octal(header, 100, 8).toInt()
      val padded = (size + 511) / 512 * 512
      when (type) {
        'L' -> {
          longName = String(readExact(input, size.toInt()), Charsets.UTF_8).trimEnd('\u0000')
          skip(input, padded - size)
          continue
        }

        'x' -> {
          val text = String(readExact(input, size.toInt()), Charsets.UTF_8)
          skip(input, padded - size)
          paxPath = Regex("""\d+ path=(.*)\n""").find(text)?.groupValues?.get(1)
          continue
        }

        'g' -> {
          skip(input, padded)
          continue
        }
      }
      val name = paxPath ?: longName ?: if (prefix.isEmpty()) name0 else "$prefix/$name0"
      longName = null
      paxPath = null
      val target = File(root, name.trimStart('/')).canonicalFile
      if (!target.path.startsWith(root.path + File.separator) && target != root) throw IOException("Unsafe path in archive: $name")
      when (type) {
        '5' -> {
          target.mkdirs()
          skip(input, padded)
        }

        '2' -> {
          target.parentFile?.mkdirs()
          Files.deleteIfExists(target.toPath())
          Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(link))
          skip(input, padded)
        }

        '0', '\u0000' -> {
          target.parentFile?.mkdirs()
          target.outputStream().use { out ->
            var left = size
            val buf = ByteArray(1 shl 16)
            while (left > 0) {
              val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
              if (n < 0) throw EOFException("Truncated archive")
              out.write(buf, 0, n)
              left -= n
            }
          }
          skip(input, padded - size)
          if (mode and 0b001_001_001 != 0) target.setExecutable(true, false)
          onFile(name)
        }

        else -> {
          skip(input, padded)
        }
      }
    }
  }

  private fun cString(
    b: ByteArray,
    off: Int,
    len: Int,
  ): String {
    var end = off
    while (end < off + len && b[end] != 0.toByte()) end++
    return String(b, off, end - off, Charsets.UTF_8)
  }

  private fun octal(
    b: ByteArray,
    off: Int,
    len: Int,
  ): Long {
    val text = cString(b, off, len).trim()
    return if (text.isEmpty()) 0 else text.toLong(8)
  }

  private fun readExact(
    input: DataInputStream,
    n: Int,
  ): ByteArray = ByteArray(n).also(input::readFully)

  private fun skip(
    input: DataInputStream,
    n: Long,
  ) {
    var left = n
    while (left > 0) {
      val skipped = input.skip(left)
      if (skipped <= 0) {
        input.readByte()
        left--
      } else {
        left -= skipped
      }
    }
  }
}
