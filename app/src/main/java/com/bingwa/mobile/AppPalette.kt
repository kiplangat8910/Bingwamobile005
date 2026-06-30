package com.bingwa.mobile

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

internal object C {
    var bg by mutableStateOf(Color(0xFF0C1017))
    var surface by mutableStateOf(Color(0xFF19161D))
    var card by mutableStateOf(Color(0xFF2B262A))
    var cardHi by mutableStateOf(Color(0xFF343035))
    var border by mutableStateOf(Color(0xFF5B4F54))
    var borderHi by mutableStateOf(Color(0xFF75696E))
    var cyan by mutableStateOf(Color(0xFFF7A600))
    var cyanDim by mutableStateOf(cyan.copy(alpha = 0.12f))
    var cyanGlow by mutableStateOf(cyan.copy(alpha = 0.20f))
    var orange by mutableStateOf(Color(0xFFF7A600))
    var orangeDim by mutableStateOf(orange.copy(alpha = 0.12f))
    var purple by mutableStateOf(Color(0xFFB6BDC9))
    var purpleDim by mutableStateOf(purple.copy(alpha = 0.13f))
    var green by mutableStateOf(Color(0xFF16C784))
    var greenDim by mutableStateOf(green.copy(alpha = 0.10f))
    var greenGlow by mutableStateOf(green.copy(alpha = 0.22f))
    var red by mutableStateOf(Color(0xFFF6465D))
    var redDim by mutableStateOf(red.copy(alpha = 0.10f))
    var amber by mutableStateOf(Color(0xFFF7A600))
    var amberDim by mutableStateOf(amber.copy(alpha = 0.10f))
    var blue by mutableStateOf(Color(0xFFFFD38A))
    var blueDim by mutableStateOf(blue.copy(alpha = 0.10f))
    var t1 by mutableStateOf(Color(0xFFFFFFFF))
    var t2 by mutableStateOf(Color(0xFFD0D5DD))
    var t3 by mutableStateOf(Color(0xFF98A2B3))
    var w12 by mutableStateOf(Color.White.copy(alpha = 0.12f))
    var w08 by mutableStateOf(Color.White.copy(alpha = 0.08f))
    var w04 by mutableStateOf(Color.White.copy(alpha = 0.04f))
}

internal enum class AppearancePreset(val label: String, val description: String) {
    VOLCANIC_GOLD("Volcanic Gold", "Bold gold, graphite, and emerald"),
    OCEAN_TEAL("Ocean Teal", "Fresh teal, indigo, and aqua"),
    ROYAL_VIOLET("Royal Violet", "Deep violet, rose, and amber")
}

private data class AppearancePaletteSpec(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val primaryContainer: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val success: Color,
    val danger: Color,
    val info: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val glowAlpha: Float
)

internal fun appearancePresetFromName(value: String?): AppearancePreset =
    runCatching { AppearancePreset.valueOf(value.orEmpty().uppercase()) }
        .getOrDefault(AppearancePreset.VOLCANIC_GOLD)

internal fun appearancePresetOptions(): List<AppearancePreset> = AppearancePreset.values().toList()

internal fun appearancePresetLabel(preset: AppearancePreset): String = preset.label

