package ai.openclaw.app

import kotlinx.coroutines.launch

/** A run of text with one ANSI style. [fg] is 0 for default, else 1..16 (30-37 → 1..8, 90-97 → 9..16). */
data class TermRun(
  val text: String,
  val fg: Int,
  val bold: Boolean,
)

/**
 * Line-oriented ANSI renderer for the native terminal. It handles colors, carriage returns,
 * backspace, line erase and screen clear, which covers shells, build logs, git, and test output.
 * Full-screen programs (vim, htop) need a cell grid and are not supported; their cursor
 * addressing sequences are dropped instead of printed as garbage.
 */
class AnsiTerminalBuffer(
  private val maxLines: Int = 2000,
) {
  private class Line {
    val chars = StringBuilder()
    val styles = ArrayList<Int>()
  }

  private val lines = ArrayList<Line>().apply { add(Line()) }
  private var col = 0
  private var fg = 0
  private var bold = false
  private var pending = ""
  var revision = 0
    private set

  private fun style() = fg or (if (bold) 0x100 else 0)

  fun clear() {
    lines.clear()
    lines.add(Line())
    col = 0
    revision++
  }

  fun append(chunk: String) {
    val data = pending + chunk
    pending = ""
    var i = 0
    while (i < data.length) {
      val c = data[i]
      when {
        c == '\u001b' -> {
          val end = escapeEnd(data, i)
          if (end < 0) {
            pending = data.substring(i)
            break
          }
          applyEscape(data.substring(i, end))
          i = end
          continue
        }

        c == '\n' -> {
          lines.add(Line())
          col = 0
          if (lines.size > maxLines) lines.removeAt(0)
        }

        c == '\r' -> {
          col = 0
        }

        c == '\b' -> {
          if (col > 0) col--
        }

        c == '\t' -> {
          repeat(8 - col % 8) { put(' ') }
        }

        c == '\u0007' || c < ' ' -> {}

        else -> {
          put(c)
        }
      }
      i++
    }
    revision++
  }

  private fun put(ch: Char) {
    val line = lines.last()
    while (line.chars.length < col) {
      line.chars.append(' ')
      line.styles.add(0)
    }
    if (col < line.chars.length) {
      line.chars.setCharAt(col, ch)
      line.styles[col] = style()
    } else {
      line.chars.append(ch)
      line.styles.add(style())
    }
    col++
  }

  /** Index just past the escape sequence starting at [start], or -1 when it is incomplete. */
  private fun escapeEnd(
    s: String,
    start: Int,
  ): Int {
    if (start + 1 >= s.length) return -1
    return when (s[start + 1]) {
      '[' -> {
        var j = start + 2
        while (j < s.length && s[j] !in '@'..'~') j++
        if (j >= s.length) -1 else j + 1
      }

      ']' -> {
        var j = start + 2
        while (j < s.length) {
          if (s[j] == '\u0007') return j + 1
          if (s[j] == '\u001b' && j + 1 < s.length && s[j + 1] == '\\') return j + 2
          j++
        }
        -1
      }

      else -> {
        minOf(start + 2, s.length)
      }
    }
  }

  private fun applyEscape(seq: String) {
    if (!seq.startsWith("\u001b[")) return
    val final = seq.last()
    val args = seq.substring(2, seq.length - 1).split(';').map { it.filter(Char::isDigit).toIntOrNull() }
    when (final) {
      'm' -> {
        for (a in args.ifEmpty { listOf(0) }) {
          when (val n = a ?: 0) {
            0 -> {
              fg = 0
              bold = false
            }

            1 -> {
              bold = true
            }

            22 -> {
              bold = false
            }

            in 30..37 -> {
              fg = n - 29
            }

            in 90..97 -> {
              fg = n - 81
            }

            39 -> {
              fg = 0
            }
          }
        }
      }

      'K' -> {
        val line = lines.last()
        when (args.firstOrNull() ?: 0) {
          0 -> {
            if (col < line.chars.length) {
              line.chars.setLength(col)
              while (line.styles.size > col) line.styles.removeAt(line.styles.size - 1)
            }
          }

          else -> {
            line.chars.setLength(0)
            line.styles.clear()
          }
        }
      }

      'J' -> {
        if ((args.firstOrNull() ?: 0) >= 2) {
          lines.clear()
          lines.add(Line())
          col = 0
        }
      }

      'D' -> {
        col = maxOf(0, col - (args.firstOrNull() ?: 1))
      }

      'C' -> {
        col += args.firstOrNull() ?: 1
      }

      'G' -> {
        col = maxOf(0, (args.firstOrNull() ?: 1) - 1)
      }
    }
  }

  fun snapshot(): List<List<TermRun>> =
    lines.map { line ->
      val runs = ArrayList<TermRun>()
      var start = 0
      for (i in 1..line.chars.length) {
        if (i == line.chars.length || line.styles[i] != line.styles[start]) {
          val st = line.styles[start]
          runs.add(TermRun(line.chars.substring(start, i), st and 0xff, st and 0x100 != 0))
          start = i
        }
      }
      runs
    }
}

