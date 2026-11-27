package com.minesweeper

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minesweeper.ui.theme.GamePalette
import com.minesweeper.ui.theme.LocalGamePalette
import com.minesweeper.ui.theme.MinesweeperTheme
import com.minesweeper.ui.theme.ThemeMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private enum class AppPage { MENU, DIFFICULTY, SETTINGS, GAME }

private const val KEY_THEME = "visual_theme"
private const val KEY_SOUND_ENABLED = "sound_enabled"
private const val KEY_SOUND_VOLUME = "sound_volume"
private const val KEY_VIBRATION_ENABLED = "vibration_enabled"

@Composable
fun MinesweeperApp(game: GameEngine) {
    val context = LocalContext.current
    val prefs = remember { appPrefs(context) }

    var page by remember { mutableStateOf(AppPage.MENU) }
    var themeMode by remember {
        mutableStateOf(
            runCatching {
                ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
            }.getOrDefault(ThemeMode.DARK),
        )
    }
    var soundEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_SOUND_ENABLED, true)) }
    var soundVolume by remember { mutableStateOf(prefs.getFloat(KEY_SOUND_VOLUME, 0.72f)) }
    var vibrationEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_VIBRATION_ENABLED, true)) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var lastCustom by remember {
        mutableStateOf(Difficulty.custom(size = 16, mines = 40, fog = false, safeStart = true))
    }

    val audio = remember(context.applicationContext) { GameAudio(context.applicationContext) }
    SideEffect { audio.configure(soundEnabled, soundVolume) }
    DisposableEffect(audio) {
        onDispose { audio.release() }
    }

    MinesweeperTheme(themeMode) {
        when (page) {
            AppPage.MENU -> MainMenuScreen(
                themeMode = themeMode,
                onPlay = {
                    audio.menu()
                    page = AppPage.DIFFICULTY
                },
                onSettings = {
                    audio.menu()
                    page = AppPage.SETTINGS
                },
            )

            AppPage.DIFFICULTY -> DifficultyPickerScreen(
                onBack = {
                    audio.menu()
                    page = AppPage.MENU
                },
                onCustom = {
                    audio.menu()
                    showCustomDialog = true
                },
                onSelect = { difficulty ->
                    audio.menu()
                    game.newGame(difficulty)
                    page = AppPage.GAME
                },
            )

            AppPage.SETTINGS -> SettingsScreen(
                themeMode = themeMode,
                soundEnabled = soundEnabled,
                soundVolume = soundVolume,
                vibrationEnabled = vibrationEnabled,
                onBack = {
                    audio.menu()
                    page = AppPage.MENU
                },
                onThemeChange = {
                    audio.menu()
                    themeMode = it
                    prefs.edit().putString(KEY_THEME, it.name).apply()
                },
                onSoundEnabledChange = {
                    audio.menu()
                    soundEnabled = it
                    prefs.edit().putBoolean(KEY_SOUND_ENABLED, it).apply()
                },
                onSoundVolumeChange = {
                    soundVolume = it.coerceIn(0f, 1f)
                    prefs.edit().putFloat(KEY_SOUND_VOLUME, soundVolume).apply()
                },
                onVibrationChange = {
                    vibrationEnabled = it
                    prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, it).apply()
                },
                onLicenses = {
                    audio.menu()
                    showLicenses = true
                },
            )

            AppPage.GAME -> GameBoardScreen(
                game = game,
                onBack = {
                    audio.menu()
                    page = AppPage.MENU
                },
                audio = audio,
                vibrationEnabled = vibrationEnabled,
            )
        }

        if (showCustomDialog) {
            CustomConfigDialog(
                initial = lastCustom,
                onDismiss = { showCustomDialog = false },
                onConfirm = { config ->
                    lastCustom = config
                    showCustomDialog = false
                    game.newGame(config)
                    page = AppPage.GAME
                },
            )
        }
        if (showLicenses) {
            LicensesDialog(onDismiss = { showLicenses = false })
        }
    }
}

