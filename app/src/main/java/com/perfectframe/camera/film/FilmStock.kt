package com.perfectframe.camera.film

import androidx.compose.ui.graphics.Color
import com.perfectframe.camera.editor.FilmLook

/**
 * A film stock the user can "load" in Film mode. Deliberately original, generic naming
 * ("Sunset 200", "Mono 400") rather than real trademarked stock names — the look each maps to is
 * an honest colour-science approximation (see [FilmLook]), not a claim to reproduce a specific
 * commercial product.
 */
data class FilmStock(
    val id: String,
    val displayName: String,
    val look: FilmLook,
    val iso: Int,
    val tagline: String,
    val boxColor: Color,
    val accentColor: Color,
)

object FilmStocks {
    val ALL: List<FilmStock> = listOf(
        FilmStock("sunset200", "Sunset 200", FilmLook.GOLD, 200, "Warm consumer glow", Color(0xFFE8B23A), Color(0xFF6B4310)),
        FilmStock("soft400", "Soft 400", FilmLook.PORTRAIT, 400, "Skin-flattering pastel", Color(0xFFE7C7B0), Color(0xFF8B5E3C)),
        FilmStock("chrome100", "Chrome 100", FilmLook.CHROME, 100, "Punchy neutral slide", Color(0xFF3E6FA8), Color(0xFFEFEFEF)),
        FilmStock("field50", "Field 50", FilmLook.VIVID, 50, "Landscape-grade saturation", Color(0xFF2E7D46), Color(0xFFEFEFEF)),
        FilmStock("cine250", "CineStock 250", FilmLook.CINE, 250, "Cool shadows, warm highlights", Color(0xFF232830), Color(0xFFE0A526)),
        FilmStock("xpro400", "XPro 400", FilmLook.CROSS, 400, "Skewed greens & yellows", Color(0xFF7C8D2E), Color(0xFFEFEFEF)),
        FilmStock("heritage", "Heritage", FilmLook.SEPIA, 100, "Warm monochrome", Color(0xFF8B5E3C), Color(0xFFEFEFEF)),
        FilmStock("mono400", "Mono 400", FilmLook.TRIX, 400, "High-contrast black & white", Color(0xFF1B1B1B), Color(0xFFEFEFEF)),
        FilmStock("mono400s", "Mono 400 Soft", FilmLook.HP5, 400, "Soft classic black & white", Color(0xFF3A3A3A), Color(0xFFEFEFEF)),
        FilmStock("noir3200", "Noir 3200", FilmLook.NOIR, 3200, "Deep-contrast black & white", Color(0xFF0A0A0A), Color(0xFFEFEFEF)),
    )
}
