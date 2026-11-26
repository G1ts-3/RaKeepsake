package id.rakeepsake.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import id.rakeepsake.R

/** Token warna dari DESIGN.md & Audit UI/UX (tema gelap hangat, aksen kuning emas "Ra"). */
@Immutable
data class RaColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val surfaceHigh: Color,
    val navBg: Color,
    val outline: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val sun: Color,
    val sunDim: Color,
    val sunSoft: Color,
    val onSun: Color,
    val danger: Color,
    val live: Color,
    val liveSoft: Color,
    val storyFg: Color,
    val storyBg: Color,
    val postFg: Color,
    val postBg: Color,
    val photoFg: Color,
    val photoBg: Color,
    val audioFg: Color,
    val audioBg: Color,
    val ribbon: Color,
)

val DarkRa = RaColors(
    bg = Color(0xFF18120F),
    surface = Color(0xFF211A17),
    surfaceAlt = Color(0xFF281F1B),
    surfaceHigh = Color(0xFF302825),
    navBg = Color(0xFF140F0D),
    outline = Color(0xFF3B3330),
    text = Color(0xFFEDE0DA),
    textSecondary = Color(0xFFD3C5B0),
    textMuted = Color(0xFFB3A792),
    sun = Color(0xFFFFC85A),
    sunDim = Color(0xFF3A2D1A),
    sunSoft = Color(0xFF514028),
    onSun = Color(0xFF3A2600),
    danger = Color(0xFFE8A39C),
    live = Color(0xFFFF5A4F),
    liveSoft = Color(0xFF3A1B18),
    storyFg = Color(0xFFB8A6FF),
    storyBg = Color(0xFF2A2340),
    postFg = Color(0xFF6FD6C2),
    postBg = Color(0xFF1B3330),
    photoFg = Color(0xFFFFB36B),
    photoBg = Color(0xFF3A2A18),
    audioFg = Color(0xFF8FC7FF),
    audioBg = Color(0xFF1B2B3A),
    ribbon = Color(0xFFD8433F),
)

val LightRa = RaColors(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF1EFEA),
    surfaceAlt = Color(0xFFF6F4F0),
    surfaceHigh = Color(0xFFE3DFD8),
    navBg = Color(0xFF2A1E19),
    outline = Color(0xFFDCD6CC),
    text = Color(0xFF2A1E19),
    textSecondary = Color(0xFF5E5650),
    textMuted = Color(0xFF6F665F),
    sun = Color(0xFFFDB835),
    sunDim = Color(0xFFFCEDCB),
    sunSoft = Color(0xFFF8E2B0),
    onSun = Color(0xFF2A1E19),
    danger = Color(0xFFB8402F),
    live = Color(0xFFD9382E),
    liveSoft = Color(0xFFFDE8E6),
    storyFg = Color(0xFF5B45B8),
    storyBg = Color(0xFFEEEAFC),
    postFg = Color(0xFF0E7A68),
    postBg = Color(0xFFE4F7F3),
    photoFg = Color(0xFFB85D00),
    photoBg = Color(0xFFFFF0E0),
    audioFg = Color(0xFF1E6BB8),
    audioBg = Color(0xFFE6F2FC),
    ribbon = Color(0xFFD8433F),
)

val LocalRaColors = staticCompositionLocalOf { DarkRa }

/** Font variabel Plus Jakarta Sans; di-cache menjadi SATU FontFamily tunggal agar startup dan pengukuran teks ringan. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val RaFontFamily: FontFamily = FontFamily(
    Font(
        resId = R.font.plus_jakarta_sans,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Medium.weight)),
    ),
    Font(
        resId = R.font.plus_jakarta_sans,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.SemiBold.weight)),
    ),
    Font(
        resId = R.font.plus_jakarta_sans,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Bold.weight)),
    ),
)

private fun style(weight: FontWeight, size: androidx.compose.ui.unit.TextUnit, spacing: androidx.compose.ui.unit.TextUnit = 0.sp) =
    TextStyle(
        fontFamily = RaFontFamily,
        fontWeight = weight,
        fontSize = size,
        letterSpacing = spacing,
    )

private val RaTypography = Typography(
    displayLarge = style(FontWeight.Bold, 32.sp, (-0.5).sp),   // Judul layar
    titleLarge = style(FontWeight.Bold, 22.sp),
    titleMedium = style(FontWeight.SemiBold, 15.sp),           // Judul baris kartu (cegah patah canggung)
    titleSmall = style(FontWeight.SemiBold, 14.sp),
    bodyMedium = style(FontWeight.Medium, 14.sp),
    bodySmall = style(FontWeight.Medium, 13.sp),               // Meta & subjudul
    labelLarge = style(FontWeight.Bold, 14.sp),                // Chip/tombol
    labelMedium = style(FontWeight.Medium, 12.sp),
    labelSmall = style(FontWeight.Bold, 12.sp, 0.5.sp),        // Label seksi & badge (min 12sp)
)

@Composable
fun RaKeepsakeTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkRa else LightRa
    androidx.compose.runtime.CompositionLocalProvider(LocalRaColors provides colors) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = colors.sun,
                onPrimary = colors.onSun,
                secondary = colors.sunSoft,
                surface = colors.bg,
                onSurface = colors.text,
                surfaceVariant = colors.surfaceAlt,
                onSurfaceVariant = colors.textSecondary,
                outline = colors.outline,
                background = colors.bg,
                error = colors.danger,
            ),
            typography = RaTypography,
            content = content,
        )
    }
}

/** Akses token warna di composable. */
object Ra {
    val colors: RaColors
        @Composable get() = LocalRaColors.current
}