@Composable
private fun MainMenuScreen(
    themeMode: ThemeMode,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
) {
    val palette = LocalGamePalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(palette.backgroundStart, palette.backgroundEnd), radius = 1500f)),
    ) {
        Starfield(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 30.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (palette.pixel) "MINESWEEPER" else "Minesweeper",
                color = palette.text,
                fontSize = if (palette.pixel) 29.sp else 34.sp,
                fontWeight = FontWeight.Black,
                fontFamily = if (palette.pixel) FontFamily.Monospace else FontFamily.Default,
                letterSpacing = if (palette.pixel) 2.sp else 0.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (palette.pixel) "[ ARCADE MODE: ${themeMode.name} ]" else "A little logic. A lot of mines.",
                color = palette.muted,
                fontSize = 12.sp,
                fontFamily = if (palette.pixel) FontFamily.Monospace else FontFamily.Default,
            )
            Spacer(Modifier.height(44.dp))
            MenuActionButton(
                label = if (palette.pixel) "▶  PLAY" else "Play",
                accent = true,
                palette = palette,
                onClick = onPlay,
            )
            Spacer(Modifier.height(14.dp))
            MenuActionButton(
                label = if (palette.pixel) "⚙  THEME & SOUND" else "Theme & Sounds",
                accent = false,
                palette = palette,
                onClick = onSettings,
            )
            Spacer(Modifier.height(42.dp))
            AuthorLink()
        }
    }
}

@Composable
private fun DifficultyPickerScreen(
    onBack: () -> Unit,
    onCustom: () -> Unit,
    onSelect: (Difficulty) -> Unit,
) {
    val palette = LocalGamePalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(palette.backgroundStart, palette.backgroundEnd), radius = 1500f)),
    ) {
        Starfield(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MenuBackButton("‹  BACK", palette, onBack)
            Spacer(Modifier.height(20.dp))
            Text(
                text = if (palette.pixel) "SELECT LEVEL" else "Choose your difficulty",
                color = palette.text,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = if (palette.pixel) FontFamily.Monospace else FontFamily.Default,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            MenuActionButton("Custom", false, palette, onCustom, subtitle = "Your board, your rules")
            Spacer(Modifier.height(10.dp))
            MenuActionButton("Hard", false, palette, { onSelect(Difficulty.HARD) }, subtitle = "14 × 14 · Fog of war")
            Spacer(Modifier.height(10.dp))
            MenuActionButton("Medium", false, palette, { onSelect(Difficulty.MEDIUM) }, subtitle = "12 × 12 · 5 minute timer")
            Spacer(Modifier.height(10.dp))
            MenuActionButton("Easy", true, palette, { onSelect(Difficulty.EASY) }, subtitle = "9 × 9 · Relaxed classic game")
            Spacer(Modifier.height(34.dp))
            AuthorLink()
        }
    }
}

@Composable
private fun SettingsScreen(
    themeMode: ThemeMode,
    soundEnabled: Boolean,
    soundVolume: Float,
    vibrationEnabled: Boolean,
    onBack: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSoundEnabledChange: (Boolean) -> Unit,
    onSoundVolumeChange: (Float) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onLicenses: () -> Unit,
) {
    val palette = LocalGamePalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(palette.backgroundStart, palette.backgroundEnd), radius = 1500f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        MenuBackButton("‹  BACK", palette, onBack)
        Spacer(Modifier.height(18.dp))
        Text("Settings", color = palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))

        SettingsSectionTitle("THEME", palette)
        ThemeMode.values().forEach { mode ->
            val selected = mode == themeMode
            Surface(
                onClick = { onThemeChange(mode) },
                color = if (selected) palette.accent.copy(alpha = 0.16f) else palette.panel,
                shape = RoundedCornerShape(if (palette.pixel) 2.dp else 12.dp),
                border = BorderStroke(1.dp, if (selected) palette.accent else palette.muted.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (mode) {
                            ThemeMode.DARK -> "◐"
                            ThemeMode.LIGHT -> "☼"
                            ThemeMode.RETRO -> "▦"
                        },
                        color = palette.accent,
                        fontSize = 21.sp,
                        fontFamily = if (palette.pixel) FontFamily.Monospace else FontFamily.Default,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mode.title, color = palette.text, fontWeight = FontWeight.SemiBold)
                        Text(mode.subtitle, color = palette.muted, fontSize = 11.sp)
                    }
                    if (selected) Text("✓", color = palette.accent, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SettingsSectionTitle("SOUNDS", palette)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sound effects", color = palette.text, fontWeight = FontWeight.SemiBold)
                Text(if (soundEnabled) "Sounds are enabled" else "All game sounds are muted", color = palette.muted, fontSize = 11.sp)
            }
            Button(
                onClick = { onSoundEnabledChange(!soundEnabled) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (soundEnabled) Color(0xFF1F9D55) else Color(0xFFD64545),
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(if (palette.pixel) 2.dp else 10.dp),
            ) {
                Text(if (soundEnabled) "ON" else "OFF", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", color = palette.muted, fontSize = 12.sp)
            Spacer(Modifier.width(12.dp))
            Slider(
                value = soundVolume,
                onValueChange = onSoundVolumeChange,
                enabled = soundEnabled,
                modifier = Modifier.weight(1f),
            )
            Text("${(soundVolume * 100).toInt()}%", color = palette.text, fontSize = 11.sp)
        }

        Spacer(Modifier.height(12.dp))
        SettingsSectionTitle("VIBRATION", palette)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mine explosion vibration", color = palette.text, fontWeight = FontWeight.SemiBold)
                Text("Only vibrates when you trigger a mine", color = palette.muted, fontSize = 11.sp)
            }
            Switch(
                checked = vibrationEnabled,
                onCheckedChange = onVibrationChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = palette.accentText,
                    checkedTrackColor = palette.accent,
                    uncheckedThumbColor = palette.muted,
                ),
            )
        }

        Spacer(Modifier.height(18.dp))
        MenuActionButton("Licenses", false, palette, onLicenses, subtitle = "Open third-party license notices")
        Spacer(Modifier.height(12.dp))
        AuthorLink()
    }
}

