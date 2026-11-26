package kz.damird.diktofon.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Палитра как у веб-версии: тёплая бумага, чернила, красная точка записи. */
@Immutable
data class Cveta(
    val bg: Color, val card: Color, val ink: Color, val muted: Color, val faint: Color,
    val line: Color, val accent: Color, val accentSoft: Color, val ok: Color,
)

val Svetlaya = Cveta(
    bg = Color(0xFFF4F2EE), card = Color(0xFFFFFEFB), ink = Color(0xFF1D1C1A), muted = Color(0xFF6F6C66),
    faint = Color(0xFFA3A09A), line = Color(0xFFE3E0D9), accent = Color(0xFFC8401C), accentSoft = Color(0xFFF6E3DC),
    ok = Color(0xFF2F7A4B),
)
val Temnaya = Cveta(
    bg = Color(0xFF161615), card = Color(0xFF201F1D), ink = Color(0xFFEDEAE4), muted = Color(0xFFA29E96),
    faint = Color(0xFF6E6B65), line = Color(0xFF2F2E2B), accent = Color(0xFFEC6A43), accentSoft = Color(0xFF3A2219),
    ok = Color(0xFF5FBF83),
)

val LocalCveta = staticCompositionLocalOf { Svetlaya }

object T {
    val h1 = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val h2 = TextStyle(fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
    val title = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 23.sp)
    val small = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
    val meta = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
}

@Composable
fun DiktofonTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Temnaya else Svetlaya
    val scheme = if (dark) darkColorScheme(primary = c.accent, background = c.bg, surface = c.card, onBackground = c.ink, onSurface = c.ink)
    else lightColorScheme(primary = c.accent, background = c.bg, surface = c.card, onBackground = c.ink, onSurface = c.ink)
    androidx.compose.runtime.CompositionLocalProvider(LocalCveta provides c) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