sealed interface NativeTerminalState {
  data object Idle : NativeTerminalState

  data object Opening : NativeTerminalState

  data class Open(
    val sessionId: String,
    val shell: String,
    val cwd: String,
    val confined: Boolean,
  ) : NativeTerminalState

  data class Ended(
    val reason: String,
  ) : NativeTerminalState
}

/**
 * One PTY-backed gateway shell (`terminal.*`, operator.admin) rendered natively. Output arrives as
 * `terminal.data` events; keystrokes go back through `terminal.input`.
 */
class NativeTerminalSession(
  private val scope: kotlinx.coroutines.CoroutineScope,
  private val call: suspend (method: String, paramsJson: String) -> String,
  private val json: kotlinx.serialization.json.Json,
) {
  private val lock = Any()
  private val buffer = AnsiTerminalBuffer()
  private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<NativeTerminalState>(NativeTerminalState.Idle)
  val state: kotlinx.coroutines.flow.StateFlow<NativeTerminalState> = mutableState
  private val mutableRevision = kotlinx.coroutines.flow.MutableStateFlow(0)
  val revision: kotlinx.coroutines.flow.StateFlow<Int> = mutableRevision

  fun lines(): List<List<TermRun>> = synchronized(lock) { buffer.snapshot() }

  fun open(
    cols: Int,
    rows: Int,
    sessionKey: String? = null,
  ) {
    if (mutableState.value is NativeTerminalState.Opening || mutableState.value is NativeTerminalState.Open) return
    mutableState.value = NativeTerminalState.Opening
    synchronized(lock) { buffer.clear() }
    scope.launch {
      try {
        val params =
          kotlinx.serialization.json.buildJsonObject {
            put("cols", kotlinx.serialization.json.JsonPrimitive(cols.coerceIn(20, 500)))
            put("rows", kotlinx.serialization.json.JsonPrimitive(rows.coerceIn(5, 200)))
            sessionKey?.let { put("sessionKey", kotlinx.serialization.json.JsonPrimitive(it)) }
          }
        val root = json.parseToJsonElement(call("terminal.open", params.toString())) as kotlinx.serialization.json.JsonObject
        fun str(k: String) = (root[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        mutableState.value =
          NativeTerminalState.Open(
            sessionId = str("sessionId").ifEmpty { error("terminal.open returned no session") },
            shell = str("shell"),
            cwd = str("cwd"),
            confined = (root["confined"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
          )
      } catch (err: kotlinx.coroutines.CancellationException) {
        throw err
      } catch (err: Throwable) {
        mutableState.value = NativeTerminalState.Ended(err.message ?: "Could not open a terminal.")
      }
    }
  }

  /** Sends raw keystrokes (text, `\u0003` for Ctrl-C, escape sequences for arrows). */
  fun send(data: String) {
    val open = mutableState.value as? NativeTerminalState.Open ?: return
    scope.launch {
      runCatching {
        call(
          "terminal.input",
          kotlinx.serialization.json.buildJsonObject {
            put("sessionId", kotlinx.serialization.json.JsonPrimitive(open.sessionId))
            put("data", kotlinx.serialization.json.JsonPrimitive(data))
          }.toString(),
        )
      }
    }
  }

  fun resize(
    cols: Int,
    rows: Int,
  ) {
    val open = mutableState.value as? NativeTerminalState.Open ?: return
    scope.launch {
      runCatching {
        call(
          "terminal.resize",
          kotlinx.serialization.json.buildJsonObject {
            put("sessionId", kotlinx.serialization.json.JsonPrimitive(open.sessionId))
            put("cols", kotlinx.serialization.json.JsonPrimitive(cols.coerceIn(20, 500)))
            put("rows", kotlinx.serialization.json.JsonPrimitive(rows.coerceIn(5, 200)))
          }.toString(),
        )
      }
    }
  }

  fun close() {
    val open = mutableState.value as? NativeTerminalState.Open
    mutableState.value = NativeTerminalState.Idle
    if (open != null) {
      scope.launch {
        runCatching {
          call(
            "terminal.close",
            kotlinx.serialization.json.buildJsonObject {
              put("sessionId", kotlinx.serialization.json.JsonPrimitive(open.sessionId))
            }.toString(),
          )
        }
      }
    }
  }

  fun onGatewayEvent(
    event: String,
    payloadJson: String?,
  ) {
    if (event != "terminal.data" && event != "terminal.exit") return
    val open = mutableState.value as? NativeTerminalState.Open ?: return
    val root = runCatching { json.parseToJsonElement(payloadJson ?: return) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return
    if ((root["sessionId"] as? kotlinx.serialization.json.JsonPrimitive)?.content != open.sessionId) return
    if (event == "terminal.data") {
      val data = (root["data"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
      synchronized(lock) { buffer.append(data) }
      mutableRevision.value += 1
    } else {
      val reason = (root["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content
      mutableState.value = NativeTerminalState.Ended(if (reason == null || reason == "process_exit") "Shell exited" else "Session $reason")
    }
  }
}
