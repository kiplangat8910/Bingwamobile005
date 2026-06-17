package com.bingwa.mobile

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

internal object C {
    var bg by mutableStateOf(Color(0xFF0C111B))
    var surface by mutableStateOf(Color(0xFF121826))
    var card by mutableStateOf(Color(0xFF171F30))
    var cardHi by mutableStateOf(Color(0xFF1E2940))
    var border by mutableStateOf(Color(0xFF2A3650))
    var borderHi by mutableStateOf(Color(0xFF3D4D6A))
    var cyan by mutableStateOf(Color(0xFFFFB547))
    var cyanDim by mutableStateOf(cyan.copy(alpha = 0.12f))
    var cyanGlow by mutableStateOf(cyan.copy(alpha = 0.20f))
    var orange by mutableStateOf(Color(0xFFFFB547))
    var orangeDim by mutableStateOf(orange.copy(alpha = 0.12f))
    var purple by mutableStateOf(Color(0xFF9CB2FF))
    var purpleDim by mutableStateOf(purple.copy(alpha = 0.13f))
    var green by mutableStateOf(Color(0xFF2DD4A3))
    var greenDim by mutableStateOf(green.copy(alpha = 0.10f))
    var greenGlow by mutableStateOf(green.copy(alpha = 0.22f))
    var red by mutableStateOf(Color(0xFFFF6B81))
    var redDim by mutableStateOf(red.copy(alpha = 0.10f))
    var amber by mutableStateOf(Color(0xFFFFB547))
    var amberDim by mutableStateOf(amber.copy(alpha = 0.10f))
    var blue by mutableStateOf(Color(0xFF77B8FF))
    var blueDim by mutableStateOf(blue.copy(alpha = 0.10f))
    var t1 by mutableStateOf(Color(0xFFFFFFFF))
    var t2 by mutableStateOf(Color(0xFFD6DCE8))
    var t3 by mutableStateOf(Color(0xFF90A0BA))
    var w12 by mutableStateOf(Color.White.copy(alpha = 0.12f))
    var w08 by mutableStateOf(Color.White.copy(alpha = 0.08f))
    var w04 by mutableStateOf(Color.White.copy(alpha = 0.04f))
}

internal enum class ThemeMode { SYSTEM, DARK, LIGHT }

internal enum class ThemeAccent(val label: String) {
    BYBIT("Bybit Yellow")
}

private data class AccentPaletteSpec(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val primaryContainer: Color
)

internal fun themeAccentFromName(value: String?): ThemeAccent =
    ThemeAccent.BYBIT

internal fun themeAccentOptions(): List<ThemeAccent> = ThemeAccent.values().toList()

internal fun themeAccentLabel(accent: ThemeAccent): String = accent.label

private fun accentPaletteSpec(accent: ThemeAccent): AccentPaletteSpec = when (accent) {
    ThemeAccent.BYBIT -> AccentPaletteSpec(
        primary = Color(0xFFF7A600),
        secondary = Color(0xFFB6BDC9),
        tertiary = Color(0xFFF7A600),
        primaryContainer = Color(0xFFFFE1A6)
    )
}

private fun onColorFor(color: Color): Color =
    if (color.luminance() > 0.5f) Color(0xFF0B0E11) else Color(0xFFF7F8FA)

