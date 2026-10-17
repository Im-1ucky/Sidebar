
package com.example.sidebar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF242424),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = androidx.compose.ui.graphics.Color(0xFF5F5F5F),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    background = androidx.compose.ui.graphics.Color(0xFFFAFAFA),
    onBackground = androidx.compose.ui.graphics.Color(0xFF171717),
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = androidx.compose.ui.graphics.Color(0xFF171717),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF0F0F0),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF626262),
    outline = androidx.compose.ui.graphics.Color(0xFFD0D0D0),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFE2E2E2)
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFF0F0F0),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF171717),
    secondary = androidx.compose.ui.graphics.Color(0xFFC4C4C4),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF171717),
    background = androidx.compose.ui.graphics.Color(0xFF121212),
    onBackground = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
    surface = androidx.compose.ui.graphics.Color(0xFF1B1B1B),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF292929),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
    outline = androidx.compose.ui.graphics.Color(0xFF484848),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF383838)
)

@Composable
fun SidebarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
