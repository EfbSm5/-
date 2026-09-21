package com.example.agent.rootpilot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** The pilot theme is scoped to settings; task/chat keep the application's existing theme. */
@Composable
internal fun RootPilotSettingsTheme(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val colors = MiuixTheme.colorScheme
        // Keep the existing password-capable text fields, using matching settings colors.
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            background = colors.background,
            onBackground = colors.onBackground,
            surface = colors.surface,
            onSurface = colors.onSurface,
            onSurfaceVariant = colors.onSurfaceVariantSummary,
            error = colors.error,
            outline = colors.outline,
        )) {
            // Material's content color does not propagate to Miuix text outside its cards.
            CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
        }
    }
}
