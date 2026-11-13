package me.piyaphat.kei.ui.theme

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
import androidx.compose.material3.Typography

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E3FF),
    onPrimaryContainer = Color(0xFF101A58),
    secondary = Color(0xFF565D85),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E2FF),
    onSecondaryContainer = Color(0xFF171B3C),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191B23),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191B23),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF454650)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBC3FF),
    onPrimary = Color(0xFF1F2A7A),
    primaryContainer = Color(0xFF37459A),
    onPrimaryContainer = Color(0xFFE0E3FF),
    secondary = Color(0xFFC1C5EB),
    onSecondary = Color(0xFF292E52),
    secondaryContainer = Color(0xFF3F456A),
    onSecondaryContainer = Color(0xFFE0E2FF),
    background = Color(0xFF111319),
    onBackground = Color(0xFFE2E2EB),
    surface = Color(0xFF111319),
    onSurface = Color(0xFFE2E2EB),
    surfaceVariant = Color(0xFF454650),
    onSurfaceVariant = Color(0xFFC5C5D0)
)

object ReaderColors {
    val OledBackground = Color(0xFF000000)
    val Controls = Color(0xFFF5F5F5)
}

@Composable
fun KeiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