private fun appearancePaletteSpec(preset: AppearancePreset): AppearancePaletteSpec = when (preset) {
    AppearancePreset.VOLCANIC_GOLD -> AppearancePaletteSpec(
        primary = Color(0xFFF7A600),
        secondary = Color(0xFF8FD8B6),
        tertiary = Color(0xFFFFD38A),
        primaryContainer = Color(0xFFFFE1A6),
        background = Color(0xFF0C1017),
        surface = Color(0xFF19161D),
        surfaceVariant = Color(0xFF312B30),
        outline = Color(0xFF5B4F54),
        outlineVariant = Color(0xFF75696E),
        success = Color(0xFF16C784),
        danger = Color(0xFFF6465D),
        info = Color(0xFFFFD38A),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFD0D5DD),
        textTertiary = Color(0xFF98A2B3),
        glowAlpha = 0.24f
    )
    AppearancePreset.OCEAN_TEAL -> AppearancePaletteSpec(
        primary = Color(0xFF23C7B7),
        secondary = Color(0xFF7AA2FF),
        tertiary = Color(0xFF7EE7FF),
        primaryContainer = Color(0xFF0F3D42),
        background = Color(0xFF07161B),
        surface = Color(0xFF0F222A),
        surfaceVariant = Color(0xFF17343D),
        outline = Color(0xFF2D5962),
        outlineVariant = Color(0xFF3F7480),
        success = Color(0xFF33D17A),
        danger = Color(0xFFFF6B81),
        info = Color(0xFF7AA2FF),
        textPrimary = Color(0xFFF4FEFF),
        textSecondary = Color(0xFFC3DADF),
        textTertiary = Color(0xFF88AAB2),
        glowAlpha = 0.22f
    )
    AppearancePreset.ROYAL_VIOLET -> AppearancePaletteSpec(
        primary = Color(0xFFA56BFF),
        secondary = Color(0xFFFF7DB8),
        tertiary = Color(0xFFFFC857),
        primaryContainer = Color(0xFF35214E),
        background = Color(0xFF120D1E),
        surface = Color(0xFF1A1428),
        surfaceVariant = Color(0xFF2A1F3D),
        outline = Color(0xFF54426D),
        outlineVariant = Color(0xFF6A5588),
        success = Color(0xFF3ED598),
        danger = Color(0xFFFF5D73),
        info = Color(0xFFFFC857),
        textPrimary = Color(0xFFFDF9FF),
        textSecondary = Color(0xFFD8CCE7),
        textTertiary = Color(0xFFA99ABF),
        glowAlpha = 0.24f
    )
}

private fun onColorFor(color: Color): Color =
    if (color.luminance() > 0.5f) Color(0xFF0B0E11) else Color(0xFFF7F8FA)

internal fun buildAppColorScheme(preset: AppearancePreset): ColorScheme {
    val palette = appearancePaletteSpec(preset)
    return darkColorScheme(
        primary = palette.primary,
        onPrimary = onColorFor(palette.primary),
        secondary = palette.secondary,
        onSecondary = onColorFor(palette.secondary),
        tertiary = palette.tertiary,
        onTertiary = onColorFor(palette.tertiary),
        primaryContainer = palette.primaryContainer,
        onPrimaryContainer = onColorFor(palette.primaryContainer),
        background = palette.background,
        surface = palette.surface,
        surfaceVariant = palette.surfaceVariant,
        onBackground = palette.textPrimary,
        onSurface = palette.textPrimary,
        outline = palette.outline,
        outlineVariant = palette.outlineVariant,
        error = palette.danger
    )
}

internal object AppTheme {
    var appearance by mutableStateOf(AppearancePreset.VOLCANIC_GOLD)

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        appearance = appearancePresetFromName(
            prefs.safeGetString("theme_appearance", AppearancePreset.VOLCANIC_GOLD.name)
        )
    }
}

internal fun applyVolcanicPaletteFromScheme(s: ColorScheme, preset: AppearancePreset) {
    val palette = appearancePaletteSpec(preset)
    C.bg = s.background
    C.surface = s.surface
    C.card = lerp(s.surface, s.surfaceVariant, 0.82f)
    C.cardHi = lerp(C.card, s.primary, 0.08f)
    C.border = s.outline
    C.borderHi = s.outlineVariant
    C.cyan = s.primary
    C.cyanDim = s.primary.copy(alpha = 0.12f)
    C.cyanGlow = s.primary.copy(alpha = palette.glowAlpha)
    C.purple = s.secondary
    C.purpleDim = C.purple.copy(alpha = 0.13f)
    C.orange = s.primary
    C.orangeDim = s.primary.copy(alpha = 0.12f)
    C.green = palette.success
    C.greenDim = C.green.copy(alpha = 0.10f)
    C.greenGlow = C.green.copy(alpha = 0.22f)
    C.red = palette.danger
    C.redDim = C.red.copy(alpha = 0.10f)
    C.amber = s.tertiary
    C.amberDim = C.amber.copy(alpha = 0.10f)
    C.blue = palette.info
    C.blueDim = C.blue.copy(alpha = 0.10f)
    C.t1 = palette.textPrimary
    C.t2 = palette.textSecondary
    C.t3 = palette.textTertiary
    C.w12 = palette.textPrimary.copy(alpha = 0.12f)
    C.w08 = palette.textPrimary.copy(alpha = 0.08f)
    C.w04 = palette.textPrimary.copy(alpha = 0.04f)
}

internal fun surfaceGradient(): Brush = Brush.linearGradient(listOf(C.cardHi, C.card, C.surface))

internal fun accentSurfaceGradient(accent: Color): Brush =
    Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), C.card))
