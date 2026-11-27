package com.minesweeper.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** The selectable visual skins. DARK remains the app's original appearance. */
enum class ThemeMode(val title: String, val subtitle: String) {
    DARK("Dark", "Original space theme"),
    LIGHT("White", "Clean light surfaces"),
    RETRO("Retro pixels", "Neon arcade mode"),
}

data class GamePalette(
    val backgroundStart: Color,
    val backgroundEnd: Color,
    val panel: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentText: Color,
    val cellTop: Color,
    val cellBottom: Color,
    val cellOpen: Color,
    val digitBackground: Color,
    val winBackground: Color,
    val lossBackground: Color,
    val pixel: Boolean,
    val numberColors: List<Color>,
) {
    companion object {
        val Dark = GamePalette(
            backgroundStart = Color(0xFF0D1B2A),
            backgroundEnd = Color(0xFF070B14),
            panel = Color(0xFF131D2E),
            surface = Color(0xFF1B2B3C),
            text = Color(0xFFCDD5E0),
            muted = Color(0xFF8BA7C4),
            accent = Color(0xFF4ECDC4),
            accentText = Color(0xFF070B14),
            cellTop = Color(0xFF253648),
            cellBottom = Color(0xFF1B2B3C),
            cellOpen = Color(0xFF0D1421),
            digitBackground = Color(0xFF040710),
            winBackground = Color(0xFF0E3A20),
            lossBackground = Color(0xFF3D0808),
            pixel = false,
            numberColors = listOf(
                Color.Transparent, Color(0xFF5BA3F5), Color(0xFF56CF72),
                Color(0xFFFF5151), Color(0xFFAB7FEB), Color(0xFFFF8C42),
                Color(0xFF00CEC9), Color(0xFFFF6CAE), Color(0xFF95A5A6),
            ),
        )

        val Light = GamePalette(
            backgroundStart = Color(0xFFF8FAFD),
            backgroundEnd = Color(0xFFE6ECF4),
            panel = Color(0xFFFFFFFF),
            surface = Color(0xFFE4EAF2),
            text = Color(0xFF172033),
            muted = Color(0xFF5B687B),
            accent = Color(0xFF2563EB),
            accentText = Color.White,
            cellTop = Color(0xFFE4EAF2),
            cellBottom = Color(0xFFCAD5E2),
            cellOpen = Color(0xFFF7F9FC),
            digitBackground = Color(0xFF172033),
            winBackground = Color(0xFFD9F4E2),
            lossBackground = Color(0xFFFCE1E1),
            pixel = false,
            numberColors = listOf(
                Color.Transparent, Color(0xFF1769D2), Color(0xFF16803C),
                Color(0xFFC62828), Color(0xFF6A35B5), Color(0xFFB45309),
                Color(0xFF007C83), Color(0xFFB52B77), Color(0xFF475569),
            ),
        )

        val Retro = GamePalette(
            backgroundStart = Color(0xFF071A12),
            backgroundEnd = Color(0xFF020805),
            panel = Color(0xFF0A1B11),
            surface = Color(0xFF102719),
            text = Color(0xFFB8FFD1),
            muted = Color(0xFF70B98B),
            accent = Color(0xFF39FF14),
            accentText = Color(0xFF041006),
            cellTop = Color(0xFF183D26),
            cellBottom = Color(0xFF0B2414),
            cellOpen = Color(0xFF04120A),
            digitBackground = Color(0xFF010501),
            winBackground = Color(0xFF0C351B),
            lossBackground = Color(0xFF3B0909),
            pixel = true,
            numberColors = listOf(
                Color.Transparent, Color(0xFF62D7FF), Color(0xFF39FF14),
                Color(0xFFFF4D6D), Color(0xFFC77DFF), Color(0xFFFFB347),
                Color(0xFF00F5D4), Color(0xFFFF70C8), Color(0xFFB8FFD1),
            ),
        )
    }
}

val LocalGamePalette = staticCompositionLocalOf { GamePalette.Dark }

@Composable
fun MinesweeperTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val palette = when (mode) {
        ThemeMode.DARK -> GamePalette.Dark
        ThemeMode.LIGHT -> GamePalette.Light
        ThemeMode.RETRO -> GamePalette.Retro
    }
    val scheme = when (mode) {
        ThemeMode.DARK -> dynamicDarkColorScheme(context)
        ThemeMode.LIGHT -> dynamicLightColorScheme(context)
        ThemeMode.RETRO -> darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.accentText,
            secondary = Color(0xFF00F5D4),
            background = palette.backgroundEnd,
            onBackground = palette.text,
            surface = palette.panel,
            onSurface = palette.text,
        )
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.Transparent.toArgb()
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    CompositionLocalProvider(LocalGamePalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
