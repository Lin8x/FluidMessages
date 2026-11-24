package org.fluidmessages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * Forms open as full pages, not small pop-ups: there's room to read and type, the keyboard never covers the
 * buttons, and Android's Back gesture or the arrow at the top closes them.
 */

/**
 * A full-screen page with a back arrow, a title, scrolling content, and its buttons in a bar along the bottom
 * that stays above the keyboard. Takes the same parts as an AlertDialog, so any form can become a page.
 */
@Composable
internal fun FormPage(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismissRequest, modifier = Modifier.semantics { contentDescription = "Back" }) {
                        DrawGlyph(Glyph.Back, MaterialTheme.colorScheme.onSurface, Modifier.size(22.dp))
                    }
                    Box(Modifier.weight(1f).padding(start = 4.dp)) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge) { title?.invoke() }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyLarge) { text?.invoke() }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelLarge) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