@Composable
private fun SettingsSectionTitle(label: String, palette: GamePalette) {
    Text(
        label,
        color = palette.accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        fontFamily = if (palette.pixel) FontFamily.Monospace else FontFamily.Default,
        modifier = Modifier.padding(bottom = 7.dp),
    )
}

@Composable
private fun MenuActionButton(
    label: String,
    accent: Boolean,
    palette: GamePalette,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "arcadeButton")
    val pulse by transition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "arcadePulse",
    )
    Surface(
        onClick = onClick,
        color = if (accent) palette.accent else palette.panel,
        contentColor = if (accent) palette.accentText else palette.text,
        shape = RoundedCornerShape(if (palette.pixel) 2.dp else 15.dp),
        border = BorderStroke(1.dp, if (accent) palette.accent else palette.muted.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (subtitle == null) 56.dp else 62.dp)
            .scale(if (palette.pixel) pulse else 1f),
        shadowElevation = if (accent && !palette.pixel) 8.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                label,
                color = if (accent) palette.accentText else palette.text,
                fontWeight = FontWeight.Bold,
                fontSize = if (palette.pixel) 15.sp else 16.sp,
                fontFamily = if (palette.pixel) FontFamily.Monospace else FontFamily.Default,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = (if (accent) palette.accentText else palette.muted).copy(alpha = 0.9f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun MenuBackButton(label: String, palette: GamePalette, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            onClick = onClick,
            color = palette.panel,
            contentColor = palette.text,
            shape = RoundedCornerShape(if (palette.pixel) 2.dp else 10.dp),
            border = BorderStroke(1.dp, palette.muted.copy(alpha = 0.3f)),
            modifier = Modifier.wrapContentWidth(),
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                color = palette.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = if (palette.pixel) FontFamily.Monospace else FontFamily.Default,
            )
        }
    }
}

@Composable
internal fun AuthorLink(modifier: Modifier = Modifier) {
    val palette = LocalGamePalette.current
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = modifier.clickable { uriHandler.openUri("https://github.com/ofand04") }.padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("by", color = palette.muted, fontSize = 11.sp)
        Spacer(Modifier.width(5.dp))
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_github),
            contentDescription = "Open ofand04's GitHub profile",
            modifier = Modifier.size(16.dp),
            colorFilter = ColorFilter.tint(palette.muted),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "ofand04",
            color = palette.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Starfield(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "starPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "starAlpha",
    )
    Canvas(modifier) {
        repeat(56) { index ->
            val x = ((index * 37) % 101) / 100f * size.width
            val y = ((index * 61) % 97) / 100f * size.height
            val wave = abs(sin((pulse * 1.8f + index * 0.37f) * PI.toFloat()))
            val radius = 0.7f + (index % 4) * 0.35f
            val alpha = 0.10f + wave * 0.38f
            drawCircle(Color(0xFFDAF4FF).copy(alpha = alpha), radius, androidx.compose.ui.geometry.Offset(x, y))
            if (index % 9 == 0) {
                drawCircle(
                    Color(0xFF8BE9FF).copy(alpha = alpha * 0.22f),
                    radius * 3.4f,
                    androidx.compose.ui.geometry.Offset(x, y),
                    style = Stroke(width = 0.7f),
                )
            }
        }
    }
}
