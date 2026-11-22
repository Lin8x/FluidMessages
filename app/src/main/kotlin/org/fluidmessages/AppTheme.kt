package org.fluidmessages

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

internal val Appearance.uiColors: MessageStyle.UiColors
    get() = MessageStyle.uiColors(theme, canvas, controls, contrast)

internal val LocalControlTint = compositionLocalOf { 0xff007aff.toInt() }

@Composable
internal fun AppTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val palette = remember(appearance) { appearance.uiColors }
    val canvas = Color(appearance.canvas)
    val ink = Color(palette.ink())
    val muted = Color(palette.muted())
    val surface = Color(palette.surface())
    val raised = Color(palette.raised())
    val action = Color(palette.action())
    val onAction = Color(MessageStyle.textOn(palette.action()))
    val base = if (palette.ink() == android.graphics.Color.WHITE) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = action, onPrimary = onAction,
        primaryContainer = raised, onPrimaryContainer = ink,
        secondary = action, onSecondary = onAction,
        secondaryContainer = raised, onSecondaryContainer = ink,
        tertiary = action, onTertiary = onAction,
        tertiaryContainer = raised, onTertiaryContainer = ink,
        background = canvas, onBackground = ink,
        surface = canvas, onSurface = ink,
        surfaceVariant = raised, onSurfaceVariant = muted, surfaceTint = Color.Transparent,
        surfaceDim = surface, surfaceBright = raised,
        surfaceContainerLowest = canvas, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised,
        outline = Color(palette.outline()), outlineVariant = Color(palette.divider()),
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalControlTint provides appearance.controls, content = content)
    }
}

@Composable
internal fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val tint = LocalControlTint.current
    Button(onClick = onClick, modifier = modifier, enabled = enabled, colors = ButtonDefaults.buttonColors(
        containerColor = Color(MessageStyle.filledControl(tint)),
        contentColor = Color(MessageStyle.filledControlInk(tint)),
    ), content = content)
}
