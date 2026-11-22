package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class AppThemeMode(val id: String, val nameEn: String, val nameBn: String, val previewColor: Color) {
    SYSTEM("system", "System Default", "সিস্টেম ডিফল্ট", Color(0xFF6750A4)),
    DARK("dark", "Dark Mode", "ডার্ক মোড", Color(0xFF141218)),
    LIGHT("light", "Light Mode", "লাইট মোড", Color(0xFFFBF8FF)),
    AMOLED("amoled", "AMOLED Black", "অ্যামোলেড ব্ল্যাক", Color(0xFF000000)),
    COSMIC("cosmic", "Cosmic Slate", "কসমিক স্লেট", Color(0xFF0F172A)),
    GREEN("green", "Emerald Green", "সবুজ", Color(0xFF10B981)),
    RED("red", "Ruby Red", "লাল", Color(0xFFEF4444)),
    BLUE("blue", "Ocean Blue", "নীল", Color(0xFF008DDA)),
    YELLOW("yellow", "Amber Yellow", "হলুদ", Color(0xFFF59E0B))
}

private val DarkColorScheme =
  darkColorScheme(
    primary = ElegantPrimary,
    onPrimary = ElegantOnPrimary,
    primaryContainer = ElegantPrimaryContainer,
    onPrimaryContainer = ElegantOnPrimaryContainer,
    secondary = ElegantSecondary,
    onSecondary = ElegantOnSecondary,
    secondaryContainer = ElegantSecondaryContainer,
    onSecondaryContainer = ElegantOnSecondaryContainer,
    background = ElegantBackground,
    onBackground = ElegantOnBackground,
    surface = ElegantSurface,
    onSurface = ElegantOnSurface,
    surfaceVariant = ElegantSurfaceVariant,
    onSurfaceVariant = ElegantOnSurfaceVariant,
    outline = ElegantOutline,
    outlineVariant = ElegantOutlineVariant
  )

private val LightColorScheme =
  lightColorScheme(
    primary = ElegantLightPrimary,
    onPrimary = ElegantLightOnPrimary,
    primaryContainer = ElegantLightPrimaryContainer,
    onPrimaryContainer = ElegantLightOnPrimaryContainer,
    secondary = ElegantLightSecondary,
    onSecondary = ElegantLightOnSecondary,
    secondaryContainer = ElegantLightSecondaryContainer,
    onSecondaryContainer = ElegantLightOnSecondaryContainer,
    background = ElegantLightBackground,
    onBackground = ElegantLightOnBackground,
    surface = ElegantLightSurface,
    onSurface = ElegantLightOnSurface,
    surfaceVariant = ElegantLightSurfaceVariant,
    onSurfaceVariant = ElegantLightOnSurfaceVariant,
    outline = ElegantLightOutline,
    outlineVariant = ElegantLightOutlineVariant
  )

private val AmoledColorScheme =
  darkColorScheme(
    primary = AmoledPrimary,
    onPrimary = AmoledOnPrimary,
    primaryContainer = AmoledPrimaryContainer,
    onPrimaryContainer = AmoledOnPrimaryContainer,
    secondary = AmoledPrimary,
    background = AmoledBackground,
    onBackground = Color.White,
    surface = AmoledSurface,
    onSurface = Color.White,
    surfaceVariant = AmoledSurfaceVariant,
    onSurfaceVariant = Color(0xFFD0D0D0)
  )

private val CosmicColorScheme =
  darkColorScheme(
    primary = CosmicPrimary,
    onPrimary = CosmicOnPrimary,
    primaryContainer = CosmicPrimaryContainer,
    onPrimaryContainer = CosmicOnPrimaryContainer,
    secondary = CosmicPrimary,
    background = CosmicBackground,
    onBackground = Color(0xFFF1F5F9),
    surface = CosmicSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = CosmicSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A3B8)
  )

private val GreenColorScheme =
  darkColorScheme(
    primary = GreenPrimary,
    onPrimary = GreenOnPrimary,
    primaryContainer = GreenPrimaryContainer,
    onPrimaryContainer = GreenOnPrimaryContainer,
    secondary = GreenPrimary,
    background = GreenBackground,
    onBackground = Color(0xFFECFDF5),
    surface = GreenSurface,
    onSurface = Color(0xFFECFDF5),
    surfaceVariant = GreenSurfaceVariant,
    onSurfaceVariant = Color(0xFFA7F3D0)
  )

private val RedColorScheme =
  darkColorScheme(
    primary = RedPrimary,
    onPrimary = RedOnPrimary,
    primaryContainer = RedPrimaryContainer,
    onPrimaryContainer = RedOnPrimaryContainer,
    secondary = RedPrimary,
    background = RedBackground,
    onBackground = Color(0xFFFEF2F2),
    surface = RedSurface,
    onSurface = Color(0xFFFEF2F2),
    surfaceVariant = RedSurfaceVariant,
    onSurfaceVariant = Color(0xFFFECACA)
  )

private val BlueColorScheme =
  darkColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BluePrimaryContainer,
    onPrimaryContainer = BlueOnPrimaryContainer,
    secondary = BluePrimary,
    background = BlueBackground,
    onBackground = Color(0xFFF0F9FF),
    surface = BlueSurface,
    onSurface = Color(0xFFF0F9FF),
    surfaceVariant = BlueSurfaceVariant,
    onSurfaceVariant = Color(0xFFBAE6FD)
  )

private val YellowColorScheme =
  darkColorScheme(
    primary = YellowPrimary,
    onPrimary = YellowOnPrimary,
    primaryContainer = YellowPrimaryContainer,
    onPrimaryContainer = YellowOnPrimaryContainer,
    secondary = YellowPrimary,
    background = YellowBackground,
    onBackground = Color(0xFFFEF3C7),
    surface = YellowSurface,
    onSurface = Color(0xFFFEF3C7),
    surfaceVariant = YellowSurfaceVariant,
    onSurfaceVariant = Color(0xFFFDE68A)
  )

@Composable
fun MyApplicationTheme(
  themeMode: AppThemeMode = AppThemeMode.DARK,
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val systemIsDark = isSystemInDarkTheme()
  val colorScheme = when (themeMode) {
    AppThemeMode.SYSTEM -> if (systemIsDark) DarkColorScheme else LightColorScheme
    AppThemeMode.LIGHT -> LightColorScheme
    AppThemeMode.DARK -> DarkColorScheme
    AppThemeMode.AMOLED -> AmoledColorScheme
    AppThemeMode.COSMIC -> CosmicColorScheme
    AppThemeMode.GREEN -> GreenColorScheme
    AppThemeMode.RED -> RedColorScheme
    AppThemeMode.BLUE -> BlueColorScheme
    AppThemeMode.YELLOW -> YellowColorScheme
  }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