internal fun buildAppColorScheme(accent: ThemeAccent, dark: Boolean): ColorScheme {
    val palette = accentPaletteSpec(accent)
    val background = if (dark) Color(0xFF0C111B) else Color(0xFFF6F8FC)
    val surface = if (dark) Color(0xFF121826) else Color(0xFFFFFFFF)
    val surfaceVariantBase = if (dark) Color(0xFF182133) else Color(0xFFF0F4FA)
    val surfaceVariant = lerp(surfaceVariantBase, palette.primary, if (dark) 0.12f else 0.06f)
    val outline = lerp(if (dark) Color(0xFF313845) else Color(0xFFD6DBE4), palette.primary, if (dark) 0.22f else 0.10f)
    val outlineVariant = lerp(if (dark) Color(0xFF465062) else Color(0xFFE4E7EC), palette.secondary, if (dark) 0.18f else 0.10f)

    return if (dark) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = onColorFor(palette.primary),
            secondary = palette.secondary,
            onSecondary = onColorFor(palette.secondary),
            tertiary = palette.tertiary,
            onTertiary = onColorFor(palette.tertiary),
            primaryContainer = palette.primaryContainer,
            onPrimaryContainer = onColorFor(palette.primaryContainer),
            background = background,
            surface = surface,
            surfaceVariant = surfaceVariant,
            onBackground = Color(0xFFF8FAFC),
            onSurface = Color(0xFFF8FAFC),
            outline = outline,
            outlineVariant = outlineVariant,
            error = Color(0xFFF6465D)
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = onColorFor(palette.primary),
            secondary = palette.secondary,
            onSecondary = onColorFor(palette.secondary),
            tertiary = palette.tertiary,
            onTertiary = onColorFor(palette.tertiary),
            primaryContainer = palette.primaryContainer,
            onPrimaryContainer = onColorFor(palette.primaryContainer),
            background = background,
            surface = surface,
            surfaceVariant = surfaceVariant,
            onBackground = Color(0xFF101625),
            onSurface = Color(0xFF101625),
            outline = outline,
            outlineVariant = outlineVariant,
            error = Color(0xFFF6465D)
        )
    }
}

internal object AppTheme {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
    var useDynamicColors by mutableStateOf(false)
    var accent by mutableStateOf(ThemeAccent.BYBIT)

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        mode = runCatching { ThemeMode.valueOf((prefs.safeGetString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name).uppercase()) }
            .getOrDefault(ThemeMode.SYSTEM)
        useDynamicColors = false
        accent = ThemeAccent.BYBIT
    }
}

internal fun applyVolcanicPaletteFromScheme(s: ColorScheme, dark: Boolean) {
    C.bg = s.background
    C.surface = s.surface
    C.card = if (dark) lerp(s.surface, s.surfaceVariant, 0.88f) else lerp(s.surface, s.surfaceVariant, 0.64f)
    C.cardHi = if (dark) lerp(C.card, s.primary, 0.10f) else lerp(C.card, s.primary, 0.05f)
    C.border = s.outline
    C.borderHi = s.outlineVariant
    C.cyan = s.primary
    C.cyanDim = s.primary.copy(alpha = 0.12f)
    C.cyanGlow = s.primary.copy(alpha = if (dark) 0.28f else 0.18f)
    C.purple = if (dark) Color(0xFF9CB2FF) else Color(0xFF5D71C8)
    C.purpleDim = C.purple.copy(alpha = 0.13f)
    C.orange = s.primary
    C.orangeDim = s.primary.copy(alpha = 0.12f)
    C.green = Color(0xFF2DD4A3)
    C.greenDim = C.green.copy(alpha = 0.10f)
    C.greenGlow = C.green.copy(alpha = 0.26f)
    C.red = Color(0xFFFF6B81)
    C.redDim = C.red.copy(alpha = 0.10f)
    C.amber = s.primary
    C.amberDim = C.amber.copy(alpha = 0.10f)
    C.blue = if (dark) Color(0xFF77B8FF) else Color(0xFF286FB8)
    C.blueDim = C.blue.copy(alpha = 0.10f)
    val base = if (dark) Color.White else Color.Black
    C.t1 = base
    C.t2 = base.copy(alpha = if (dark) 0.80f else 0.72f)
    C.t3 = base.copy(alpha = if (dark) 0.58f else 0.52f)
    C.w12 = base.copy(alpha = 0.12f)
    C.w08 = base.copy(alpha = 0.08f)
    C.w04 = base.copy(alpha = 0.04f)
}

internal fun surfaceGradient(): Brush = Brush.linearGradient(
    listOf(
        C.cardHi,
        lerp(C.card, C.surface, 0.35f),
        C.surface
    )
)

internal fun accentSurfaceGradient(accent: Color): Brush =
    Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), C.card, C.surface))
