package ru.tulsu.dp30

import android.os.Build
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class ThemeMode(val title: String) {
    DARK("Тёмная"), LIGHT("Светлая"), SYSTEM("Как в системе")
}

private val DayColors = lightColorScheme(
    primary = Color(0xFF8D3F58), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E2), onPrimaryContainer = Color(0xFF3B071C),
    secondary = Color(0xFF76564D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCF), onSecondaryContainer = Color(0xFF31130C),
    tertiary = Color(0xFF745181), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6DCFF), onTertiaryContainer = Color(0xFF2D143B),
    background = Color(0xFFFFF8F5), onBackground = Color(0xFF281813),
    surface = Color(0xFFFFFAF8), onSurface = Color(0xFF281813),
    surfaceVariant = Color(0xFFF0DFD7), onSurfaceVariant = Color(0xFF6F5B53),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF4EF),
    surfaceContainer = Color(0xFFF9EEE9), surfaceContainerHigh = Color(0xFFF7EBE6),
    surfaceContainerHighest = Color(0xFFF0DFD7),
    outline = Color(0xFF9A8178), outlineVariant = Color(0xFFDDC4BA),
    error = Color(0xFFAA2739), onError = Color.White,
    errorContainer = Color(0xFFFFDADB), onErrorContainer = Color(0xFF400011),
    inverseSurface = Color(0xFF3C2922), inverseOnSurface = Color(0xFFFFEDE4),
    inversePrimary = Color(0xFFFFB0C4),
)

private val NightColors = darkColorScheme(
    primary = Color(0xFFFFB0C4), onPrimary = Color(0xFF57152D),
    primaryContainer = Color(0xFF733047), onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFE8BDB0), onSecondary = Color(0xFF43291F),
    secondaryContainer = Color(0xFF5D3F36), onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFFE8B9F4), onTertiary = Color(0xFF40254D),
    tertiaryContainer = Color(0xFF573B65), onTertiaryContainer = Color(0xFFF6DCFF),
    background = Color(0xFF19120F), onBackground = Color(0xFFF6E6DF),
    surface = Color(0xFF251A16), onSurface = Color(0xFFF6E6DF),
    surfaceVariant = Color(0xFF3C2922), onSurfaceVariant = Color(0xFFD7C0B7),
    surfaceContainerLowest = Color(0xFF1D1512), surfaceContainerLow = Color(0xFF251A16),
    surfaceContainer = Color(0xFF2B1E19), surfaceContainerHigh = Color(0xFF30211C),
    surfaceContainerHighest = Color(0xFF3C2922),
    outline = Color(0xFFA98C81), outlineVariant = Color(0xFF59423A),
    error = Color(0xFFFFB2BB), onError = Color(0xFF640020),
    errorContainer = Color(0xFF752533), onErrorContainer = Color(0xFFFFDADB),
    inverseSurface = Color(0xFFF6E6DF), inverseOnSurface = Color(0xFF392822),
    inversePrimary = Color(0xFF8D3F58),
)

@OptIn(ExperimentalTextApi::class)
private val Manrope = FontFamily(
    Font(R.font.manrope, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.manrope, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

private val DefaultType = Typography()
private val DispatchType = Typography(
    displaySmall = DefaultType.displaySmall.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1.1).sp),
    headlineLarge = DefaultType.headlineLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.65).sp),
    headlineMedium = DefaultType.headlineMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold),
    headlineSmall = DefaultType.headlineSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
    titleLarge = DefaultType.titleLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
    titleMedium = DefaultType.titleMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
    titleSmall = DefaultType.titleSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
    bodyLarge = DefaultType.bodyLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Medium),
    bodyMedium = DefaultType.bodyMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.Medium),
    bodySmall = DefaultType.bodySmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Medium),
    labelLarge = DefaultType.labelLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
    labelMedium = DefaultType.labelMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
    labelSmall = DefaultType.labelSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
)

@Composable
internal fun DispatchTheme(
    mode: ThemeMode,
    wallpaperColors: Boolean,
    updateSystemBars: (SystemBarStyle) -> Unit,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors = if (wallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) NightColors else DayColors
    LaunchedEffect(dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        updateSystemBars(style)
    }
    MaterialTheme(
        colorScheme = colors,
        typography = DispatchType,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(34.dp),
        ),
        content = content,
    )
}
