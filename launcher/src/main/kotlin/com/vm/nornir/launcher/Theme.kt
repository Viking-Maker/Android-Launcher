package com.vm.nornir.launcher

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.vm.nornir.launcher.ui.LocalNornirColors
import com.vm.nornir.launcher.ui.NornirColors

private val DarkColorScheme = darkColorScheme()
private val LightColorScheme = lightColorScheme()

/**
 * App theme (ADR-0004 §6): M3 supplies typography/elevation/ripple; the spec's exact
 * hex states ride [LocalNornirColors] so leaf composables pin the launcher-UI.md §2
 * palette without M3 role remapping. The launcher is a dark, wallpaper-overlaid shell,
 * so the Nornir tokens apply regardless of the system light/dark setting.
 */
@Composable
fun NornirTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
    ) {
        CompositionLocalProvider(LocalNornirColors provides NornirColors(), content = content)
    }
}
