package com.minesweeper.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val scheme = darkColorScheme(
    primary             = CyberCyan,
    onPrimary           = SpaceBlack,
    primaryContainer    = SlateBlue,
    onPrimaryContainer  = SoftWhite,
    secondary           = CoralRed,
    onSecondary         = SpaceBlack,
    background          = SpaceBlack,
    onBackground        = SoftWhite,
    surface             = DeepNavy,
    surfaceVariant      = NavyBlue,
    onSurface           = SoftWhite,
    onSurfaceVariant    = MutedBlue,
    error               = CoralRed,
)

@Composable
fun MinesweeperTheme(content: @Composable () -> Unit) {
    // Use Material You dynamic colour on Android 12+; fall back to custom dark scheme
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        scheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.statusBarColor     = colorScheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
