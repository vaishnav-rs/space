package ai.openclaw.app.ui.chat

private val latexSymbols =
  mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
    "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
    "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝", "infty" to "∞", "partial" to "∂", "nabla" to "∇", "sum" to "∑", "prod" to "∏",
    "int" to "∫", "oint" to "∮", "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "cup" to "∪", "cap" to "∩",
    "emptyset" to "∅", "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨", "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
    "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "mapsto" to "↦", "ldots" to "…", "cdots" to "⋯", "dots" to "…",
    "angle" to "∠", "degree" to "°", "circ" to "∘", "ell" to "ℓ", "hbar" to "ℏ", "Re" to "ℜ", "Im" to "ℑ", "sqrt" to "√",
    "log" to "log", "ln" to "ln", "sin" to "sin", "cos" to "cos", "tan" to "tan", "exp" to "exp", "lim" to "lim", "max" to "max", "min" to "min",
  )

private val latexFunctions = setOf("log", "ln", "sin", "cos", "tan", "exp", "lim", "max", "min")

private const val SUP_CHARS = "0123456789+-=()abcdefghijklmnoprstuvwxyzABDEGHIJKLMNOPRTUVW"
private const val SUP_MAP = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂ"
private const val SUB_CHARS = "0123456789+-=()aehijklmnoprstuvx"
private const val SUB_MAP = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓ"

private fun script(
  text: String,
  chars: String,
  map: String,
  fallbackPrefix: String,
): String {
  val mapped = text.map { c -> chars.indexOf(c).takeIf { it >= 0 }?.let { map[it] } }
  return if (mapped.all { it != null }) mapped.joinToString("") else "$fallbackPrefix(${text})"
}

/** Reads one LaTeX argument: a `{group}` or a single token. Returns content and the next index. */
private fun readArg(
  s: String,
  start: Int,
): Pair<String, Int> {
  var i = start
  while (i < s.length && s[i] == ' ') i++
  if (i >= s.length) return "" to i
  if (s[i] != '{') {
    if (s[i] == '\\') {
      var j = i + 1
      while (j < s.length && s[j].isLetter()) j++
      return s.substring(i, maxOf(j, i + 2).coerceAtMost(s.length)) to maxOf(j, i + 2).coerceAtMost(s.length)
    }
    return s[i].toString() to i + 1
  }
  var depth = 0
  var j = i
  while (j < s.length) {
    if (s[j] == '{') depth++
    if (s[j] == '}' && --depth == 0) return s.substring(i + 1, j) to j + 1
    j++
  }
  return s.substring(i + 1) to s.length
}

/**
 * Renders common LaTeX math as readable Unicode text (fractions, roots, super/subscripts, Greek,
 * operators, matrices as rows). It is deliberately a text rendering: no typesetting engine.
 */
fun latexToUnicode(latex: String): String {
  val s = latex.trim().removePrefix("$$").removeSuffix("$$").trim()
  val out = StringBuilder()
  var i = 0
  while (i < s.length) {
    val c = s[i]
    when {
      c == '\\' -> {
        var j = i + 1
        while (j < s.length && s[j].isLetter()) j++
        val name = s.substring(i + 1, j)
        if (name.isEmpty()) {
          // Escaped symbol: \\ row break, \, \; \! \  spaces, \{ \} \% etc.
          val next = s.getOrNull(i + 1)
          out.append(
            when (next) {
              '\\' -> "\n"
              ',', ';', ':', ' ' -> " "
              '!' -> ""
              null -> ""
              else -> next.toString()
            },
          )
          i += 2
          continue
        }
        i = j
        when (name) {
          "frac", "dfrac", "tfrac" -> {
            val (a, n1) = readArg(s, i)
            val (b, n2) = readArg(s, n1)
            val num = latexToUnicode(a)
            val den = latexToUnicode(b)
            fun wrap(t: String) = if (t.length <= 1 || t.all { it.isLetterOrDigit() }) t else "($t)"
            out.append(wrap(num)).append('/').append(wrap(den))
            i = n2
          }

          "sqrt" -> {
            var index: String? = null
            if (s.getOrNull(i) == '[') {
              val close = s.indexOf(']', i)
              if (close > 0) {
                index = s.substring(i + 1, close)
                i = close + 1
              }
            }
            val (a, n) = readArg(s, i)
            val inner = latexToUnicode(a)
            out.append(if (index == null) "√" else script(index, SUP_CHARS, SUP_MAP, "^") + "√").append(if (inner.length <= 1) inner else "($inner)")
            i = n
          }

          "text", "mathrm", "mathbf", "mathit", "mathbb", "mathcal", "operatorname", "textbf", "textit", "boldsymbol", "vec", "hat", "bar", "overline", "tilde" -> {
            val (a, n) = readArg(s, i)
            out.append(latexToUnicode(a))
            i = n
          }

          "left", "right", "big", "Big", "bigg", "Bigg", "displaystyle", "textstyle", "limits", "nolimits" -> {
            if ((name == "left" || name == "right") && s.getOrNull(i) == '.') i++
          }

          "quad", "qquad" -> {
            out.append("  ")
          }

          "begin", "end" -> {
            val (_, n) = readArg(s, i)
            i = n
          }

          else -> {
            out.append(latexSymbols[name] ?: name)
            // Named functions read better with a trailing space (sin x).
            if (name in latexFunctions) out.append(' ')
          }
        }
      }

      c == '^' || c == '_' -> {
        val (a, n) = readArg(s, i + 1)
        val inner = latexToUnicode(a)
        out.append(if (c == '^') script(inner, SUP_CHARS, SUP_MAP, "^") else script(inner, SUB_CHARS, SUB_MAP, "_"))
        i = n
      }

      c == '&' -> {
        out.append("  ")
        i++
      }

      c == '{' || c == '}' -> {
        i++
      }

      c == '~' -> {
        out.append(' ')
        i++
      }

      else -> {
        out.append(c)
        i++
      }
    }
  }
  return out.toString().replace(Regex("[ \\t]+\\n"), "\n").trim()
}
